package enterfind.server.mcp.tool;

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

/**
 * MCP tool for GraphQL schema introspection
 */
@Component
public class GraphQLIntrospectionTool {

    private static final Logger logger = LoggerFactory.getLogger(GraphQLIntrospectionTool.class);

    private final ExecutionGraphQlService graphQlService;
    private final ObjectMapper objectMapper;

    private static final String INTROSPECTION_QUERY = """
        query IntrospectionQuery {
          __schema {
            queryType { name }
            mutationType { name }
            types {
              name
              kind
              description
              fields(includeDeprecated: false) {
                name
                description
                args {
                  name
                  description
                  type {
                    name
                    kind
                    ofType {
                      name
                      kind
                    }
                  }
                }
                type {
                  name
                  kind
                  ofType {
                    name
                    kind
                  }
                }
              }
              inputFields {
                name
                description
                type {
                  name
                  kind
                  ofType {
                    name
                    kind
                  }
                }
              }
              enumValues {
                name
                description
              }
            }
          }
        }
        """;

    public GraphQLIntrospectionTool(ExecutionGraphQlService graphQlService, ObjectMapper objectMapper) {
        this.graphQlService = graphQlService;
        this.objectMapper = objectMapper;
    }

    public ToolDefinition getDefinition() {
        // No parameters. There was an "includeDescriptions" flag here, but execute() never
        // read it, so it was inert — and descriptions are the point of this tool: the search
        // contract (term scoping, quoting, the short-term and large-document limits) lives in
        // them and is always returned. Rather than honour a flag whose only effect would be to
        // withhold that, the parameter is gone.
        Map<String, Object> inputSchema = Map.of(
            "type", "object",
            "properties", Map.of()
        );

        return new ToolDefinition(
            "introspect_graphql_schema",
            "Get the GraphQL schema for Enterfind document search system. IMPORTANT: Call this FIRST before using execute_graphql_query. " +
            "AUTHENTICATION: Most operations have a 'token: String' argument. Workflow: " +
            "(1) Call 'isAuthenticationRequired' query to check if login is needed. " +
            "(2) If auth required, call 'login' mutation with nickname and totpCode to get a token. " +
            "(3) Pass the token in variables for subsequent operations: { \"token\": \"your-token\", ... }. " +
            "KEY OPERATIONS: (1) 'search' query for finding documents with faceted filtering, " +
            "(2) 'availableItemAttributeKeys' for discovering facet dimensions, " +
            "(3) 'getFacets' for getting facet values and counts, (4) 'itemAttributes' for detailed item metadata. " +
            "SEARCH: 'search' takes two different kinds of filter and they do not mean the same thing. " +
            "'searchString' spans the whole item — each term may be satisfied from a file name or from " +
            "document text independently — and is the right default for finding documents about a topic. " +
            "A 'searchConditions' entry filters one named attribute, and all of its terms must share a " +
            "single value of that key. In both, whitespace separates terms and a double-quoted term must " +
            "appear contiguous and in order. " +
            "The schema's own descriptions carry the full contract, including which fields match characters " +
            "versus whole words and the limits on very short terms and very large documents — read them " +
            "rather than inferring behaviour from operation names.",
            inputSchema
        );
    }

    public ToolResult execute(Map<String, Object> arguments) {
        try {
            logger.info("Executing GraphQL schema introspection");

            // Create execution request
            DefaultExecutionGraphQlRequest request = new DefaultExecutionGraphQlRequest(
                INTROSPECTION_QUERY,
                null,  // operationName
                null,  // variables
                null,  // extensions
                "",    // id (empty string instead of null)
                null   // locale
            );

            // Execute introspection query
            ExecutionResult executionResult = graphQlService.execute(request)
                .block()  // Block to get result synchronously
                .getExecutionResult();

            if (executionResult.getErrors().isEmpty()) {
                // Convert result to pretty JSON string
                Object data = executionResult.getData();
                String jsonResult = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(data);

                logger.info("GraphQL introspection successful");
                return ToolResult.success(jsonResult);
            } else {
                String errors = objectMapper.writeValueAsString(executionResult.getErrors());
                logger.error("GraphQL introspection errors: {}", errors);
                return ToolResult.error("GraphQL introspection failed: " + errors);
            }
        } catch (Exception e) {
            logger.error("Error executing GraphQL introspection", e);
            return ToolResult.error("Error executing introspection: " + e.getMessage());
        }
    }
}
