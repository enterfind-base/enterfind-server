package enterfind.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Verifies appliance-mode behaviour at the GraphQL layer.
 * The server is started with enterfind.appliance-mode=true for this entire class.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("alpha")
@TestPropertySource(properties = "enterfind.appliance-mode=true")
public class ApplianceModeIntegrationTest extends BaseDatabaseTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    private GraphQLTestClient graphQLClient;
    private final ObjectMapper objectMapper = new JsonMapper();

    @Override
    @BeforeEach
    protected void setUp() {
        super.setUp();
        graphQLClient = new GraphQLTestClient(restTemplate, port);
    }

    /**
     * An admin mutation must be refused when appliance-mode is on.
     * No users exist in this test, so auth is not required — the appliance guard
     * fires first and still blocks the mutation.
     */
    @Test
    void adminMutation_isRefused_inApplianceMode() throws Exception {
        String mutation = """
                mutation {
                    createLocalFileSystemDataSource(input: {
                        name: "Should Not Be Created"
                        localPath: "/tmp/appliance-test"
                    }) {
                        name
                    }
                }
                """;

        JsonNode response = objectMapper.readTree(graphQLClient.execute(mutation).getBody());

        assertTrue(response.has("errors"), "Expected a GraphQL error but got none");

        String errorMessage = response.path("errors").get(0).path("message").asString();
        assertTrue(
            errorMessage.contains("appliance mode"),
            "Error message should mention appliance mode, but was: " + errorMessage
        );

        assertFalse(
            response.path("data").has("createLocalFileSystemDataSource"),
            "Mutation data should not be present in the response"
        );
    }
}
