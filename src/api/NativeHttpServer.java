package api;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONObject;
import org.neo4j.driver.Session;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import scrape.DbClient;
import seek.FileWalker;

public class NativeHttpServer {

    private final HttpServer server;

    private static final ExecutorService embedPool = Executors.newSingleThreadExecutor();

    public NativeHttpServer(int port) throws IOException {

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/api/uploadDirectory", new UploadDirectoryHandler());
        server.createContext("/api/processUploads", new ProcessUploadsHandler());

        server.createContext("/api/query", new QueryHandler());

        server.createContext("/api/connectToNeo4j", new ConnectNeo4jHandler());
        server.createContext("/api/isGraphEmpty", new IsGraphEmptyHandler());
        server.createContext("/api/resetGraph", new ResetGraphHandler());

        server.createContext("/api/getInferenceConfig", new GetInferenceConfigHandler());
        server.createContext("/api/setInferenceConfig", new SetInferenceConfigHandler());

        server.createContext("/api/logs", new LogsHandler());

        server.createContext("/api/openaiChat", new OpenaiChatHandler());

        server.createContext("/api/getNeo4jConnection", new GetNeo4jConnectionHandler());

    }

    public void start() {
        server.start();
        System.out.println("Server running on http://localhost:" + server.getAddress().getPort());
    }

    static class ResetGraphHandler implements HttpHandler {

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
                exchange.sendResponseHeaders(405, -1); // Method Not Allowed
                return;
            }

            try {
                // Make sure we've connected to Neo4j 
                if (!DbClient.isNeo4jAvailable()) {
                    // Optionally do ScrapeJava.initNeo4jConnection() if you expect an auto-reconnect
                    exchange.sendResponseHeaders(500, -1);
                    return;
                }

                // Delete all nodes/relationships
                try (Session session = DbClient.getNeo4jDriver().session()) {
                    session.run("MATCH (n) DETACH DELETE n");
                }

                // Return success
                String response = "Graph has been reset successfully.";
                byte[] respBytes = response.getBytes();
                exchange.sendResponseHeaders(200, respBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(respBytes);
                }

            } catch (Exception e) {
                e.printStackTrace();
                exchange.sendResponseHeaders(500, -1);
            }
        }
    }

    static class IsGraphEmptyHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "*");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1); // Method Not Allowed
                return;
            }

            // Now query Neo4j to see if any nodes exist
            boolean isEmpty = false;
            try {
                long count = 0;
                try (Session session = DbClient.getNeo4jDriver().session()) {
                    var result = session.run("MATCH (n) RETURN count(n) AS c");
                    if (result.hasNext()) {
                        count = result.next().get("c").asLong();
                    }
                    System.out.println("Node count: " + count);

                }
                isEmpty = (count == 0);
            } catch (Exception e) {
                e.printStackTrace();
                // If there's an error, treat it as not empty or just respond with an error
                exchange.sendResponseHeaders(500, -1);
                return;
            }

            String jsonResp = "{\"isEmpty\":" + isEmpty + "}";
            byte[] respBytes = jsonResp.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        }
    }

    static class GetInferenceConfigHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {

            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "*");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            JSONObject json = new JSONObject();
            JSONObject embObj = new JSONObject();
            embObj.put("url", InferenceConfig.getEmbeddingsUrl());
            embObj.put("token", InferenceConfig.getEmbeddingsToken());
            embObj.put("dim", InferenceConfig.getEmbeddingsDim());
            embObj.put("tpe", InferenceConfig.getEmbeddingsTokensPerEmb());
            json.put("embeddings", embObj);

            JSONObject llmObj = new JSONObject();
            llmObj.put("provider", InferenceConfig.getLLMProvider());
            llmObj.put("model", InferenceConfig.getLLMModel());
            llmObj.put("apiKey", InferenceConfig.getLLMApiKey());
            llmObj.put("tpr", InferenceConfig.getLLMTokensPerRequest());
            json.put("llm", llmObj);

            // Return 404 if your config is "unset"
            if (InferenceConfig.getEmbeddingsUrl() == null
                    && InferenceConfig.getLLMProvider() == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            String response = json.toString();
            byte[] respBytes = response.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, respBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(respBytes);
            }
        }
    }

    static class SetInferenceConfigHandler implements HttpHandler {

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

            try (InputStream is = exchange.getRequestBody()) {
                byte[] rawBody = is.readAllBytes();
                if (rawBody.length == 0) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"Empty request body\"}");
                    return;
                }

                String bodyString = new String(rawBody);
                System.out.println("SetInferenceConfig body: " + bodyString);

                JSONObject json = new JSONObject(bodyString);

                System.out.println("json version of set config");
                System.out.println(json);

                JSONObject embObj = json.optJSONObject("embeddings");
                if (embObj != null) {
                    String eUrl = embObj.optString("url", null);
                    String eToken = embObj.optString("token", null);
                    String eDim = embObj.optString("dim", null);
                    String eTpe = embObj.optString("tpe", null);

                    InferenceConfig.setEmbeddingsConfig(eUrl, eToken, eDim, eTpe);
                }

                JSONObject llmObj = json.optJSONObject("llm");
                if (llmObj != null) {
                    String lProvider = llmObj.optString("provider", null);
                    String lModel = llmObj.optString("model", null);
                    String lApiKey = llmObj.optString("apiKey", null);
                    String lTpr = llmObj.optString("tpr", null);

                    InferenceConfig.setLLMConfig(lProvider, lModel, lApiKey, lTpr);
                }

                sendJsonResponse(exchange, 200, "{\"status\":\"ok\"}");
            } catch (Exception e) {
                e.printStackTrace();
                sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }

        private void sendJsonResponse(HttpExchange exchange, int status, String json) throws IOException {
            byte[] bytes = json.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    static class UploadDirectoryHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "*");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            if ("HEAD".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendErrorResponse(exchange, 405, "Method not allowed. Use POST.");
                return;
            }

            try (InputStream is = exchange.getRequestBody()) {
                System.out.println("Upload request received...");
                byte[] rawBody = is.readAllBytes();
                if (rawBody.length == 0) {
                    sendErrorResponse(exchange, 400, "Empty request body");
                    return;
                }

                String bodyString = new String(rawBody);
                System.out.println("Request JSON: " + bodyString);

                JSONObject json;
                try {
                    json = new JSONObject(bodyString);
                } catch (Exception e) {
                    sendErrorResponse(exchange, 400, "Invalid JSON: " + e.getMessage());
                    return;
                }

                String projectRoot;
                try {
                    projectRoot = json.getString("projectRoot");
                } catch (Exception e) {
                    sendErrorResponse(exchange, 400, "Missing or invalid project path: " + e.getMessage());
                    return;
                }

                if (projectRoot == null || projectRoot == "") {
                    sendErrorResponse(exchange, 500, "Path not to a valid directory");
                    return;
                }

                // Ensure path is a valid dir
                Boolean validPath = false;
                Path dir = Paths.get(projectRoot);
                if (!(Files.exists(dir) && Files.isDirectory(dir))) {
                    sendErrorResponse(exchange, 500, "Path not to a valid directory");
                    return;
                }

                String response = "Success projectRoot is valid dir";
                int status = 207;

                byte[] respBytes = response.getBytes();
                exchange.sendResponseHeaders(status, respBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(respBytes);
                }

            } catch (Exception e) {
                e.printStackTrace();
                sendErrorResponse(exchange, 500, "Internal server error: " + e.getMessage());
            }
        }

        private void sendErrorResponse(HttpExchange exchange, int statusCode, String message) throws IOException {
            byte[] responseBytes = message.getBytes();
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        }
    }

    static class ConnectNeo4jHandler implements HttpHandler {

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

            try (InputStream is = exchange.getRequestBody()) {
                byte[] rawBody = is.readAllBytes();
                if (rawBody.length == 0) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"Empty request body\"}");
                    return;
                }

                String bodyString = new String(rawBody);
                JSONObject json = new JSONObject(bodyString);

                String url = json.optString("url", null);
                String user = json.optString("username", null);
                String pass = json.optString("password", null);

                if (url == null || user == null || pass == null) {
                    sendJsonResponse(exchange, 400, "{\"error\":\"Missing one of url/username/password\"}");
                    return;
                }

                // Set config
                InferenceConfig.setNeo4jConfig(url, user, pass);
                // Re-initialize the driver with these creds
                DbClient.initNeo4jConnection();

                boolean isAvailable = DbClient.isNeo4jAvailable();

                // Return success or failure
                if (isAvailable) {
                    sendJsonResponse(exchange, 200, "{\"connected\":true}");
                } else {
                    sendJsonResponse(exchange, 200, "{\"connected\":false}");
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendJsonResponse(exchange, 500, "{\"error\":\"" + e.getMessage() + "\"}");
            }
        }

        private void sendJsonResponse(HttpExchange exchange, int status, String json) throws IOException {
            byte[] bytes = json.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    static class GetNeo4jConnectionHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, OPTIONS");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "*");

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1); // Method Not Allowed
                return;
            }

            JSONObject json = new JSONObject();
            json.put("url", InferenceConfig.getNeo4jUrl());
            json.put("user", InferenceConfig.getNeo4jUser());
            json.put("password", InferenceConfig.getNeo4jPassword());

            byte[] bytes = json.toString().getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    static class ProcessUploadsHandler implements HttpHandler {

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

            try (InputStream is = exchange.getRequestBody()) {
                byte[] rawBody = is.readAllBytes();
                String bodyString = new String(rawBody);

                JSONObject json = new JSONObject(bodyString);

                JSONObject embObj = json.optJSONObject("embeddings");
                if (embObj != null) {
                    String eUrl = embObj.optString("url", null);
                    String eToken = embObj.optString("token", null);
                    String eDim = embObj.optString("dim", null);
                    String eTpe = embObj.optString("tpe", null);

                    InferenceConfig.setEmbeddingsConfig(eUrl, eToken, eDim, eTpe);
                }

                JSONObject llmObj = json.optJSONObject("llm");
                if (llmObj != null) {
                    String lProvider = llmObj.optString("provider", null);
                    String lModel = llmObj.optString("model", null);
                    String lApiKey = llmObj.optString("apiKey", null);
                    String lTpr = llmObj.optString("tpr", null);

                    InferenceConfig.setLLMConfig(lProvider, lModel, lApiKey, lTpr);
                }

                DbClient.initNeo4jConnection(); // we rely on the config set by connectToNeo4j
                System.out.println("Processing 'uploads' with FileWalker...");
                String projectRoot = json.getString("projectRoot");
                FileWalker.processProjectFiles(projectRoot);

                // run embeddings Python script 
                embedPool.submit(() -> {
                    try {
                        Path pythonBin = Paths.get("venv", "bin", "python");
                        Path script = Paths.get("src/gen/embed.py");

                        ProcessBuilder pb = new ProcessBuilder(
                                pythonBin.toAbsolutePath().toString(),
                                script.toAbsolutePath().toString(),
                                "--hf-url", InferenceConfig.getEmbeddingsUrl(),
                                "--hf-token", InferenceConfig.getEmbeddingsToken(),
                                "--hf-dim", InferenceConfig.getEmbeddingsDim(),
                                "--hf-tpe", InferenceConfig.getEmbeddingsTokensPerEmb(),
                                "--neo4j-uri", InferenceConfig.getNeo4jUrl(),
                                "--neo4j-user", InferenceConfig.getNeo4jUser(),
                                "--neo4j-pass", InferenceConfig.getNeo4jPassword()
                        );

                        // redirect all output into loading.log 
                        File logFile = Paths.get(projectRoot, "loading.log").toFile();
                        pb.redirectOutput(logFile);
                        pb.redirectErrorStream(true);

                        pb.start();
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                });

                String resp = "Embedding started in background.";
                byte[] bytes = resp.getBytes();
                exchange.sendResponseHeaders(202, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception e) {
                e.printStackTrace();
                exchange.sendResponseHeaders(500, -1);
            }
        }
    }

    /*  Inside NativeHttpServer.java  (outside the other handler classes) */
    static class LogsHandler implements HttpHandler {

        // file you want to stream – change as needed
        private static final Path LOG_FILE = Paths.get("loading.log");

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            exchange.getResponseHeaders().add("Connection", "keep-alive");

            exchange.sendResponseHeaders(200, 0);

            try (OutputStream out = exchange.getResponseBody(); RandomAccessFile raf = new RandomAccessFile(LOG_FILE.toFile(), "r")) {
                // start at end of file so we only send new lines
                long position = raf.length();
                while (true) {
                    long len = raf.length();
                    if (len < position) {
                        position = len;
                    } else if (len > position) {   // new data appended
                        raf.seek(position);
                        String line;
                        while ((line = raf.readLine()) != null) {
                            out.write(("data: " + line + "\n\n").getBytes());
                            out.flush();           // push immediately
                        }
                        position = raf.getFilePointer();
                    }

                    // poll every 300 ms so we don’t spin‑cpu 
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            } catch (IOException clientGone) {
                // browser closed tab – just exit the handler
            }
        }
    }
}
