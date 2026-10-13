package enterfind.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Integration test to verify that SMB datasource cache is properly invalidated
 * when credentials are updated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
public class SMBCacheInvalidationIntegrationTest extends BaseDatabaseTest {

    private static final Logger logger = LoggerFactory.getLogger(SMBCacheInvalidationIntegrationTest.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Execute a GraphQL query/mutation
     */
    private ResponseEntity<String> executeGraphQL(String query) {
        String requestBody = String.format("{\"query\": %s}", escapeJsonString(query));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(requestBody, headers);
        return restTemplate.postForEntity("/graphql", request, String.class);
    }

    /**
     * Escape a string for use in JSON
     */
    private String escapeJsonString(String str) {
        try {
            return objectMapper.writeValueAsString(str);
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to escape JSON string", e);
        }
    }

    @Test
    void testSMBDataSourceCacheInvalidationOnPasswordUpdate() throws JacksonException {
        // Step 1: Create a Remote File System datasource with correct credentials
        String createMutation = """
            mutation {
                createRemoteFileSystemDataSource(input: {
                    name: "Test SMB Cache Invalidation"
                    cronPattern: "0 0 * * *"
                    server: "localhost"
                    username: "smbtest"
                    password: "smbtest123"
                    shareName: "scratch"
                    path: ""
                }) {
                    name
                    server
                    username
                    shareName
                }
            }
            """;

        ResponseEntity<String> createResponse = executeGraphQL(createMutation);
        assertTrue(createResponse.getStatusCode().is2xxSuccessful());
        assertNotNull(createResponse.getBody());

        JsonNode createJsonResponse = objectMapper.readTree(createResponse.getBody());
        assertFalse(createJsonResponse.has("errors"),
            "GraphQL response should not contain errors: " + createResponse.getBody());

        logger.info("Created test datasource successfully");

        // Step 2: Test the datasource - should succeed with correct password
        String testQuery = """
            query {
                testDataSource(name: "Test SMB Cache Invalidation", type: REMOTE_FILESYSTEM) {
                    success
                    message
                }
            }
            """;

        ResponseEntity<String> testResponse1 = executeGraphQL(testQuery);
        assertTrue(testResponse1.getStatusCode().is2xxSuccessful());
        assertNotNull(testResponse1.getBody());

        JsonNode testJsonResponse1 = objectMapper.readTree(testResponse1.getBody());
        assertFalse(testJsonResponse1.has("errors"),
            "GraphQL test response should not contain errors: " + testResponse1.getBody());

        JsonNode testResult1 = testJsonResponse1.get("data").get("testDataSource");
        assertTrue(testResult1.get("success").asBoolean(),
            "Test should succeed with correct password. Message: " + testResult1.get("message"));

        logger.info("Step 2: Test with correct password succeeded");

        // Step 3: Update the datasource with an incorrect password
        String updateMutation1 = """
            mutation {
                updateRemoteFileSystemDataSource(
                    name: "Test SMB Cache Invalidation"
                    input: {
                        password: "wrongpassword"
                    }
                ) {
                    name
                }
            }
            """;

        ResponseEntity<String> updateResponse1 = executeGraphQL(updateMutation1);
        assertTrue(updateResponse1.getStatusCode().is2xxSuccessful());
        assertNotNull(updateResponse1.getBody());

        JsonNode updateJsonResponse1 = objectMapper.readTree(updateResponse1.getBody());
        assertFalse(updateJsonResponse1.has("errors"),
            "GraphQL update response should not contain errors: " + updateResponse1.getBody());

        logger.info("Step 3: Updated password to incorrect value");

        // Step 4: Test the datasource - should fail with incorrect password
        ResponseEntity<String> testResponse2 = executeGraphQL(testQuery);
        assertTrue(testResponse2.getStatusCode().is2xxSuccessful());
        assertNotNull(testResponse2.getBody());

        JsonNode testJsonResponse2 = objectMapper.readTree(testResponse2.getBody());
        assertFalse(testJsonResponse2.has("errors"),
            "GraphQL test response should not contain errors: " + testResponse2.getBody());

        JsonNode testResult2 = testJsonResponse2.get("data").get("testDataSource");
        assertFalse(testResult2.get("success").asBoolean(),
            "Test should fail with incorrect password");
        assertNotNull(testResult2.get("message"), "Should have error message when test fails");
        assertTrue(testResult2.get("message").asString().contains("SMB connection"),
            "Error message should mention SMB connection. Message: " + testResult2.get("message"));

        logger.info("Step 4: Test with wrong password failed as expected. Message: {}",
            testResult2.get("message"));

        // Step 5: Update the datasource back to correct password
        String updateMutation2 = """
            mutation {
                updateRemoteFileSystemDataSource(
                    name: "Test SMB Cache Invalidation"
                    input: {
                        password: "smbtest123"
                    }
                ) {
                    name
                }
            }
            """;

        ResponseEntity<String> updateResponse2 = executeGraphQL(updateMutation2);
        assertTrue(updateResponse2.getStatusCode().is2xxSuccessful());
        assertNotNull(updateResponse2.getBody());

        JsonNode updateJsonResponse2 = objectMapper.readTree(updateResponse2.getBody());
        assertFalse(updateJsonResponse2.has("errors"),
            "GraphQL update response should not contain errors: " + updateResponse2.getBody());

        logger.info("Step 5: Updated password back to correct value");

        // Step 6: Test the datasource - should succeed again with correct password
        ResponseEntity<String> testResponse3 = executeGraphQL(testQuery);
        assertTrue(testResponse3.getStatusCode().is2xxSuccessful());
        assertNotNull(testResponse3.getBody());

        JsonNode testJsonResponse3 = objectMapper.readTree(testResponse3.getBody());
        assertFalse(testJsonResponse3.has("errors"),
            "GraphQL test response should not contain errors: " + testResponse3.getBody());

        JsonNode testResult3 = testJsonResponse3.get("data").get("testDataSource");
        assertTrue(testResult3.get("success").asBoolean(),
            "Test should succeed again after updating back to correct password. Message: " + testResult3.get("message"));

        logger.info("Step 6: Test with correct password succeeded again - cache invalidation working!");
    }
}
