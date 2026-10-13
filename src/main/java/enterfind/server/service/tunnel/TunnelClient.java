package enterfind.server.service.tunnel;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public class TunnelClient extends WebSocketClient {

    private static final Logger logger = LoggerFactory.getLogger(TunnelClient.class);
    private static final int CLOSE_CODE_POLICY_VIOLATION = 1008;

    private final String token;
    private final String version;
    private final TunnelRequestHandler requestHandler;
    private final WsSessionManager wsSessionManager;
    private final ObjectMapper objectMapper;
    private final Consumer<TunnelDisconnect> onDisconnectCallback;
    private final BiConsumer<String, String> onCredentialUpdate;

    private ScheduledExecutorService heartbeatScheduler;
    private volatile boolean opened;
    private volatile String registrationRejectedReason;

    public TunnelClient(URI serverUri, String token, String version,
            BiConsumer<String, String> onCredentialUpdate,
            Consumer<TunnelDisconnect> onDisconnectCallback,
            String localHttpBaseUrl, String localWsBaseUrl) {
        super(serverUri, token == null
            ? Map.of()
            : Map.of("Authorization", "Bearer " + token));
        this.token = token;
        this.version = version;
        this.onCredentialUpdate = onCredentialUpdate;
        this.onDisconnectCallback = onDisconnectCallback;
        this.objectMapper = new JsonMapper();
        this.requestHandler = new TunnelRequestHandler(localHttpBaseUrl, this::sendMessage);
        this.wsSessionManager = new WsSessionManager(this::sendMessage, localWsBaseUrl);
    }

    @Override
    public void onOpen(ServerHandshake handshake) {
        opened = true;
        logger.info("Tunnel connected to {} ({})", getURI(), token == null ? "enroll" : "authed");
        sendMessage(TunnelMessage.register(version));

        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tunnel-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                sendMessage(TunnelMessage.pong());
            } catch (Exception e) {
                logger.warn("Failed to send heartbeat PONG", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    @Override
    public void onMessage(String rawMessage) {
        TunnelMessage msg;
        try {
            msg = objectMapper.readValue(rawMessage, TunnelMessage.class);
        } catch (JacksonException e) {
            logger.warn("Failed to deserialize tunnel message: {}", rawMessage, e);
            return;
        }

        if (msg.type() == null) {
            logger.warn("Received tunnel message with null type");
            return;
        }

        switch (msg.type()) {
            case "HTTP_REQUEST" -> requestHandler.handle(msg);
            case "WS_CONNECT"  -> wsSessionManager.openSession(msg.wsSessionId(), msg.wsPath());
            case "WS_MESSAGE"  -> wsSessionManager.routeMessage(msg.wsSessionId(), msg.data());
            case "WS_CLOSE"    -> wsSessionManager.closeSession(msg.wsSessionId());
            case "PING"        -> sendMessage(TunnelMessage.pong());
            case "REGISTERED" -> {
                if (msg.serverId() == null || msg.token() == null) {
                    logger.warn("REGISTERED message missing serverId or token; ignoring");
                    return;
                }
                logger.info("REGISTERED: serverId={}, token={}", msg.serverId(),
                    msg.token().substring(0, Math.min(11, msg.token().length())) + "…****");
                if (onCredentialUpdate != null) {
                    onCredentialUpdate.accept(msg.serverId(), msg.token());
                }
            }
            case "REGISTRATION_REJECTED" -> {
                logger.warn("REGISTRATION_REJECTED: reason={}", msg.reason());
                // The edge leaves the socket open, so our own close() below is what ends it — and a
                // self-initiated close looks exactly like a clean shutdown. Record the reason first
                // so onClose can tell the two apart.
                registrationRejectedReason = msg.reason() != null ? msg.reason() : "unspecified";
                close();
            }
            default -> logger.debug("Received unhandled message type: {}", msg.type());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.info("Tunnel disconnected: code={}, reason={}, remote={}", code, reason, remote);
        shutdownHeartbeat();
        wsSessionManager.closeAllSessions();
        requestHandler.shutdown();
        if (onDisconnectCallback != null) {
            onDisconnectCallback.accept(classify(code, reason));
        }
    }

    /**
     * Classifies a disconnect from observable facts only: whether the socket ever opened, whether a
     * registration rejection arrived, and the close code. Measured against the deployed tunnel on
     * 2026-09-23: a refused connection surfaces as {@link CloseFrame#PROTOCOL_ERROR} carrying the
     * HTTP status in its reason, never as 1008.
     */
    private TunnelDisconnect classify(int code, String reason) {
        String rejected = registrationRejectedReason;
        if (rejected != null) {
            return new TunnelDisconnect(TunnelDisconnect.Kind.REJECTED, rejected);
        }
        if (!opened) {
            if (code == CloseFrame.PROTOCOL_ERROR) {
                logger.error("Tunnel refused before the socket opened: {}", reason);
                return new TunnelDisconnect(TunnelDisconnect.Kind.REFUSED, reason);
            }
            return new TunnelDisconnect(TunnelDisconnect.Kind.UNREACHABLE, reason);
        }
        if (code == CLOSE_CODE_POLICY_VIOLATION) {
            // Not observed against the deployed tunnel — revocation takes effect at $connect and
            // leaves a live socket alone. Logged rather than acted on, so that if the edge ever does
            // close this way it shows up instead of being silently treated as an ordinary drop.
            logger.warn("Tunnel closed with policy violation (1008) on an established connection: {}", reason);
        }
        return TunnelDisconnect.normal();
    }

    @Override
    public void onError(Exception ex) {
        logger.error("Tunnel error", ex);
    }

    public void sendMessage(TunnelMessage msg) {
        try {
            String json = objectMapper.writeValueAsString(msg);
            send(json);
        } catch (JacksonException e) {
            logger.error("Failed to serialize tunnel message of type {}", msg.type(), e);
        } catch (Exception e) {
            logger.warn("Failed to send tunnel message of type {}", msg.type(), e);
        }
    }

    private void shutdownHeartbeat() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }
    }
}
