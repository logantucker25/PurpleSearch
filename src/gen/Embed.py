import argparse
import logging
import time
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry
from py2neo import Graph
from transformers import AutoTokenizer
import sys

log_file = open("loading.log", "a", buffering=1)  
sys.stdout = log_file

# ——— Argument parsing ———
parser = argparse.ArgumentParser(description="Embed methods in Neo4j")
parser.add_argument("--hf-url",       required=True,  help="HuggingFace API URL")
parser.add_argument("--hf-token",     required=True,  help="HuggingFace API token")
parser.add_argument("--hf-dim",       required=True,  help="HuggingFace Embedding Model Dimesions")
parser.add_argument("--hf-tpe",       required=True,  help="HuggingFace Embedding Model Tokens Allowed Per Embedding")
parser.add_argument("--neo4j-uri",    required=True,  help="Neo4j bolt URI")
parser.add_argument("--neo4j-user",   required=True,  help="Neo4j username")
parser.add_argument("--neo4j-pass",   required=True,  help="Neo4j password")
args = parser.parse_args()

API_URL   = args.hf_url
HF_TOKEN  = args.hf_token
MAX_TOKENS = int(args.hf_tpe)
EMBEDDING_DIM = int(args.hf_dim)

NEO4J_URI = args.neo4j_uri
NEO4J_USER = args.neo4j_user
NEO4J_PASSWORD = args.neo4j_pass

TOKENIZER = AutoTokenizer.from_pretrained(
    "sentence-transformers/all-MiniLM-L6-v2",
    use_fast=True
)

BATCH_SIZE = 30
INDEX_NAME = "methodEmbeddings"

HEADERS = {
    "Accept": "application/json",
    "Authorization": f"Bearer {HF_TOKEN}",
    "Content-Type": "application/json"
}

def make_session_with_retries(
    total_retries=5,
    backoff_factor=1.0,
    status_forcelist=(429, 500, 502, 503, 504),
):
    session = requests.Session()
    retry = Retry(
        total=total_retries,
        read=total_retries,
        connect=total_retries,
        backoff_factor=backoff_factor,
        status_forcelist=status_forcelist,
        allowed_methods=["POST"]
    )
    adapter = HTTPAdapter(max_retries=retry)
    session.mount("https://", adapter)
    session.mount("http://", adapter)
    return session


SESSION = make_session_with_retries()

def truncate_code(code: str) -> str:
    toks = TOKENIZER.encode(code, add_special_tokens=False)
    if len(toks) <= MAX_TOKENS:
        return code
    print(f"    ...truncated a method")
    return TOKENIZER.decode(toks[:MAX_TOKENS], clean_up_tokenization_spaces=False)

def load_graph():
    return Graph(NEO4J_URI, auth=(NEO4J_USER, NEO4J_PASSWORD))

def create_index(graph):

    drop = f"""
    DROP INDEX {INDEX_NAME} IF EXISTS
    """
    try:
        graph.run(drop)
    except Exception as e:
        print("Failed to drop index:", e)

    
    create = f"""
    CREATE VECTOR INDEX {INDEX_NAME} 
    FOR (m:Method) ON (m.embedding)
    OPTIONS {{
        indexConfig: {{
            `vector.dimensions`: {EMBEDDING_DIM},
            `vector.similarity_function`: 'cosine'
        }}
    }}
    """
    try:
        graph.run(create)
        print(f"Vector index '{INDEX_NAME}' created.")
    except Exception as e:
        print("Failed to create index:", e)

def query_huggingface(text_list, max_attempts=2):
    payload = {"inputs": text_list}

    try:
        resp = SESSION.post(API_URL, headers=HEADERS, json=payload, timeout=30)
        resp.raise_for_status()
    except requests.exceptions.HTTPError as http_err:
        # If we hit a token‐limit error, try once with truncation
        err_msg = ""
        try:
            err_msg = resp.json().get("error", "")
        except Exception:
            pass

        if resp.status_code == 400 and "must have less than" in err_msg and max_attempts > 0:
            # logging.warning("Hit token limit; truncating and retrying…")
            truncated = [truncate_code(c) for c in text_list]
            return query_huggingface(truncated, max_attempts - 1)
        else:
            logging.error(f"HTTP error from HF API: {resp.status_code} {resp.text}")
            return []
    except (requests.exceptions.ConnectionError,
            requests.exceptions.Timeout) as conn_err:
        logging.error(f"Connection error: {conn_err}")
        return []
    else:
        return resp.json()

def embed_methods(graph):
    total_count = graph.evaluate("MATCH (m:Method) WHERE m.code IS NOT NULL RETURN count(m)")
    print(f"Total methods to embed: {total_count}")

    for skip in range(0, total_count, BATCH_SIZE):
        print(f"Embedding methods {skip} to {skip + BATCH_SIZE} of {total_count}")

        query = f"""
        MATCH (m:Method)
        WHERE m.code IS NOT NULL
        RETURN id(m) as id, m.code as code
        SKIP {skip}
        LIMIT {BATCH_SIZE}
        """
        methods = graph.run(query).data()
        if not methods:
            break

        ids = [row["id"] for row in methods]
        codes = [row["code"] for row in methods]
        embeddings = query_huggingface(codes)

        if not embeddings or len(embeddings) != len(ids):
            print("Warning: Embedding count mismatch; skipping batch.")
            continue

        for node_id, emb in zip(ids, embeddings):
            graph.run("""
                MATCH (m) WHERE id(m) = $id
                SET m.embedding = $embedding
            """, id=node_id, embedding=emb)

    print("\nReady to search.")

def main():
    print(f"Embedding your code.")
    print(f"fallback truncation size: {MAX_TOKENS} tokens")

    graph = load_graph()
    create_index(graph)
    embed_methods(graph)

if __name__ == "__main__":
    main()