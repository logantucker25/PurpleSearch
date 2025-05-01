package query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet; // We'll rely on ScrapeJava’s driver & isNeo4jAvailable()
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.neo4j.driver.types.Node;

import api.InferenceConfig;
import gen.AIClient;
import scrape.DbClient;

public class UserQuery {

    public static JSONObject runEmbeddingQuery(String query, int topN) {
        // Verify that Neo4j is available (via ScrapeJava).
        if (!DbClient.isNeo4jAvailable()) {
            System.err.println("[UserQuery] Neo4j not available. Aborting query.");
            // Return empty results
            return new JSONObject();
        }

        String embUrl = InferenceConfig.embeddingsUrl;
        String embToken = InferenceConfig.embeddingsToken;

        if (embUrl == null || embToken == null) {
            System.err.println("[UserQuery] ERROR: Missing or invalid embeddings config. Cannot proceed.");
            return new JSONObject();
        }

        // Generate an embedding for the user’s query text
        double[] queryEmbedding = AIClient.generateEmbedding(query, embUrl, embToken);
        if (queryEmbedding == null || queryEmbedding.length == 0) {
            System.err.println("[UserQuery] ERROR: Embedding generation returned empty/failed. Query: " + query);
            return new JSONObject();
        }

        // turn into clusters here
        List<JSONObject> codeMatches = findSimilarNodes(queryEmbedding, topN);
        List<JSONObject> clusters = getClusters(codeMatches);

        JSONArray graphData = new JSONArray();
        for (JSONObject cluster : clusters) {
            graphData.put(cluster);
        }

        // llm digest info
        JSONArray chatData = getChatData(clusters);

        JSONObject result = new JSONObject();
        result.put("graph", graphData);
        result.put("chat", chatData);
        return result;
    }

    private static List<JSONObject> findSimilarNodes(double[] queryEmbedding, int limit) {
        List<JSONObject> resultNodes = new ArrayList<>();

        if (!DbClient.isNeo4jAvailable() || queryEmbedding == null) {
            System.err.println("[UserQuery] Cannot query: driver not available or queryEmbedding is null.");
            return resultNodes;
        }

        String indexName = "methodEmbeddings";

        String cypher = String.format("""
            CALL db.index.vector.queryNodes('%s', %d, $queryEmbedding)
              YIELD node, score
            RETURN
              id(node)       AS id,
              score          AS similarity
            ORDER BY similarity DESC
            """, indexName, limit);

        try (Session session = DbClient.getNeo4jDriver().session()) {
            var rs = session.run(cypher,
                    Values.parameters("queryEmbedding", queryEmbedding));
            while (rs.hasNext()) {
                var record = rs.next();
                JSONObject json = new JSONObject();
                json.put("id", record.get("id").asLong());
                json.put("similarity", record.get("similarity").asDouble());
                resultNodes.add(json);
            }
        } catch (Exception e) {
            System.err.println("Error querying Neo4j for initial matches: " + e.getMessage());
        }

        return resultNodes;
    }

    private static List<String> getCypherStack() {

        List<String> stack = new ArrayList<>();

        // methods along calls_method edges up to 3 hops away undirected
        String cypher1 = """
        MATCH (start)
        WHERE id(start) = $id

        MATCH (start)-[:CALLS_METHOD*1..3]-(n)
        RETURN collect(DISTINCT n) AS nodes, [] AS rels
        """;

        stack.add(cypher1);

        // nodes 2 hop walks in any direction (LIM 30)
        String cypher2 = """
        MATCH (start)
        WHERE id(start) = $id

        MATCH (start)-[*..2]-(n)
        WITH DISTINCT n
        LIMIT 30
        RETURN collect(n) AS nodes, [] AS rels
        """;

        stack.add(cypher2);

        // nodes 2 hop walks in any direction (LIM 30)
        String cypher3 = """
        MATCH (start)
        WHERE id(start) = $id

        MATCH path = (start)-[*..3]-(m:Method)
        WHERE id(m) <> id(start)
        WITH m, length(path) AS depth
        ORDER BY depth ASC
        LIMIT 10

        RETURN collect(m) AS nodes, [] AS rels
        """;

        stack.add(cypher3);

        // start node only
        String cypher4 = """
        MATCH (start)
        WHERE id(start) = $id
        RETURN [start] AS nodes, [] AS rels
        """;

        stack.add(cypher4);

        return stack;
    }

    private static List<JSONObject> getClusters(List<JSONObject> methodIds) {

        List<JSONObject> clusters = new ArrayList<>();

        if (!DbClient.isNeo4jAvailable()) {
            System.err.println("[getClusters] Neo4j not available. Returning empty clusters.");
            return clusters;
        }

        try (Session session = DbClient.getNeo4jDriver().session()) {

            for (JSONObject match : methodIds) {
                long nodeId = match.getLong("id");

                Set<Long> nodeIds = new HashSet<>();
                Set<Long> relIds = new HashSet<>();

                List<String> cyphers = getCypherStack();

                for (String cypher : cyphers) {

                    var result = session.run(cypher, Values.parameters("id", nodeId));

                    while (result.hasNext()) {
                        var rec = result.next();

                        if (rec.containsKey("nodes")) {
                            for (var value : rec.get("nodes").values()) {
                                nodeIds.add(value.asNode().id());
                            }
                        }

                        if (rec.containsKey("rels")) {
                            for (var value : rec.get("rels").values()) {
                                relIds.add(value.asRelationship().id());
                            }
                        }
                    }
                }

                // Build nodes list: [{ id: 3735 }, { id: 3729 }, …]
                List<JSONObject> simpleNodes = new ArrayList<>();
                for (Long id : nodeIds) {
                    simpleNodes.add(new JSONObject().put("id", String.valueOf(id)));
                }

                // Build rels list: [{ id: 1152… }, { id: 1157… }, …]
                List<JSONObject> simpleRels = new ArrayList<>();
                for (Long id : relIds) {
                    simpleRels.add(new JSONObject().put("id", String.valueOf(id)));
                }

                JSONObject simpleCluster = new JSONObject()
                        .put("nodes", simpleNodes)
                        .put("rels", simpleRels);

                clusters.add(simpleCluster);
            }

        } catch (Exception e) {
            System.err.println("[getClusters] Error fetching clusters: " + e.getMessage());
        }

        return clusters;
    }

    private static JSONArray getChatData(List<JSONObject> clusters) {
        JSONArray data = new JSONArray();

        if (!DbClient.isNeo4jAvailable()) {
            System.err.println("[getSecondScreenData] Neo4j not available. Returning empty data.");
            return data;
        }

        try (Session session = DbClient.getNeo4jDriver().session()) {
            for (JSONObject clusterSummary : clusters) {

                // Extract the raw ID lists
                JSONArray nodeArray = clusterSummary.getJSONArray("nodes");
                JSONArray relArray = clusterSummary.getJSONArray("rels");

                List<Long> nodeIds = new ArrayList<>();
                for (int i = 0; i < nodeArray.length(); i++) {
                    nodeIds.add(nodeArray.getJSONObject(i).getLong("id"));
                }
                List<Long> relIds = new ArrayList<>();
                for (int i = 0; i < relArray.length(); i++) {
                    relIds.add(relArray.getJSONObject(i).getLong("id"));
                }

                // Fetch full node details
                List<JSONObject> nodesDetail = new ArrayList<>();
                String nodeQuery
                        = "MATCH (n) WHERE id(n) IN $ids "
                        + "RETURN id(n) AS id, labels(n) AS labels, n AS node";
                var nodeResult = session.run(nodeQuery, Values.parameters("ids", nodeIds));
                while (nodeResult.hasNext()) {
                    var rec = nodeResult.next();
                    long id = rec.get("id").asLong();
                    List<String> labels = rec.get("labels").asList(v -> v.asString());
                    Node n = rec.get("node").asNode();

                    Map<String, Object> props = new HashMap<>(n.asMap());
                    props.remove("embedding");
                    props.remove("start_line");
                    props.remove("end_line");

                    JSONObject no = new JSONObject()
                            .put("id", id)
                            .put("labels", labels)
                            .put("properties", new JSONObject(props));
                    nodesDetail.add(no);
                }

                // Fetch full relationship details
                List<JSONObject> relsDetail = new ArrayList<>();
                String relQuery
                        = "MATCH ()-[r]-() WHERE id(r) IN $ids "
                        + "RETURN id(r) AS id, type(r) AS type, "
                        + "id(startNode(r)) AS start, id(endNode(r)) AS end, "
                        + "properties(r) AS props";
                var relResult = session.run(relQuery, Values.parameters("ids", relIds));
                while (relResult.hasNext()) {
                    var rec = relResult.next();
                    String type = rec.get("type").asString();
                    long start = rec.get("start").asLong();
                    long end = rec.get("end").asLong();

                    JSONObject ro = new JSONObject()
                            .put("type", type)
                            .put("start", start)
                            .put("end", end);
                    relsDetail.add(ro);
                }

                // Package into one cluster‐object
                JSONObject clusterData = new JSONObject()
                        .put("nodes", nodesDetail)
                        .put("relationships", relsDetail);

                data.put(clusterData);
            }

        } catch (Exception e) {
            System.err.println("[getSecondScreenData] caught exception:");
            e.printStackTrace();
        }

        return data;
    }
}
