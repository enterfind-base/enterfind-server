package enterfind.server.service.tunnel;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.exceptions.WebsocketNotConnectedException;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WsSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(WsSessionManager.class);

    private final Consumer<TunnelMessage> messageSender;
    private final String localWsBaseUrl;
    private final ConcurrentHashMap<String, WebSocketClient> sessions = new ConcurrentHashMap<>();

    public WsSessionManager(Consumer<TunnelMessage> messageSender, String localWsBaseUrl) {
        this.messageSender = messageSender;
        this.localWsBaseUrl = localWsBaseUrl;
    }

    public CompletableFuture<Void> openSession(String wsSessionId, String wsPath) {
        CompletableFuture<Void> connected = new CompletableFuture<>();
        String localUrl = localWsBaseUrl + wsPath;
        boolean isGraphql = wsPath != null && wsPath.toLowerCase().contains("graphql");
        WebSocketClient wsClient = new WebSocketClient(URI.create(localUrl)) {
            {
                if (isGraphql) {
                    addHeader("Sec-WebSocket-Protocol", "graphql-transport-ws");
                }
            }

            @Override
            public void onOpen(ServerHandshake handshake) {
                logger.debug("WS session {} opened to {}", wsSessionId, localUrl);
                connected.complete(null);
                messageSender.accept(TunnelMessage.wsSessionOpen(wsSessionId));
            }

            @Override
            public void onMessage(String message) {
                messageSender.accept(TunnelMessage.wsServerMsg(wsSessionId, message));
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                logger.debug("WS session {} closed: code={}, reason={}", wsSessionId, code, reason);
                connected.complete(null); // no-op if already completed from onOpen
                sessions.remove(wsSessionId);
                try {
                    messageSender.accept(TunnelMessage.wsServerClose(wsSessionId));
                } catch (Exception e) {
                    logger.warn("Failed to send WS_SERVER_CLOSE for session {}", wsSessionId, e);
                }
            }

            @Override
            public void onError(Exception ex) {
                logger.warn("WS session {} error", wsSessionId, ex);
            }
        };

        sessions.put(wsSessionId, wsClient);
        wsClient.connect();
        return connected;
    }

    public void routeMessage(String wsSessionId, String data) {
        WebSocketClient session = sessions.get(wsSessionId);
        if (session != null) {
            try {
                session.send(data);
            } catch (WebsocketNotConnectedException e) {
                logger.warn("WS session {} not yet connected, dropping message", wsSessionId);
            }
        } else {
            logger.debug("routeMessage called for unknown session {}", wsSessionId);
        }
    }

    public void closeSession(String wsSessionId) {
        WebSocketClient session = sessions.remove(wsSessionId);
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                logger.warn("Error closing WS session {}", wsSessionId, e);
            }
        }
    }

    public void closeAllSessions() {
        for (String wsSessionId : sessions.keySet()) {
            closeSession(wsSessionId);
        }
    }
}
