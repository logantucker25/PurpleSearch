package api;

public class InferenceConfig {

    // Embeddings
    public static String embeddingsUrl;
    public static String embeddingsToken;
    public static String embeddingsDim;
    public static String embeddingsTokensPerEmb;

    // LLM
    public static String llmProvider;
    public static String llmModel;
    public static String llmApiKey;
    public static String llmTokensPerRequest;

    // Neo4j
    public static String neo4jUrl;
    public static String neo4jUser;
    public static String neo4jPassword;

    // -- Setters --
    public static void setEmbeddingsConfig(String url, String token, String dim, String tpe) {
        embeddingsUrl = url;
        embeddingsToken = token;
        embeddingsDim = dim;
        embeddingsTokensPerEmb = tpe;
    }

    public static void setLLMConfig(String provider, String model, String apiKey, String tpr) {
        llmProvider = provider;
        llmModel = model;
        llmApiKey = apiKey;
        llmTokensPerRequest = tpr;
    }

    public static void setNeo4jConfig(String url, String user, String password) {
        neo4jUrl = url;
        neo4jUser = user;
        neo4jPassword = password;
    }

    // -- Getters --
    public static String getEmbeddingsUrl() {
        return embeddingsUrl;
    }
    
    public static String getEmbeddingsToken() {
        return embeddingsToken;
    }

    public static String getEmbeddingsDim() {
        return embeddingsDim;
    }

    public static String getEmbeddingsTokensPerEmb() {
        return embeddingsTokensPerEmb;
    }

    public static String getEmbeddings() {
        return embeddingsDim;
    }

    public static String getLLMProvider() {
        return llmProvider;
    }

    public static String getLLMModel() {
        return llmModel;
    }

    public static String getLLMApiKey() {
        return llmApiKey;
    }

    public static String getLLMTokensPerRequest() {
        return llmTokensPerRequest;
    }

    public static String getNeo4jUrl() {
        return neo4jUrl;
    }

    public static String getNeo4jUser() {
        return neo4jUser;
    }

    public static String getNeo4jPassword() {
        return neo4jPassword;
    }

    public static boolean isConfigured() {
        return (embeddingsUrl != null && embeddingsToken != null && embeddingsDim != null && embeddingsTokensPerEmb != null &&
                llmProvider != null && llmModel != null && llmApiKey != null &&
                neo4jUrl != null && neo4jUser != null && neo4jPassword != null);
    }
}
