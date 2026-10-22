package enterfind.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;

import enterfind.server.dao.ApiSessionDao;
import enterfind.server.dao.ApiUserDao;
import enterfind.server.model.UserLevel;
import enterfind.server.service.AuthenticationService;
import enterfind.server.service.AuthenticationService.UserCreationResult;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("alpha")
public class ApiKeyAuthenticationIntegrationTest extends BaseDatabaseTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private ApiUserDao apiUserDao;

    @Autowired
    private ApiSessionDao apiSessionDao;

    private GraphQLTestClient graphQLClient;
    private String adminToken;

    @Override
    @BeforeEach
    protected void setUp() {
        super.setUp();
        graphQLClient = new GraphQLTestClient(port);

        // Create admin user and get token for tests that need it
        UserCreationResult admin = authenticationService.createUser("admin", UserLevel.ADMIN, null);
        String recoveryCode = admin.recoveryCodes().get(0);

        try {
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
        } catch (Exception e) {
            throw new RuntimeException("Failed to setup admin user", e);
        }
    }

    // ========== Create API User Tests ==========

    @Test
    public void testCreateApiUser_success() throws Exception {
        String mutation = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "jenkins-ci"
                    userLevel: READER
                    expirationDays: 30
                }) {
                    apiUser {
                        id
                        nickname
                        keyValuePreview
                        userLevel
                        isActive
                        isExpired
                    }
                    keyValue
                }
            }
            """, adminToken);

        JsonNode response = graphQLClient.executeAndParse(mutation);

        assertFalse(response.has("errors"), "Response has errors: " + response.path("errors").toString());
        JsonNode data = response.path("data").path("createApiUser");

        // Verify API user data
        JsonNode apiUser = data.path("apiUser");
        assertNotNull(apiUser.path("id").asString());
        assertEquals("jenkins-ci", apiUser.path("nickname").asString());
        assertEquals("READER", apiUser.path("userLevel").asString());
        assertTrue(apiUser.path("isActive").asBoolean());
        assertFalse(apiUser.path("isExpired").asBoolean());

        // Verify key value is returned
        String keyValue = data.path("keyValue").asString();
        assertNotNull(keyValue);
        assertTrue(keyValue.startsWith("efk_"), "API key should start with efk_ prefix");
        assertTrue(keyValue.length() > 10, "API key should be sufficiently long");

        // Verify key preview
        String preview = apiUser.path("keyValuePreview").asString();
        assertTrue(preview.startsWith("..."), "Preview should start with ...");
        assertTrue(keyValue.endsWith(preview.substring(3)), "Preview should match last 4 chars");

        // Verify it's in the database
        assertTrue(apiUserDao.existsByNickname("jenkins-ci"));
    }

    @Test
    public void testCreateApiUser_adminLevel() throws Exception {
        String mutation = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "admin-api"
                    userLevel: ADMIN
                    expirationDays: 365
                }) {
                    apiUser {
                        nickname
                        userLevel
                    }
                }
            }
            """, adminToken);

        JsonNode response = graphQLClient.executeAndParse(mutation);

        assertFalse(response.has("errors"));
        assertEquals("ADMIN", response.path("data").path("createApiUser")
            .path("apiUser").path("userLevel").asString());
    }

    @Test
    public void testCreateApiUser_duplicateNickname() throws Exception {
        // Create first API key
        String mutation1 = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "duplicate-test"
                    userLevel: READER
                    expirationDays: 30
                }) {
                    apiUser { id }
                }
            }
            """, adminToken);
        graphQLClient.executeAndParse(mutation1);

        // Try to create second with same nickname
        String mutation2 = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "duplicate-test"
                    userLevel: READER
                    expirationDays: 30
                }) {
                    apiUser { id }
                }
            }
            """, adminToken);

        JsonNode response = graphQLClient.executeAndParse(mutation2);

        assertTrue(response.has("errors"));
        String errorMessage = response.path("errors").get(0).path("message").asString();
        assertTrue(errorMessage.contains("already exists") || errorMessage.contains("duplicate"),
            "Error should mention duplicate/already exists");
    }

    @Test
    public void testCreateApiUser_requiresAdmin() throws Exception {
        // Create a reader user
        UserCreationResult reader = authenticationService.createUser("reader", UserLevel.READER, null);
        String readerRecoveryCode = reader.recoveryCodes().get(0);

        String readerLoginMutation = String.format("""
            mutation {
                loginWithRecoveryCode(input: {
                    nickname: "reader"
                    recoveryCode: "%s"
                }) {
                    token
                }
            }
            """, readerRecoveryCode);

        JsonNode loginResponse = graphQLClient.executeAndParse(readerLoginMutation);
        String readerToken = loginResponse.path("data").path("loginWithRecoveryCode").path("token").asString();

        // Try to create API key with reader token
        String mutation = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "should-fail"
                    userLevel: READER
                    expirationDays: 30
                }) {
                    apiUser { id }
                }
            }
            """, readerToken);

        JsonNode response = graphQLClient.executeAndParse(mutation);

        assertTrue(response.has("errors"), "Reader should not be able to create API keys");
        String errorMessage = response.path("errors").get(0).path("message").asString();
        assertTrue(errorMessage.toLowerCase().contains("admin") || errorMessage.toLowerCase().contains("permission"),
            "Error should mention admin/permission");
    }

    @Test
    public void testCreateApiUser_variousExpirationDays() throws Exception {
        int[] expirationOptions = {1, 7, 30, 120, 365, 730};

        for (int days : expirationOptions) {
            String mutation = String.format("""
                mutation {
                    createApiUser(token: "%s", input: {
                        nickname: "test-expiry-%d"
                        userLevel: READER
                        expirationDays: %d
                    }) {
                        apiUser {
                            nickname
                            dateExpires
                        }
                    }
                }
                """, adminToken, days, days);

            JsonNode response = graphQLClient.executeAndParse(mutation);

            assertFalse(response.has("errors"), 
                "Should accept " + days + " days expiration: " + response.path("errors"));
            assertNotNull(response.path("data").path("createApiUser").path("apiUser").path("dateExpires").asString());
        }
    }

    // ========== Login with API Key Tests ==========

    @Test
    public void testLoginWithApiKey_success() throws Exception {
        // Create API key
        String createMutation = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "test-login"
                    userLevel: READER
                    expirationDays: 30
                }) {
                    keyValue
                }
            }
            """, adminToken);

        JsonNode createResponse = graphQLClient.executeAndParse(createMutation);
        String keyValue = createResponse.path("data").path("createApiUser").path("keyValue").asString();

        // Login with API key
        String loginMutation = String.format("""
            mutation {
                loginWithApiKey(input: {
                    nickname: "test-login"
                    keyValue: "%s"
                }) {
                    token
                    user {
                        id
                        nickname
                        userLevel
                        isActive
                    }
                    expiresAt
                }
            }
            """, keyValue);

        JsonNode loginResponse = graphQLClient.executeAndParse(loginMutation);

        assertFalse(loginResponse.has("errors"), "Login should succeed");
        JsonNode data = loginResponse.path("data").path("loginWithApiKey");

        // Verify login response
        String sessionToken = data.path("token").asString();
        assertNotNull(sessionToken);
        assertFalse(sessionToken.isEmpty());

        JsonNode user = data.path("user");
        assertEquals("test-login", user.path("nickname").asString());
        assertEquals("READER", user.path("userLevel").asString());
        assertTrue(user.path("isActive").asBoolean());

        assertNotNull(data.path("expiresAt").asString());

        // Verify session was created in database
        assertTrue(apiSessionDao.findByToken(sessionToken).isPresent());

        // Verify last used was updated
        var apiUser = apiUserDao.findByNickname("test-login");
        assertTrue(apiUser.isPresent());
        assertNotNull(apiUser.get().dateLastUsed());
    }

    @Test
    public void testLoginWithApiKey_invalidKey() throws Exception {
        // Create API key but use wrong key value
        String createMutation = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "test-invalid"
                    userLevel: READER
                    expirationDays: 30
                }) {
                    keyValue
                }
            }
            """, adminToken);

        graphQLClient.executeAndParse(createMutation);

        // Try to login with wrong key
        String loginMutation = """
            mutation {
                loginWithApiKey(input: {
                    nickname: "test-invalid"
                    keyValue: "efk_wrongkey123456789"
                }) {
                    token
                }
            }
            """;

        JsonNode loginResponse = graphQLClient.executeAndParse(loginMutation);

        assertTrue(loginResponse.has("errors"), "Should fail with invalid key");
        String errorMessage = loginResponse.path("errors").get(0).path("message").asString();
        assertTrue(errorMessage.contains("Invalid credentials") || errorMessage.contains("invalid"),
            "Error should mention invalid credentials");
    }

    @Test
    public void testLoginWithApiKey_wrongNickname() throws Exception {
        String loginMutation = """
            mutation {
                loginWithApiKey(input: {
                    nickname: "nonexistent"
                    keyValue: "efk_doesnotexist123"
                }) {
                    token
                }
            }
            """;

        JsonNode response = graphQLClient.executeAndParse(loginMutation);

        assertTrue(response.has("errors"));
        String errorMessage = response.path("errors").get(0).path("message").asString();
        assertTrue(errorMessage.contains("Invalid credentials") || errorMessage.contains("not found"));
    }

    @Test
    public void testLoginWithApiKey_expiredKey() throws Exception {
        // Create API key with 1 day expiration
        String createMutation = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "test-expired"
                    userLevel: READER
                    expirationDays: 1
                }) {
                    apiUser { id }
                    keyValue
                }
            }
            """, adminToken);

        JsonNode createResponse = graphQLClient.executeAndParse(createMutation);
        String apiUserId = createResponse.path("data").path("createApiUser").path("apiUser").path("id").asString();
        String keyValue = createResponse.path("data").path("createApiUser").path("keyValue").asString();

        // Manually set expiration to past
        dbAccess.update("UPDATE API_USERS SET date_expires = NOW() - INTERVAL '1 day' WHERE id = ?", apiUserId);

        // Try to login with expired key
        String loginMutation = String.format("""
            mutation {
                loginWithApiKey(input: {
                    nickname: "test-expired"
                    keyValue: "%s"
                }) {
                    token
                }
            }
            """, keyValue);

        JsonNode loginResponse = graphQLClient.executeAndParse(loginMutation);

        assertTrue(loginResponse.has("errors"), "Should fail with expired key");
        String errorMessage = loginResponse.path("errors").get(0).path("message").asString();
        assertTrue(errorMessage.toLowerCase().contains("expired"));
    }

    @Test
    public void testLoginWithApiKey_inactiveKey() throws Exception {
        // Create API key
        String createMutation = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "test-inactive"
                    userLevel: READER
                    expirationDays: 30
                }) {
                    apiUser { id }
                    keyValue
                }
            }
            """, adminToken);

        JsonNode createResponse = graphQLClient.executeAndParse(createMutation);
        String apiUserId = createResponse.path("data").path("createApiUser").path("apiUser").path("id").asString();
        String keyValue = createResponse.path("data").path("createApiUser").path("keyValue").asString();

        // Deactivate the key
        apiUserDao.deactivate(apiUserId);

        // Try to login with inactive key
        String loginMutation = String.format("""
            mutation {
                loginWithApiKey(input: {
                    nickname: "test-inactive"
                    keyValue: "%s"
                }) {
                    token
                }
            }
            """, keyValue);

        JsonNode loginResponse = graphQLClient.executeAndParse(loginMutation);

        assertTrue(loginResponse.has("errors"), "Should fail with inactive key");
        String errorMessage = loginResponse.path("errors").get(0).path("message").asString();
        assertTrue(errorMessage.toLowerCase().contains("disabled") || errorMessage.toLowerCase().contains("inactive"));
    }

    // ========== Query API Users Tests ==========

    @Test
    public void testGetApiUsers() throws Exception {
        // Create multiple API keys
        for (int i = 1; i <= 3; i++) {
            String mutation = String.format("""
                mutation {
                    createApiUser(token: "%s", input: {
                        nickname: "api-key-%d"
                        userLevel: READER
                        expirationDays: 30
                    }) {
                        apiUser { id }
                    }
                }
                """, adminToken, i);
            graphQLClient.executeAndParse(mutation);
        }

        // Query all API users
        String query = String.format("""
            query {
                apiUsers(token: "%s") {
                    id
                    nickname
                    keyValuePreview
                    userLevel
                    dateCreated
                    dateExpires
                    dateLastUsed
                    isActive
                    isExpired
                }
            }
            """, adminToken);

        JsonNode response = graphQLClient.executeAndParse(query);

        assertFalse(response.has("errors"));
        JsonNode apiUsers = response.path("data").path("apiUsers");
        assertTrue(apiUsers.isArray());
        assertEquals(3, apiUsers.size());

        // Verify each has required fields
        for (JsonNode apiUser : apiUsers) {
            assertNotNull(apiUser.path("id").asString());
            assertNotNull(apiUser.path("nickname").asString());
            assertNotNull(apiUser.path("keyValuePreview").asString());
            assertTrue(apiUser.path("keyValuePreview").asString().startsWith("..."));
            assertEquals("READER", apiUser.path("userLevel").asString());
            assertTrue(apiUser.path("isActive").asBoolean());
            assertFalse(apiUser.path("isExpired").asBoolean());
        }
    }

    @Test
    public void testGetApiUser_byId() throws Exception {
        // Create API key
        String createMutation = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "single-api-key"
                    userLevel: ADMIN
                    expirationDays: 365
                }) {
                    apiUser { id }
                }
            }
            """, adminToken);

        JsonNode createResponse = graphQLClient.executeAndParse(createMutation);
        String apiUserId = createResponse.path("data").path("createApiUser").path("apiUser").path("id").asString();

        // Query single API user
        String query = String.format("""
            query {
                apiUser(token: "%s", id: "%s") {
                    id
                    nickname
                    userLevel
                    createdByUserId
                }
            }
            """, adminToken, apiUserId);

        JsonNode response = graphQLClient.executeAndParse(query);

        assertFalse(response.has("errors"));
        JsonNode apiUser = response.path("data").path("apiUser");
        assertEquals(apiUserId, apiUser.path("id").asString());
        assertEquals("single-api-key", apiUser.path("nickname").asString());
        assertEquals("ADMIN", apiUser.path("userLevel").asString());
        assertNotNull(apiUser.path("createdByUserId").asString());
    }

    @Test
    public void testGetApiUsers_requiresAdmin() throws Exception {
        // Create a reader user
        UserCreationResult reader = authenticationService.createUser("reader", UserLevel.READER, null);
        String readerRecoveryCode = reader.recoveryCodes().get(0);

        String readerLoginMutation = String.format("""
            mutation {
                loginWithRecoveryCode(input: {
                    nickname: "reader"
                    recoveryCode: "%s"
                }) {
                    token
                }
            }
            """, readerRecoveryCode);

        JsonNode loginResponse = graphQLClient.executeAndParse(readerLoginMutation);
        String readerToken = loginResponse.path("data").path("loginWithRecoveryCode").path("token").asString();

        // Try to query API users with reader token
        String query = String.format("""
            query {
                apiUsers(token: "%s") {
                    id
                }
            }
            """, readerToken);

        JsonNode response = graphQLClient.executeAndParse(query);

        assertTrue(response.has("errors"), "Reader should not be able to list API keys");
    }

    // ========== Delete API User Tests ==========

    @Test
    public void testDeleteApiUser() throws Exception {
        // Create API key
        String createMutation = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "to-delete"
                    userLevel: READER
                    expirationDays: 30
                }) {
                    apiUser { id }
                }
            }
            """, adminToken);

        JsonNode createResponse = graphQLClient.executeAndParse(createMutation);
        String apiUserId = createResponse.path("data").path("createApiUser").path("apiUser").path("id").asString();

        // Verify it exists
        assertTrue(apiUserDao.findById(apiUserId).isPresent());

        // Delete it
        String deleteMutation = String.format("""
            mutation {
                deleteApiUser(token: "%s", id: "%s")
            }
            """, adminToken, apiUserId);

        JsonNode deleteResponse = graphQLClient.executeAndParse(deleteMutation);

        assertFalse(deleteResponse.has("errors"));
        assertTrue(deleteResponse.path("data").path("deleteApiUser").asBoolean());

        // Verify it's deleted
        assertFalse(apiUserDao.findById(apiUserId).isPresent());
    }

    @Test
    public void testDeleteApiUser_invalidatesActiveSessions() throws Exception {
        // Create API key and login
        String createMutation = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "session-test"
                    userLevel: READER
                    expirationDays: 30
                }) {
                    keyValue
                    apiUser { id }
                }
            }
            """, adminToken);

        JsonNode createResponse = graphQLClient.executeAndParse(createMutation);
        String apiUserId = createResponse.path("data").path("createApiUser").path("apiUser").path("id").asString();
        String keyValue = createResponse.path("data").path("createApiUser").path("keyValue").asString();

        String loginMutation = String.format("""
            mutation {
                loginWithApiKey(input: {
                    nickname: "session-test"
                    keyValue: "%s"
                }) {
                    token
                }
            }
            """, keyValue);

        JsonNode loginResponse = graphQLClient.executeAndParse(loginMutation);
        String sessionToken = loginResponse.path("data").path("loginWithApiKey").path("token").asString();

        // Verify session exists and is valid
        assertTrue(apiSessionDao.findValidByToken(sessionToken).isPresent());

        // Delete API user
        String deleteMutation = String.format("""
            mutation {
                deleteApiUser(token: "%s", id: "%s")
            }
            """, adminToken, apiUserId);

        graphQLClient.executeAndParse(deleteMutation);

        // Verify session is gone (cascade delete)
        assertFalse(apiSessionDao.findByToken(sessionToken).isPresent());
    }

    @Test
    public void testDeleteApiUser_requiresAdmin() throws Exception {
        // Create a reader user
        UserCreationResult reader = authenticationService.createUser("reader", UserLevel.READER, null);
        String readerRecoveryCode = reader.recoveryCodes().get(0);

        String readerLoginMutation = String.format("""
            mutation {
                loginWithRecoveryCode(input: {
                    nickname: "reader"
                    recoveryCode: "%s"
                }) {
                    token
                }
            }
            """, readerRecoveryCode);

        JsonNode loginResponse = graphQLClient.executeAndParse(readerLoginMutation);
        String readerToken = loginResponse.path("data").path("loginWithRecoveryCode").path("token").asString();

        // Create API key to delete
        String createMutation = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "protected"
                    userLevel: READER
                    expirationDays: 30
                }) {
                    apiUser { id }
                }
            }
            """, adminToken);

        JsonNode createResponse = graphQLClient.executeAndParse(createMutation);
        String apiUserId = createResponse.path("data").path("createApiUser").path("apiUser").path("id").asString();

        // Try to delete with reader token
        String deleteMutation = String.format("""
            mutation {
                deleteApiUser(token: "%s", id: "%s")
            }
            """, readerToken, apiUserId);

        JsonNode deleteResponse = graphQLClient.executeAndParse(deleteMutation);

        assertTrue(deleteResponse.has("errors"), "Reader should not be able to delete API keys");

        // Verify it still exists
        assertTrue(apiUserDao.findById(apiUserId).isPresent());
    }

    // ========== API Session with Token Tests ==========

    @Test
    public void testApiSession_canAccessReaderEndpoints() throws Exception {
        // Create reader API key
        String createMutation = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "reader-api"
                    userLevel: READER
                    expirationDays: 30
                }) {
                    keyValue
                }
            }
            """, adminToken);

        JsonNode createResponse = graphQLClient.executeAndParse(createMutation);
        String keyValue = createResponse.path("data").path("createApiUser").path("keyValue").asString();

        // Login with API key
        String loginMutation = String.format("""
            mutation {
                loginWithApiKey(input: {
                    nickname: "reader-api"
                    keyValue: "%s"
                }) {
                    token
                }
            }
            """, keyValue);

        JsonNode loginResponse = graphQLClient.executeAndParse(loginMutation);
        String apiToken = loginResponse.path("data").path("loginWithApiKey").path("token").asString();

        // Use API token to access reader endpoint
        String query = String.format("""
            query {
                currentUser(token: "%s") {
                    nickname
                    userLevel
                }
            }
            """, apiToken);

        JsonNode response = graphQLClient.executeAndParse(query);

        assertFalse(response.has("errors"));
        JsonNode user = response.path("data").path("currentUser");
        assertEquals("reader-api", user.path("nickname").asString());
        assertEquals("READER", user.path("userLevel").asString());
    }

    @Test
    public void testApiSession_adminApiKey_canAccessAdminEndpoints() throws Exception {
        // Create admin API key
        String createMutation = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "admin-api"
                    userLevel: ADMIN
                    expirationDays: 30
                }) {
                    keyValue
                }
            }
            """, adminToken);

        JsonNode createResponse = graphQLClient.executeAndParse(createMutation);
        String keyValue = createResponse.path("data").path("createApiUser").path("keyValue").asString();

        // Login with API key
        String loginMutation = String.format("""
            mutation {
                loginWithApiKey(input: {
                    nickname: "admin-api"
                    keyValue: "%s"
                }) {
                    token
                }
            }
            """, keyValue);

        JsonNode loginResponse = graphQLClient.executeAndParse(loginMutation);
        String apiToken = loginResponse.path("data").path("loginWithApiKey").path("token").asString();

        // Use API token to access admin-only endpoint
        String query = String.format("""
            query {
                humanUsers(token: "%s") {
                    nickname
                }
            }
            """, apiToken);

        JsonNode response = graphQLClient.executeAndParse(query);

        assertFalse(response.has("errors"), "Admin API key should access admin endpoints");
        assertTrue(response.path("data").path("humanUsers").isArray());
    }

    @Test
    public void testApiSession_readerApiKey_cannotAccessAdminEndpoints() throws Exception {
        // Create reader API key
        String createMutation = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "limited-api"
                    userLevel: READER
                    expirationDays: 30
                }) {
                    keyValue
                }
            }
            """, adminToken);

        JsonNode createResponse = graphQLClient.executeAndParse(createMutation);
        String keyValue = createResponse.path("data").path("createApiUser").path("keyValue").asString();

        // Login with API key
        String loginMutation = String.format("""
            mutation {
                loginWithApiKey(input: {
                    nickname: "limited-api"
                    keyValue: "%s"
                }) {
                    token
                }
            }
            """, keyValue);

        JsonNode loginResponse = graphQLClient.executeAndParse(loginMutation);
        String apiToken = loginResponse.path("data").path("loginWithApiKey").path("token").asString();

        // Try to access admin-only endpoint
        String query = String.format("""
            query {
                humanUsers(token: "%s") {
                    nickname
                }
            }
            """, apiToken);

        JsonNode response = graphQLClient.executeAndParse(query);

        assertTrue(response.has("errors"), "Reader API key should not access admin endpoints");
    }

    @Test
    public void testApiKeyFormat_hasCorrectPrefix() throws Exception {
        String mutation = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "format-test"
                    userLevel: READER
                    expirationDays: 30
                }) {
                    keyValue
                }
            }
            """, adminToken);

        JsonNode response = graphQLClient.executeAndParse(mutation);
        String keyValue = response.path("data").path("createApiUser").path("keyValue").asString();

        assertTrue(keyValue.startsWith("efk_"), "API key should start with 'efk_' prefix");
        assertTrue(keyValue.length() > 10, "API key should have sufficient length");
    }

    @Test
    public void testApiUser_canExecuteRegularQueries() throws Exception {
        // Create reader API key
        String createMutation = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "query-test-api"
                    userLevel: READER
                    expirationDays: 30
                }) {
                    keyValue
                }
            }
            """, adminToken);

        JsonNode createResponse = graphQLClient.executeAndParse(createMutation);
        String keyValue = createResponse.path("data").path("createApiUser").path("keyValue").asString();

        // Login with API key
        String loginMutation = String.format("""
            mutation {
                loginWithApiKey(input: {
                    nickname: "query-test-api"
                    keyValue: "%s"
                }) {
                    token
                }
            }
            """, keyValue);

        JsonNode loginResponse = graphQLClient.executeAndParse(loginMutation);
        String apiToken = loginResponse.path("data").path("loginWithApiKey").path("token").asString();

        // Use API token to execute regular queries
        String aboutQuery = String.format("""
            query {
                about {
                    name
                    version
                }
            }
            """);

        JsonNode aboutResponse = graphQLClient.executeAndParse(aboutQuery);
        assertFalse(aboutResponse.has("errors"), "API user should be able to execute regular queries");
        assertNotNull(aboutResponse.path("data").path("about").path("name").asString());

        // Test currentUser query (reader-level access)
        String currentUserQuery = String.format("""
            query {
                currentUser(token: "%s") {
                    nickname
                    userLevel
                }
            }
            """, apiToken);

        JsonNode currentUserResponse = graphQLClient.executeAndParse(currentUserQuery);
        assertFalse(currentUserResponse.has("errors"), "API user should be able to query current user");
        assertEquals("query-test-api", currentUserResponse.path("data").path("currentUser").path("nickname").asString());
        assertEquals("READER", currentUserResponse.path("data").path("currentUser").path("userLevel").asString());
    }

    @Test
    public void testReaderApiUser_cannotExecuteAdminQueries() throws Exception {
        // Create reader API key
        String createMutation = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "limited-query-api"
                    userLevel: READER
                    expirationDays: 30
                }) {
                    keyValue
                }
            }
            """, adminToken);

        JsonNode createResponse = graphQLClient.executeAndParse(createMutation);
        String keyValue = createResponse.path("data").path("createApiUser").path("keyValue").asString();

        // Login with API key
        String loginMutation = String.format("""
            mutation {
                loginWithApiKey(input: {
                    nickname: "limited-query-api"
                    keyValue: "%s"
                }) {
                    token
                }
            }
            """, keyValue);

        JsonNode loginResponse = graphQLClient.executeAndParse(loginMutation);
        String apiToken = loginResponse.path("data").path("loginWithApiKey").path("token").asString();

        // Try to execute admin-only query - humanUsers
        String adminQuery = String.format("""
            query {
                humanUsers(token: "%s") {
                    id
                    nickname
                }
            }
            """, apiToken);

        JsonNode response = graphQLClient.executeAndParse(adminQuery);
        assertTrue(response.has("errors"), "Reader API user should not be able to execute admin queries");
        String errorMessage = response.path("errors").get(0).path("message").asString();
        assertTrue(errorMessage.toLowerCase().contains("admin") || errorMessage.toLowerCase().contains("permission"),
            "Error should mention admin/permission restriction");

        // Try to create a data source (admin operation)
        String createDsMutation = String.format("""
            mutation {
                createLocalFileSystemDataSource(token: "%s", input: {
                    name: "test-ds"
                    path: "/tmp/test"
                }) {
                    name
                }
            }
            """, apiToken);

        JsonNode createDsResponse = graphQLClient.executeAndParse(createDsMutation);
        assertTrue(createDsResponse.has("errors"), "Reader API user should not be able to create data sources");
    }

    @Test
    public void testCannotCreateApiUser_whenNoHumanUsersExist() throws Exception {
        // Wipe database to ensure no human users
        wipeDatabase();

        // Try to create API user without any human users existing
        String mutation = """
            mutation {
                createApiUser(input: {
                    nickname: "orphan-api"
                    userLevel: READER
                    expirationDays: 30
                }) {
                    apiUser { id }
                }
            }
            """;

        JsonNode response = graphQLClient.executeAndParse(mutation);

        assertTrue(response.has("errors"), "Should not be able to create API key without human users");
        String errorMessage = response.path("errors").get(0).path("message").asString();
        assertTrue(
            errorMessage.toLowerCase().contains("human user") ||
            errorMessage.toLowerCase().contains("unauthorized") ||
            errorMessage.toLowerCase().contains("authentication"),
            "Error should mention requirement for human users or authentication: " + errorMessage
        );

        // Verify no API user was created
        assertFalse(apiUserDao.existsByNickname("orphan-api"));
    }

    @Test
    public void testCreateApiUser_requiresHumanUserContext() throws Exception {
        // Wipe database and create a human user
        wipeDatabase();
        UserCreationResult admin = authenticationService.createUser("admin", UserLevel.ADMIN, null);
        String recoveryCode = admin.recoveryCodes().get(0);

        // Login as human user
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
        String token = loginResponse.path("data").path("loginWithRecoveryCode").path("token").asString();

        // Now create API user with valid token
        String createMutation = String.format("""
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "valid-api"
                    userLevel: READER
                    expirationDays: 30
                }) {
                    apiUser {
                        id
                        nickname
                        createdByUserId
                    }
                }
            }
            """, token);

        JsonNode createResponse = graphQLClient.executeAndParse(createMutation);

        assertFalse(createResponse.has("errors"), "Should be able to create API key with valid admin token");
        JsonNode apiUser = createResponse.path("data").path("createApiUser").path("apiUser");
        assertEquals("valid-api", apiUser.path("nickname").asString());
        assertEquals(admin.user().id(), apiUser.path("createdByUserId").asString(),
            "API user should be linked to creating human user");

        // Verify it exists in database
        assertTrue(apiUserDao.existsByNickname("valid-api"));
    }

    // ========== API session duration setting ==========

    @Test
    public void testApiSessionDurationSetting_isBounded() throws Exception {
        String mutation = """
            mutation {
                setUserSetting(token: "%s", settingName: "ApiSessionDurationMinutes", value: "%s") { value }
            }
            """;

        JsonNode accepted = graphQLClient.executeAndParse(String.format(mutation, adminToken, "1440"));
        assertFalse(accepted.has("errors"), "Response has errors: " + accepted.path("errors"));
        assertEquals(1440, authenticationService.getApiSessionDurationMinutes());

        for (String rejected : List.of("0", "1441", "an hour")) {
            JsonNode response = graphQLClient.executeAndParse(String.format(mutation, adminToken, rejected));
            assertEquals("INVALID_ARGUMENT", response.path("errors").get(0).path("extensions").path("errorType").asString(),
                "value " + rejected + " should be rejected");
        }
        assertEquals(1440, authenticationService.getApiSessionDurationMinutes());
    }
}
