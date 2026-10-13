package enterfind.server.mcp.tool;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.support.DefaultExecutionGraphQlRequest;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import enterfind.server.mcp.model.ToolDefinition;
import enterfind.server.mcp.model.ToolResult;
import graphql.ExecutionResult;
import graphql.GraphQLError;

/**
 * MCP tool for executing GraphQL queries and mutations
 */
@Component
public class GraphQLExecutionTool {

    private static final Logger logger = LoggerFactory.getLogger(GraphQLExecutionTool.class);

    private final ExecutionGraphQlService graphQlService;
    private final ObjectMapper objectMapper;

    public GraphQLExecutionTool(ExecutionGraphQlService graphQlService, ObjectMapper objectMapper) {
        this.graphQlService = graphQlService;
        this.objectMapper = objectMapper;
    }

    public ToolDefinition getDefinition() {
        Map<String, Object> variablesProperty = Map.of(
            "type", "object",
            "description", "Query variables as JSON object. Include 'token' for authenticated operations: { \"token\": \"your-token\", ... }"
        );

        Map<String, Object> queryProperty = Map.of(
            "type", "string",
            "description", "GraphQL query or mutation string"
        );

        Map<String, Object> properties = Map.of(
            "query", queryProperty,
            "variables", variablesProperty
        );

        Map<String, Object> inputSchema = Map.of(
            "type", "object",
            "properties", properties,
            "required", new String[]{"query"}
        );

        return new ToolDefinition(
            "execute_graphql_query",
            "Execute GraphQL queries against Enterfind document search system. " +
            "PREREQUISITE: Call introspect_graphql_schema first to understand the schema. " +
            "AUTHENTICATION: Most operations require a 'token' variable. Workflow: " +
            "(1) Call 'isAuthenticationRequired' query to check if login is needed. " +
            "(2) If auth required, call 'login' mutation with nickname and totpCode to get a token. " +
            "(3) For authenticated operations, include token in variables: { \"token\": \"your-token\", ... }. " +
            "SEARCH WORKFLOW: " +
            "(1) Start with a broad 'search' query using searchString with keywords. " +
            "(2) Call 'availableItemAttributeKeys' to discover facet dimensions. " +
            "(3) Use 'getFacets' with attribute keys to see value distributions. " +
            "(4) Refine with searchConditions array to filter by facet values. " +
            "(5) Use 'itemAttributes' to get detailed metadata. " +
            "TIPS: Combine searchString with searchConditions for precise results. Use skip/howmany for pagination. " +
            "DOCUMENT RETRIEVAL: Construct URLs: /content/{dataSourceType}/{dataSourceName}/{folder}/{name}. " +
            "Replace folder commas with slashes. URL-encode special characters.",
            inputSchema
        );
    }

    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            String query = (String) arguments.get("query");
            if (query == null || query.isEmpty()) {
                return ToolResult.error("Query parameter is required");
            }

            Map<String, Object> variables = (Map<String, Object>) arguments.get("variables");

            logger.info("Executing GraphQL query: {}", query.substring(0, Math.min(100, query.length())));

            // Create execution request
            DefaultExecutionGraphQlRequest request = new DefaultExecutionGraphQlRequest(
                query,
                null,       // operationName
                variables,  // variables
                null,       // extensions
                "",         // id (empty string instead of null)
                null        // locale
            );

            // Execute query
            ExecutionResult executionResult = graphQlService.execute(request)
                .block()  // Block to get result synchronously
                .getExecutionResult();

            if (executionResult.getErrors().isEmpty()) {
                // Convert result to pretty JSON string
                Object data = executionResult.getData();
                String jsonResult = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(data);

                logger.info("GraphQL query execution successful");
                return ToolResult.success(jsonResult);
            } else {
                // Serialize via toSpecification() so the client-facing payload carries only the
                // GraphQL-spec fields (message, locations, path, extensions) — never the raw
                // GraphQLError objects, whose underlying Throwable would otherwise leak a full
                // Java stack trace through Jackson's default serialization.
                List<Map<String, Object>> errorSpecs = executionResult.getErrors().stream()
                        .map(GraphQLError::toSpecification)
                        .toList();
                String errors = objectMapper.writeValueAsString(errorSpecs);
                logger.error("GraphQL query execution errors: {}", errors);
                return ToolResult.error("GraphQL query failed: " + errors);
            }
        } catch (ClassCastException e) {
            logger.error("Invalid argument types", e);
            return ToolResult.error("Invalid argument types: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error executing GraphQL query", e);
            return ToolResult.error("Error executing query: " + e.getMessage());
        }
    }
}
