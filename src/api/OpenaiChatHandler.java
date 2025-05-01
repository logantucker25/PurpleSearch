package api;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class OpenaiChatHandler implements HttpHandler {

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "*");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(200, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String body;
        try (InputStream is = exchange.getRequestBody()) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        JSONObject reqJson;
        try {
            reqJson = new JSONObject(body);
        } catch (Exception e) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }

        // Expecting { "messages": [ { role, content }, ... ] }
        JSONArray messages = reqJson.optJSONArray("messages");
        if (messages == null) {
            exchange.sendResponseHeaders(400, -1);
            return;
        }

        String model = InferenceConfig.getLLMModel();
        String apiKey = InferenceConfig.getLLMApiKey();

        if (model == null || apiKey == null) {
            exchange.sendResponseHeaders(500, -1);
            return;
        }

        JSONObject payload = new JSONObject()
                .put("model", model)
                .put("messages", messages);

        // System.out.println("~~~~~~~~~~~~~~~~~~~~~payload sent to open ai~~~~~~~~~~~~~~~~~~~");
        // System.out.println(payload.toString(2));
        // Call OpenAI
        String aiResponse;
        try {
            URL url = new URL("https://api.openai.com/v1/chat/completions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();
            InputStream respStream = (status < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            String respBody = new String(respStream.readAllBytes(), StandardCharsets.UTF_8);
            if (status >= 300) {
                throw new IOException("OpenAI returned HTTP " + status + ": " + respBody);
            }

            JSONObject respJson = new JSONObject(respBody);
            // choices[0].message.content
            aiResponse = respJson
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");

        } catch (Exception e) {
            e.printStackTrace();
            exchange.sendResponseHeaders(502, -1);
            return;
        }

        // Return { "reply": aiResponse }
        JSONObject out = new JSONObject().put("reply", aiResponse);
        byte[] outBytes = out.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, outBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(outBytes);
        }
    }
}
