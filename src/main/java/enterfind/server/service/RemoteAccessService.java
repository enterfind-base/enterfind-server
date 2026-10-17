package enterfind.server.service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import enterfind.server.config.EgressProxy;
import enterfind.server.dao.SystemSettingsDao;
import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.model.RemoteAccessConfig;
import enterfind.server.service.tunnel.TunnelClient;
import enterfind.server.service.tunnel.TunnelDisconnect;
import enterfind.server.util.ProfileHelper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

@Service
public class RemoteAccessService {

    private static final Logger logger = LoggerFactory.getLogger(RemoteAccessService.class);
    /** For a tunnel that had been working: come back quickly. */
    private static final int[] FAST_BACKOFF_SECONDS = {1, 5, 10, 30, 60};
    /**
     * For a connection that never established. Retrying stays on — a refused instance heals itself
     * once a credential is issued for it, or if the tunnel stops requiring one — but at a rate that
     * does not knock sixty times an hour on an edge that has already said no.
     */
    private static final int[] SLOW_BACKOFF_SECONDS = {1, 5, 10, 30, 60, 300, 900};
    /** How many consecutive unreachable attempts before blaming anything in the UI. */
    private static final int UNREACHABLE_REPORT_THRESHOLD = 3;

    private static final String REFUSED_NO_CREDENTIAL_MESSAGE =
        "The tunnel refused this instance because it has no credential. Enter an invite code above "
        + "and click Use invite code — an invite code that is mistyped, withdrawn or already used is "
        + "refused in exactly the same way.";
    private static final String REFUSED_MESSAGE =
        "The tunnel refused this instance's stored credential, which means it has been withdrawn. "
        + "Enter a new invite code above and click Use invite code. Retrying periodically.";
    private static final String INVITE_REFUSED_MESSAGE =
        "The invite code was not accepted: it may be mistyped, withdrawn, or already used by another "
        + "instance.";
    private static final String INVITE_INTERRUPTED_MESSAGE =
        "The connection to the tunnel closed before the invite code was accepted. Enter it again; if "
        + "it is then reported as already used, ask for a new one.";
    private static final String UNREACHABLE_MESSAGE =
        "Cannot reach the Enterfind tunnel. This looks like a network or DNS problem rather than a "
        + "credential one. Retrying periodically.";

    private record Credential(String serverId, String token) {
        @Override
        public String toString() {
            String prefix = token != null ? token.substring(0, Math.min(11, token.length())) + "…****" : "null";
            return "Credential[serverId=" + serverId + ", token=" + prefix + "]";
        }
    }

    private final Sinks.Many<RemoteAccessConfig> configSink =
            Sinks.many().multicast().directBestEffort();

    private final SystemSettingsDao systemSettingsDao;
    private final ProfileHelper profileHelper;
    private final String version;
    private final int serverPort;
    private final ObjectMapper objectMapper = new JsonMapper();

    private String tunnelEndpoint;
    private volatile Credential credential;

    private volatile TunnelClient tunnelClient;
    private ScheduledExecutorService reconnectScheduler;
    private volatile int reconnectAttempt = 0;
    private volatile int[] reconnectBackoff = FAST_BACKOFF_SECONDS;
    private volatile int consecutiveUnreachable = 0;

    /** Incremented per connection attempt, so a disconnect can be traced to the attempt it ends. */
    private volatile long connectGeneration = 0;
    /** The attempt carrying an invite code until it ends either way, 0 when there is none. */
    private volatile long inviteGeneration = 0;
    /**
     * The credential an invite attempt set aside. It is still in {@code RemoteAccessCredential} and
     * comes back if the code is not accepted, so a mistyped code cannot cost a working Server ID.
     */
    private volatile Credential displacedCredential;
    /**
     * Why the last invite attempt failed. Kept until the operator acts again, so the reconnect that
     * follows — which may well succeed on the restored credential — does not wipe the explanation.
     */
    private volatile String inviteFailureMessage;

    public RemoteAccessService(SystemSettingsDao systemSettingsDao, ProfileHelper profileHelper,
            @Value("${app.version:0}") String version, @Value("${server.port:8080}") int serverPort) {
        this.systemSettingsDao = systemSettingsDao;
        this.profileHelper = profileHelper;
        this.version = version;
        // The configured port, not the bound one: init() connects before the web server has bound.
        this.serverPort = serverPort;
    }

    @PostConstruct
    public void init() {
        String stage = profileHelper.getActiveProfile();
        tunnelEndpoint = "wss://tunnel-" + stage + ".enterfind.com";

        credential = systemSettingsDao.getSetting("RemoteAccessCredential")
            .filter(s -> !s.isBlank())
            .map(this::deserializeCredential)
            .orElse(null);

        if ("true".equals(systemSettingsDao.getSetting("RemoteAccessEnabled").orElse("false"))) {
            startTunnel();
        }
    }

    /**
     * Connects on the stored credential, or with none. This is also the reconnect path, which is why
     * an invite code is never retried: it reaches the tunnel only through {@link #startTunnel(String, boolean)}.
     */
    private synchronized void startTunnel() {
        startTunnel(credential != null ? credential.token() : null, false);
    }

    private synchronized void startTunnel(String token, boolean carriesInvite) {
        if (tunnelClient != null && !tunnelClient.isClosed()) {
            return;
        }
        try {
            URI uri = URI.create(tunnelEndpoint);
            long generation = ++connectGeneration;
            if (carriesInvite) {
                // Set before the connect thread starts: a refusal can arrive within milliseconds.
                inviteGeneration = generation;
            }
            TunnelClient client = new TunnelClient(uri, token, version,
                (serverId, issued) -> onCredentialUpdate(generation, serverId, issued),
                disconnect -> onTunnelDisconnect(generation, disconnect),
                // 127.0.0.1 rather than localhost: it reaches a server bound to all addresses or to
                // loopback only, whichever way the name localhost happens to resolve.
                "http://127.0.0.1:" + serverPort, "ws://127.0.0.1:" + serverPort);
            EgressProxy.configure(client, uri);
            tunnelClient = client;
            Thread.ofVirtual().name("tunnel-connect").start(() -> {
                try {
                    boolean connected = client.connectBlocking();
                    if (connected) {
                        reconnectAttempt = 0;
                        reconnectBackoff = FAST_BACKOFF_SECONDS;
                        consecutiveUnreachable = 0;
                        systemSettingsDao.setSetting("RemoteAccessLastConnected", LocalDateTime.now().toString());
                        String failure = inviteFailureMessage;
                        systemSettingsDao.setSetting("RemoteAccessStatusMessage", failure != null ? failure : "");
                        logger.info("Tunnel connected successfully");
                        emitConfigUpdate();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Tunnel connection interrupted");
                } catch (Exception e) {
                    logger.error("Tunnel connection failed", e);
                }
            });
        } catch (Exception e) {
            logger.error("Failed to start tunnel", e);
        }
    }

    private void stopTunnel() {
        TunnelClient client;
        synchronized (this) {
            cancelReconnect();
            client = tunnelClient;
            tunnelClient = null;
        }
        // Closed outside the lock: the websocket's read thread holds its own lock while it delivers
        // onClose, which reaches scheduleReconnect() and needs ours — closing under our lock deadlocks.
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                logger.warn("Error closing tunnel", e);
            }
        }
    }

    /**
     * Reports what the tunnel did and picks a retry rate. Reconnect is never suppressed: a refused
     * instance becomes a working one the moment a credential is issued for it, and an operator who
     * has to press a button to discover that is an operator who never finds out.
     */
    private void onTunnelDisconnect(long generation, TunnelDisconnect disconnect) {
        if (generation != connectGeneration) {
            // A connection that has already been replaced — typically the one stopped to make way for
            // an invite attempt. Its ending says nothing about the connection that superseded it.
            return;
        }
        if (!"true".equals(systemSettingsDao.getSetting("RemoteAccessEnabled").orElse("false"))) {
            // Closing the tunnel is what disabling does. Reporting that as a failure would also race
            // the clearing below and put the banner straight back.
            emitConfigUpdate();
            return;
        }
        if (generation == inviteGeneration) {
            // Still set, so the attempt ended without REGISTERED: the code was not redeemed for us.
            settleFailedInvite(disconnect);
            emitConfigUpdate();
            scheduleReconnect();
            return;
        }

        switch (disconnect.kind()) {
            case REFUSED -> {
                logger.error("Tunnel refused the connection: {}", disconnect.detail());
                reconnectBackoff = SLOW_BACKOFF_SECONDS;
                consecutiveUnreachable = 0;
                // Being refused with nothing to present is a different situation from being refused
                // with a credential. After a failed invite the specific reason is the more useful one.
                String failure = inviteFailureMessage;
                systemSettingsDao.setSetting("RemoteAccessStatusMessage",
                    credential != null ? REFUSED_MESSAGE
                        : failure != null ? failure : REFUSED_NO_CREDENTIAL_MESSAGE);
            }
            case REJECTED -> {
                logger.error("Tunnel rejected registration: {}", disconnect.detail());
                reconnectBackoff = SLOW_BACKOFF_SECONDS;
                consecutiveUnreachable = 0;
                systemSettingsDao.setSetting("RemoteAccessStatusMessage",
                    rejectionMessage(disconnect.detail()));
            }
            case UNREACHABLE -> {
                reconnectBackoff = SLOW_BACKOFF_SECONDS;
                // One failed attempt is a blip, not a fault worth reporting — and reporting it as a
                // credential problem would be actively wrong.
                if (++consecutiveUnreachable >= UNREACHABLE_REPORT_THRESHOLD) {
                    systemSettingsDao.setSetting("RemoteAccessStatusMessage", UNREACHABLE_MESSAGE);
                }
            }
            case NORMAL -> {
                reconnectBackoff = FAST_BACKOFF_SECONDS;
                consecutiveUnreachable = 0;
            }
        }

        emitConfigUpdate();
        scheduleReconnect();
    }

    /**
     * Ends an invite attempt that never got REGISTERED. Whatever the reason, the reconnect that
     * follows goes out without the code, on the credential the attempt set aside if there was one.
     */
    private void settleFailedInvite(TunnelDisconnect disconnect) {
        inviteGeneration = 0;
        consecutiveUnreachable = 0;
        String message = switch (disconnect.kind()) {
            case REFUSED -> INVITE_REFUSED_MESSAGE;
            case REJECTED -> rejectionMessage(disconnect.detail());
            case UNREACHABLE, NORMAL -> INVITE_INTERRUPTED_MESSAGE;
        };
        logger.warn("Invite code not redeemed ({}: {})", disconnect.kind(), disconnect.detail());

        Credential restored = displacedCredential;
        displacedCredential = null;
        if (restored != null) {
            credential = restored;
            message += " This instance keeps its Server ID " + restored.serverId() + ".";
            // The restored credential was working a moment ago; come straight back on it.
            reconnectAttempt = 0;
            reconnectBackoff = FAST_BACKOFF_SECONDS;
            logger.info("Restored the credential for serverId={}", restored.serverId());
        } else {
            reconnectBackoff = SLOW_BACKOFF_SECONDS;
        }
        inviteFailureMessage = message;
        systemSettingsDao.setSetting("RemoteAccessStatusMessage", message);
    }

    /**
     * Forgets an unfinished invite attempt, putting back the credential it set aside. The persisted
     * setting was never touched, so only the in-memory copy needs restoring.
     */
    private void abandonInvite() {
        inviteGeneration = 0;
        inviteFailureMessage = null;
        Credential restored = displacedCredential;
        displacedCredential = null;
        if (restored != null) {
            credential = restored;
        }
    }

    private String rejectionMessage(String reason) {
        return switch (reason == null ? "" : reason) {
            case "SERVER_ID_TAKEN" -> "Another instance is already connected with this Server ID. "
                + "Two instances cannot share one credential, which is what copying an instance's "
                + "settings does — use a separate invite code on one of them, or disable remote access "
                + "on the other.";
            case "INVITE_REDEEMED" -> "That invite code has just been used by another instance. Ask "
                + "for a new one.";
            case "QUOTA_EXCEEDED" -> "This instance has exceeded its remote access quota.";
            default -> "The tunnel rejected this instance (" + reason + ").";
        };
    }

    private void onCredentialUpdate(long generation, String serverId, String token) {
        if (generation != connectGeneration) {
            // REGISTERED from a connection already replaced — typically the one stopped for an invite
            // attempt, answering its REGISTER a moment late. With the stored credential set aside it
            // would pass for an enrollment and end the invite attempt it has nothing to do with.
            logger.debug("Ignoring REGISTERED from a superseded connection (serverId={})", serverId);
            return;
        }
        Credential incoming = new Credential(serverId, token);
        Credential old = credential;

        if (old != null && old.serverId().equals(serverId) && old.token().equals(token)) {
            return;
        }

        // A serverId we did not ask for is a protocol anomaly and is refused. Moving to a new
        // serverId is done with an invite code, which discards the stored credential first, so the
        // enrolling REGISTERED arrives with nothing stored and never reaches this guard.
        if (old != null && !old.serverId().equals(serverId)) {
            logger.error("REGISTERED returned a different serverId (was {}, got {}); ignoring",
                old.serverId(), serverId);
            systemSettingsDao.setSetting("RemoteAccessStatusMessage",
                "Credential conflict: the tunnel returned a different Server ID (" + serverId +
                ") than the one this instance is using (" + old.serverId() + "). If this persists, "
                + "use a new invite code.");
            emitConfigUpdate();
            return;
        }

        if (old == null) {
            // The code has been redeemed for us: the attempt succeeded, and whatever it set aside is
            // superseded. Cleared before the reconnect below, whose close would otherwise read as a
            // failed invite.
            inviteGeneration = 0;
            displacedCredential = null;
            inviteFailureMessage = null;
        }
        applyCredential(incoming);

        if (old == null) {
            logger.info("Enrolled with serverId={}", serverId);
        } else {
            logger.info("Token rotated for serverId={}", serverId);
        }

        triggerReconnect();
    }

    /**
     * Persists a credential issued by the tunnel and clears whatever the panel was complaining about.
     */
    private void applyCredential(Credential incoming) {
        systemSettingsDao.setSetting("RemoteAccessCredential", serializeCredential(incoming));
        credential = incoming;
        systemSettingsDao.setSetting("RemoteAccessStatusMessage", "");
    }

    private void triggerReconnect() {
        if (tunnelClient != null) {
            try {
                tunnelClient.close();
            } catch (Exception e) {
                logger.warn("Error closing tunnel during credential-triggered reconnect", e);
            }
        }
    }

    private String serializeCredential(Credential c) {
        try {
            return objectMapper.writeValueAsString(Map.of("serverId", c.serverId(), "token", c.token()));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize RemoteAccessCredential", e);
        }
    }

    private Credential deserializeCredential(String json) {
        try {
            var node = objectMapper.readTree(json);
            String sid = node.path("serverId").asString(null);
            String tok = node.path("token").asString(null);
            if (sid == null || tok == null) {
                logger.warn("RemoteAccessCredential JSON missing serverId or token; treating as unenrolled");
                return null;
            }
            return new Credential(sid, tok);
        } catch (Exception e) {
            logger.warn("Failed to deserialize RemoteAccessCredential; treating as unenrolled: {}", e.getMessage());
            return null;
        }
    }

    private synchronized void scheduleReconnect() {
        int[] backoff = reconnectBackoff;
        int attempt = reconnectAttempt;
        int delayIndex = Math.min(attempt, backoff.length - 1);
        int delaySecs = backoff[delayIndex];
        reconnectAttempt = attempt + 1;

        if (reconnectScheduler == null || reconnectScheduler.isShutdown()) {
            reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = Thread.ofVirtual().name("tunnel-reconnect-scheduler").unstarted(r);
                return t;
            });
        }

        logger.info("Tunnel disconnected; reconnecting in {} s (attempt {})", delaySecs, attempt + 1);
        reconnectScheduler.schedule(() -> {
            if ("true".equals(systemSettingsDao.getSetting("RemoteAccessEnabled").orElse("false"))) {
                startTunnel();
            }
        }, delaySecs, TimeUnit.SECONDS);
    }

    private void cancelReconnect() {
        reconnectAttempt = 0;
        if (reconnectScheduler != null) {
            reconnectScheduler.shutdownNow();
            reconnectScheduler = null;
        }
    }

    public void setRemoteAccessEnabled(boolean enabled) {
        setRemoteAccessEnabled(enabled, null);
    }

    /**
     * Enables or disables remote access, optionally redeeming an invite code.
     *
     * <p>Without a code this is the toggle: it connects on the stored credential, keeping the Server
     * ID, or with none at all, leaving it to the tunnel to decide whether to enroll the instance.
     * Nothing here knows or cares whether the tunnel currently accepts that.
     *
     * <p>With a code it enrolls afresh: the stored credential is set aside and the code is presented
     * on exactly one connection attempt. If the tunnel redeems it, the credential it issues replaces
     * the old one; if not, the old one is put back. The code itself is never stored or retried.
     */
    public void setRemoteAccessEnabled(boolean enabled, String inviteCode) {
        if (inviteCode != null) {
            if (inviteCode.isBlank()) {
                throw new InvalidArgumentException("inviteCode must not be blank");
            }
            if (!enabled) {
                throw new InvalidArgumentException("An invite code can only be used when enabling remote access");
            }
        }
        // Any operator action supersedes an attempt still in flight, and its explanation.
        abandonInvite();

        systemSettingsDao.setSetting("RemoteAccessEnabled", String.valueOf(enabled));
        if (enabled) {
            reconnectAttempt = 0;
            reconnectBackoff = FAST_BACKOFF_SECONDS;
            consecutiveUnreachable = 0;
            if (inviteCode != null) {
                // startTunnel() is a no-op against a live client, so the current connection has to go.
                stopTunnel();
                displacedCredential = credential;
                credential = null;
                systemSettingsDao.setSetting("RemoteAccessStatusMessage", "");
                logger.info("Enrolling with an invite code{}", displacedCredential != null
                    ? "; setting aside the credential for serverId=" + displacedCredential.serverId() : "");
                startTunnel(inviteCode.trim(), true);
            } else {
                startTunnel();
            }
        } else {
            stopTunnel();
            // The message described a failure to connect; with remote access off there is nothing to
            // connect, so it would otherwise outlive the situation it describes — including a reload.
            systemSettingsDao.setSetting("RemoteAccessStatusMessage", "");
        }
        emitConfigUpdate();
    }

    private void emitConfigUpdate() {
        RemoteAccessConfig config = getConfig();
        logger.debug("Emitting config update to subscribers: enabled={}, isConnected={}", config.enabled(), config.isConnected());
        Sinks.EmitResult result = configSink.tryEmitNext(config);
        if (result.isFailure()) {
            logger.debug("Config update emit result: {} (no active subscribers is normal)", result);
        }
    }

    public Flux<RemoteAccessConfig> configUpdates() {
        return configSink.asFlux();
    }

    void setTunnelEndpointForTesting(String endpoint) {
        this.tunnelEndpoint = endpoint;
    }

    public boolean isTunnelConnected() {
        return tunnelClient != null && tunnelClient.isOpen();
    }

    public RemoteAccessConfig getConfig() {
        boolean enabled = "true".equals(systemSettingsDao.getSetting("RemoteAccessEnabled").orElse("false"));
        String lastConnected = systemSettingsDao.getSetting("RemoteAccessLastConnected")
                .filter(s -> !s.isBlank())
                .orElse(null);
        String serverId = credential != null ? credential.serverId() : null;
        String statusMessage = systemSettingsDao.getSetting("RemoteAccessStatusMessage")
                .filter(s -> !s.isBlank())
                .orElse(null);
        return new RemoteAccessConfig(serverId, enabled, lastConnected, isTunnelConnected(), statusMessage);
    }

    public RemoteAccessConfig reenroll() {
        logger.info("Re-enrollment requested: clearing credential and status message");
        stopTunnel();
        abandonInvite();
        credential = null;
        systemSettingsDao.setSetting("RemoteAccessCredential", "");
        systemSettingsDao.setSetting("RemoteAccessStatusMessage", "");
        if ("true".equals(systemSettingsDao.getSetting("RemoteAccessEnabled").orElse("false"))) {
            reconnectAttempt = 0;
            startTunnel();
        }
        emitConfigUpdate();
        return getConfig();
    }

    @PreDestroy
    public void destroy() {
        stopTunnel();
    }
}
