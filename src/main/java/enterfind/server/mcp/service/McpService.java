package enterfind.server.mcp.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import enterfind.server.mcp.model.JsonRpcRequest;
import enterfind.server.mcp.model.JsonRpcResponse;
import enterfind.server.mcp.model.McpCallContext;
import enterfind.server.mcp.model.ServerInfo;
import enterfind.server.mcp.model.ToolDefinition;
import enterfind.server.mcp.model.ToolResult;
import enterfind.server.mcp.tool.GraphQLExecutionTool;
import enterfind.server.mcp.tool.GraphQLIntrospectionTool;

/**
 * Service handling MCP protocol requests
 */
@Service
public class McpService {

    private static final Logger logger = LoggerFactory.getLogger(McpService.class);

    private final GraphQLIntrospectionTool introspectionTool;
    private final GraphQLExecutionTool executionTool;
    private final ObjectMapper objectMapper;

    @Value("${spring.application.name:enterfind-server}")
    private String applicationName;

    @Value("${app.version:0.0.1-SNAPSHOT}")
    private String applicationVersion;

    public McpService(GraphQLIntrospectionTool introspectionTool,
                     GraphQLExecutionTool executionTool,
                     ObjectMapper objectMapper) {
        this.introspectionTool = introspectionTool;
        this.executionTool = executionTool;
        this.objectMapper = objectMapper;
    }

    /**
     * Handle JSON-RPC 2.0 request with no HTTP request behind it.
     */
    public JsonRpcResponse handleRequest(JsonRpcRequest request) {
        return handleRequest(request, McpCallContext.NONE);
    }

    /**
     * Handle JSON-RPC 2.0 request
     */
    public JsonRpcResponse handleRequest(JsonRpcRequest request, McpCallContext callContext) {
        try {
            logger.info("Handling MCP request: method={}", request.getMethod());

            return switch (request.getMethod()) {
                case "initialize" -> handleInitialize(request);
                case "tools/list" -> handleToolsList(request, callContext);
                case "tools/call" -> handleToolsCall(request, callContext);
                default -> JsonRpcResponse.error(
                    -32601,
                    "Method not found: " + request.getMethod(),
                    request.getId()
                );
            };
        } catch (Exception e) {
            logger.error("Error handling MCP request", e);
            return JsonRpcResponse.error(
                -32603,
                "Internal error: " + e.getMessage(),
                request.getId()
            );
        }
    }

    /**
     * Handle initialize request
     */
    private JsonRpcResponse handleInitialize(JsonRpcRequest request) {
        logger.info("Initializing MCP server");

        ServerInfo serverInfo = new ServerInfo(
            applicationName + " MCP Server",
            applicationVersion
        );

        Map<String, Object> result = Map.of(
            "protocolVersion", "2024-11-05",
            "serverInfo", serverInfo,
            "capabilities", serverInfo.getCapabilities()
        );

        return JsonRpcResponse.success(result, request.getId());
    }

    /**
     * Handle tools/list request. The descriptions follow whether the connection carries an
     * Authorization header — its presence only: the key is not checked here, so listing tools
     * never creates a session, and a bad key is reported when a tool is called.
     */
    private JsonRpcResponse handleToolsList(JsonRpcRequest request, McpCallContext callContext) {
        logger.info("Listing available tools");

        boolean headerAuth = callContext.hasAuthorization();
        List<ToolDefinition> tools = List.of(
            introspectionTool.getDefinition(headerAuth),
            executionTool.getDefinition(headerAuth)
        );

        Map<String, Object> result = Map.of("tools", tools);
        return JsonRpcResponse.success(result, request.getId());
    }

    /**
     * Handle tools/call request
     */
    private JsonRpcResponse handleToolsCall(JsonRpcRequest request, McpCallContext callContext) {
        try {
            // Parse params
            JsonNode paramsNode = objectMapper.valueToTree(request.getParams());
            String toolName = paramsNode.get("name").asString();
            JsonNode argumentsNode = paramsNode.get("arguments");

            logger.info("Calling tool: {}", toolName);

            // Convert arguments to Map
            @SuppressWarnings("unchecked")
            Map<String, Object> arguments = argumentsNode != null
                ? objectMapper.convertValue(argumentsNode, Map.class)
                : Map.of();

            // Route to appropriate tool
            ToolResult toolResult = switch (toolName) {
                case "introspect_graphql_schema" -> introspectionTool.execute(arguments);
                // Introspection needs no auth and must not create sessions, so it gets no context
                case "execute_graphql_query" -> executionTool.execute(arguments, callContext);
                default -> ToolResult.error("Unknown tool: " + toolName);
            };

            return JsonRpcResponse.success(toolResult, request.getId());
        } catch (Exception e) {
            logger.error("Error calling tool", e);
            return JsonRpcResponse.error(
                -32603,
                "Error calling tool: " + e.getMessage(),
                request.getId()
            );
        }
    }
}
