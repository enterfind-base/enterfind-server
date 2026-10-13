package enterfind.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Fake Ollama server for testing purposes.
 * Simulates Ollama API responses for integration tests.
 */
public class FakeOllamaServer {

    private static final Logger logger = LoggerFactory.getLogger(FakeOllamaServer.class);
    private static final ObjectMapper objectMapper = new JsonMapper();

    private final HttpServer server;
    private final int port;
    private final Map<String, ResponseProvider> responseProviders = new HashMap<>();
    private boolean shouldFail = false;

    /**
     * Create a fake Ollama server on a random available port.
     */
    public FakeOllamaServer() throws IOException {
        this(0); // 0 means random available port
    }

    /**
     * Create a fake Ollama server on a specific port.
     *
     * @param port the port to bind to (0 for random)
     */
    public FakeOllamaServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        this.port = server.getAddress().getPort();
        server.setExecutor(null); // Use default executor
        setupDefaultHandlers();
    }

    /**
     * Setup default request handlers.
     */
    private void setupDefaultHandlers() {
        // Handler for chat completions (Spring AI 1.1.5 uses /api/chat)
        server.createContext("/api/chat", exchange -> {
            handleChatRequest(exchange);
        });
        
        // Legacy handler for generate (kept for backward compatibility)
        server.createContext("/api/generate", exchange -> {
            handleChatRequest(exchange);
        });
    }

    /**
     * Handle chat/generate requests.
     */
    private void handleChatRequest(HttpExchange exchange) {
        try {
            logger.info("Fake Ollama Server: Received request to {}", exchange.getRequestURI().getPath());

            if (shouldFail) {
                sendError(exchange, 500, "Simulated server error");
                return;
            }

            // Read request body
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            logger.info("Fake Ollama Server: Request body: {}", requestBody);

            JsonNode requestJson = objectMapper.readTree(requestBody);
            
            // Extract prompt from messages array (Spring AI 1.1.5 format) or prompt field (legacy)
            String prompt;
            if (requestJson.has("messages")) {
                // Spring AI 1.1.5 format: messages array
                JsonNode messages = requestJson.get("messages");
                if (messages.isArray() && messages.size() > 0) {
                    prompt = messages.get(messages.size() - 1).path("content").asString();
                } else {
                    prompt = "";
                }
            } else {
                // Legacy format: prompt field
                prompt = requestJson.path("prompt").asString();
            }

            // Determine which response provider to use based on prompt content
            String responseText = getResponseForPrompt(prompt);
            logger.info("Fake Ollama Server: Response text: {}", responseText);

            // Build Ollama-style response
            ObjectNode response = objectMapper.createObjectNode();
            response.put("model", requestJson.path("model").asString("llama2"));
            
            // Spring AI 1.1.5 expects message format
            ObjectNode message = objectMapper.createObjectNode();
            message.put("role", "assistant");
            message.put("content", responseText);
            response.set("message", message);
            
            // Also include legacy response field for compatibility
            response.put("response", responseText);
            response.put("done", true);

            sendJsonResponse(exchange, 200, response);
        } catch (Exception e) {
            logger.error("Error handling request", e);
            try {
                sendError(exchange, 500, "Internal server error: " + e.getMessage());
            } catch (Exception ex) {
                logger.error("Error sending error response", ex);
            }
        }
    }

    /**
     * Get the appropriate response for a given prompt.
     */
    private String getResponseForPrompt(String prompt) {
        // Check for registered response providers
        for (Map.Entry<String, ResponseProvider> entry : responseProviders.entrySet()) {
            if (prompt.contains(entry.getKey())) {
                return entry.getValue().provide(prompt);
            }
        }

        // Default behavior based on prompt content
        if (prompt.contains("Reply with 'OK'")) {
            return "OK";
        } else if (prompt.contains("converts natural language descriptions into cron expressions")) {
            // Extract natural language from prompt
            String naturalLanguage = extractNaturalLanguage(prompt);
            return interpretCronExpression(naturalLanguage);
        } else if (prompt.contains("explains cron expressions in natural language")) {
            // Extract cron expression from prompt
            String cronExpression = extractCronExpression(prompt);
            return explainCronExpression(cronExpression);
        } else if (prompt.contains("converts natural language descriptions into regex patterns for file filtering")) {
            // Extract natural language from prompt
            String naturalLanguage = extractNaturalLanguageForSkipPattern(prompt);
            return interpretSkipPattern(naturalLanguage);
        } else if (prompt.contains("explains regex patterns for file filtering in natural language")) {
            // Extract skip pattern from prompt
            String skipPattern = extractSkipPattern(prompt);
            return explainSkipPattern(skipPattern);
        } else if (prompt.contains("search query interpreter for EnterFind")) {
            // Extract natural language query from prompt
            String query = extractSearchQuery(prompt);
            return interpretSearchQuery(query);
        }

        return "OK";
    }

    /**
     * Extract search query from interpretation prompt.
     */
    private String extractSearchQuery(String prompt) {
        int startIdx = prompt.indexOf("Natural language query:");
        if (startIdx == -1) return "find all pdf files";

        startIdx += "Natural language query:".length();
        int endIdx = prompt.indexOf("JSON:", startIdx);
        if (endIdx == -1) endIdx = prompt.length();

        return prompt.substring(startIdx, endIdx).trim();
    }

    /**
     * Simple search query interpretation logic for testing.
     * Returns JSON response matching expected format.
     */
    private String interpretSearchQuery(String query) {
        String lower = query.toLowerCase();
        
        if (lower.contains("pdf files") || lower.contains("pdf documents")) {
            return """
                {
                    "searchString": null,
                    "searchConditions": [
                        {"name": "content-type", "operation": "LIKE", "value": "pdf"}
                    ],
                    "explanation": "Finding all PDF files by filtering content type",
                    "confidence": 0.95
                }""";
        } else if (lower.contains("not in") && (lower.contains("folder") || lower.contains("directory"))) {
            // Check "not in folder" BEFORE "in folder" to avoid false matches
            String folder = extractFolderName(lower);
            return """
                {
                    "searchString": null,
                    "searchConditions": [
                        {"name": "path", "operation": "NOT_LIKE", "value": "%s"}
                    ],
                    "explanation": "Finding files NOT in folder containing '%s'",
                    "confidence": 0.85
                }""".formatted(folder, folder);
        } else if (lower.contains("in folder") || lower.contains("in the folder")) {
            // Extract folder name
            String folder = extractFolderName(lower);
            return """
                {
                    "searchString": null,
                    "searchConditions": [
                        {"name": "path", "operation": "LIKE", "value": "%s"}
                    ],
                    "explanation": "Finding files in folder containing '%s'",
                    "confidence": 0.9
                }""".formatted(folder, folder);
        } else if (lower.contains("image") || lower.contains("images") || lower.contains("pictures")) {
            return """
                {
                    "searchString": null,
                    "searchConditions": [
                        {"name": "content-type", "operation": "LIKE", "value": "image"}
                    ],
                    "explanation": "Finding all image files",
                    "confidence": 0.95
                }""";
        } else if (lower.contains("containing") || lower.contains("about") || lower.contains("mentions")) {
            String term = extractSearchTerm(lower);
            return """
                {
                    "searchString": "%s",
                    "searchConditions": [],
                    "explanation": "Searching for content containing '%s'",
                    "confidence": 0.8
                }""".formatted(term, term);
        } else if (lower.contains("named") || lower.contains("called") || lower.contains("name is")) {
            String name = extractFileName(lower);
            return """
                {
                    "searchString": null,
                    "searchConditions": [
                        {"name": "name", "operation": "LIKE", "value": "%s"}
                    ],
                    "explanation": "Finding files with name containing '%s'",
                    "confidence": 0.9
                }""".formatted(name, name);
        } else if (lower.contains("spreadsheet") || lower.contains("excel")) {
            return """
                {
                    "searchString": null,
                    "searchConditions": [
                        {"name": "content-type", "operation": "LIKE", "value": "spreadsheet"}
                    ],
                    "explanation": "Finding spreadsheet files",
                    "confidence": 0.9
                }""";
        }

        // Default: treat as free text search
        return """
            {
                "searchString": "%s",
                "searchConditions": [],
                "explanation": "Performing free text search for '%s'",
                "confidence": 0.6
            }""".formatted(query, query);
    }

    /**
     * Extract folder name from query like "in folder documents" or "in the archive folder".
     */
    private String extractFolderName(String query) {
        // Try "in folder X" pattern
        int idx = query.indexOf("folder");
        if (idx != -1) {
            String remaining = query.substring(idx + 6).trim();
            // Get first word after "folder"
            String[] words = remaining.split("\\s+");
            if (words.length > 0 && !words[0].isEmpty()) {
                return words[0];
            }
        }
        // Try "in the X folder" pattern
        idx = query.indexOf("in the ");
        if (idx != -1) {
            String remaining = query.substring(idx + 7).trim();
            int folderIdx = remaining.indexOf(" folder");
            if (folderIdx > 0) {
                return remaining.substring(0, folderIdx);
            }
        }
        return "documents"; // default
    }

    /**
     * Extract search term from queries like "containing budget" or "about sales".
     */
    private String extractSearchTerm(String query) {
        for (String keyword : new String[]{"containing", "about", "mentions"}) {
            int idx = query.indexOf(keyword);
            if (idx != -1) {
                String remaining = query.substring(idx + keyword.length()).trim();
                String[] words = remaining.split("\\s+");
                if (words.length > 0 && !words[0].isEmpty()) {
                    return words[0];
                }
            }
        }
        return "keyword";
    }

    /**
     * Extract file name from queries like "named report" or "called budget".
     */
    private String extractFileName(String query) {
        for (String keyword : new String[]{"named", "called", "name is"}) {
            int idx = query.indexOf(keyword);
            if (idx != -1) {
                String remaining = query.substring(idx + keyword.length()).trim();
                String[] words = remaining.split("\\s+");
                if (words.length > 0 && !words[0].isEmpty()) {
                    return words[0];
                }
            }
        }
        return "file";
    }

    /**
     * Extract natural language from interpretation prompt.
     */
    private String extractNaturalLanguage(String prompt) {
        int startIdx = prompt.indexOf("Natural language:");
        if (startIdx == -1) return "every 2 hours";

        startIdx += "Natural language:".length();
        int endIdx = prompt.indexOf("Cron expression:", startIdx);
        if (endIdx == -1) endIdx = prompt.length();

        return prompt.substring(startIdx, endIdx).trim();
    }

    /**
     * Extract cron expression from explanation prompt.
     */
    private String extractCronExpression(String prompt) {
        int startIdx = prompt.indexOf("Cron expression:");
        if (startIdx == -1) return "0 */2 * * *";

        startIdx += "Cron expression:".length();
        int endIdx = prompt.indexOf("Short description:", startIdx);
        if (endIdx == -1) endIdx = prompt.length();

        return prompt.substring(startIdx, endIdx).trim();
    }

    /**
     * Simple cron interpretation logic for testing.
     */
    private String interpretCronExpression(String naturalLanguage) {
        String lower = naturalLanguage.toLowerCase();
        if (lower.contains("every 2 hours")) {
            return "0 */2 * * *";
        } else if (lower.contains("every day at 3am") || lower.contains("daily at 3am")) {
            return "0 3 * * *";
        } else if (lower.contains("every monday at 9am")) {
            return "0 9 * * 1";
        } else if (lower.contains("every 15 minutes")) {
            return "*/15 * * * *";
        } else if (lower.contains("every hour")) {
            return "0 * * * *";
        } else if (lower.contains("every 30 minutes")) {
            return "*/30 * * * *";
        }
        return "0 0 * * *"; // Default: daily at midnight
    }

    /**
     * Simple cron explanation logic for testing.
     */
    private String explainCronExpression(String cronExpression) {
        return switch (cronExpression.trim()) {
            case "0 */2 * * *" -> "Every 2 hours";
            case "0 3 * * *" -> "Every day at 3:00 AM";
            case "0 9 * * 1" -> "Every Monday at 9:00 AM";
            case "*/15 * * * *" -> "Every 15 minutes";
            case "0 * * * *" -> "Every hour";
            case "*/30 * * * *" -> "Every 30 minutes";
            case "0 0 * * *" -> "Every day at midnight";
            default -> "Unknown schedule";
        };
    }

    /**
     * Extract natural language from skip pattern interpretation prompt.
     */
    private String extractNaturalLanguageForSkipPattern(String prompt) {
        int startIdx = prompt.indexOf("Natural language:");
        if (startIdx == -1) return "all jpg and png files";

        startIdx += "Natural language:".length();
        int endIdx = prompt.indexOf("Regex pattern:", startIdx);
        if (endIdx == -1) endIdx = prompt.length();

        return prompt.substring(startIdx, endIdx).trim();
    }

    /**
     * Extract skip pattern from explanation prompt.
     */
    private String extractSkipPattern(String prompt) {
        int startIdx = prompt.indexOf("Regex pattern:");
        if (startIdx == -1) return ".*\\.jpg|.*\\.png";

        startIdx += "Regex pattern:".length();
        int endIdx = prompt.indexOf("Short description:", startIdx);
        if (endIdx == -1) endIdx = prompt.length();

        return prompt.substring(startIdx, endIdx).trim();
    }

    /**
     * Simple skip pattern interpretation logic for testing.
     */
    private String interpretSkipPattern(String naturalLanguage) {
        String lower = naturalLanguage.toLowerCase();
        if (lower.contains("all jpg and png files") || lower.contains("jpg and png")) {
            return ".*\\.jpg|.*\\.png";
        } else if (lower.contains("all image files") || lower.contains("image files")) {
            return ".*\\.(jpg|jpeg|png|gif|bmp|svg)";
        } else if (lower.contains("temporary files") || lower.contains("temp files")) {
            return ".*\\.tmp|.*\\.temp|.*~";
        } else if (lower.contains("pdf and doc") || lower.contains("pdf and word")) {
            return ".*\\.pdf|.*\\.(doc|docx)";
        } else if (lower.contains("files starting with test")) {
            return "test.*";
        } else if (lower.contains("hidden files")) {
            return "\\..*";
        }
        return ".*\\.tmp"; // Default: temp files
    }

    /**
     * Simple skip pattern explanation logic for testing.
     */
    private String explainSkipPattern(String skipPattern) {
        return switch (skipPattern.trim()) {
            case ".*\\.jpg|.*\\.png" -> "All JPG and PNG files";
            case ".*\\.(jpg|jpeg|png|gif|bmp|svg)" -> "All image files (JPG, JPEG, PNG, GIF, BMP, SVG)";
            case ".*\\.tmp|.*\\.temp|.*~" -> "All temporary files";
            case ".*\\.pdf|.*\\.(doc|docx)" -> "All PDF and DOC files";
            case "test.*" -> "Files starting with 'test'";
            case "\\..*" -> "Hidden files (starting with dot)";
            default -> "Unknown pattern";
        };
    }

    /**
     * Register a custom response provider for prompts containing a specific keyword.
     *
     * @param keyword the keyword to match in prompts
     * @param provider the response provider
     */
    public void registerResponseProvider(String keyword, ResponseProvider provider) {
        responseProviders.put(keyword, provider);
    }

    /**
     * Set whether the server should fail requests with 500 error.
     *
     * @param shouldFail true to fail requests
     */
    public void setShouldFail(boolean shouldFail) {
        this.shouldFail = shouldFail;
    }

    /**
     * Send a JSON response.
     */
    private void sendJsonResponse(HttpExchange exchange, int statusCode, JsonNode json) throws IOException {
        String response = objectMapper.writeValueAsString(json);
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);

        OutputStream os = exchange.getResponseBody();
        os.write(responseBytes);
        os.flush();
        os.close();
    }

    /**
     * Send an error response.
     */
    private void sendError(HttpExchange exchange, int statusCode, String message) throws IOException {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("error", message);
        sendJsonResponse(exchange, statusCode, error);
    }

    /**
     * Start the fake server.
     */
    public void start() {
        server.start();
        logger.info("Fake Ollama server started on port {}", port);
    }

    /**
     * Stop the fake server.
     */
    public void stop() {
        server.stop(0);
        logger.info("Fake Ollama server stopped");
    }

    /**
     * Get the port the server is running on.
     */
    public int getPort() {
        return port;
    }

    /**
     * Get the base URL of the fake server.
     */
    public String getBaseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * Interface for providing custom responses based on prompts.
     */
    @FunctionalInterface
    public interface ResponseProvider {
        String provide(String prompt);
    }
}
