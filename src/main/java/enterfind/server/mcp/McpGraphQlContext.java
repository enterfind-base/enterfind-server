package enterfind.server.mcp;

/**
 * Keys the MCP tool puts into the GraphQL context of its in-process execution. {@code /graphql}
 * sets neither, which is how the readers of these keys leave the web UI's requests unchanged.
 */
public final class McpGraphQlContext {

    /** Session token from the connector's Authorization header, filled into every {@code token} argument. */
    public static final String SESSION_TOKEN = "enterfind.mcp.sessionToken";

    /** Public URL of this instance as the client reached it, so returned links are absolute. */
    public static final String PUBLIC_BASE_URL = "enterfind.mcp.publicBaseUrl";

    private McpGraphQlContext() {
    }
}
