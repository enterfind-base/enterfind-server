package enterfind.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.java_websocket.WebSocket;
import reactor.core.Disposable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.dao.SystemSettingsDao;
import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.model.RemoteAccessConfig;
import enterfind.server.service.tunnel.FakeTunnelServer;
import enterfind.server.service.tunnel.TunnelMessage;

@SpringBootTest
@ActiveProfiles("alpha")
public class RemoteAccessServiceTest extends BaseDatabaseTest {

    private static final ObjectMapper objectMapper = new JsonMapper();

    @Autowired
    private RemoteAccessService remoteAccessService;

    @Autowired
    private SystemSettingsDao systemSettingsDao;

    private FakeTunnelServer fakeTunnelServer;

    @Override
    @BeforeEach
    protected void setUp() {
        super.setUp();
    }

    @BeforeEach
    void startFakeServer() throws Exception {
        CountDownLatch serverStarted = new CountDownLatch(1);
        fakeTunnelServer = new FakeTunnelServer(new InetSocketAddress(0)) {
            @Override
            public void onStart() {
                serverStarted.countDown();
            }
        };
        fakeTunnelServer.start();
        assertTrue(serverStarted.await(5, TimeUnit.SECONDS));
        remoteAccessService.setTunnelEndpointForTesting("ws://localhost:" + fakeTunnelServer.getPort());
    }

    @AfterEach
    void stopFakeServer() throws Exception {
        remoteAccessService.setRemoteAccessEnabled(false);
        // The next test's context is built before wipeDatabase() runs, so a credential left in
        // SYSTEM_SETTINGS is read into the new service instance and outlives the wipe in memory.
        remoteAccessService.reenroll();
        fakeTunnelServer.stop(1000);
    }

    @Test
    void init_noCredential_serverIdIsNull() {
        // DB wiped by setUp(); no RemoteAccessCredential present — server is unenrolled.
        remoteAccessService.init();

        assertNull(remoteAccessService.getConfig().serverId());
    }

    @Test
    void init_doesNotStartTunnelWhenDisabled() {
        // DB wiped by setUp(); RemoteAccessEnabled absent defaults to "false" in init().
        remoteAccessService.init();

        assertFalse(remoteAccessService.isTunnelConnected());
    }

    @Test
    void setRemoteAccessEnabled_false_persistsDisabledState() {
        remoteAccessService.setRemoteAccessEnabled(false);

        Optional<String> value = systemSettingsDao.getSetting("RemoteAccessEnabled");
        assertTrue(value.isPresent());
        assertEquals("false", value.get());
        assertFalse(remoteAccessService.getConfig().enabled());
    }

    @Test
    void setRemoteAccessEnabled_true_connectsToTunnelAndPersistsState() throws Exception {
        CountDownLatch registerLatch = new CountDownLatch(1);
        AtomicReference<TunnelMessage> registerRef = new AtomicReference<>();
        fakeTunnelServer.setOnMessageReceived(raw -> {
            try {
                TunnelMessage msg = objectMapper.readValue(raw, TunnelMessage.class);
                if ("REGISTER".equals(msg.type())) {
                    registerRef.set(msg);
                    registerLatch.countDown();
                }
            } catch (Exception ignored) {}
        });

        remoteAccessService.setRemoteAccessEnabled(true);

        assertTrue(registerLatch.await(5, TimeUnit.SECONDS), "Expected REGISTER after enabling");

        Optional<String> value = systemSettingsDao.getSetting("RemoteAccessEnabled");
        assertTrue(value.isPresent());
        assertEquals("true", value.get());
        assertTrue(remoteAccessService.getConfig().enabled());

        TunnelMessage register = registerRef.get();
        assertNull(register.serverId());
        assertNotNull(register.version());
    }

    @Test
    void getConfig_enabledFalse_isConnectedFalse() {
        RemoteAccessConfig config = remoteAccessService.getConfig();

        assertFalse(config.enabled());
        assertFalse(config.isConnected());
    }

    @Test
    void configUpdates_emitsEnabledFalseWhenDisabled() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RemoteAccessConfig> received = new AtomicReference<>();

        Disposable sub = remoteAccessService.configUpdates().subscribe(config -> {
            received.set(config);
            latch.countDown();
        });

        try {
            remoteAccessService.setRemoteAccessEnabled(false);
            assertTrue(latch.await(2, TimeUnit.SECONDS), "Expected config update emission on disable");
            assertFalse(received.get().enabled());
            assertFalse(received.get().isConnected());
        } finally {
            sub.dispose();
        }
    }

    @Test
    void configUpdates_emitsEnabledTrueWhenEnabled() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RemoteAccessConfig> received = new AtomicReference<>();

        Disposable sub = remoteAccessService.configUpdates().subscribe(config -> {
            if (config.enabled() && received.compareAndSet(null, config)) {
                latch.countDown();
            }
        });

        try {
            remoteAccessService.setRemoteAccessEnabled(true);
            assertTrue(latch.await(2, TimeUnit.SECONDS), "Expected enabled=true emission");
            assertTrue(received.get().enabled());
        } finally {
            sub.dispose();
        }
    }

    @Test
    void configUpdates_emitsIsConnectedTrueAfterTunnelConnects() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<RemoteAccessConfig> received = new AtomicReference<>();

        Disposable sub = remoteAccessService.configUpdates().subscribe(config -> {
            if (config.isConnected() && received.compareAndSet(null, config)) {
                latch.countDown();
            }
        });

        try {
            remoteAccessService.setRemoteAccessEnabled(true);
            assertTrue(latch.await(5, TimeUnit.SECONDS), "Expected isConnected=true after tunnel connects");
            assertTrue(received.get().enabled());
            assertTrue(received.get().isConnected());
        } finally {
            sub.dispose();
        }
    }

    @Test
    void configUpdates_emitsIsConnectedFalseAfterTunnelDisconnects() throws Exception {
        // Connect first
        CountDownLatch connectedLatch = new CountDownLatch(1);
        fakeTunnelServer.setOnClientConnected(connectedLatch::countDown);
        remoteAccessService.setRemoteAccessEnabled(true);
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "Expected tunnel to connect");

        // Subscribe after connection is established — multicast sink won't replay past events
        CountDownLatch disconnectedLatch = new CountDownLatch(1);
        AtomicReference<RemoteAccessConfig> received = new AtomicReference<>();

        Disposable sub = remoteAccessService.configUpdates().subscribe(config -> {
            if (!config.isConnected() && config.enabled() && received.compareAndSet(null, config)) {
                disconnectedLatch.countDown();
            }
        });

        try {
            fakeTunnelServer.getConnections().forEach(WebSocket::close);
            assertTrue(disconnectedLatch.await(5, TimeUnit.SECONDS), "Expected isConnected=false after server closes connection");
            assertTrue(received.get().enabled());
            assertFalse(received.get().isConnected());
        } finally {
            sub.dispose();
        }
    }

    // ================= Enrollment: both worlds, always both green =================
    //
    // The deployed tunnel is invite-only, so the ENROLL_ALLOWED cases below cover a path that
    // production no longer exercises. They stay because the server is supposed to work under either
    // mode without a code change, and an untested path is how that quietly stops being true.

    private static final String TEST_SERVER_ID = "abc123def4567890";
    private static final String TEST_TOKEN = "efl_test_token_value";

    /** Waits for a config predicate, polling — most of these outcomes arrive on the tunnel thread. */
    private RemoteAccessConfig awaitConfig(java.util.function.Predicate<RemoteAccessConfig> p,
            String what) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        RemoteAccessConfig config = remoteAccessService.getConfig();
        while (System.currentTimeMillis() < deadline) {
            config = remoteAccessService.getConfig();
            if (p.test(config)) return config;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + what + "; last config was " + config);
    }

    @Test
    void enrollmentAllowed_noCredential_takesCredentialFromRegistered() throws Exception {
        fakeTunnelServer.setEnrollmentAllowed(TEST_SERVER_ID, TEST_TOKEN);

        remoteAccessService.setRemoteAccessEnabled(true);

        RemoteAccessConfig config = awaitConfig(c -> TEST_SERVER_ID.equals(c.serverId()),
            "self-enrollment to store the issued serverId");
        assertTrue(config.enabled());
        assertTrue(systemSettingsDao.getSetting("RemoteAccessCredential")
            .orElse("").contains(TEST_TOKEN), "issued token should be persisted");
    }

    private static final String TEST_INVITE_CODE = "efi_test_invite_code";

    /** Redeems TEST_INVITE_CODE and waits for the reconnect on the credential it was exchanged for. */
    private RemoteAccessConfig enrollWithInviteCode() throws Exception {
        fakeTunnelServer.setInviteOnly(TEST_INVITE_CODE, TEST_SERVER_ID, TEST_TOKEN);
        remoteAccessService.setRemoteAccessEnabled(true, TEST_INVITE_CODE);
        return awaitConfig(c -> c.isConnected() && TEST_SERVER_ID.equals(c.serverId())
                && TEST_TOKEN.equals(fakeTunnelServer.getLastPresented()),
            "enrollment with the invite code and a reconnect on the issued token");
    }

    @Test
    void inviteOnly_noCredential_isRefusedAndSaysSo() throws Exception {
        fakeTunnelServer.setInviteOnly(TEST_INVITE_CODE, TEST_SERVER_ID, TEST_TOKEN);

        remoteAccessService.setRemoteAccessEnabled(true);

        RemoteAccessConfig config = awaitConfig(c -> c.statusMessage() != null,
            "a status message explaining the refusal");
        assertFalse(config.isConnected());
        assertTrue(config.statusMessage().contains("has no credential"), config.statusMessage());
        assertTrue(config.statusMessage().contains("Use invite code"),
            "the message should name the remedy: " + config.statusMessage());
    }

    @Test
    void inviteCode_persistsTheIssuedCredentialNotTheCode() throws Exception {
        RemoteAccessConfig config = enrollWithInviteCode();

        assertNull(config.statusMessage(), "a successful enrollment should leave nothing to report");
        String stored = systemSettingsDao.getSetting("RemoteAccessCredential").orElse("");
        assertTrue(stored.contains(TEST_TOKEN), "the issued token should be persisted: " + stored);
        assertFalse(stored.contains(TEST_INVITE_CODE), "the invite code must never be stored");
    }

    @Test
    void inviteCode_thenToggleOffAndOn_keepsTheServerId() throws Exception {
        enrollWithInviteCode();

        remoteAccessService.setRemoteAccessEnabled(false);
        awaitConfig(c -> !c.isConnected(), "the tunnel to close");
        remoteAccessService.setRemoteAccessEnabled(true);

        // The code is spent in the fake as at the edge, so this only connects on the stored token.
        RemoteAccessConfig config = awaitConfig(RemoteAccessConfig::isConnected,
            "the tunnel to come back on the stored credential");
        assertEquals(TEST_SERVER_ID, config.serverId());
        assertEquals(TEST_TOKEN, fakeTunnelServer.getLastPresented());
    }

    @Test
    void inviteCode_notAccepted_withNoCredential_isRefusedAndSaysSo() throws Exception {
        fakeTunnelServer.setInviteOnly(TEST_INVITE_CODE, TEST_SERVER_ID, TEST_TOKEN);

        remoteAccessService.setRemoteAccessEnabled(true, "efi_mistyped");

        RemoteAccessConfig config = awaitConfig(c -> c.statusMessage() != null,
            "a status message explaining the refusal");
        assertFalse(config.isConnected());
        assertNull(config.serverId());
        assertTrue(config.statusMessage().contains("invite code was not accepted"), config.statusMessage());
    }

    @Test
    void inviteCode_notAccepted_onAnEnrolledInstance_keepsItsServerId() throws Exception {
        enrollWithInviteCode();

        remoteAccessService.setRemoteAccessEnabled(true, "efi_mistyped");

        RemoteAccessConfig config = awaitConfig(
            c -> c.isConnected() && TEST_SERVER_ID.equals(c.serverId()) && c.statusMessage() != null,
            "the instance to come back on its old credential");
        assertTrue(config.statusMessage().contains("keeps its Server ID " + TEST_SERVER_ID),
            "the explanation should survive the successful reconnect: " + config.statusMessage());
        assertTrue(systemSettingsDao.getSetting("RemoteAccessCredential").orElse("").contains(TEST_TOKEN));
    }

    @Test
    void inviteCode_onAnEnrolledInstance_movesItToTheNewServerId() throws Exception {
        enrollWithInviteCode();
        String replacementCode = "efi_second_invite_code";
        String replacementId = "0000111122223333";
        String replacementToken = "efl_replacement_token";
        fakeTunnelServer.addInviteCode(replacementCode, replacementId, replacementToken);

        remoteAccessService.setRemoteAccessEnabled(true, replacementCode);

        RemoteAccessConfig config = awaitConfig(
            c -> c.isConnected() && replacementId.equals(c.serverId())
                && replacementToken.equals(fakeTunnelServer.getLastPresented()),
            "the tunnel to come back under the new Server ID");
        assertNull(config.statusMessage());
        assertTrue(systemSettingsDao.getSetting("RemoteAccessCredential").orElse("").contains(replacementToken));
    }

    @Test
    void serverIdTaken_reportsTheClash() throws Exception {
        enrollWithInviteCode();
        remoteAccessService.setRemoteAccessEnabled(false);
        // The edge leaves this socket open and the client closes it itself, so the close code says
        // "clean shutdown" — recognising it depends on the REGISTRATION_REJECTED message.
        fakeTunnelServer.setServerIdTaken(true);

        remoteAccessService.setRemoteAccessEnabled(true);

        RemoteAccessConfig config = awaitConfig(c -> c.statusMessage() != null,
            "a status message explaining the rejection");
        assertTrue(config.statusMessage().contains("Server ID"),
            "the clash should be named rather than retried silently: " + config.statusMessage());
    }

    @Test
    void disablingClearsTheStatusMessage() throws Exception {
        fakeTunnelServer.setInviteOnly(TEST_INVITE_CODE, TEST_SERVER_ID, TEST_TOKEN);
        remoteAccessService.setRemoteAccessEnabled(true);
        awaitConfig(c -> c.statusMessage() != null, "the refusal banner");

        remoteAccessService.setRemoteAccessEnabled(false);

        // Not merely hidden in the panel: cleared, so it does not come back on the next page load.
        RemoteAccessConfig config = awaitConfig(c -> c.statusMessage() == null,
            "the status message to be cleared when remote access is switched off");
        assertFalse(config.enabled());
        assertEquals("", systemSettingsDao.getSetting("RemoteAccessStatusMessage").orElse(""));
    }

    @Test
    void setRemoteAccessEnabled_unusableInviteCode_isRejected() {
        assertThrows(InvalidArgumentException.class,
            () -> remoteAccessService.setRemoteAccessEnabled(false, TEST_INVITE_CODE));
        assertThrows(InvalidArgumentException.class,
            () -> remoteAccessService.setRemoteAccessEnabled(true, "  "));
    }
}
