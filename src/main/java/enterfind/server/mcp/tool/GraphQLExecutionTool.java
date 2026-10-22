package enterfind.server.mcp.tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.support.DefaultExecutionGraphQlRequest;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import enterfind.server.mcp.McpGraphQlContext;
import enterfind.server.mcp.model.McpCallContext;
import enterfind.server.mcp.model.ToolDefinition;
import enterfind.server.mcp.model.ToolResult;
import enterfind.server.security.ApiKeyCredentialResolver;
import enterfind.server.security.ApiKeyExchange;
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
    private final ApiKeyCredentialResolver credentialResolver;

    public GraphQLExecutionTool(ExecutionGraphQlService graphQlService, ObjectMapper objectMapper,
                                ApiKeyCredentialResolver credentialResolver) {
        this.graphQlService = graphQlService;
        this.objectMapper = objectMapper;
        this.credentialResolver = credentialResolver;
    }

    /**
     * @param headerAuth whether the connection carries an Authorization header; selects which way
     *                   of authenticating, and of fetching documents, the description teaches
     */
    public ToolDefinition getDefinition(boolean headerAuth) {
        Map<String, Object> variablesProperty = Map.of(
            "type", "object",
            "description", ToolText.variablesParameter(headerAuth)
        );

        Map<String, Object> queryProperty = Map.of(
            "type", "string",
            "description", ToolText.QUERY_PARAMETER
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
            ToolText.executionDescription(headerAuth),
            inputSchema
        );
    }

    @SuppressWarnings("unchecked")
    public ToolResult execute(Map<String, Object> arguments, McpCallContext callContext) {
        try {
            String query = (String) arguments.get("query");
            if (query == null || query.isEmpty()) {
                return ToolResult.error("Query parameter is required");
            }

            // Before the query is even looked at, so a bad key fails the same way however the
            // query is formed.
            ApiKeyExchange exchange = credentialResolver.resolve(callContext.authorization());
            if (exchange instanceof ApiKeyExchange.Failed(String reason)) {
                return ToolResult.error(reason);
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
            Map<String, Object> context = new HashMap<>();
            if (callContext.publicBaseUrl() != null) {
                context.put(McpGraphQlContext.PUBLIC_BASE_URL, callContext.publicBaseUrl());
            }
            if (exchange instanceof ApiKeyExchange.Resolved(String sessionToken)) {
                context.put(McpGraphQlContext.SESSION_TOKEN, sessionToken);
            }
            request.configureExecutionInput((input, builder) -> builder.graphQLContext(context).build());

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
                String hint = exchange instanceof ApiKeyExchange.Absent && isAuthenticationRequired(errorSpecs)
                    ? ToolText.setupHint(Objects.toString(callContext.publicBaseUrl(), ""), callContext.viaRemoteAccess())
                    : "";
                return ToolResult.error("GraphQL query failed: " + errors + hint);
            }
        } catch (ClassCastException e) {
            logger.error("Invalid argument types", e);
            return ToolResult.error("Invalid argument types: " + e.getMessage());
        } catch (Exception e) {
            logger.error("Error executing GraphQL query", e);
            return ToolResult.error("Error executing query: " + e.getMessage());
        }
    }

    private static boolean isAuthenticationRequired(List<Map<String, Object>> errorSpecs) {
        return errorSpecs.stream()
            .map(spec -> spec.get("extensions"))
            .filter(Map.class::isInstance)
            .anyMatch(extensions -> "AUTHENTICATION_REQUIRED".equals(((Map<?, ?>) extensions).get("errorCode")));
    }
}
