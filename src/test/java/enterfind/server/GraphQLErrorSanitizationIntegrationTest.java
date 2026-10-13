package enterfind.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import enterfind.server.mcp.model.JsonRpcResponse;

/**
 * Regression test: GraphQL errors must not leak full Java stack traces to
 * anonymous callers.
 *
 * The original observation was an MCP-driven query: an invalid
 * enum value passed through a query variable (e.g. "WEB" for a DataSourceType-
 * typed variable) triggered a CoercingParseValueException whose ~80-frame stack
 * trace ended up in the client-facing error payload, leaking Spring / Tomcat /
 * MCP internals.
 *
 * Two surfaces serialize GraphQL errors and need to behave consistently:
 *
 *   1. The HTTP /graphql endpoint — Spring's WebGraphQlHandler sanitizes errors.
 *      Pinned here so a future change does not regress it.
 *
 *   2. The MCP execute_graphql_query tool — GraphQLExecutionTool serializes raw
 *      GraphQLError objects via Jackson, which by default walks the underlying
 *      Throwable and emits stackTrace. This is the actual leak path.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class GraphQLErrorSanitizationIntegrationTest extends BaseDatabaseTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private TestRestTemplate restTemplate;
    private McpTestClient mcpClient;

    @BeforeEach
    void setUpClients() {
        restTemplate = new TestRestTemplate();
        mcpClient = new McpTestClient(port);
    }

    private ResponseEntity<String> executeGraphQL(String query, Map<String, Object> variables) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of("query", query, "variables", variables);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        return restTemplate.postForEntity("http://localhost:" + port + "/graphql", request, String.class);
    }

    private void assertNoStackTraceLeak(String body) {
        assertThat(body)
                .as("Error response must not leak Java stack frames or internal class names")
                .doesNotContain("stackTrace")
                .doesNotContain("CoercingParseValueException")
                .doesNotContain("graphql.execution")
                .doesNotContain("graphql.schema")
                .doesNotContain("enterfind.server.mcp")
                .doesNotContain("org.springframework")
                .doesNotContain("org.apache.tomcat")
                .doesNotContain("java.lang.Thread.run");
    }

    @Test
    void httpGraphQL_invalidEnumVariable_doesNotLeakStackTrace() throws Exception {
        // "WEB" is not a member of DataSourceType (LOCAL_FILESYSTEM, REMOTE_FILESYSTEM, S3_BUCKET).
        String query = """
            query ($type: DataSourceType!) {
                dataSource(name: "anything", type: $type) {
                    name
                }
            }
            """;

        ResponseEntity<String> response = executeGraphQL(query, Map.of("type", "WEB"));

        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.has("errors")).isTrue();
        assertThat(json.get("errors").size()).isGreaterThan(0);

        JsonNode firstError = json.get("errors").get(0);
        assertThat(firstError.has("message")).isTrue();
        assertThat(firstError.has("stackTrace")).isFalse();
        assertThat(firstError.path("extensions").has("stackTrace")).isFalse();
        assertThat(firstError.path("extensions").has("exception")).isFalse();

        assertNoStackTraceLeak(response.getBody());
    }

    @Test
    void httpGraphQL_invalidEnumLiteral_doesNotLeakStackTrace() throws Exception {
        // The query-literal path returns a ValidationError; pin clean behavior.
        String query = """
            query {
                dataSource(name: "anything", type: WEB) {
                    name
                }
            }
            """;

        ResponseEntity<String> response = executeGraphQL(query, Map.of());

        JsonNode json = objectMapper.readTree(response.getBody());
        assertThat(json.has("errors")).isTrue();
        assertThat(json.get("errors").size()).isGreaterThan(0);

        assertNoStackTraceLeak(response.getBody());
    }

    @Test
    void mcpExecuteGraphql_invalidEnumVariable_doesNotLeakStackTrace() throws Exception {
        // Same invalid-enum coercion error driven through the MCP execute_graphql_query
        // tool — this is the path the bug report observed leaking the stack trace.
        String query = """
            query ($type: DataSourceType!) {
                dataSource(name: "anything", type: $type) {
                    name
                }
            }
            """;

        var variables = objectMapper.createObjectNode();
        variables.put("type", "WEB");

        ResponseEntity<JsonRpcResponse> response = mcpClient.executeGraphQLQuery(query, variables);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonRpcResponse body = response.getBody();
        assertThat(body).isNotNull();

        JsonNode resultNode = objectMapper.valueToTree(body.getResult());
        assertThat(resultNode.path("isError").asBoolean()).isTrue();

        String errorText = resultNode.path("content").get(0).path("text").asString();
        assertNoStackTraceLeak(errorText);
    }
}
