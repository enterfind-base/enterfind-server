package enterfind.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;

import enterfind.server.controller.RemoteAccessController;
import enterfind.server.model.RemoteAccessConfig;
import enterfind.server.model.UserLevel;
import enterfind.server.service.AuthenticationService;
import enterfind.server.service.RemoteAccessService;
import enterfind.server.service.AuthenticationService.UserCreationResult;
import reactor.core.Disposable;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("alpha")
public class RemoteAccessGraphQLIntegrationTest extends BaseDatabaseTest {

    @LocalServerPort
    private int port;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private RemoteAccessController remoteAccessController;

    @Autowired
    private RemoteAccessService remoteAccessService;

    private GraphQLTestClient graphQLClient;
    private String adminToken;

    @Override
    @BeforeEach
    protected void setUp() {
        super.setUp();
        graphQLClient = new GraphQLTestClient(port);

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

    /**
     * Clears the credential between tests. The next test's context is built <em>before</em>
     * {@code wipeDatabase()} runs, so a RemoteAccessCredential row left behind here is read into the
     * new service instance and survives the wipe in memory.
     */
    @AfterEach
    void clearRemoteAccessState() {
        remoteAccessService.setRemoteAccessEnabled(false);
        remoteAccessService.reenroll();
    }

    @Test
    void remoteAccessConfig_returnsExpectedShape() {
        String query = String.format("""
                query {
                    remoteAccessConfig(token: "%s") {
                        serverId
                        enabled
                        isConnected
                        lastConnected
                    }
                }
                """, adminToken);

        JsonNode data = graphQLClient.executeAndGetData(query);
        JsonNode config = data.path("remoteAccessConfig");

        // Against a clean, unenrolled database serverId is null (matching
        // remoteAccessConfigUpdates_startsWithCurrentConfig, which asserts the same).
        // The previous assertFalse(serverId.isBlank()) only passed under Jackson 2, whose
        // NullNode.asText() returned the literal string "null"; Jackson 3's asString()
        // returns "" for a JSON null, so we now correctly check the field is present but null.
        assertTrue(config.has("serverId"));
        assertTrue(config.path("serverId").isNull());

        assertFalse(config.path("enabled").asBoolean());
        assertFalse(config.path("isConnected").asBoolean());
    }

    @Test
    void remoteAccessConfig_missingToken_returnsError() {
        String query = """
                query {
                    remoteAccessConfig {
                        serverId
                        enabled
                        isConnected
                    }
                }
                """;

        JsonNode response = graphQLClient.executeAndParse(query);
        assertTrue(response.has("errors"), "Expected GraphQL errors for missing token");
    }

    @Test
    void setRemoteAccessEnabled_false_persistsAndReturnsConfig() {
        String mutation = String.format("""
                mutation {
                    setRemoteAccessEnabled(token: "%s", enabled: false) {
                        serverId
                        enabled
                        isConnected
                    }
                }
                """, adminToken);

        JsonNode data = graphQLClient.executeAndGetData(mutation);
        JsonNode config = data.path("setRemoteAccessEnabled");

        assertFalse(config.path("enabled").asBoolean());
        assertFalse(config.path("isConnected").asBoolean());

        // Follow-up query confirms the persisted state
        String followUp = String.format("""
                query {
                    remoteAccessConfig(token: "%s") {
                        enabled
                    }
                }
                """, adminToken);

        JsonNode followUpData = graphQLClient.executeAndGetData(followUp);
        assertFalse(followUpData.path("remoteAccessConfig").path("enabled").asBoolean());
    }

    @Test
    void remoteAccessConfigUpdates_startsWithCurrentConfig() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RemoteAccessConfig> first = new AtomicReference<>();

        Disposable sub = remoteAccessController.remoteAccessConfigUpdates(adminToken)
                .subscribe(config -> {
                    if (first.compareAndSet(null, config)) {
                        latch.countDown();
                    }
                });

        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS), "Expected immediate initial emission");
            RemoteAccessConfig config = first.get();
            assertNull(config.serverId());
            assertFalse(config.enabled());
            assertFalse(config.isConnected());
        } finally {
            sub.dispose();
        }
    }

    @Test
    void remoteAccessConfigUpdates_missingToken_throwsSecurityException() {
        assertThrows(SecurityException.class,
                () -> remoteAccessController.remoteAccessConfigUpdates(null));
    }

    @Test
    void setRemoteAccessEnabled_missingToken_returnsError() {
        String mutation = """
                mutation {
                    setRemoteAccessEnabled(enabled: false) {
                        enabled
                    }
                }
                """;

        JsonNode response = graphQLClient.executeAndParse(mutation);
        assertTrue(response.has("errors"), "Expected GraphQL errors for missing token");
    }

    @Test
    void remoteUsage_returnsEmptyList() {
        String query = String.format("""
                query {
                    remoteUsage(token: "%s") {
                        serverId
                        granularity
                        period
                        ingressBytes
                        egressBytes
                    }
                }
                """, adminToken);

        JsonNode data = graphQLClient.executeAndGetData(query);
        JsonNode usage = data.path("remoteUsage");

        assertTrue(usage.isArray(), "Expected remoteUsage to be an array");
        assertTrue(usage.isEmpty(), "Expected remoteUsage to return an empty list");
    }

    @Test
    void remoteUsage_missingToken_returnsError() {
        String query = """
                query {
                    remoteUsage(token: "") {
                        serverId
                    }
                }
                """;

        JsonNode response = graphQLClient.executeAndParse(query);
        assertTrue(response.has("errors"), "Expected GraphQL errors for missing token");
    }

    @Test
    void setRemoteAccessEnabled_inviteCodeWhileDisabling_returnsBadRequest() {
        // An invite code is presented on the connection that enabling opens; with enabled:false there
        // is none, so accepting it would silently drop it. (Also keeps this from dialling the tunnel.)
        String mutation = String.format("""
                mutation {
                    setRemoteAccessEnabled(token: "%s", enabled: false, inviteCode: "efi_some_invite_code") {
                        serverId
                    }
                }
                """, adminToken);

        JsonNode response = graphQLClient.executeAndParse(mutation);

        assertTrue(response.has("errors"), "Expected an error for an invite code with enabled:false");
        JsonNode extensions = response.path("errors").get(0).path("extensions");
        assertEquals("INVALID_ARGUMENT", extensions.path("errorType").asString());
        assertEquals("BAD_REQUEST", extensions.path("errorCode").asString());
    }

    @Test
    void setRemoteAccessEnabled_withoutCredential_isAccepted() {
        // inviteCode is optional. Omitting it must stay valid: that is the toggle, and the
        // self-registering path the server falls back to if the tunnel ever stops requiring an invite.
        String mutation = String.format("""
                mutation {
                    setRemoteAccessEnabled(token: "%s", enabled: false) {
                        enabled
                    }
                }
                """, adminToken);

        JsonNode response = graphQLClient.executeAndParse(mutation);

        assertFalse(response.has("errors"), "Omitting the credential must remain valid: " + response);
    }
}
