package gen;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.json.JSONArray;
import org.json.JSONObject;

public class AIClient {

    private static final HttpClient httpClient = HttpClient.newHttpClient();

    private AIClient() {
        /* Utility class; no public constructor. */ }

    public static double[] generateEmbedding(String text, String url, String token) {
        if (text == null || url == null || token == null) {
            System.err.println("Missing required parameter for embedding.");
            return new double[0];
        }
        try {
            return callHuggingFaceEmbedding(text, url, token);
        } catch (Exception e) {
            System.err.println("Error while requesting embedding: " + e.getMessage());
            return new double[0];
        }
    }

    private static double[] callHuggingFaceEmbedding(String text, String url, String token) throws IOException, InterruptedException {

        JSONObject bodyJson = new JSONObject();
        bodyJson.put("inputs", text);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("HuggingFace embedding error "
                    + response.statusCode() + ": " + response.body());
        }

        JSONArray embArray = new JSONArray(response.body());
        if (embArray.length() == 0) {
            System.err.println("HuggingFace returned an empty array for embeddings");
            return new double[0];
        }

        JSONArray vector;
        if (embArray.get(0) instanceof JSONArray) {
            vector = embArray.getJSONArray(0);
        } else {
            vector = embArray;
        }

        double[] embedding = new double[vector.length()];
        for (int i = 0; i < vector.length(); i++) {
            embedding[i] = vector.getDouble(i);
        }
        return embedding;
    }
}
