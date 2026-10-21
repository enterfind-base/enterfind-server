package enterfind.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import enterfind.server.service.RemoteAccessService;

/**
 * Integration tests for the /openapi.json endpoint.
 */
@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"server.forward-headers-strategy=native"})
@ActiveProfiles("alpha")
public class OpenApiControllerIntegrationTest extends BaseDatabaseTest {

    @LocalServerPort
    private int port;

    @Autowired
    private RemoteAccessService remoteAccessService;

    private final TestRestTemplate restTemplate = new TestRestTemplate();
    private final ObjectMapper objectMapper = new JsonMapper();

    @Test
    void openApiJson_returnsOkWithJsonContentType() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/openapi.json", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isNotNull()
                .matches(ct -> ct.isCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void openApiJson_hasRequiredTopLevelFields() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/openapi.json", String.class);

        JsonNode doc = objectMapper.readTree(response.getBody());
        assertThat(doc.path("openapi").asString()).isEqualTo("3.1.0");
        assertThat(doc.path("info").path("title").asString()).isEqualTo("Enterfind");
        assertThat(doc.path("info").path("description").asString()).isNotBlank();
        assertThat(doc.path("info").path("version").asString()).isNotBlank();
        assertThat(doc.path("servers").isArray()).isTrue();
        assertThat(doc.path("servers").size()).isGreaterThanOrEqualTo(1);
        assertThat(doc.path("paths").has("/graphql")).isTrue();
    }

    @Test
    void openApiJson_serverUrl_reflectsLocalRequest() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/openapi.json", String.class);

        JsonNode doc = objectMapper.readTree(response.getBody());
        String serverUrl = doc.path("servers").get(0).path("url").asString();
        assertThat(serverUrl).isEqualTo("http://localhost:" + port);
    }

    @Test
    void openApiJson_serverUrl_usesTunnelUrlWhenProxyHeaderPresent() throws Exception {
        String expectedServerId = remoteAccessService.getConfig().serverId();
        String expectedUrl = "https://" + expectedServerId + ".remote-alpha.enterfind.com";

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Enterfind-Tunnel-Proxy", "1");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/openapi.json",
                HttpMethod.GET,
                entity,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode doc = objectMapper.readTree(response.getBody());
        String serverUrl = doc.path("servers").get(0).path("url").asString();
        assertThat(serverUrl).isEqualTo(expectedUrl);
    }

    @Test
    void openApiJson_serverUrl_usesForwardedProtoAndHost() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-Proto", "https");
        headers.set("X-Forwarded-Host", "my-server.example.com");
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + "/openapi.json",
                HttpMethod.GET,
                entity,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode doc = objectMapper.readTree(response.getBody());
        String serverUrl = doc.path("servers").get(0).path("url").asString();
        assertThat(serverUrl).isEqualTo("https://my-server.example.com");
    }

    @Test
    void openApiJson_graphqlOperation_hasRequiredRequestBodyFields() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/openapi.json", String.class);

        JsonNode post = objectMapper.readTree(response.getBody())
                .path("paths").path("/graphql").path("post");

        assertThat(post.path("operationId").asString()).isEqualTo("executeGraphQL");
        assertThat(post.path("summary").asString()).isNotBlank();
        assertThat(post.path("description").asString()).isNotBlank();

        JsonNode requestBody = post.path("requestBody");
        assertThat(requestBody.path("required").asBoolean()).isTrue();
        assertThat(requestBody.path("content").has("application/json")).isTrue();

        JsonNode schema = requestBody.path("content").path("application/json").path("schema");
        assertThat(schema.path("required").isArray()).isTrue();
        assertThat(schema.path("required").get(0).asString()).isEqualTo("query");

        JsonNode properties = schema.path("properties");
        assertThat(properties.has("query")).isTrue();
        assertThat(properties.has("variables")).isTrue();
        assertThat(properties.has("operationName")).isTrue();
    }

    @Test
    void openApiJson_graphqlOperation_hasResponse200() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/openapi.json", String.class);

        JsonNode response200 = objectMapper.readTree(response.getBody())
                .path("paths").path("/graphql").path("post").path("responses").path("200");

        assertThat(response200.path("description").asString()).isNotBlank();
        assertThat(response200.path("content").has("application/json")).isTrue();

        JsonNode responseSchema = response200.path("content").path("application/json").path("schema");
        assertThat(responseSchema.path("properties").has("data")).isTrue();
        assertThat(responseSchema.path("properties").has("errors")).isTrue();
    }
}
