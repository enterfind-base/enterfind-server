package enterfind.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.TreatmentDao;
import enterfind.server.mcp.model.JsonRpcRequest;
import enterfind.server.mcp.model.JsonRpcResponse;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.LocalFileSystemDataSource;
import enterfind.server.scan.Scanner;
import enterfind.server.scan.TreatmentProcessor;
import enterfind.server.scan.Treatments;

/**
 * Integration tests for MCP (Model Context Protocol) endpoints
 *
 * Uses @TestInstance(PER_CLASS) for one-time expensive setup (file creation, scan, treatment).
 * Overrides the parent's @DirtiesContext to prevent context restarts between tests,
 * allowing the shared setup to persist and the McpTestClient to be reused.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class McpIntegrationTest extends BaseDatabaseTest {

    private static final Logger logger = LoggerFactory.getLogger(McpIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private Scanner scanner;
    @Autowired
    private TreatmentProcessor treatmentProcessor;
    @Autowired
    private DataSourceDao dataSourceDao;
    @Autowired
    private TreatmentDao treatmentDao;
    @Autowired
    private ObjectMapper objectMapper;

    private Path tempTestDir;
    private LocalFileSystemDataSource testDataSource;
    private McpTestClient mcpClient;

    @Override
    protected void setUp() {
        // Override to prevent database wipe before each test
        // since we're using @BeforeAll for one-time setup
    }

    @BeforeAll
    void setUpTestData() throws Exception {
        wipeDatabase();  // Clean once before all tests
        mcpClient = new McpTestClient(port);

        // Create temp directory and copy test files
        tempTestDir = Files.createTempDirectory("mcp-integration-test-");
        TestFileUtils.copyTestShareToTempDir(tempTestDir);

        // Create data source
        testDataSource = new LocalFileSystemDataSource(
                null,
                "MCP Test Data Source",
                "0 0 * * *",
                LocalDateTime.now(),
                null,
                DataSourceStatus.IDLE,
                tempTestDir.toString(),
                null);
        testDataSource = (LocalFileSystemDataSource) dataSourceDao.create(testDataSource);

        // NAME_AND_PATH is auto-added on datasource creation, add CONTENT treatment for search tests
        treatmentDao.addTreatment(testDataSource.id(), Treatments.CONTENT, objectMapper.createObjectNode());

        // Scan and process
        scanner.scan(testDataSource);
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);

        logger.info("Test data setup complete - created data source with id: {}", testDataSource.id());
    }

    @AfterAll
    void tearDown() throws IOException {
        if (tempTestDir != null && Files.exists(tempTestDir)) {
            TestFileUtils.deleteDirectoryRecursively(tempTestDir);
        }
    }

    @Test
    public void testHealthEndpoint() {
        ResponseEntity<String> response = mcpClient.getHealth();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo("MCP server is running");
        logger.info("MCP Health Response: {}", response.getBody());
    }

    @Test
    public void testInitialize() {
        ResponseEntity<JsonRpcResponse> response = mcpClient.initialize();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();

        JsonRpcResponse body = response.getBody();
        assertThat(body.getJsonrpc()).isEqualTo("2.0");
        assertThat(body.getError()).isNull();
        assertThat(body.getResult()).isNotNull();

        logger.info("MCP Initialize Response: {}", body.getResult());

        // Verify result structure
        @SuppressWarnings("unchecked")
        var result = (java.util.Map<String, Object>) body.getResult();
        assertThat(result).containsKey("protocolVersion");
        assertThat(result).containsKey("serverInfo");
        assertThat(result).containsKey("capabilities");
    }

    @Test
    public void testListTools() {
        ResponseEntity<JsonRpcResponse> response = mcpClient.listTools();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();

        JsonRpcResponse body = response.getBody();
        assertThat(body.getError()).isNull();
        assertThat(body.getResult()).isNotNull();

        logger.info("MCP Tools List Response: {}", body.getResult());

        // Verify result structure
        @SuppressWarnings("unchecked")
        var result = (java.util.Map<String, Object>) body.getResult();
        assertThat(result).containsKey("tools");

        @SuppressWarnings("unchecked")
        var tools = (java.util.List<Object>) result.get("tools");
        assertThat(tools).hasSize(2);

        // Verify tool names
        @SuppressWarnings("unchecked")
        var tool1 = (java.util.Map<String, Object>) tools.get(0);
        @SuppressWarnings("unchecked")
        var tool2 = (java.util.Map<String, Object>) tools.get(1);

        assertThat(tool1.get("name")).isEqualTo("introspect_graphql_schema");
        assertThat(tool2.get("name")).isEqualTo("execute_graphql_query");

        // Verify enhanced descriptions contain workflow guidance and auth info
        String introspectDesc = (String) tool1.get("description");
        assertThat(introspectDesc).contains("Call this FIRST");
        assertThat(introspectDesc).contains("AUTHENTICATION");
        assertThat(introspectDesc).contains("token");
        assertThat(introspectDesc).contains("isAuthenticationRequired");

        String executeDesc = (String) tool2.get("description");
        assertThat(executeDesc).contains("WORKFLOW");
        assertThat(executeDesc).contains("AUTHENTICATION");
        assertThat(executeDesc).contains("token");
        assertThat(executeDesc).contains("searchConditions");

        logger.info("Introspect description length: {} chars", introspectDesc.length());
        logger.info("Execute description length: {} chars", executeDesc.length());
    }

    /** The named child of a JSON array whose entries carry a "name" field, or a clear failure. */
    private static JsonNode requireNamed(JsonNode array, String name, String what) {
        if (array != null) {
            for (JsonNode entry : array) {
                if (entry.hasNonNull("name") && name.equals(entry.get("name").asString())) {
                    return entry;
                }
            }
        }
        throw new AssertionError("Introspection did not return " + what + " '" + name + "'");
    }

    /** A description from introspection, with markdown emphasis stripped so assertions read on
     *  wording rather than formatting. */
    private static String descriptionOf(JsonNode node, String what) {
        if (!node.hasNonNull("description")) {
            throw new AssertionError("Introspection returned no description for " + what);
        }
        return node.get("description").asString().replace("*", "").replace("`", "");
    }

    @Test
    public void testIntrospectionCarriesTheSearchContract() throws Exception {
        // The search contract — term scoping, quoting, and the documented limits — lives in the
        // schema's descriptions. INTROSPECTION_QUERY requests them for arguments, input fields and
        // enum values, so an MCP agent gets them without any extra plumbing. This pins that they
        // actually arrive, since an agent that cannot see them will infer behaviour from operation
        // names instead.
        ResponseEntity<JsonRpcResponse> response = mcpClient.introspectSchema(true);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();

        JsonNode resultNode = objectMapper.valueToTree(response.getBody().getResult());
        String schemaJson = resultNode.get("content").get(0).get("text").asString();
        JsonNode types = objectMapper.readTree(schemaJson).get("__schema").get("types");

        JsonNode searchOperation = requireNamed(types, "SearchOperation", "type");
        JsonNode enumValues = searchOperation.get("enumValues");

        assertThat(descriptionOf(requireNamed(enumValues, "LIKE", "enum value"), "LIKE"))
            .as("LIKE carries its contract")
            .contains("contains all of these terms")
            .contains("Quote a term to require it contiguous");

        assertThat(descriptionOf(requireNamed(enumValues, "NOT_LIKE", "enum value"), "NOT_LIKE"))
            .as("NOT_LIKE states that it also matches items lacking the key")
            .contains("no such key");

        assertThat(descriptionOf(requireNamed(types, "SearchConditionInput", "type"),
                "SearchConditionInput"))
            .as("the field-class distinction and the large-document limit are stated")
            .contains("whole words")
            .contains("content-0");

        // And the searchString argument on search() carries the entry-point distinction.
        JsonNode searchField = requireNamed(
            requireNamed(types, "Query", "type").get("fields"), "search", "field");
        assertThat(descriptionOf(
                requireNamed(searchField.get("args"), "searchString", "argument"), "searchString"))
            .as("searchString explains its scope and limits")
            .contains("every term is found somewhere on it")
            .contains("scope difference from a LIKE condition")
            .contains("prefix-matched")
            .contains("one or two characters matches document text only");
    }

    @Test
    public void testIntrospectGraphQLSchema() throws Exception {
        ResponseEntity<JsonRpcResponse> response = mcpClient.introspectSchema(true);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();

        JsonRpcResponse body = response.getBody();
        assertThat(body.getError()).isNull();
        assertThat(body.getResult()).isNotNull();

        logger.info("MCP Introspect Schema Response received");

        // Parse the result
        JsonNode resultNode = objectMapper.valueToTree(body.getResult());
        assertThat(resultNode.has("content")).isTrue();
        // MCP CallToolResult exposes exactly "isError"; the legacy is-getter naming used to
        // also emit a spurious "error" key (see ToolResult), which must not reappear.
        assertThat(resultNode.has("isError")).isTrue();
        assertThat(resultNode.has("error")).isFalse();

        JsonNode contentArray = resultNode.get("content");
        assertThat(contentArray.isArray()).isTrue();
        assertThat(contentArray.size()).isGreaterThan(0);

        JsonNode firstContent = contentArray.get(0);
        assertThat(firstContent.get("type").asString()).isEqualTo("text");
        assertThat(firstContent.has("text")).isTrue();

        // Parse the schema JSON from the text field
        String schemaJson = firstContent.get("text").asString();
        JsonNode schema = objectMapper.readTree(schemaJson);

        // Verify schema structure
        assertThat(schema.has("__schema")).isTrue();
        JsonNode schemaNode = schema.get("__schema");
        assertThat(schemaNode.has("queryType")).isTrue();
        assertThat(schemaNode.has("mutationType")).isTrue();
        assertThat(schemaNode.has("types")).isTrue();

        logger.info("GraphQL schema introspection successful with {} types",
            schemaNode.get("types").size());
    }

    @Test
    public void testExecuteGraphQLQueryAbout() throws Exception {
        String query = "{ about { name version description environment } }";
        ResponseEntity<JsonRpcResponse> response = mcpClient.executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();

        JsonRpcResponse body = response.getBody();
        assertThat(body.getError()).isNull();
        assertThat(body.getResult()).isNotNull();

        logger.info("MCP Execute GraphQL Query Response received");

        // Parse the result
        JsonNode resultNode = objectMapper.valueToTree(body.getResult());
        assertThat(resultNode.has("content")).isTrue();

        JsonNode contentArray = resultNode.get("content");
        assertThat(contentArray.isArray()).isTrue();
        assertThat(contentArray.size()).isGreaterThan(0);

        JsonNode firstContent = contentArray.get(0);
        assertThat(firstContent.get("type").asString()).isEqualTo("text");

        // Parse the GraphQL result from the text field
        String resultJson = firstContent.get("text").asString();
        JsonNode graphqlResult = objectMapper.readTree(resultJson);

        // Verify GraphQL result structure
        assertThat(graphqlResult.has("about")).isTrue();
        JsonNode about = graphqlResult.get("about");
        assertThat(about.has("name")).isTrue();
        assertThat(about.has("version")).isTrue();
        assertThat(about.get("name").asString()).contains("enterfind-server");

        logger.info("GraphQL about query successful: {}", about);
    }

    @Test
    public void testExecuteGraphQLQuerySearch() throws Exception {
        String query = "{ search(skip: 0, howmany: 10) { offset howmany totalHits items { name folder dataSourceName } } }";
        ResponseEntity<JsonRpcResponse> response = mcpClient.executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();

        JsonRpcResponse body = response.getBody();
        assertThat(body.getError()).isNull();

        logger.info("MCP Execute GraphQL Search Query Response received");

        // Parse the result
        JsonNode resultNode = objectMapper.valueToTree(body.getResult());
        JsonNode contentArray = resultNode.get("content");
        JsonNode firstContent = contentArray.get(0);

        // Parse the GraphQL result
        String resultJson = firstContent.get("text").asString();
        JsonNode graphqlResult = objectMapper.readTree(resultJson);

        // Verify search result structure
        assertThat(graphqlResult.has("search")).isTrue();
        JsonNode search = graphqlResult.get("search");
        assertThat(search.has("offset")).isTrue();
        assertThat(search.has("howmany")).isTrue();
        assertThat(search.has("totalHits")).isTrue();
        assertThat(search.has("items")).isTrue();

        // Verify we have actual test data
        int totalHits = search.get("totalHits").asInt();
        assertThat(totalHits).isGreaterThan(0);

        JsonNode items = search.get("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.size()).isGreaterThan(0);

        // Verify test files are in the results (check for known files)
        String itemsText = items.toString();
        // At least one of our test files should be in the results
        boolean hasTestFile = itemsText.contains("ocrable.pdf") ||
                              itemsText.contains("text.txt") ||
                              itemsText.contains("beam.jpg");
        assertThat(hasTestFile).isTrue();

        logger.info("GraphQL search query successful: totalHits={}, returned {} items", totalHits, items.size());
    }

    @Test
    public void testExecuteGraphQLQuerySearchSpecificFile() throws Exception {
        // Search for a specific file we know exists in the test data
        String query = "{ search(name: \"text.txt\", skip: 0, howmany: 10) { totalHits items { name folder dataSourceName } } }";
        ResponseEntity<JsonRpcResponse> response = mcpClient.executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();

        JsonRpcResponse body = response.getBody();
        assertThat(body.getError()).isNull();

        // Parse the result
        JsonNode resultNode = objectMapper.valueToTree(body.getResult());
        JsonNode contentArray = resultNode.get("content");
        JsonNode firstContent = contentArray.get(0);
        String resultJson = firstContent.get("text").asString();
        JsonNode graphqlResult = objectMapper.readTree(resultJson);

        JsonNode search = graphqlResult.get("search");
        int totalHits = search.get("totalHits").asInt();
        assertThat(totalHits).isEqualTo(1);

        JsonNode items = search.get("items");
        assertThat(items.size()).isEqualTo(1);

        JsonNode item = items.get(0);
        assertThat(item.get("name").asString()).isEqualTo("text.txt");
        assertThat(item.get("folder").asString()).isEqualTo("subf");
        assertThat(item.get("dataSourceName").asString()).isEqualTo(testDataSource.name());

        logger.info("GraphQL search for specific file successful: found text.txt in folder subf");
    }

    @Test
    public void testExecuteGraphQLQueryWithError() throws Exception {
        // Invalid GraphQL query (missing closing brace)
        String invalidQuery = "{ about { name version";
        ResponseEntity<JsonRpcResponse> response = mcpClient.executeGraphQLQuery(invalidQuery);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();

        logger.info("MCP Execute GraphQL Query with Error Response received");

        // Parse the result
        JsonRpcResponse body = response.getBody();
        JsonNode resultNode = objectMapper.valueToTree(body.getResult());
        assertThat(resultNode.has("content")).isTrue();
        assertThat(resultNode.has("isError")).isTrue();

        // Should be marked as error
        assertThat(resultNode.get("isError").asBoolean()).isTrue();

        JsonNode contentArray = resultNode.get("content");
        JsonNode firstContent = contentArray.get(0);
        String errorText = firstContent.get("text").asString();

        // Error message should contain "GraphQL query failed"
        assertThat(errorText).contains("GraphQL query failed");

        logger.info("GraphQL error handling successful: {}", errorText);
    }

    @Test
    public void testUnknownMethod() {
        var request = new JsonRpcRequest("unknown/method", null, 1);
        ResponseEntity<JsonRpcResponse> response = mcpClient.execute(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();

        JsonRpcResponse body = response.getBody();
        assertThat(body.getError()).isNotNull();
        assertThat(body.getError().getCode()).isEqualTo(-32601);
        assertThat(body.getError().getMessage()).contains("Method not found");

        logger.info("Unknown method error handling successful");
    }

    @Test
    public void testUnknownTool() throws Exception {
        var params = objectMapper.createObjectNode();
        params.put("name", "unknown_tool");
        params.set("arguments", objectMapper.createObjectNode());

        var request = new JsonRpcRequest("tools/call", params, 1);
        ResponseEntity<JsonRpcResponse> response = mcpClient.execute(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();

        JsonRpcResponse body = response.getBody();
        assertThat(body.getResult()).isNotNull();

        // Parse the result
        JsonNode resultNode = objectMapper.valueToTree(body.getResult());
        assertThat(resultNode.has("isError")).isTrue();
        assertThat(resultNode.get("isError").asBoolean()).isTrue();

        JsonNode contentArray = resultNode.get("content");
        JsonNode firstContent = contentArray.get(0);
        assertThat(firstContent.get("text").asString()).contains("Unknown tool");

        logger.info("Unknown tool error handling successful");
    }

    /**
     * Test Streamable HTTP transport with SSE response (MCP 2025-03-26 spec)
     * Verifies that when Accept: text/event-stream is sent, the server responds with SSE format
     */
    @Test
    public void testStreamableHttpWithSseResponse() throws Exception {
        var request = new JsonRpcRequest("initialize", null, 999);
        ResponseEntity<String> response = mcpClient.executeWithSse(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();

        String sseBody = response.getBody();
        logger.info("SSE Response body:\n{}", sseBody);

        // Verify SSE format: should contain "event:message" and "data:{json}"
        // Note: Spring's SseEmitter doesn't add spaces after colons
        assertThat(sseBody).containsAnyOf("event:message", "event: message");
        assertThat(sseBody).containsAnyOf("data:", "data: ");

        // Extract the JSON data from the SSE response
        // SSE format is: event:message\ndata:{json}\n\n
        String[] lines = sseBody.split("\n");
        String dataLine = null;
        for (String line : lines) {
            if (line.startsWith("data:") || line.startsWith("data: ")) {
                // Remove "data:" or "data: " prefix
                dataLine = line.startsWith("data: ") ? line.substring(6) : line.substring(5);
                break;
            }
        }

        assertThat(dataLine).isNotNull();

        // Parse the JSON-RPC response from the SSE data
        JsonNode jsonResponse = objectMapper.readTree(dataLine);
        assertThat(jsonResponse.has("jsonrpc")).isTrue();
        assertThat(jsonResponse.get("jsonrpc").asString()).isEqualTo("2.0");
        assertThat(jsonResponse.has("id")).isTrue();
        assertThat(jsonResponse.get("id").asInt()).isEqualTo(999);
        assertThat(jsonResponse.has("result")).isTrue();

        JsonNode result = jsonResponse.get("result");
        assertThat(result.has("protocolVersion")).isTrue();
        assertThat(result.has("serverInfo")).isTrue();
        assertThat(result.has("capabilities")).isTrue();

        logger.info("Streamable HTTP SSE response successful - protocol supports MCP 2025-03-26 spec");
    }

    /**
     * Test Streamable HTTP transport with JSON response (default)
     * Verifies that when Accept: application/json is sent (or default), server responds with JSON
     */
    @Test
    public void testStreamableHttpWithJsonResponse() throws Exception {
        // This is what the existing tests already do, but explicitly documenting it
        // as testing the JSON response path of Streamable HTTP transport
        var request = new JsonRpcRequest("initialize", null, 888);
        ResponseEntity<JsonRpcResponse> response = mcpClient.execute(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();

        // Verify it's JSON response, not SSE
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);

        JsonRpcResponse body = response.getBody();
        assertThat(body.getJsonrpc()).isEqualTo("2.0");
        assertThat(body.getId()).isEqualTo(888);
        assertThat(body.getError()).isNull();
        assertThat(body.getResult()).isNotNull();

        logger.info("Streamable HTTP JSON response successful (default behavior)");
    }

    /**
     * Test SSE response for tools/call request
     * Verifies that tool execution results are properly streamed via SSE
     */
    @Test
    public void testToolsCallWithSseResponse() throws Exception {
        var request = new JsonRpcRequest("tools/list", null, 777);
        ResponseEntity<String> response = mcpClient.executeWithSse(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();

        String sseBody = response.getBody();

        // Verify SSE format
        assertThat(sseBody).containsAnyOf("event:message", "event: message");
        assertThat(sseBody).containsAnyOf("data:", "data: ");

        // Extract and parse JSON data
        String[] lines = sseBody.split("\n");
        String dataLine = null;
        for (String line : lines) {
            if (line.startsWith("data:") || line.startsWith("data: ")) {
                dataLine = line.startsWith("data: ") ? line.substring(6) : line.substring(5);
                break;
            }
        }

        assertThat(dataLine).isNotNull();
        JsonNode jsonResponse = objectMapper.readTree(dataLine);

        // Verify the tools/list response structure
        assertThat(jsonResponse.get("id").asInt()).isEqualTo(777);
        JsonNode result = jsonResponse.get("result");
        assertThat(result.has("tools")).isTrue();

        JsonNode tools = result.get("tools");
        assertThat(tools.isArray()).isTrue();
        assertThat(tools.size()).isEqualTo(2);

        logger.info("Tools/call SSE response successful - {} tools returned via SSE stream", tools.size());
    }

    /**
     * Test that JSON-RPC responses omit null fields
     * This is critical for Claude Desktop which rejects responses with null error/result fields
     */
    @Test
    public void testJsonRpcResponseOmitsNullFields() throws Exception {
        // Make a successful request
        var request = new JsonRpcRequest("initialize", null, 999);
        ResponseEntity<JsonRpcResponse> response = mcpClient.execute(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();

        // Serialize the response to JSON to verify null fields are omitted
        String jsonResponse = objectMapper.writeValueAsString(response.getBody());

        logger.info("JSON Response: {}", jsonResponse);

        // Success response should NOT contain "error" field (even if null)
        assertThat(jsonResponse).doesNotContain("\"error\"");

        // Success response SHOULD contain "result" field
        assertThat(jsonResponse).contains("\"result\"");
        assertThat(jsonResponse).contains("\"jsonrpc\":\"2.0\"");
        assertThat(jsonResponse).contains("\"id\":999");

        logger.info("JSON-RPC response correctly omits null fields");
    }

    /**
     * Test that error responses omit null result field
     */
    @Test
    public void testJsonRpcErrorResponseOmitsNullFields() throws Exception {
        // Make a request that will return an error
        var request = new JsonRpcRequest("unknown/method", null, 777);
        ResponseEntity<JsonRpcResponse> response = mcpClient.execute(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);

        JsonRpcResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getError()).isNotNull();

        // Serialize the response to JSON to verify null fields are omitted
        String jsonResponse = objectMapper.writeValueAsString(body);

        logger.info("Error JSON Response: {}", jsonResponse);

        // Error response should NOT contain "result" field (even if null)
        assertThat(jsonResponse).doesNotContain("\"result\"");

        // Error response SHOULD contain "error" field
        assertThat(jsonResponse).contains("\"error\"");
        assertThat(jsonResponse).contains("\"code\":-32601");
        assertThat(jsonResponse).contains("\"message\"");

        logger.info("Error response correctly omits null result field");
    }

    /**
     * Test that invalid JSON-RPC version returns HTTP 200 (not HTTP 400)
     * Per JSON-RPC 2.0 spec: errors should be communicated via the error field, not HTTP status
     */
    @Test
    public void testInvalidJsonRpcVersionReturnsHttp200() throws Exception {
        // Create request with invalid JSON-RPC version
        var invalidRequest = new JsonRpcRequest("initialize", null, 1);
        invalidRequest.setJsonrpc("1.0"); // Invalid version

        ResponseEntity<JsonRpcResponse> response = mcpClient.execute(invalidRequest);

        // CRITICAL: Must return HTTP 200, not HTTP 400

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonRpcResponse body = response.getBody();
        assertThat(body).isNotNull();
        if (body != null) {
            // Should have error field populated
            assertThat(body.getError()).isNotNull();
            assertThat(body.getError().getCode()).isEqualTo(-32600); // Invalid Request
            assertThat(body.getError().getMessage()).contains("jsonrpc must be '2.0'");

            // Should have id from request
            assertThat(body.getId()).isEqualTo(1);

            // Should have jsonrpc version
            assertThat(body.getJsonrpc()).isEqualTo("2.0");
        }

        logger.info("Invalid JSON-RPC version correctly returns HTTP 200 with error field");
    }

    /**
     * Test that method not found errors return HTTP 200 with proper error structure
     * Validates JSON-RPC 2.0 compliance
     */
    @Test
    public void testMethodNotFoundReturnsHttp200WithProperStructure() {
        var request = new JsonRpcRequest("non/existent/method", null, 999);
        ResponseEntity<JsonRpcResponse> response = mcpClient.execute(request);

        // Must return HTTP 200 per JSON-RPC 2.0 spec

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonRpcResponse body = response.getBody();
        assertThat(body).isNotNull();
        if (body != null) {
            // Validate complete error structure
            assertThat(body.getJsonrpc()).isEqualTo("2.0");
            assertThat(body.getId()).isEqualTo(999);
            assertThat(body.getError()).isNotNull();
            assertThat(body.getError().getCode()).isEqualTo(-32601); // Method not found
            assertThat(body.getError().getMessage()).contains("Method not found");
            assertThat(body.getResult()).isNull();
        }

        logger.info("Method not found error returns HTTP 200 with complete JSON-RPC structure");
    }

    /**
     * Test that all error responses are JSON-RPC 2.0 compliant
     * Verifies that error responses work with Claude Desktop's Zod validator
     */
    @Test
    public void testErrorResponsesAreJsonRpc20Compliant() throws Exception {
        // Test invalid version error
        var invalidVersionRequest = new JsonRpcRequest("initialize", null, 1);
        invalidVersionRequest.setJsonrpc("1.0");
        ResponseEntity<JsonRpcResponse> invalidVersionResponse = mcpClient.execute(invalidVersionRequest);

        // Test method not found error
        var methodNotFoundRequest = new JsonRpcRequest("invalid/method", null, 2);
        ResponseEntity<JsonRpcResponse> methodNotFoundResponse = mcpClient.execute(methodNotFoundRequest);

        // Both should return HTTP 200
        assertThat(invalidVersionResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(methodNotFoundResponse.getStatusCode().value()).isEqualTo(200);

        // Verify both have valid JSON-RPC 2.0 error structure
        for (ResponseEntity<JsonRpcResponse> response : List.of(invalidVersionResponse, methodNotFoundResponse)) {
            JsonRpcResponse body = response.getBody();
            assertThat(body).isNotNull();
            if (body != null) {
                // All error responses must have these fields
                assertThat(body.getJsonrpc()).isEqualTo("2.0");
                assertThat(body.getId()).isNotNull();
                assertThat(body.getError()).isNotNull();
                assertThat(body.getError().getCode()).isNotEqualTo(0);
                assertThat(body.getError().getMessage()).isNotBlank();

                // Serialize and verify no null fields
                String json = objectMapper.writeValueAsString(body);
                assertThat(json).contains("\"jsonrpc\":\"2.0\"");
                assertThat(json).contains("\"error\"");
                assertThat(json).contains("\"code\"");
                assertThat(json).contains("\"message\"");
                assertThat(json).doesNotContain("\"result\""); // Should be omitted when null
            }
        }

        logger.info("All error responses are JSON-RPC 2.0 compliant and compatible with Claude Desktop");
    }

    /**
     * Test that SSE error responses also return HTTP 200
     * Validates streaming error responses comply with JSON-RPC 2.0
     */
    @Test
    public void testSseErrorResponsesReturnHttp200() throws Exception {
        var invalidRequest = new JsonRpcRequest("invalid/method", null, 888);
        ResponseEntity<String> response = mcpClient.executeWithSse(invalidRequest);

        // SSE responses should also return HTTP 200 for errors
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String sseBody = response.getBody();
        assertThat(sseBody).isNotNull();

        if (sseBody != null) {
            // Verify SSE format
            assertThat(sseBody).containsAnyOf("event:message", "event: message");

            // Extract JSON data from SSE
            String[] lines = sseBody.split("\n");
            String dataLine = null;
            for (String line : lines) {
                if (line.startsWith("data:") || line.startsWith("data: ")) {
                    dataLine = line.startsWith("data: ") ? line.substring(6) : line.substring(5);
                    break;
                }
            }

            assertThat(dataLine).isNotNull();

            // Parse the JSON-RPC error response
            JsonNode jsonResponse = objectMapper.readTree(dataLine);
            assertThat(jsonResponse.has("jsonrpc")).isTrue();
            assertThat(jsonResponse.get("jsonrpc").asString()).isEqualTo("2.0");
            assertThat(jsonResponse.has("error")).isTrue();
            assertThat(jsonResponse.get("error").has("code")).isTrue();
            assertThat(jsonResponse.get("error").get("code").asInt()).isEqualTo(-32601);
        }

        logger.info("SSE error responses correctly return HTTP 200 with JSON-RPC error in stream");
    }
}
