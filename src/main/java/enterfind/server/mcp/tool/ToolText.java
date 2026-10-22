package enterfind.server.mcp.tool;

/**
 * Everything the MCP tools say to the model: the tool descriptions, their parameter descriptions and
 * the setup hint. Kept in one place so the whole contract a model works from can be read, and
 * reviewed for consistency, without reading tool logic.
 *
 * <p>{@code tools/list} serves one of two variants depending on whether the connection carries an
 * Authorization header, so the model is told only the way that applies to it rather than finding
 * out by failing. A header connection is never told about {@code login}, {@code token} or
 * {@code ?t=}: it holds no credential and needs none.
 */
final class ToolText {

    // ---- Authentication, shared by both tools ----

    /** The connector carries an API key; the server turns it into a session on every call. */
    private static final String AUTH_BY_HEADER =
        "AUTHENTICATION is handled by the connection. Never pass a 'token' argument, never call " +
        "'login', and never ask the user for credentials. ";

    /** No header: the model logs in itself, as before connectors could carry a key. */
    private static final String AUTH_BY_LOGIN =
        "AUTHENTICATION: Most operations have a 'token: String' argument. Workflow: " +
        "(1) Call 'isAuthenticationRequired' query to check if login is needed. " +
        "(2) If auth required, call 'loginWithApiKey' (input: nickname, keyValue) with an Enterfind API key, " +
        "or 'login' (input: nickname, totpCode) for a person signing in, to get a token. " +
        "(3) Pass the token in variables for subsequent operations: { \"token\": \"your-token\", ... }. ";

    // ---- introspect_graphql_schema ----

    private static final String INTROSPECTION_INTRO =
        "Get the GraphQL schema for Enterfind document search system. IMPORTANT: Call this FIRST before using execute_graphql_query. ";

    private static final String INTROSPECTION_BODY =
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
        "rather than inferring behaviour from operation names.";

    // ---- execute_graphql_query ----

    private static final String EXECUTION_INTRO =
        "Execute GraphQL queries against Enterfind document search system. " +
        "PREREQUISITE: Call introspect_graphql_schema first to understand the schema. ";

    private static final String EXECUTION_WORKFLOW =
        "SEARCH WORKFLOW: " +
        "(1) Start with a broad 'search' query using searchString with keywords. " +
        "(2) Call 'availableItemAttributeKeys' to discover facet dimensions. " +
        "(3) Use 'getFacets' with attribute keys to see value distributions. " +
        "(4) Refine with searchConditions array to filter by facet values. " +
        "(5) Use 'itemAttributes' to get detailed metadata. " +
        "TIPS: Combine searchString with searchConditions for precise results. Use skip/howmany for pagination. ";

    // Links come back absolute through MCP (see the Item link resolvers), so the model never has to
    // know or guess the host. With a header they already carry their credential, which is why ?t= is
    // only ever explained to a model that logged in and so holds the token anyway.
    private static final String RETRIEVAL_BY_HEADER =
        "DOCUMENT RETRIEVAL: request an item's 'downloadLink' (or 'previewLink' for a rendered preview) " +
        "and fetch that URL exactly as returned; do not build or modify content URLs. " +
        "If the URL cannot be fetched, request 'rawBase64Content' instead.";

    private static final String RETRIEVAL_BY_LOGIN =
        "DOCUMENT RETRIEVAL: request an item's 'downloadLink' (or 'previewLink' for a rendered preview). " +
        "It is an absolute URL. If you logged in, append '?t=<your token>' to it. " +
        "Do not build content URLs yourself. " +
        "If the URL cannot be fetched, request 'rawBase64Content' instead.";

    static final String QUERY_PARAMETER = "GraphQL query or mutation string";

    private static final String VARIABLES_BY_HEADER = "Query variables as JSON object.";

    private static final String VARIABLES_BY_LOGIN =
        "Query variables as JSON object. Include 'token' for authenticated operations: { \"token\": \"your-token\", ... }";

    // Sent with an authentication error on a connection without a header, so that a model left
    // without credentials points the user at connector setup instead of asking for a key in chat.
    private static final String SETUP_HINT =
        " This Enterfind instance requires sign-in. The recommended setup puts an API key in the " +
        "connector's Authorization header so that no credentials pass through the chat; see %s%s.";

    // A cloud connector reaches the instance through the relay; a local client such as Claude Code
    // or the Gemini CLI reaches it directly, and its header setup is on the local page.
    private static final String REMOTE_SETUP_PAGE = "/public-ai-setup.html";
    private static final String LOCAL_SETUP_PAGE = "/mcp-setup.html#cli-assistants";

    static String introspectionDescription(boolean headerAuth) {
        return INTROSPECTION_INTRO + auth(headerAuth) + INTROSPECTION_BODY;
    }

    static String executionDescription(boolean headerAuth) {
        return EXECUTION_INTRO + auth(headerAuth) + EXECUTION_WORKFLOW
            + (headerAuth ? RETRIEVAL_BY_HEADER : RETRIEVAL_BY_LOGIN);
    }

    static String variablesParameter(boolean headerAuth) {
        return headerAuth ? VARIABLES_BY_HEADER : VARIABLES_BY_LOGIN;
    }

    /**
     * @param publicBaseUrl   the instance's URL as the client reached it; empty for a relative link
     * @param viaRemoteAccess whether the request came through the remote-access tunnel
     */
    static String setupHint(String publicBaseUrl, boolean viaRemoteAccess) {
        return SETUP_HINT.formatted(publicBaseUrl, viaRemoteAccess ? REMOTE_SETUP_PAGE : LOCAL_SETUP_PAGE);
    }

    private static String auth(boolean headerAuth) {
        return headerAuth ? AUTH_BY_HEADER : AUTH_BY_LOGIN;
    }

    private ToolText() {
    }
}
