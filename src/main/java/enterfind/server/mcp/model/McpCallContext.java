package enterfind.server.mcp.model;

/**
 * Per-HTTP-request facts the MCP tools need: the connector's Authorization header, the URL the
 * client reached this instance at, and whether it came through the remote-access tunnel. Never
 * serialized, and the header value is never logged.
 */
public record McpCallContext(String authorization, String publicBaseUrl, boolean viaRemoteAccess) {

    public static final McpCallContext NONE = new McpCallContext(null, null, false);

    public boolean hasAuthorization() {
        return authorization != null && !authorization.isBlank();
    }
}
