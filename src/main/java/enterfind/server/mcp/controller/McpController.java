package enterfind.server.mcp.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import tools.jackson.databind.ObjectMapper;

import enterfind.server.mcp.model.JsonRpcRequest;
import enterfind.server.mcp.model.JsonRpcResponse;
import enterfind.server.mcp.model.McpCallContext;
import enterfind.server.mcp.service.McpService;
import enterfind.server.util.PublicBaseUrl;
import jakarta.servlet.http.HttpServletRequest;

/**
 * REST controller for MCP (Model Context Protocol) endpoints
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final Logger logger = LoggerFactory.getLogger(McpController.class);

    private final McpService mcpService;
    private final ObjectMapper objectMapper;
    private final PublicBaseUrl publicBaseUrl;

    public McpController(McpService mcpService, ObjectMapper objectMapper, PublicBaseUrl publicBaseUrl) {
        this.mcpService = mcpService;
        this.objectMapper = objectMapper;
        this.publicBaseUrl = publicBaseUrl;
    }

    /**
     * SSE endpoint for MCP clients (Deprecated - kept for backward compatibility)
     * This endpoint is used by MCP clients following the old 2024-11-05 spec
     * New clients should use the POST endpoint with Accept: text/event-stream header
     *
     * @deprecated Use POST /mcp with Accept: text/event-stream instead
     */
    @Deprecated
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter handleMcpSseConnection() {
        logger.info("New MCP SSE connection established (deprecated endpoint)");

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);

        // Send initial connection event
        try {
            emitter.send(SseEmitter.event()
                .name("endpoint")
                .data("/mcp"));
        } catch (Exception e) {
            logger.error("Error sending SSE connection event", e);
            emitter.completeWithError(e);
        }

        // Handle completion and errors
        emitter.onCompletion(() -> logger.info("MCP SSE connection completed"));
        emitter.onError((e) -> logger.error("MCP SSE connection error", e));
        emitter.onTimeout(() -> logger.warn("MCP SSE connection timeout"));

        return emitter;
    }

    /**
     * POST endpoint for MCP JSON-RPC requests (Streamable HTTP transport)
     * Supports both JSON and SSE responses based on Accept header
     * MCP Protocol Specification 2025-03-26
     *
     * The client MUST include an Accept header with application/json and/or text/event-stream.
     * The server responds with either:
     * - Content-Type: application/json for single response
     * - Content-Type: text/event-stream for streaming responses
     *
     * An Authorization header carries the connector's API key, which the execution tool exchanges
     * for a session. A key that cannot be exchanged is a tool error, not an HTTP 401: a connector
     * configured with a header has no sign-in, and a 401 would send it into OAuth discovery. The
     * request with no header at all stays its own path (ApiKeyExchange.Absent).
     */
    @PostMapping(
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE}
    )
    public Object handleMcpRequest(
            @RequestBody JsonRpcRequest request,
            @RequestHeader(value = "Accept", defaultValue = MediaType.APPLICATION_JSON_VALUE) String acceptHeader,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest httpRequest) {

        try {
            McpCallContext callContext = new McpCallContext(authorization, publicBaseUrl.of(httpRequest),
                publicBaseUrl.viaRemoteAccess(httpRequest));
            logger.info("Received MCP request: method={}, id={}, Accept={}, auth={}",
                request.getMethod(), request.getId(), acceptHeader,
                callContext.hasAuthorization() ? "present" : "absent");

            // Validate JSON-RPC version
            if (!"2.0".equals(request.getJsonrpc())) {
                JsonRpcResponse errorResponse = JsonRpcResponse.error(
                    -32600,
                    "Invalid Request: jsonrpc must be '2.0'",
                    request.getId()
                );

                // Return error based on Accept header
                // Note: JSON-RPC errors should still return HTTP 200
                if (acceptHeader.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
                    return createSseResponse(errorResponse);
                }
                return ResponseEntity.ok(errorResponse);
            }

            // Handle the request
            JsonRpcResponse response = mcpService.handleRequest(request, callContext);

            // Check if client accepts SSE
            if (acceptHeader.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
                logger.info("Returning SSE response for request id={}", request.getId());
                return createSseResponse(response);
            }

            // Default to JSON response
            logger.info("Returning JSON response for request id={}", request.getId());
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error processing MCP request", e);

            JsonRpcResponse errorResponse = JsonRpcResponse.error(
                -32603,
                "Internal error: " + e.getMessage(),
                request != null ? request.getId() : null
            );

            // Return error based on Accept header
            // Note: JSON-RPC errors should still return HTTP 200
            if (acceptHeader.contains(MediaType.TEXT_EVENT_STREAM_VALUE)) {
                return createSseResponse(errorResponse);
            }
            return ResponseEntity.ok(errorResponse);
        }
    }

    /**
     * Create an SSE response for a JSON-RPC response
     * The SSE stream will send the response and then close
     */
    private SseEmitter createSseResponse(JsonRpcResponse response) {
        SseEmitter emitter = new SseEmitter(30000L); // 30 second timeout

        try {
            // Send the JSON-RPC response as SSE message event
            String jsonData = objectMapper.writeValueAsString(response);
            emitter.send(SseEmitter.event()
                .name("message")
                .data((Object) jsonData));

            // Close the stream after sending the response
            emitter.complete();

            logger.info("SSE response sent and completed for request id={}", response.getId());
        } catch (Exception e) {
            logger.error("Error creating SSE response", e);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /**
     * Health check endpoint for MCP server
     */
    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("MCP server is running");
    }
}
