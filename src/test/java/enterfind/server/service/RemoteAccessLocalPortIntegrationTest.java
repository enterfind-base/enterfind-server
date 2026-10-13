package enterfind.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.service.tunnel.FakeTunnelServer;
import enterfind.server.service.tunnel.TunnelMessage;

/**
 * Requests arriving through the tunnel must be forwarded to the port this instance actually listens
 * on. {@code TunnelClientTest} hands the client its local URLs directly, so only a test that goes
 * through {@link RemoteAccessService} sees which port production code picks.
 *
 * <p>The server runs on a free port chosen up front rather than {@code RANDOM_PORT}, which sets
 * {@code server.port=0} — the configured port is what an installed instance has to go on.
 */
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@ActiveProfiles("alpha")
public class RemoteAccessLocalPortIntegrationTest extends BaseDatabaseTest {

    private static final ObjectMapper objectMapper = new JsonMapper();
    private static final int SERVER_PORT = freePort();

    @Autowired
    private RemoteAccessService remoteAccessService;

    private FakeTunnelServer fakeTunnelServer;

    @DynamicPropertySource
    static void serverPort(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> SERVER_PORT);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            int port = socket.getLocalPort();
            assertNotEquals(8080, port);
            return port;
        } catch (IOException e) {
            throw new IllegalStateException("No free port for the test server", e);
        }
    }

    @BeforeEach
    void connectTunnel() throws Exception {
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

        CountDownLatch connected = new CountDownLatch(1);
        fakeTunnelServer.setOnClientConnected(connected::countDown);
        remoteAccessService.setRemoteAccessEnabled(true);
        assertTrue(connected.await(5, TimeUnit.SECONDS), "Tunnel should connect");
    }

    @AfterEach
    void disconnectTunnel() throws Exception {
        remoteAccessService.setRemoteAccessEnabled(false);
        remoteAccessService.reenroll();
        fakeTunnelServer.stop(1000);
    }

    @Test
    void httpRequest_isForwardedToConfiguredPort() throws Exception {
        CountDownLatch responseLatch = new CountDownLatch(1);
        AtomicReference<TunnelMessage> responseRef = new AtomicReference<>();
        fakeTunnelServer.setOnMessageReceived(raw -> {
            try {
                TunnelMessage msg = objectMapper.readValue(raw, TunnelMessage.class);
                if (msg.type() != null && msg.type().startsWith("HTTP_RESPONSE")) {
                    responseRef.set(msg);
                    responseLatch.countDown();
                }
            } catch (Exception ignored) {}
        });

        String graphqlBody = objectMapper.writeValueAsString(
            Map.of("query", "{ about { name } }"));
        fakeTunnelServer.sendToClient(objectMapper.writeValueAsString(new TunnelMessage(
            "HTTP_REQUEST", null, null, null, null, "req-port", "router-1",
            "POST", "/graphql",
            Map.of("content-type", "application/json"),
            graphqlBody,
            null, null, null, null, null, null, null)));

        assertTrue(responseLatch.await(10, TimeUnit.SECONDS), "Expected HTTP_RESPONSE");
        TunnelMessage response = responseRef.get();
        assertEquals(200, response.status(),
            "Request should reach this instance on port " + SERVER_PORT);
        String decoded = new String(Base64.getDecoder().decode(response.body()), StandardCharsets.UTF_8);
        assertTrue(decoded.contains("enterfind-server"), "Unexpected body: " + decoded);
    }

    @Test
    void wsConnect_isForwardedToConfiguredPort() throws Exception {
        CountDownLatch sessionOpen = new CountDownLatch(1);
        fakeTunnelServer.setOnMessageReceived(raw -> {
            try {
                TunnelMessage msg = objectMapper.readValue(raw, TunnelMessage.class);
                if ("WS_SESSION_OPEN".equals(msg.type()) && "session-port".equals(msg.wsSessionId())) {
                    sessionOpen.countDown();
                }
            } catch (Exception ignored) {}
        });

        fakeTunnelServer.sendToClient(objectMapper.writeValueAsString(new TunnelMessage(
            "WS_CONNECT", null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, "session-port", "/graphql", null)));

        assertTrue(sessionOpen.await(5, TimeUnit.SECONDS),
            "WebSocket session should open against this instance on port " + SERVER_PORT);
    }
}
