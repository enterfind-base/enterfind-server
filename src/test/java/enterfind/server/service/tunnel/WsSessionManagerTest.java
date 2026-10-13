package enterfind.server.service.tunnel;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import enterfind.server.BaseDatabaseTest;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("alpha")
public class WsSessionManagerTest extends BaseDatabaseTest {

    @LocalServerPort
    private int springPort;

    private List<TunnelMessage> sentMessages;
    private WsSessionManager manager;

    @BeforeEach
    protected void setUp() {
        sentMessages = new CopyOnWriteArrayList<>();
        manager = new WsSessionManager(sentMessages::add, "ws://localhost:" + springPort);
    }

    @AfterEach
    void tearDown() {
        manager.closeAllSessions();
    }

    @Test
    void openSession_connectsToSpringGraphqlWs() throws Exception {
        CountDownLatch connectionAckLatch = new CountDownLatch(1);

        // Override message handler to also release the latch on connection_ack
        WsSessionManager localManager = new WsSessionManager(msg -> {
            sentMessages.add(msg);
            if ("WS_SERVER_MSG".equals(msg.type()) && msg.data() != null
                    && msg.data().contains("connection_ack")) {
                connectionAckLatch.countDown();
            }
        }, "ws://localhost:" + springPort);

        localManager.openSession("session-1", "/graphql").get(5, TimeUnit.SECONDS);

        // Send connection_init to trigger connection_ack from Spring's graphql-transport-ws
        localManager.routeMessage("session-1", "{\"type\":\"connection_init\"}");

        boolean ackReceived = connectionAckLatch.await(10, TimeUnit.SECONDS);
        assertTrue(ackReceived, "Expected connection_ack from Spring GraphQL WS");

        localManager.closeAllSessions();
    }

    @Test
    void routeMessage_forwardsGraphqlFrameToSpring() throws Exception {
        CountDownLatch ackLatch = new CountDownLatch(1);

        WsSessionManager localManager = new WsSessionManager(msg -> {
            sentMessages.add(msg);
            if ("WS_SERVER_MSG".equals(msg.type()) && msg.data() != null
                    && msg.data().contains("connection_ack")) {
                ackLatch.countDown();
            }
        }, "ws://localhost:" + springPort);

        localManager.openSession("session-2", "/graphql").get(5, TimeUnit.SECONDS);

        localManager.routeMessage("session-2", "{\"type\":\"connection_init\"}");
        assertTrue(ackLatch.await(10, TimeUnit.SECONDS), "Expected connection_ack");

        // Verify the WS_SERVER_MSG was captured
        boolean hasAck = sentMessages.stream()
            .anyMatch(m -> "WS_SERVER_MSG".equals(m.type())
                && m.wsSessionId().equals("session-2")
                && m.data() != null
                && m.data().contains("connection_ack"));
        assertTrue(hasAck, "Should have received WS_SERVER_MSG with connection_ack");

        localManager.closeAllSessions();
    }

    @Test
    void closeSession_closesConnection() throws Exception {
        CountDownLatch connectedLatch = new CountDownLatch(1);
        CountDownLatch closedLatch = new CountDownLatch(1);

        WsSessionManager localManager = new WsSessionManager(msg -> {
            if ("WS_SERVER_MSG".equals(msg.type())) {
                connectedLatch.countDown();
            } else if ("WS_SERVER_CLOSE".equals(msg.type())) {
                closedLatch.countDown();
            }
        }, "ws://localhost:" + springPort);

        localManager.openSession("session-3", "/graphql").get(5, TimeUnit.SECONDS);
        localManager.routeMessage("session-3", "{\"type\":\"connection_init\"}");
        assertTrue(connectedLatch.await(10, TimeUnit.SECONDS), "Session should connect");

        localManager.closeSession("session-3");
        assertTrue(closedLatch.await(5, TimeUnit.SECONDS), "Session should send WS_SERVER_CLOSE on close");

        // Subsequent routeMessage on the closed session should be a no-op (no exception)
        localManager.routeMessage("session-3", "{\"type\":\"ping\"}");
    }

    @Test
    void closeAllSessions_closesAllOpenSessions() throws Exception {
        CountDownLatch connectedLatch = new CountDownLatch(2);
        CountDownLatch closedLatch = new CountDownLatch(2);

        WsSessionManager localManager = new WsSessionManager(msg -> {
            if ("WS_SERVER_MSG".equals(msg.type())) {
                connectedLatch.countDown();
            } else if ("WS_SERVER_CLOSE".equals(msg.type())) {
                closedLatch.countDown();
            }
        }, "ws://localhost:" + springPort);

        localManager.openSession("session-a", "/graphql").get(5, TimeUnit.SECONDS);
        localManager.openSession("session-b", "/graphql").get(5, TimeUnit.SECONDS);

        localManager.routeMessage("session-a", "{\"type\":\"connection_init\"}");
        localManager.routeMessage("session-b", "{\"type\":\"connection_init\"}");

        assertTrue(connectedLatch.await(10, TimeUnit.SECONDS), "Both sessions should connect");

        localManager.closeAllSessions();
        assertTrue(closedLatch.await(10, TimeUnit.SECONDS), "Both sessions should send WS_SERVER_CLOSE");
    }
}
