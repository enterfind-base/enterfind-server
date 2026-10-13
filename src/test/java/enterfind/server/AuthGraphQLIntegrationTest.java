package enterfind.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import enterfind.server.dao.HumanUserDao;
import enterfind.server.model.UserLevel;
import enterfind.server.service.AuthenticationService;
import enterfind.server.service.AuthenticationService.UserCreationResult;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("alpha")
public class AuthGraphQLIntegrationTest extends BaseDatabaseTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private HumanUserDao humanUserDao;

    private ObjectMapper objectMapper = new JsonMapper();

    @Override
    @BeforeEach
    protected void setUp() {
        super.setUp();
        // Clean up auth tables
        dbAccess.execute("DELETE FROM SESSIONS");
        dbAccess.execute("DELETE FROM HUMAN_USERS");
    }

    private JsonNode executeGraphQL(String query) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> requestBody = Map.of("query", query);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        ResponseEntity<String> response = restTemplate.postForEntity(
            "http://localhost:" + port + "/graphql",
            request,
            String.class
        );

        return objectMapper.readTree(response.getBody());
    }

    @Test
    public void testIsAuthenticationRequired_noUsers() throws Exception {
        String query = "query { isAuthenticationRequired }";

        JsonNode response = executeGraphQL(query);

        assertFalse(response.has("errors"));
        assertFalse(response.path("data").path("isAuthenticationRequired").asBoolean());
    }

    @Test
    public void testIsAuthenticationRequired_withUsers() throws Exception {
        humanUserDao.create("testuser", "secret", "codes", UserLevel.ADMIN);

        String query = "query { isAuthenticationRequired }";

        JsonNode response = executeGraphQL(query);

        assertFalse(response.has("errors"));
        assertTrue(response.path("data").path("isAuthenticationRequired").asBoolean());
    }

    @Test
    public void testCreateHumanUser_firstUser_noAuth() throws Exception {
        String mutation = """
            mutation {
                createHumanUser(input: {
                    nickname: "admin"
                    userLevel: ADMIN
                }) {
                    user {
                        id
                        nickname
                        userLevel
                    }
                    setupToken
                    setupUrl
                }
            }
            """;

        JsonNode response = executeGraphQL(mutation);

        assertFalse(response.has("errors"), "Response has errors: " + response.path("errors").toString());
        JsonNode data = response.path("data").path("createHumanUser");
        assertNotNull(data.path("user").path("id").asString());
        assertEquals("admin", data.path("user").path("nickname").asString());
        assertEquals("ADMIN", data.path("user").path("userLevel").asString());
        assertFalse(data.path("setupToken").asString().isEmpty());
        assertTrue(data.path("setupUrl").asString().contains("token="));

        // User is immediately persisted in database
        assertTrue(humanUserDao.existsByNickname("admin"),
            "User should be in database immediately after creation");
    }

    @Test
    public void testCreateHumanUser_requiresAdmin() throws Exception {
        // Create first admin user
        UserCreationResult admin = authenticationService.createUser("admin", UserLevel.ADMIN, null);
        String recoveryCode = admin.recoveryCodes().get(0);

        // Login to get token
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

        JsonNode loginResponse = executeGraphQL(loginMutation);
        String token = loginResponse.path("data").path("loginWithRecoveryCode").path("token").asString();

        // Now try to create another user with the token
        String createMutation = """
            mutation {
                createHumanUser(token: "%s", input: {
                    nickname: "reader"
                    userLevel: READER
                }) {
                    user {
                        id
                        nickname
                        userLevel
                    }
                    setupToken
                    setupUrl
                }
            }
            """.formatted(token);

        JsonNode response = executeGraphQL(createMutation);

        assertFalse(response.has("errors"), "Response has errors: " + response.path("errors").toString());
        JsonNode data = response.path("data").path("createHumanUser");
        assertNotNull(data.path("user").path("id").asString());
        assertEquals("reader", data.path("user").path("nickname").asString());
        assertEquals("READER", data.path("user").path("userLevel").asString());
    }

    @Test
    public void testCreateHumanUser_firstUserMustBeAdmin() throws Exception {
        // Try to create first user as READER - should be forced to ADMIN
        String mutation = """
            mutation {
                createHumanUser(input: {
                    nickname: "firstuser"
                    userLevel: READER
                }) {
                    user {
                        id
                        nickname
                        userLevel
                    }
                    setupToken
                    setupUrl
                }
            }
            """;

        JsonNode response = executeGraphQL(mutation);

        assertFalse(response.has("errors"), "Response has errors: " + response.path("errors").toString());
        JsonNode data = response.path("data").path("createHumanUser");
        assertNotNull(data.path("user").path("id").asString());

        // First user should be forced to ADMIN
        assertEquals("ADMIN", data.path("user").path("userLevel").asString());
    }

    @Test
    public void testCreateHumanUser_unauthorizedAfterFirstUser() throws Exception {
        // Create first user
        humanUserDao.create("admin", "secret", "codes", UserLevel.ADMIN);

        // Try to create another user without auth
        String mutation = """
            mutation {
                createHumanUser(input: {
                    nickname: "reader"
                    userLevel: READER
                }) {
                    user {
                        id
                    }
                    setupToken
                }
            }
            """;

        JsonNode response = executeGraphQL(mutation);

        assertTrue(response.has("errors"));
    }

    @Test
    public void testUserSetup_validToken() throws Exception {
        // Create user via GraphQL to get a setup token
        String createMutation = """
            mutation {
                createHumanUser(input: {
                    nickname: "setupuser"
                    userLevel: ADMIN
                }) {
                    user {
                        id
                    }
                    setupToken
                }
            }
            """;

        JsonNode createResponse = executeGraphQL(createMutation);
        assertFalse(createResponse.has("errors"), "Response has errors: " + createResponse.path("errors").toString());
        String setupToken = createResponse.path("data").path("createHumanUser").path("setupToken").asString();

        // Use the setup token to get setup info
        String setupQuery = String.format("""
            query {
                userSetup(token: "%s") {
                    nickname
                    qrCodeDataUrl
                    totpSecret
                    recoveryCodes
                    alreadyUsed
                }
            }
            """, setupToken);

        JsonNode setupResponse = executeGraphQL(setupQuery);

        assertFalse(setupResponse.has("errors"), "Response has errors: " + setupResponse.path("errors").toString());
        JsonNode data = setupResponse.path("data").path("userSetup");
        assertEquals("setupuser", data.path("nickname").asString());
        assertTrue(data.path("qrCodeDataUrl").asString().startsWith("data:image/png;base64,"));
        assertFalse(data.path("totpSecret").asString().isEmpty(), "totpSecret should not be empty");
        assertEquals(10, data.path("recoveryCodes").size());
        assertFalse(data.path("alreadyUsed").asBoolean());
    }

    @Test
    public void testUserSetup_tokenIsOneTimeUse() throws Exception {
        // Create user via GraphQL to get a setup token
        String createMutation = """
            mutation {
                createHumanUser(input: {
                    nickname: "onetimeuser"
                    userLevel: ADMIN
                }) {
                    setupToken
                }
            }
            """;

        JsonNode createResponse = executeGraphQL(createMutation);
        String setupToken = createResponse.path("data").path("createHumanUser").path("setupToken").asString();

        // First use of token - should work
        String setupQuery = String.format("""
            query {
                userSetup(token: "%s") {
                    nickname
                    alreadyUsed
                }
            }
            """, setupToken);

        JsonNode firstResponse = executeGraphQL(setupQuery);
        assertFalse(firstResponse.path("data").path("userSetup").path("alreadyUsed").asBoolean());

        // Second use of token - should indicate already used
        JsonNode secondResponse = executeGraphQL(setupQuery);
        assertTrue(secondResponse.path("data").path("userSetup").path("alreadyUsed").asBoolean());
    }

    @Test
    public void testUserSetup_invalidToken() throws Exception {
        String setupQuery = """
            query {
                userSetup(token: "invalid-token-12345") {
                    nickname
                    alreadyUsed
                }
            }
            """;

        JsonNode response = executeGraphQL(setupQuery);

        assertFalse(response.has("errors"));
        assertTrue(response.path("data").path("userSetup").path("alreadyUsed").asBoolean());
    }

    @Test
    public void testRegenerateSetupToken() throws Exception {
        // Create first admin user and login
        UserCreationResult admin = authenticationService.createUser("admin", UserLevel.ADMIN, null);
        String adminToken = getAuthToken(admin);

        // Create another user
        authenticationService.createUser("testuser", UserLevel.READER, null);
        String testUserId = humanUserDao.findByNickname("testuser").get().id();

        // Regenerate setup token
        String regenMutation = String.format("""
            mutation {
                regenerateSetupToken(token: "%s", userId: "%s") {
                    user {
                        id
                        nickname
                    }
                    setupToken
                    setupUrl
                }
            }
            """, adminToken, testUserId);

        JsonNode response = executeGraphQL(regenMutation);

        assertFalse(response.has("errors"), "Response has errors: " + response.path("errors").toString());
        JsonNode data = response.path("data").path("regenerateSetupToken");
        assertEquals("testuser", data.path("user").path("nickname").asString());
        assertFalse(data.path("setupToken").asString().isEmpty());
        assertTrue(data.path("setupUrl").asString().contains("token="));

        // Use the new setup token
        String newSetupToken = data.path("setupToken").asString();
        String setupQuery = String.format("""
            query {
                userSetup(token: "%s") {
                    nickname
                    qrCodeDataUrl
                    alreadyUsed
                }
            }
            """, newSetupToken);

        JsonNode setupResponse = executeGraphQL(setupQuery);
        assertFalse(setupResponse.path("data").path("userSetup").path("alreadyUsed").asBoolean());
        assertEquals("testuser", setupResponse.path("data").path("userSetup").path("nickname").asString());
    }

    @Test
    public void testLoginWithRecoveryCode() throws Exception {
        UserCreationResult created = authenticationService.createUser("testuser", UserLevel.ADMIN, null);
        String recoveryCode = created.recoveryCodes().get(0);

        String mutation = String.format("""
            mutation {
                loginWithRecoveryCode(input: {
                    nickname: "testuser"
                    recoveryCode: "%s"
                }) {
                    token
                    user {
                        id
                        nickname
                        userLevel
                    }
                    expiresAt
                }
            }
            """, recoveryCode);

        JsonNode response = executeGraphQL(mutation);

        assertFalse(response.has("errors"), "Response has errors: " + response.path("errors").toString());
        JsonNode data = response.path("data").path("loginWithRecoveryCode");
        assertNotNull(data.path("token").asString());
        assertEquals("testuser", data.path("user").path("nickname").asString());
        assertEquals("ADMIN", data.path("user").path("userLevel").asString());
    }

    @Test
    public void testLogin_invalidCredentials() throws Exception {
        authenticationService.createUser("testuser", UserLevel.ADMIN, null);

        String mutation = """
            mutation {
                login(input: {
                    nickname: "testuser"
                    totpCode: "000000"
                    rememberMe: false
                }) {
                    token
                }
            }
            """;

        JsonNode response = executeGraphQL(mutation);

        assertTrue(response.has("errors"));
    }

    @Test
    public void testCurrentUser_authenticated() throws Exception {
        UserCreationResult created = authenticationService.createUser("testuser", UserLevel.ADMIN, null);
        String recoveryCode = created.recoveryCodes().get(0);

        // Login
        String loginMutation = String.format("""
            mutation {
                loginWithRecoveryCode(input: {
                    nickname: "testuser"
                    recoveryCode: "%s"
                }) {
                    token
                }
            }
            """, recoveryCode);

        JsonNode loginResponse = executeGraphQL(loginMutation);
        String token = loginResponse.path("data").path("loginWithRecoveryCode").path("token").asString();

        // Query current user
        String query = String.format("""
            query {
                currentUser(token: "%s") {
                    id
                    nickname
                    userLevel
                    isActive
                }
            }
            """, token);

        JsonNode response = executeGraphQL(query);

        assertFalse(response.has("errors"), "Response has errors: " + response.path("errors").toString());
        JsonNode user = response.path("data").path("currentUser");
        assertEquals("testuser", user.path("nickname").asString());
        assertEquals("ADMIN", user.path("userLevel").asString());
        assertTrue(user.path("isActive").asBoolean());
    }

    @Test
    public void testCurrentUser_notAuthenticated() throws Exception {
        // Create a user so auth is required
        humanUserDao.create("admin", "secret", "codes", UserLevel.ADMIN);

        String query = """
            query {
                currentUser {
                    id
                    nickname
                }
            }
            """;

        JsonNode response = executeGraphQL(query);

        // currentUser should return null when not authenticated
        assertTrue(response.path("data").path("currentUser").isNull());
    }

    @Test
    public void testHumanUsers_requiresAdmin() throws Exception {
        UserCreationResult admin = authenticationService.createUser("admin", UserLevel.ADMIN, null);
        @SuppressWarnings("unused")
        UserCreationResult reader = authenticationService.createUser("reader", UserLevel.READER, null);

        // Login as admin
        String adminToken = getAuthToken(admin);

        String query = String.format("""
            query {
                humanUsers(token: "%s") {
                    id
                    nickname
                    userLevel
                }
            }
            """, adminToken);

        JsonNode response = executeGraphQL(query);

        assertFalse(response.has("errors"), "Response has errors: " + response.path("errors").toString());
        assertEquals(2, response.path("data").path("humanUsers").size());
    }

    @Test
    public void testHumanUsers_forbiddenForReader() throws Exception {
        authenticationService.createUser("admin", UserLevel.ADMIN, null);
        UserCreationResult reader = authenticationService.createUser("reader", UserLevel.READER, null);

        // Login as reader
        String readerToken = getAuthToken(reader);

        String query = String.format("""
            query {
                humanUsers(token: "%s") {
                    id
                    nickname
                }
            }
            """, readerToken);

        JsonNode response = executeGraphQL(query);

        assertTrue(response.has("errors"));
        
        // Verify the error is FORBIDDEN (authorization), not UNAUTHORIZED (authentication)
        JsonNode firstError = response.path("errors").get(0);
        JsonNode extensions = firstError.path("extensions");
        assertEquals("FORBIDDEN", extensions.path("errorType").asString(), 
            "Reader accessing admin-only endpoint should get FORBIDDEN, not UNAUTHORIZED");
        assertEquals("FORBIDDEN", extensions.path("classification").asString(),
            "Classification should be FORBIDDEN for authorization errors");
        assertTrue(firstError.path("message").asString().contains("Insufficient permissions"),
            "Error message should indicate insufficient permissions");
    }

    @Test
    public void testLogout() throws Exception {
        UserCreationResult created = authenticationService.createUser("testuser", UserLevel.ADMIN, null);
        String token = getAuthToken(created);

        String mutation = String.format("mutation { logout(token: \"%s\") }", token);

        JsonNode response = executeGraphQL(mutation);

        assertFalse(response.has("errors"));
        assertTrue(response.path("data").path("logout").asBoolean());

        // Token should no longer work
        String query = "query { currentUser { nickname } }";
        JsonNode afterLogout = executeGraphQL(query);
        assertTrue(afterLogout.path("data").path("currentUser").isNull());
    }

    @Test
    public void testDeleteHumanUser() throws Exception {
        UserCreationResult admin = authenticationService.createUser("admin", UserLevel.ADMIN, null);
        UserCreationResult toDelete = authenticationService.createUser("toDelete", UserLevel.READER, null);
        String adminToken = getAuthToken(admin);

        String mutation = String.format("""
            mutation {
                deleteHumanUser(token: "%s", id: "%s")
            }
            """, adminToken, toDelete.user().id());

        JsonNode response = executeGraphQL(mutation);

        assertFalse(response.has("errors"), "Response has errors: " + response.path("errors").toString());
        assertTrue(response.path("data").path("deleteHumanUser").asBoolean());

        // User should be deleted
        assertFalse(humanUserDao.findById(toDelete.user().id()).isPresent());
    }

    @Test
    public void testDeleteHumanUser_canDeleteSelfIfOnlyUser() throws Exception {
        UserCreationResult admin = authenticationService.createUser("admin", UserLevel.ADMIN, null);
        String adminToken = getAuthToken(admin);

        String mutation = String.format("""
            mutation {
                deleteHumanUser(token: "%s", id: "%s")
            }
            """, adminToken, admin.user().id());

        JsonNode response = executeGraphQL(mutation);

        assertFalse(response.has("errors"), "Response has errors: " + response.path("errors").toString());
        assertTrue(response.path("data").path("deleteHumanUser").asBoolean());

        // Verify user is deleted
        assertFalse(humanUserDao.findById(admin.user().id()).isPresent(),
            "Admin user should be deleted when they are the only user");
    }

    @Test
    public void testDeleteHumanUser_cannotDeleteSelfWhenOtherUsersExist() throws Exception {
        UserCreationResult admin = authenticationService.createUser("admin", UserLevel.ADMIN, null);
        authenticationService.createUser("otheruser", UserLevel.READER, null);
        String adminToken = getAuthToken(admin);

        String mutation = String.format("""
            mutation {
                deleteHumanUser(token: "%s", id: "%s")
            }
            """, adminToken, admin.user().id());

        JsonNode response = executeGraphQL(mutation);

        assertTrue(response.has("errors"));
        assertTrue(response.path("errors").get(0).path("message").asString()
            .contains("Cannot delete your own account while other users exist"));

        // Verify user still exists in database
        assertTrue(humanUserDao.findById(admin.user().id()).isPresent(),
            "Admin user should still exist after failed self-delete attempt");
    }

    @Test
    public void testUpdateHumanUser_cannotDeactivateOnlyUser() throws Exception {
        UserCreationResult admin = authenticationService.createUser("admin", UserLevel.ADMIN, null);
        String adminToken = getAuthToken(admin);

        String mutation = String.format("""
            mutation {
                updateHumanUser(token: "%s", input: {
                    id: "%s"
                    isActive: false
                }) {
                    id
                    isActive
                }
            }
            """, adminToken, admin.user().id());

        JsonNode response = executeGraphQL(mutation);

        assertTrue(response.has("errors"));
        assertTrue(response.path("errors").get(0).path("message").asString()
            .contains("Cannot deactivate the only user"));

        // Verify user is still active
        assertTrue(humanUserDao.findById(admin.user().id()).get().isActive(),
            "User should still be active after failed deactivation attempt");
    }

    @Test
    public void testUpdateHumanUser_canDeactivateWhenOtherUsersExist() throws Exception {
        UserCreationResult admin = authenticationService.createUser("admin", UserLevel.ADMIN, null);
        UserCreationResult other = authenticationService.createUser("otheruser", UserLevel.READER, null);
        String adminToken = getAuthToken(admin);

        String mutation = String.format("""
            mutation {
                updateHumanUser(token: "%s", input: {
                    id: "%s"
                    isActive: false
                }) {
                    id
                    isActive
                }
            }
            """, adminToken, other.user().id());

        JsonNode response = executeGraphQL(mutation);

        assertFalse(response.has("errors"), "Response has errors: " + response.path("errors").toString());
        assertFalse(response.path("data").path("updateHumanUser").path("isActive").asBoolean());

        // Verify user is deactivated in database
        assertFalse(humanUserDao.findById(other.user().id()).get().isActive(),
            "User should be deactivated");
    }

    @Test
    public void testUpdateHumanUser_cannotDowngradeLastAdmin() throws Exception {
        UserCreationResult admin = authenticationService.createUser("admin", UserLevel.ADMIN, null);
        authenticationService.createUser("otheruser", UserLevel.READER, null);
        String adminToken = getAuthToken(admin);

        String mutation = String.format("""
            mutation {
                updateHumanUser(token: "%s", input: {
                    id: "%s"
                    userLevel: READER
                }) {
                    id
                    userLevel
                }
            }
            """, adminToken, admin.user().id());

        JsonNode response = executeGraphQL(mutation);

        assertTrue(response.has("errors"), "Downgrading the last admin should be rejected");

        // Verify user level is unchanged in database
        assertEquals(UserLevel.ADMIN, humanUserDao.findById(admin.user().id()).get().userLevel(),
            "Last admin should still be ADMIN after failed downgrade attempt");
    }

    @Test
    public void testRecoveryCodesRemaining() throws Exception {
        UserCreationResult created = authenticationService.createUser("testuser", UserLevel.ADMIN, null);
        String token = getAuthToken(created); // Uses one recovery code

        String query = String.format("""
            query {
                currentUser(token: "%s") {
                    nickname
                    recoveryCodesRemaining
                }
            }
            """, token);

        JsonNode response = executeGraphQL(query);

        assertFalse(response.has("errors"));
        assertEquals(9, response.path("data").path("currentUser").path("recoveryCodesRemaining").asInt());
    }

    private String getAuthToken(UserCreationResult user) throws Exception {
        String recoveryCode = user.recoveryCodes().get(0);
        String loginMutation = String.format("""
            mutation {
                loginWithRecoveryCode(input: {
                    nickname: "%s"
                    recoveryCode: "%s"
                }) {
                    token
                }
            }
            """, user.user().nickname(), recoveryCode);

        JsonNode loginResponse = executeGraphQL(loginMutation);
        return loginResponse.path("data").path("loginWithRecoveryCode").path("token").asString();
    }

   
}
