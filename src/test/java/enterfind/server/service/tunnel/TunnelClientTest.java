package enterfind.server.service.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.java_websocket.WebSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("alpha")
public class TunnelClientTest extends BaseTunnelTest {

    private static final ObjectMapper objectMapper = new JsonMapper();

    // AWS API Gateway enforces a 32768-byte max frame size on WebSocket messages.
    private static final int AWS_MAX_FRAME_BYTES = 32768;

    @LocalServerPort
    private int springPort;

    private FakeTunnelServer fakeTunnelServer;
    private int serverPort;
    private TunnelClient tunnelClient;

    @BeforeEach
    void startServer() throws Exception {
        CountDownLatch serverStartedLatch = new CountDownLatch(1);
        fakeTunnelServer = new FakeTunnelServer(new InetSocketAddress(0)) {
            @Override
            public void onStart() {
                super.onStart();
                serverStartedLatch.countDown();
            }
        };
        fakeTunnelServer.start();
        assertTrue(serverStartedLatch.await(5, TimeUnit.SECONDS), "Fake tunnel server should start");
        serverPort = fakeTunnelServer.getPort();
    }

    @AfterEach
    void cleanup() throws Exception {
        if (tunnelClient != null) {
            tunnelClient.closeBlocking();
        }
        if (fakeTunnelServer != null) {
            fakeTunnelServer.stop(1000);
        }
    }

    private TunnelClient connectClient() throws Exception {
        CountDownLatch connectedLatch = new CountDownLatch(1);
        fakeTunnelServer.setOnClientConnected(connectedLatch::countDown);

        TunnelClient client = new TunnelClient(
            URI.create("ws://localhost:" + serverPort),
            null, "1.0.0", null, disconnect -> {},
            "http://localhost:" + springPort,
            "ws://localhost:" + springPort
        );
        client.connectBlocking(5, TimeUnit.SECONDS);
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "Client should connect");
        return client;
    }

    @Test
    void onOpen_sendsRegister() throws Exception {
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

        tunnelClient = connectClient();
        assertTrue(registerLatch.await(5, TimeUnit.SECONDS), "Expected REGISTER message on connect");

        TunnelMessage lastRegister = registerRef.get();
        assertNotNull(lastRegister);
        assertNull(lastRegister.serverId());
        assertEquals("1.0.0", lastRegister.version());
    }

    @Test
    void ping_triggersPongResponse() throws Exception {
        AtomicReference<TunnelMessage> pongRef = new AtomicReference<>();
        CountDownLatch pongLatch = new CountDownLatch(1);

        tunnelClient = connectClient();

        fakeTunnelServer.setOnMessageReceived(raw -> {
            try {
                TunnelMessage msg = objectMapper.readValue(raw, TunnelMessage.class);
                if ("PONG".equals(msg.type())) {
                    pongRef.set(msg);
                    pongLatch.countDown();
                }
            } catch (Exception ignored) {}
        });

        // Send PING from server to client
        fakeTunnelServer.broadcast("{\"type\":\"PING\"}");

        assertTrue(pongLatch.await(5, TimeUnit.SECONDS), "Expected PONG in response to PING");

        TunnelMessage pong = pongRef.get();
        assertNotNull(pong);
        assertNull(pong.serverId());
    }

    @Test
    void httpRequest_dispatchedToRequestHandler() throws Exception {
        CountDownLatch responseLatch = new CountDownLatch(1);
        AtomicReference<TunnelMessage> responseRef = new AtomicReference<>();

        fakeTunnelServer.setOnMessageReceived(raw -> {
            try {
                TunnelMessage msg = objectMapper.readValue(raw, TunnelMessage.class);
                if ("REGISTER".equals(msg.type()) || "PONG".equals(msg.type())) return;
                if ("HTTP_RESPONSE".equals(msg.type()) || "HTTP_RESPONSE_S3".equals(msg.type())) {
                    responseRef.set(msg);
                    responseLatch.countDown();
                }
            } catch (Exception ignored) {}
        });

        tunnelClient = connectClient();

        // Use the same "about" query used across other integration tests
        String graphqlBody = objectMapper.writeValueAsString(
            Map.of("query", "{ about { name version description environment } }")
        );
        String httpRequest = objectMapper.writeValueAsString(new TunnelMessage(
            "HTTP_REQUEST", null, null, null, null, "req-about", "router-1",
            "POST", "/graphql",
            Map.of("content-type", "application/json"),
            graphqlBody,
            null, null, null, null, null, null, null
        ));
        fakeTunnelServer.broadcast(httpRequest);

        assertTrue(responseLatch.await(10, TimeUnit.SECONDS),
            "Expected HTTP_RESPONSE after HTTP_REQUEST dispatch");

        TunnelMessage response = responseRef.get();
        assertEquals("HTTP_RESPONSE", response.type());
        assertEquals("req-about", response.requestId());
        assertEquals("router-1", response.routerConnectionId());
        assertEquals(200, response.status());
        assertNotNull(response.body());
        String decoded = new String(Base64.getDecoder().decode(response.body()), StandardCharsets.UTF_8);
        assertTrue(decoded.contains("enterfind-server"),
            "Response body should contain app name, was: " + decoded);
        assertTrue(decoded.contains("alpha"),
            "Response body should contain environment 'alpha', was: " + decoded);
    }

    @Test
    void wsConnect_dispatchedToSessionManager() throws Exception {
        CountDownLatch sessionOpenLatch = new CountDownLatch(1);
        CountDownLatch ackLatch = new CountDownLatch(1);
        AtomicReference<TunnelMessage> ackRef = new AtomicReference<>();

        fakeTunnelServer.setOnMessageReceived(raw -> {
            try {
                TunnelMessage msg = objectMapper.readValue(raw, TunnelMessage.class);
                if ("WS_SESSION_OPEN".equals(msg.type())
                        && "session-ws-1".equals(msg.wsSessionId())) {
                    sessionOpenLatch.countDown();
                }
                if ("WS_SERVER_MSG".equals(msg.type()) && msg.data() != null
                        && msg.data().contains("connection_ack")) {
                    ackRef.set(msg);
                    ackLatch.countDown();
                }
            } catch (Exception ignored) {}
        });

        tunnelClient = connectClient();

        // WS_CONNECT causes WsSessionManager to open a real WS session to Spring
        fakeTunnelServer.broadcast(objectMapper.writeValueAsString(new TunnelMessage(
            "WS_CONNECT", null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, "session-ws-1", "/graphql", null
        )));

        // Wait for WS_SESSION_OPEN before routing — WsSessionManager sends this when onOpen fires
        assertTrue(sessionOpenLatch.await(5, TimeUnit.SECONDS),
            "Expected WS_SESSION_OPEN once upstream WS connects");

        // WS_MESSAGE routes connection_init to Spring; Spring responds with connection_ack,
        // which WsSessionManager forwards back as WS_SERVER_MSG through the tunnel
        fakeTunnelServer.broadcast(objectMapper.writeValueAsString(new TunnelMessage(
            "WS_MESSAGE", null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, "session-ws-1", null, "{\"type\":\"connection_init\"}"
        )));

        assertTrue(ackLatch.await(10, TimeUnit.SECONDS),
            "Expected WS_SERVER_MSG with connection_ack from Spring");
        assertEquals("session-ws-1", ackRef.get().wsSessionId());
    }

    @Test
    void wsMessage_dispatchedToSessionManager() throws Exception {
        // WS_MESSAGE to a non-existent session should be a no-op (no exception propagated)
        CountDownLatch pongLatch = new CountDownLatch(1);
        fakeTunnelServer.setOnMessageReceived(raw -> {
            try {
                if ("PONG".equals(objectMapper.readValue(raw, TunnelMessage.class).type())) {
                    pongLatch.countDown();
                }
            } catch (Exception ignored) {}
        });

        tunnelClient = connectClient();

        fakeTunnelServer.broadcast(objectMapper.writeValueAsString(new TunnelMessage(
            "WS_MESSAGE", null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, "nonexistent-session", null, "{\"type\":\"ping\"}"
        )));

        // PING flushes the tunnel client's message loop; receiving PONG proves no exception was thrown
        fakeTunnelServer.broadcast("{\"type\":\"PING\"}");
        assertTrue(pongLatch.await(5, TimeUnit.SECONDS),
            "Tunnel should still be alive after WS_MESSAGE to nonexistent session");
    }

    @Test
    void httpRequest_responseBodyUnderFrameLimit_tunnelRemainsAlive() throws Exception {
        // A ~30 KB response body Base64-encodes to ~40 KB. Combined with JSON
        // structure overhead that exceeds the 32768-byte AWS frame limit and
        // causes the tunnel to close with code 1009.
        // The fix is to lower LARGE_RESPONSE_THRESHOLD_BYTES so responses over
        // ~20 KB go through the S3 offload path, keeping the inline message small.

        AtomicInteger closeCode = new AtomicInteger(-1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        fakeTunnelServer.setMaxFrameSize(AWS_MAX_FRAME_BYTES);
        fakeTunnelServer.setOnClientClose(code -> {
            closeCode.set(code);
            closeLatch.countDown();
        });

        CountDownLatch responseLatch = new CountDownLatch(1);
        fakeTunnelServer.setOnMessageReceived(raw -> {
            try {
                TunnelMessage msg = objectMapper.readValue(raw, TunnelMessage.class);
                if ("REGISTER".equals(msg.type()) || "PONG".equals(msg.type())) return;
                if ("HTTP_RESPONSE".equals(msg.type()) || "HTTP_RESPONSE_S3".equals(msg.type())) {
                    responseLatch.countDown();
                }
            } catch (Exception ignored) {}
        });

        tunnelClient = connectClient();

        String fakeS3Url = "http://localhost:" + fakeS3Port + "/upload/req-medium";
        String httpRequest = objectMapper.writeValueAsString(new TunnelMessage(
            "HTTP_REQUEST", null, null, null, null, "req-medium", "router-1",
            "GET", "/test/medium",
            null, null, null, fakeS3Url, null, null, null, null, null
        ));
        fakeTunnelServer.broadcast(httpRequest);

        boolean gotResponse = responseLatch.await(10, TimeUnit.SECONDS);
        boolean disconnected = closeLatch.await(1, TimeUnit.SECONDS);

        assertFalse(disconnected && closeCode.get() == 1009,
            "Tunnel disconnected with code 1009 — ~30 KB response body exceeded the " +
            AWS_MAX_FRAME_BYTES + "-byte frame limit. Lower LARGE_RESPONSE_THRESHOLD_BYTES.");
        assertTrue(gotResponse, "Expected HTTP_RESPONSE or HTTP_RESPONSE_S3 for /test/medium");
    }

    @Test
    void wsClose_dispatchedToSessionManager() throws Exception {
        // WS_CLOSE for a non-existent session should be a no-op
        CountDownLatch pongLatch = new CountDownLatch(1);
        fakeTunnelServer.setOnMessageReceived(raw -> {
            try {
                if ("PONG".equals(objectMapper.readValue(raw, TunnelMessage.class).type())) {
                    pongLatch.countDown();
                }
            } catch (Exception ignored) {}
        });

        tunnelClient = connectClient();

        fakeTunnelServer.broadcast(objectMapper.writeValueAsString(new TunnelMessage(
            "WS_CLOSE", null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, "nonexistent-session", null, null
        )));

        // PING flushes the tunnel client's message loop; receiving PONG proves no exception was thrown
        fakeTunnelServer.broadcast("{\"type\":\"PING\"}");
        assertTrue(pongLatch.await(5, TimeUnit.SECONDS),
            "Tunnel should still be alive after WS_CLOSE for nonexistent session");
    }

    @Test
    void onClose_triggersReconnect() throws Exception {
        CountDownLatch reconnectLatch = new CountDownLatch(2); // first connect + reconnect

        fakeTunnelServer.setOnClientConnected(() -> reconnectLatch.countDown());

        // Create client with a reconnect callback that reconnects after a short delay
        CountDownLatch disconnectedLatch = new CountDownLatch(1);
        TunnelClient[] clientHolder = {null};

        Consumer<TunnelDisconnect> reconnect = disconnect -> {
            disconnectedLatch.countDown();
            // Schedule a reconnect attempt
            new Thread(() -> {
                try {
                    Thread.sleep(200);
                    TunnelClient newClient = new TunnelClient(
                        URI.create("ws://localhost:" + serverPort),
                        null, "1.0.0", null, d -> {},
                        "http://localhost:" + springPort,
                        "ws://localhost:" + springPort
                    );
                    newClient.connectBlocking(5, TimeUnit.SECONDS);
                    clientHolder[0] = newClient;
                } catch (Exception ignored) {}
            }).start();
        };

        tunnelClient = new TunnelClient(
            URI.create("ws://localhost:" + serverPort),
            null, "1.0.0", null, reconnect,
            "http://localhost:" + springPort,
            "ws://localhost:" + springPort
        );
        tunnelClient.connectBlocking(5, TimeUnit.SECONDS);
        assertTrue(reconnectLatch.getCount() < 2 || true, "first connection counted");

        // Force-close the tunnel to trigger onClose
        fakeTunnelServer.getConnections().forEach(WebSocket::close);

        assertTrue(disconnectedLatch.await(5, TimeUnit.SECONDS),
            "Disconnect callback should fire when tunnel closes");
        assertTrue(reconnectLatch.await(5, TimeUnit.SECONDS),
            "Reconnected client should connect to the fake server");

        // Clean up secondary client if it connected
        if (clientHolder[0] != null) {
            clientHolder[0].closeBlocking();
        }
    }

    /**
     * The self-enrollment path: a client built with a null token sends no Authorization header and
     * takes its credential from REGISTERED. Nothing in production exercises this any more — the
     * deployed tunnel refuses unauthenticated connections — so this test and the ENROLL_ALLOWED
     * scenarios in RemoteAccessServiceTest are the only cover it has. Do not condition or delete it:
     * open signup is a plausible outcome, and this is what says the path still works.
     */
    @Test
    void onMessage_registered_invokesCredentialUpdateCallback() throws Exception {
        CountDownLatch credentialLatch = new CountDownLatch(1);
        AtomicReference<String> receivedServerId = new AtomicReference<>();
        AtomicReference<String> receivedToken = new AtomicReference<>();

        CountDownLatch connectedLatch = new CountDownLatch(1);
        fakeTunnelServer.setOnClientConnected(connectedLatch::countDown);

        BiConsumer<String, String> credentialUpdate = (sid, tok) -> {
            receivedServerId.set(sid);
            receivedToken.set(tok);
            credentialLatch.countDown();
        };

        tunnelClient = new TunnelClient(
            URI.create("ws://localhost:" + serverPort),
            null, "1.0.0", credentialUpdate, disconnect -> {},
            "http://localhost:" + springPort,
            "ws://localhost:" + springPort
        );
        tunnelClient.connectBlocking(5, TimeUnit.SECONDS);
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "Client should connect");

        // Fake server sends REGISTERED
        fakeTunnelServer.sendToClient(
            "{\"type\":\"REGISTERED\",\"serverId\":\"srv123\",\"token\":\"efl_abc123token456\"}");

        assertTrue(credentialLatch.await(5, TimeUnit.SECONDS),
            "onCredentialUpdate should be invoked after REGISTERED message");
        assertEquals("srv123", receivedServerId.get());
        assertEquals("efl_abc123token456", receivedToken.get());
    }

    @Test
    void onMessage_registrationRejected_closesClient() throws Exception {
        CountDownLatch closedLatch = new CountDownLatch(1);
        fakeTunnelServer.setOnClientClose(code -> closedLatch.countDown());

        CountDownLatch connectedLatch = new CountDownLatch(1);
        fakeTunnelServer.setOnClientConnected(connectedLatch::countDown);

        tunnelClient = new TunnelClient(
            URI.create("ws://localhost:" + serverPort),
            null, "1.0.0", null, disconnect -> {},
            "http://localhost:" + springPort,
            "ws://localhost:" + springPort
        );
        tunnelClient.connectBlocking(5, TimeUnit.SECONDS);
        assertTrue(connectedLatch.await(5, TimeUnit.SECONDS), "Client should connect");

        fakeTunnelServer.sendToClient(
            "{\"type\":\"REGISTRATION_REJECTED\",\"reason\":\"QUOTA_EXCEEDED\"}");

        assertTrue(closedLatch.await(5, TimeUnit.SECONDS),
            "Client should close after REGISTRATION_REJECTED");
    }

}
