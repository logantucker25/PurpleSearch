package api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.json.JSONException;
import org.json.JSONObject;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import query.UserQuery;

public class QueryHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        try (InputStream is = exchange.getRequestBody()) {
            byte[] bodyBytes = is.readAllBytes();
            String bodyString = new String(bodyBytes, StandardCharsets.UTF_8);

            JSONObject json = new JSONObject(bodyString);
            String prompt = json.optString("prompt", "").trim();

            if (prompt.isEmpty()) {
                sendErrorResponse(exchange, 400, "Missing or empty 'prompt'");
                return;
            }

            // Top K is manual right now
            JSONObject obj = UserQuery.runEmbeddingQuery(prompt, 10);

            // System.out.println(obj.toString());
            byte[] respBytes = obj.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, respBytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }

        } catch (JSONException e) {
            e.printStackTrace();
            sendErrorResponse(exchange, 400, "Invalid JSON: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
        }
    }

    private void sendErrorResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] responseBytes = message.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }
}
