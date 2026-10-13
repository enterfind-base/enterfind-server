package enterfind.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;

import enterfind.server.model.UserLevel;
import enterfind.server.service.AuthenticationService;
import enterfind.server.service.AuthenticationService.UserCreationResult;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("alpha")
public class SavedQueryGraphQLIntegrationTest extends BaseDatabaseTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AuthenticationService authenticationService;

    private GraphQLTestClient graphQLClient;
    private String adminToken;

    @Override
    @BeforeEach
    protected void setUp() {
        super.setUp();
        graphQLClient = new GraphQLTestClient(port);
        
        // Create admin user and get token
        UserCreationResult admin = authenticationService.createUser("admin", UserLevel.ADMIN, null);
        String recoveryCode = admin.recoveryCodes().get(0);
        
        String loginMutation = String.format("""
            mutation {
                loginWithRecoveryCode(input: {
                    nickname: "admin"
                    recoveryCode: "%s"
                }) {
                    token
                }
            }
            """, recoveryCode);

        JsonNode loginResponse = graphQLClient.executeAndParse(loginMutation);
        adminToken = loginResponse.path("data").path("loginWithRecoveryCode").path("token").asString();
    }

    @Test
    public void testCreateSavedQuery() throws Exception {
        String mutation = String.format("""
            mutation {
                createSavedQuery(token: "%s", input: {
                    searchString: "pdf documents"
                    queryConditions: [
                        { name: "content-type", operation: EQUAL, value: "application/pdf" }
                        { name: "path", operation: LIKE, value: "/documents" }
                    ]
                    sortDirection: DESC
                }) {
                    id
                    name
                    searchString
                    queryConditions {
                        name
                        operation
                        value
                    }
                    sortDirection
                }
            }
            """, adminToken);

        JsonNode response = graphQLClient.executeAndParse(mutation);

        assertFalse(response.has("errors"), "Response has errors: " + response.path("errors").toString());
        JsonNode data = response.path("data").path("createSavedQuery");
        assertNotNull(data.path("id").asString());
        assertNotNull(data.path("name").asString()); // Name is auto-generated
        assertEquals("pdf documents", data.path("searchString").asString());
        assertEquals(2, data.path("queryConditions").size());
        assertEquals("content-type", data.path("queryConditions").get(0).path("name").asString());
        assertEquals("EQUAL", data.path("queryConditions").get(0).path("operation").asString());
        assertEquals("DESC", data.path("sortDirection").asString());
    }

    @Test
    public void testCreateSavedQuery_requiresAuth() throws Exception {
        String mutation = """
            mutation {
                createSavedQuery(input: {
                    queryConditions: [
                        { name: "path", operation: LIKE, value: "/test" }
                    ]
                }) {
                    id
                }
            }
            """;

        JsonNode response = graphQLClient.executeAndParse(mutation);

        assertTrue(response.has("errors"));
        assertTrue(response.path("errors").get(0).path("message").asString().contains("Authentication required"));
    }

    @Test
    public void testCreateSavedQuery_emptySearchAndConditions() throws Exception {
        String mutation = String.format("""
            mutation {
                createSavedQuery(token: "%s", input: {
                    queryConditions: []
                }) {
                    id
                }
            }
            """, adminToken);

        JsonNode response = graphQLClient.executeAndParse(mutation);

        assertTrue(response.has("errors"));
        // Should fail validation requiring at least searchString or conditions
    }

    @Test
    public void testGetSavedQueries() throws Exception {
        // Create a few saved queries first
        createTestSavedQuery("query1", adminToken);
        createTestSavedQuery("query2", adminToken);

        String query = String.format("""
            query {
                savedQueries(token: "%s") {
                    id
                    name
                    searchString
                    queryConditions {
                        name
                        operation
                        value
                    }
                    sortDirection
                    dateCreated
                }
            }
            """, adminToken);

        JsonNode response = graphQLClient.executeAndParse(query);

        assertFalse(response.has("errors"));
        JsonNode data = response.path("data").path("savedQueries");
        assertEquals(2, data.size());
    }

    @Test
    public void testGetSavedQueries_empty() throws Exception {
        String query = String.format("""
            query {
                savedQueries(token: "%s") {
                    id
                }
            }
            """, adminToken);

        JsonNode response = graphQLClient.executeAndParse(query);

        assertFalse(response.has("errors"));
        JsonNode data = response.path("data").path("savedQueries");
        assertEquals(0, data.size());
    }

    @Test
    public void testGetSavedQuery() throws Exception {
        String queryId = createTestSavedQuery("test search", adminToken);

        String query = String.format("""
            query {
                savedQuery(token: "%s", id: "%s") {
                    id
                    name
                    searchString
                    queryConditions {
                        name
                        operation
                        value
                    }
                    sortDirection
                }
            }
            """, adminToken, queryId);

        JsonNode response = graphQLClient.executeAndParse(query);

        assertFalse(response.has("errors"));
        JsonNode data = response.path("data").path("savedQuery");
        assertEquals(queryId, data.path("id").asString());
        assertNotNull(data.path("name").asString()); // Name is auto-generated
        assertEquals("test search", data.path("searchString").asString());
    }

    @Test
    public void testGetSavedQuery_notFound() throws Exception {
        String query = String.format("""
            query {
                savedQuery(token: "%s", id: "nonexistent") {
                    id
                }
            }
            """, adminToken);

        JsonNode response = graphQLClient.executeAndParse(query);

        assertTrue(response.has("errors"));
        assertTrue(response.path("errors").get(0).path("message").asString().contains("not found"));
    }

    @Test
    public void testDeleteSavedQuery() throws Exception {
        String queryId = createTestSavedQuery("test", adminToken);

        String mutation = String.format("""
            mutation {
                deleteSavedQuery(token: "%s", id: "%s")
            }
            """, adminToken, queryId);

        JsonNode response = graphQLClient.executeAndParse(mutation);

        assertFalse(response.has("errors"));
        assertTrue(response.path("data").path("deleteSavedQuery").asBoolean());

        // Verify it's actually deleted
        String verifyQuery = String.format("""
            query {
                savedQueries(token: "%s") {
                    id
                }
            }
            """, adminToken);

        JsonNode verifyResponse = graphQLClient.executeAndParse(verifyQuery);
        assertEquals(0, verifyResponse.path("data").path("savedQueries").size());
    }

    @Test
    public void testDeleteSavedQuery_notFound() throws Exception {
        String mutation = String.format("""
            mutation {
                deleteSavedQuery(token: "%s", id: "nonexistent")
            }
            """, adminToken);

        JsonNode response = graphQLClient.executeAndParse(mutation);

        assertTrue(response.has("errors"));
        assertTrue(response.path("errors").get(0).path("message").asString().contains("not found"));
    }

    @Test
    public void testSavedQueries_userIsolation() throws Exception {
        // Create another user
        UserCreationResult user2 = authenticationService.createUser("user2", UserLevel.READER, null);
        String recoveryCode2 = user2.recoveryCodes().get(0);
        
        String loginMutation = String.format("""
            mutation {
                loginWithRecoveryCode(input: {
                    nickname: "user2"
                    recoveryCode: "%s"
                }) {
                    token
                }
            }
            """, recoveryCode2);

        JsonNode loginResponse = graphQLClient.executeAndParse(loginMutation);
        String user2Token = loginResponse.path("data").path("loginWithRecoveryCode").path("token").asString();

        // Create queries for both users
        createTestSavedQuery("admin search", adminToken);
        createTestSavedQuery("user2 search", user2Token);

        // Verify admin can only see their query
        String adminQuery = String.format("query { savedQueries(token: \"%s\") { searchString } }", adminToken);
        JsonNode adminResponse = graphQLClient.executeAndParse(adminQuery);
        assertEquals(1, adminResponse.path("data").path("savedQueries").size());
        assertEquals("admin search", adminResponse.path("data").path("savedQueries").get(0).path("searchString").asString());

        // Verify user2 can only see their query
        String user2Query = String.format("query { savedQueries(token: \"%s\") { searchString } }", user2Token);
        JsonNode user2Response = graphQLClient.executeAndParse(user2Query);
        assertEquals(1, user2Response.path("data").path("savedQueries").size());
        assertEquals("user2 search", user2Response.path("data").path("savedQueries").get(0).path("searchString").asString());
    }

    /**
     * Helper method to create a test saved query and return its ID
     */
    private String createTestSavedQuery(String searchString, String token) throws Exception {
        String mutation = String.format("""
            mutation {
                createSavedQuery(token: "%s", input: {
                    searchString: "%s"
                    queryConditions: [
                        { name: "path", operation: LIKE, value: "/test" }
                    ]
                    sortDirection: DESC
                }) {
                    id
                }
            }
            """, token, searchString);

        JsonNode response = graphQLClient.executeAndParse(mutation);
        return response.path("data").path("createSavedQuery").path("id").asString();
    }
}
