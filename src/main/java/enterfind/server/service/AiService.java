package enterfind.server.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import enterfind.server.dao.SearchOperation;
import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.model.AiSearchInterpretation;
import enterfind.server.model.FieldNameConstants;
import enterfind.server.model.SearchCondition;

/**
 * AI service providing provider-agnostic AI functionality.
 * Supports cron expression interpretation/explanation and skip pattern interpretation/explanation.
 * Replaces OllamaService with multi-provider support.
 */
@Service
public class AiService {

    private static final Logger logger = LoggerFactory.getLogger(AiService.class);

    private final AiProviderFactory providerFactory;

    public AiService(AiProviderFactory providerFactory) {
        this.providerFactory = providerFactory;
    }

    /**
     * Check if AI is configured and available.
     */
    public boolean isConfigured() {
        return providerFactory.isConfigured();
    }

    /**
     * Get the current AI provider name for display.
     */
    public String getProviderName() {
        return providerFactory.getConfiguredProvider().getDisplayName();
    }

    /**
     * Test connection to the AI provider.
     */
    public boolean testConnection() {
        try {
            if (!providerFactory.isConfigured()) {
                logger.warn("AI provider not configured");
                return false;
            }

            ChatModel chatModel = providerFactory.getChatModel();
            String prompt = "Reply with 'OK' if you can read this.";
            ChatResponse response = chatModel.call(new Prompt(prompt));
            
            String responseText = response.getResult().getOutput().getText();
            boolean success = responseText != null && !responseText.isBlank();
            
            logger.info("AI connection test {}: {}", success ? "successful" : "failed", 
                        providerFactory.getConfiguredProvider());
            return success;
        } catch (Exception e) {
            logger.error("AI connection test failed", e);
            return false;
        }
    }

    /**
     * Interpret natural language into a cron expression.
     *
     * @param naturalLanguage the natural language description (e.g., "every 2 hours")
     * @return the interpreted cron expression, or null if interpretation failed
     */
    public String interpretCronExpression(String naturalLanguage) {
        if (naturalLanguage == null || naturalLanguage.isBlank()) {
            return null;
        }

        try {
            ChatModel chatModel = providerFactory.getChatModel();
            String prompt = buildCronInterpretationPrompt(naturalLanguage);
            ChatResponse response = chatModel.call(new Prompt(prompt));
            String responseText = response.getResult().getOutput().getText();

            if (responseText == null) {
                return null;
            }

            // Extract cron expression from response
            return extractCronExpression(responseText);
        } catch (Exception e) {
            logger.error("Failed to interpret cron expression for input: " + naturalLanguage, e);
            return null;
        }
    }

    public String explainCronExpression(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            return null;
        }

        try {
            ChatModel chatModel = providerFactory.getChatModel();
            String prompt = buildCronExplanationPrompt(cronExpression);
            ChatResponse response = chatModel.call(new Prompt(prompt));
            String responseText = response.getResult().getOutput().getText();

            if (responseText == null) {
                return null;
            }

            // Return the explanation (should already be in natural language)
            return responseText.trim();
        } catch (Exception e) {
            logger.error("Failed to explain cron expression: " + cronExpression, e);
            return null;
        }
    }

    /**
     * Interpret a natural language description into a skip pattern regex.
     *
     * @param naturalLanguage the natural language description (e.g., "all jpg and png files")
     * @return the interpreted skip pattern regex, or null if interpretation failed
     */
    public String interpretSkipPattern(String naturalLanguage) {
        if (naturalLanguage == null || naturalLanguage.isBlank()) {
            return null;
        }

        try {
            ChatModel chatModel = providerFactory.getChatModel();
            String prompt = buildSkipPatternInterpretationPrompt(naturalLanguage);
            ChatResponse response = chatModel.call(new Prompt(prompt));
            String responseText = response.getResult().getOutput().getText();

            if (responseText == null) {
                return null;
            }

            // Extract skip pattern from response
            return extractSkipPattern(responseText);
        } catch (Exception e) {
            logger.error("Failed to interpret skip pattern for input: " + naturalLanguage, e);
            return null;
        }
    }

    /**
     * Explain a skip pattern regex in natural language.
     *
     * @param skipPattern the skip pattern regex to explain (e.g., ".*\\.jpg|.*\\.png")
     * @return the natural language explanation, or null if explanation failed
     */
    public String explainSkipPattern(String skipPattern) {
        if (skipPattern == null || skipPattern.isBlank()) {
            return null;
        }

        try {
            ChatModel chatModel = providerFactory.getChatModel();
            String prompt = buildSkipPatternExplanationPrompt(skipPattern);
            ChatResponse response = chatModel.call(new Prompt(prompt));
            String responseText = response.getResult().getOutput().getText();

            if (responseText == null) {
                return null;
            }

            // Return the explanation (should already be in natural language)
            return responseText.trim();
        } catch (Exception e) {
            logger.error("Failed to explain skip pattern: " + skipPattern, e);
            return null;
        }
    }

    // ================= AI Search Query Interpretation =================
    
    private static final ObjectMapper objectMapper = new JsonMapper();

    /**
     * Interpret a natural language search query into structured search parameters.
     * Never throws exceptions for interpretation failures - returns error result instead.
     *
     * @param naturalLanguageQuery the user's natural language search description
     * @param availableKeys list of available facet/field keys for context
     * @return AiSearchInterpretation - always returns a result, check success field
     */
    public AiSearchInterpretation interpretSearchQuery(
            String naturalLanguageQuery, 
            List<String> availableKeys) {
        
        if (naturalLanguageQuery == null || naturalLanguageQuery.isBlank()) {
            return AiSearchInterpretation.error(naturalLanguageQuery, 
                "Please provide a search description.");
        }
        
        if (!providerFactory.isConfigured()) {
            return AiSearchInterpretation.error(naturalLanguageQuery,
                "AI is not configured. Please configure an AI provider in Settings.");
        }
        
        try {
            ChatModel chatModel = providerFactory.getChatModel();
            String prompt = buildSearchInterpretationPrompt(naturalLanguageQuery, availableKeys);
            ChatResponse response = chatModel.call(new Prompt(prompt));
            String responseText = response.getResult().getOutput().getText();
            
            return parseSearchInterpretationResponse(naturalLanguageQuery, responseText);
            
        } catch (Exception e) {
            logger.error("Failed to interpret search query: " + naturalLanguageQuery, e);
            return AiSearchInterpretation.error(naturalLanguageQuery,
                "Unable to interpret your query. AI service error: " + e.getMessage());
        }
    }

    /**
     * Parse the LLM response and construct AiSearchInterpretation.
     * Returns error result if parsing fails or no search parameters were identified.
     */
    private AiSearchInterpretation parseSearchInterpretationResponse(
            String naturalLanguageQuery, String responseText) {
        try {
            String jsonText = extractJson(responseText);
            JsonNode root = objectMapper.readTree(jsonText);
            
            List<SearchCondition> conditions = new ArrayList<>();
            JsonNode conditionsNode = root.get("searchConditions");
            if (conditionsNode != null && conditionsNode.isArray()) {
                for (JsonNode condNode : conditionsNode) {
                    String name = condNode.get("name").asString();
                    String operation = condNode.get("operation").asString();
                    String value = condNode.get("value").asString();
                    SearchOperation parsedOperation;
                    try {
                        parsedOperation = SearchOperation.valueOf(operation);
                    } catch (IllegalArgumentException e) {
                        // This is LLM output, so an unknown name is expected traffic rather than a
                        // programming error. A bare IllegalArgumentException would reach the caller
                        // as an untyped INTERNAL_ERROR.
                        throw new InvalidArgumentException(
                                "Unknown search operation '" + operation + "'");
                    }
                    conditions.add(new SearchCondition(name, parsedOperation, value));
                }
            }
            
            String searchString = root.has("searchString") && !root.get("searchString").isNull() 
                ? root.get("searchString").asString() : null;
            // Treat empty string as null
            if (searchString != null && searchString.isBlank()) {
                searchString = null;
            }
            
            String explanation = root.has("explanation") ? root.get("explanation").asString() : "Query interpreted successfully.";
            Double confidence = root.has("confidence") && !root.get("confidence").isNull() 
                ? root.get("confidence").asDouble() : null;
            
            // If no search parameters were extracted, treat as failure
            if (searchString == null && conditions.isEmpty()) {
                return AiSearchInterpretation.error(naturalLanguageQuery,
                    explanation != null && !explanation.isBlank() 
                        ? explanation 
                        : "Could not identify any search parameters from your query. Please try rephrasing.");
            }
            
            return AiSearchInterpretation.success(
                naturalLanguageQuery, conditions, searchString, explanation, confidence);
                
        } catch (Exception e) {
            logger.warn("Failed to parse AI response for query: {}, response: {}", 
                naturalLanguageQuery, responseText, e);
            return AiSearchInterpretation.error(naturalLanguageQuery,
                "Unable to understand AI response. Please try rephrasing your query.");
        }
    }

    /**
     * Extract JSON from the AI response, handling markdown code blocks if present.
     */
    private String extractJson(String response) {
        if (response == null) return "{}";
        
        String trimmed = response.trim();
        
        // Handle markdown code blocks
        if (trimmed.startsWith("```json")) {
            int endIdx = trimmed.lastIndexOf("```");
            if (endIdx > 7) {
                return trimmed.substring(7, endIdx).trim();
            }
        }
        if (trimmed.startsWith("```")) {
            int startIdx = trimmed.indexOf('\n');
            int endIdx = trimmed.lastIndexOf("```");
            if (startIdx > 0 && endIdx > startIdx) {
                return trimmed.substring(startIdx + 1, endIdx).trim();
            }
        }
        
        // Find JSON object boundaries
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        
        return trimmed;
    }

    /**
     * Build the prompt for search query interpretation.
     */
    private String buildSearchInterpretationPrompt(String naturalLanguageQuery, List<String> availableKeys) {
        String fieldList = availableKeys != null && !availableKeys.isEmpty() 
            ? String.join(", ", availableKeys)
            : "path, name, " + FieldNameConstants.FIELD_CONTENT_TYPE + ", datasourcename, datasourcetype";
            
        return """
            You are a search query interpreter for EnterFind document search system.
            Convert natural language queries into structured search parameters.
            
            AVAILABLE FIELDS FOR searchConditions:
            %s
            
            IMPORTANT FIELD MEANINGS:
            - "path": Full file path including folders. When filtering, use folder name WITHOUT slashes (e.g., use "reports" not "/reports/" or "reports/")
            - "name": Just the filename (e.g., "file.pdf")
            - "content-type": MIME type (e.g., "application/pdf", "image/jpeg", "image/png")
            - "Content": Extracted text content from documents
            
            SEARCH OPERATIONS:
            - EQUAL: exact match
            - NOT_EQUAL: exclude exact match
            - LIKE: value contains ALL of these words, in any order and not necessarily
              adjacent; quote a phrase ("annual report") to require it contiguous and in order
            - NOT_LIKE: negation of LIKE
            - LESS / GREATER: for numeric comparisons
            
            OUTPUT FORMAT (return ONLY valid JSON, no markdown):
            {
              "searchString": "text to search in content, or null if not needed",
              "searchConditions": [
                {"name": "fieldName", "operation": "OPERATION", "value": "value"}
              ],
              "explanation": "brief explanation",
              "confidence": 0.0-1.0
            }
            
            EXAMPLES:
            
            Query: "PDF files"
            {"searchString": null, "searchConditions": [{"name": "content-type", "operation": "EQUAL", "value": "application/pdf"}], "explanation": "Filter by PDF MIME type", "confidence": 0.95}
            
            Query: "images"
            {"searchString": null, "searchConditions": [{"name": "content-type", "operation": "LIKE", "value": "image/"}], "explanation": "Filter by image MIME types", "confidence": 0.9}
            
            Query: "files in reports folder"
            {"searchString": null, "searchConditions": [{"name": "path", "operation": "LIKE", "value": "reports"}], "explanation": "Files with 'reports' in path", "confidence": 0.9}
            
            Query: "PDF files not in archive"
            {"searchString": null, "searchConditions": [{"name": "content-type", "operation": "EQUAL", "value": "application/pdf"}, {"name": "path", "operation": "NOT_LIKE", "value": "archive"}], "explanation": "PDFs excluding archive folder", "confidence": 0.9}
            
            Query: "documents containing budget"
            {"searchString": "budget", "searchConditions": [], "explanation": "Full-text search for 'budget'", "confidence": 0.95}
            
            Query: "spreadsheets with revenue data"
            {"searchString": "revenue", "searchConditions": [{"name": "content-type", "operation": "LIKE", "value": "spreadsheet"}], "explanation": "Spreadsheets containing 'revenue'", "confidence": 0.85}
            
            Query: "files named report"
            {"searchString": null, "searchConditions": [{"name": "name", "operation": "LIKE", "value": "report"}], "explanation": "Files with 'report' in filename", "confidence": 0.9}
            
            RULES:
            1. PREFER searchString for content searches - when user asks for documents "containing", "mentioning", "about", or "with" certain text, use searchString
            2. Use searchConditions ONLY for filtering by metadata (path, name, file type, etc.)
            3. searchString searches inside document content; searchConditions filter by file properties
            4. IMPORTANT: Multiple searchConditions are ANDed together - all conditions must match. Adding more conditions narrows results and may return nothing. Use sparingly.
            5. For file types, use content-type with appropriate MIME type
            6. For folder filtering, use path with LIKE or NOT_LIKE
            7. ALWAYS return valid JSON only - never use markdown code blocks
            8. If unsure, set lower confidence and explain in explanation field
            
            Natural language query: %s
            
            JSON:""".formatted(fieldList, naturalLanguageQuery);
    }

    /**
     * Build a prompt for cron expression interpretation.
     */
    private String buildCronInterpretationPrompt(String naturalLanguage) {
        return """
            You are a helpful assistant that converts natural language descriptions into cron expressions.

            Convert the following natural language description into a valid cron expression.
            Return ONLY the cron expression, nothing else. Use the standard 5-field cron format (minute hour day month day-of-week).

            Examples:
            - "every 2 hours" -> "0 */2 * * *"
            - "every day at 3am" -> "0 3 * * *"
            - "every Monday at 9am" -> "0 9 * * 1"
            - "every 15 minutes" -> "*/15 * * * *"

            Natural language: %s

            Cron expression:""".formatted(naturalLanguage);
    }

    /**
     * Build a prompt for cron expression explanation.
     */
    private String buildCronExplanationPrompt(String cronExpression) {
        return """
            You are a helpful assistant that explains cron expressions in natural language.

            Convert the following cron expression into a SHORT natural language description (maximum 10 words).
            Return ONLY the short description, nothing else. No explanations, no breakdowns, no additional details.
            Use the standard 5-field cron format (minute hour day month day-of-week).

            Examples:
            - "0 */2 * * *" -> "Every 2 hours"
            - "0 */3 * * *" -> "Every 3 hours"
            - "0 3 * * *" -> "Every day at 3:00 AM"
            - "0 9 * * 1" -> "Every Monday at 9:00 AM"
            - "*/15 * * * *" -> "Every 15 minutes"
            - "30 2 * * *" -> "Every day at 2:30 AM"

            Cron expression: %s

            Short description:""".formatted(cronExpression);
    }

    /**
     * Build a prompt for skip pattern interpretation.
     */
    private String buildSkipPatternInterpretationPrompt(String naturalLanguage) {
        return """
            You are a helpful assistant that converts natural language descriptions into regex patterns for file filtering.

            Convert the following natural language description into a valid regex pattern suitable for skipping/excluding files.
            Return ONLY the regex pattern, nothing else. Use pipe (|) to combine multiple patterns.

            Examples:
            - "all jpg and png files" -> ".*\\.jpg|.*\\.png"
            - "all image files" -> ".*\\.(jpg|jpeg|png|gif|bmp|svg)"
            - "temporary files" -> ".*\\.tmp|.*\\.temp|.*~"
            - "all pdf and doc files" -> ".*\\.pdf|.*\\.(doc|docx)"
            - "files starting with test" -> "test.*"
            - "hidden files" -> "\\..*"

            Natural language: %s

            Regex pattern:""".formatted(naturalLanguage);
    }

    /**
     * Build a prompt for skip pattern explanation.
     */
    private String buildSkipPatternExplanationPrompt(String skipPattern) {
        return """
            You are a helpful assistant that explains regex patterns for file filtering in natural language.

            Convert the following regex pattern into a SHORT natural language description (maximum 15 words).
            Return ONLY the short description, nothing else. No technical details, no regex explanations.

            Examples:
            - ".*\\.jpg|.*\\.png" -> "All JPG and PNG files"
            - ".*\\.(jpg|jpeg|png|gif|bmp)" -> "All image files (JPG, JPEG, PNG, GIF, BMP)"
            - ".*\\.tmp|.*\\.temp" -> "All temporary files"
            - ".*\\.pdf|.*\\.doc" -> "All PDF and DOC files"
            - "test.*" -> "Files starting with 'test'"
            - "\\..*" -> "Hidden files (starting with dot)"

            Regex pattern: %s

            Short description:""".formatted(skipPattern);
    }

    /**
     * Extract skip pattern from AI response.
     * The response should contain a regex pattern, possibly with some explanation.
     * We extract the first line that looks like a regex pattern.
     */
    private String extractSkipPattern(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }

        // Split by lines and find the first line that looks like a regex pattern
        String[] lines = response.trim().split("\\n");
        for (String line : lines) {
            line = line.trim();
            // Skip empty lines and lines that look like explanations
            if (line.isEmpty() || line.toLowerCase().startsWith("here") ||
                line.toLowerCase().startsWith("this") || line.toLowerCase().startsWith("the pattern")) {
                continue;
            }
            // If line contains regex-like characters, it's likely the pattern
            if (line.matches(".*[.*\\\\|()\\[\\]{}^$+?].*")) {
                return line;
            }
        }

        // If no regex-like pattern found, return the first non-empty line
        for (String line : lines) {
            line = line.trim();
            if (!line.isEmpty()) {
                return line;
            }
        }

        return response.trim();
    }

    /**
     * Extract cron expression from AI response.
     * The response should contain a cron expression, possibly with some explanation.
     * We extract the first line that looks like a cron expression.
     */
    private String extractCronExpression(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }

        // Split by lines and find the first line that looks like a cron expression
        String[] lines = response.trim().split("\\n");
        for (String line : lines) {
            line = line.trim();
            // Basic validation: cron expression should have 5 fields separated by spaces
            String[] parts = line.split("\\s+");
            if (parts.length == 5 || parts.length == 6) {
                // Simple validation: check if it contains typical cron characters
                if (line.matches(".*[0-9*,/-].*")) {
                    return line;
                }
            }
        }

        // If no cron-like pattern found, return the first non-empty line
        for (String line : lines) {
            line = line.trim();
            if (!line.isEmpty()) {
                return line;
            }
        }

        return response.trim();
    }
}
