package enterfind.server;

import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import enterfind.server.mcp.model.JsonRpcRequest;
import enterfind.server.mcp.model.JsonRpcResponse;

/**
 * Utility class for executing MCP requests in integration tests.
 * Provides a consistent API for testing MCP functionality.
 */
public class McpTestClient {

    private final TestRestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final int port;
    private int requestIdCounter = 1;

    /**
     * Create an McpTestClient using a new TestRestTemplate instance.
     *
     * @param port the server port to connect to
     */
    public McpTestClient(int port) {
        this(new TestRestTemplate(), new JsonMapper(), port);
    }

    /**
     * Create an McpTestClient with a provided TestRestTemplate.
     *
     * @param restTemplate the TestRestTemplate to use
     * @param port the server port to connect to
     */
    public McpTestClient(TestRestTemplate restTemplate, int port) {
        this(restTemplate, new JsonMapper(), port);
    }

    /**
     * Create an McpTestClient with a provided TestRestTemplate and ObjectMapper.
     *
     * @param restTemplate the TestRestTemplate to use
     * @param objectMapper the ObjectMapper to use for JSON serialization
     * @param port the server port to connect to
     */
    public McpTestClient(TestRestTemplate restTemplate, ObjectMapper objectMapper, int port) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.port = port;
    }

    /**
     * Execute an MCP request and return the raw HTTP response.
     *
     * @param request the JSON-RPC request
     * @return the ResponseEntity containing the response
     */
    public ResponseEntity<JsonRpcResponse> execute(JsonRpcRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<JsonRpcRequest> httpRequest = new HttpEntity<>(request, headers);

        String url = port == 0
            ? "/mcp"
            : "http://localhost:" + port + "/mcp";

        return restTemplate.postForEntity(url, httpRequest, JsonRpcResponse.class);
    }

    /**
     * Execute an MCP request with SSE (Server-Sent Events) response.
     * This tests the Streamable HTTP transport (MCP 2025-03-26 spec).
     *
     * @param request the JSON-RPC request
     * @return the ResponseEntity containing the SSE response as a String
     */
    public ResponseEntity<String> executeWithSse(JsonRpcRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", MediaType.TEXT_EVENT_STREAM_VALUE);

        HttpEntity<JsonRpcRequest> httpRequest = new HttpEntity<>(request, headers);

        String url = port == 0
            ? "/mcp"
            : "http://localhost:" + port + "/mcp";

        return restTemplate.postForEntity(url, httpRequest, String.class);
    }

    /**
     * Execute an initialize request.
     *
     * @return the ResponseEntity containing the response
     */
    public ResponseEntity<JsonRpcResponse> initialize() {
        JsonRpcRequest request = new JsonRpcRequest("initialize", null, nextRequestId());
        return execute(request);
    }

    /**
     * Execute a tools/list request.
     *
     * @return the ResponseEntity containing the response
     */
    public ResponseEntity<JsonRpcResponse> listTools() {
        JsonRpcRequest request = new JsonRpcRequest("tools/list", null, nextRequestId());
        return execute(request);
    }

    /**
     * Execute a tools/call request for introspect_graphql_schema.
     *
     * @param includeDescriptions whether to include field descriptions
     * @return the ResponseEntity containing the response
     */
    public ResponseEntity<JsonRpcResponse> introspectSchema(boolean includeDescriptions) {
        var params = objectMapper.createObjectNode();
        params.put("name", "introspect_graphql_schema");

        var arguments = objectMapper.createObjectNode();
        arguments.put("includeDescriptions", includeDescriptions);
        params.set("arguments", arguments);

        JsonRpcRequest request = new JsonRpcRequest("tools/call", params, nextRequestId());
        return execute(request);
    }

    /**
     * Execute a tools/call request for execute_graphql_query.
     *
     * @param query the GraphQL query to execute
     * @return the ResponseEntity containing the response
     */
    public ResponseEntity<JsonRpcResponse> executeGraphQLQuery(String query) {
        return executeGraphQLQuery(query, null);
    }

    /**
     * Execute a tools/call request for execute_graphql_query with variables.
     *
     * @param query the GraphQL query to execute
     * @param variables the GraphQL query variables
     * @return the ResponseEntity containing the response
     */
    public ResponseEntity<JsonRpcResponse> executeGraphQLQuery(String query, JsonNode variables) {
        var params = objectMapper.createObjectNode();
        params.put("name", "execute_graphql_query");

        var arguments = objectMapper.createObjectNode();
        arguments.put("query", query);
        if (variables != null) {
            arguments.set("variables", variables);
        }
        params.set("arguments", arguments);

        JsonRpcRequest request = new JsonRpcRequest("tools/call", params, nextRequestId());
        return execute(request);
    }

    /**
     * Execute an MCP request and assert success.
     *
     * @param request the JSON-RPC request
     * @return the ResponseEntity containing the response
     * @throws McpTestException if the request fails
     */
    public ResponseEntity<JsonRpcResponse> executeSuccessfully(JsonRpcRequest request) {
        ResponseEntity<JsonRpcResponse> response = execute(request);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new McpTestException("MCP request failed with status: " + response.getStatusCode());
        }
        JsonRpcResponse body = response.getBody();
        if (body != null && body.getError() != null) {
            throw new McpTestException("MCP request returned error: " + body.getError().getMessage());
        }
        return response;
    }

    /**
     * Get the health endpoint.
     *
     * @return the ResponseEntity containing the health response
     */
    public ResponseEntity<String> getHealth() {
        String url = port == 0
            ? "/mcp/health"
            : "http://localhost:" + port + "/mcp/health";

        return restTemplate.getForEntity(url, String.class);
    }

    /**
     * Get the next request ID.
     *
     * @return the next request ID
     */
    private int nextRequestId() {
        return requestIdCounter++;
    }

    /**
     * Get the port number this client is configured to use.
     *
     * @return the port number
     */
    public int getPort() {
        return port;
    }

    /**
     * Exception thrown when an MCP test operation fails.
     */
    public static class McpTestException extends RuntimeException {
        public McpTestException(String message) {
            super(message);
        }

        public McpTestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
