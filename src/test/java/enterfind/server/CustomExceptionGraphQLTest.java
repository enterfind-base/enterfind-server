package enterfind.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration tests for custom exception handling in GraphQL
 *
 * This test class verifies that custom exceptions are properly mapped to GraphQL errors
 * with appropriate error types and extension information.
 *
 * Tested exceptions:
 * - NotFoundException: Mapped to ErrorType.NOT_FOUND
 * - InvalidArgumentException: Mapped to ErrorType.BAD_REQUEST
 *
 * Note on SystemIssueException:
 * SystemIssueException is configured in GraphQLConfig.java with the same pattern as the other
 * exceptions and is mapped to ErrorType.INTERNAL_ERROR. It's primarily thrown for system-level
 * issues (IO errors, serialization failures, etc.) which are difficult to reliably trigger
 * in integration tests. The configuration can be verified through code inspection in
 * GraphQLConfig.java:50-59.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class CustomExceptionGraphQLTest extends BaseDatabaseTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private GraphQLTestClient graphQLClient;

    @BeforeEach
    void setUpGraphQLClient() {
        graphQLClient = new GraphQLTestClient(port);
    }

    private ResponseEntity<String> executeGraphQL(String query) {
        return graphQLClient.execute(query);
    }

    @Test
    void testNotFoundException() throws Exception {
        // Try to query a non-existent datasource
        String query = """
            query {
                dataSourceTreatments(dataSourceName: "NonExistentDataSource", dataSourceType: LOCAL_FILESYSTEM) {
                    treatment
                }
            }
            """;

        ResponseEntity<String> response = executeGraphQL(query);
        
        // Should return 200 OK but with GraphQL error in response
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());
        
        // Should have errors
        assertThat(jsonResponse.has("errors")).isTrue();
        JsonNode errors = jsonResponse.get("errors");
        assertThat(errors.isArray()).isTrue();
        assertThat(errors.size()).isGreaterThan(0);
        
        JsonNode firstError = errors.get(0);
        assertThat(firstError.has("message")).isTrue();
        assertThat(firstError.get("message").asString()).contains("Data source not found");

        // Verify custom error information is present
        assertThat(firstError.has("extensions")).isTrue();
        JsonNode extensions = firstError.get("extensions");
        assertThat(extensions.has("errorType")).isTrue();
        assertThat(extensions.get("errorType").asString()).isEqualTo("NOT_FOUND");
        assertThat(extensions.has("errorCode")).isTrue();
        assertThat(extensions.get("errorCode").asString()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void testInvalidArgumentException() throws Exception {
        // First create a test datasource
        String createDataSource = """
            mutation {
                createLocalFileSystemDataSource(input: {
                    name: "TestExceptionDataSource",
                    cronPattern: "0 0 * * *",
                    localPath: "/tmp/test"
                }) {
                    name
                }
            }
            """;

        executeGraphQL(createDataSource);

        // Try to add treatment with invalid JSON config
        String addTreatment = """
            mutation {
                addDataSourceTreatment(input: {
                    dataSourceName: "TestExceptionDataSource",
                    dataSourceType: LOCAL_FILESYSTEM,
                    treatment: CONTENT,
                    config: "invalid json { not valid"
                }) {
                    treatment
                }
            }
            """;

        ResponseEntity<String> response = executeGraphQL(addTreatment);
        
        // Should return 200 OK but with GraphQL error in response
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());
        
        // Should have errors
        assertThat(jsonResponse.has("errors")).isTrue();
        JsonNode errors = jsonResponse.get("errors");
        assertThat(errors.isArray()).isTrue();
        assertThat(errors.size()).isGreaterThan(0);
        
        JsonNode firstError = errors.get(0);
        assertThat(firstError.has("message")).isTrue();
        assertThat(firstError.get("message").asString()).contains("Invalid JSON configuration");

        // Verify custom error information is present
        assertThat(firstError.has("extensions")).isTrue();
        JsonNode extensions = firstError.get("extensions");
        assertThat(extensions.has("errorType")).isTrue();
        assertThat(extensions.get("errorType").asString()).isEqualTo("INVALID_ARGUMENT");
        assertThat(extensions.has("errorCode")).isTrue();
        assertThat(extensions.get("errorCode").asString()).isEqualTo("BAD_REQUEST");
    }
}