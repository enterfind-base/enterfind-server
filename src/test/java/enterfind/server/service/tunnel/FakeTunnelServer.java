package enterfind.server.service.tunnel;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.WebSocketServer;

public class FakeTunnelServer extends WebSocketServer {

    /**
     * Which enrollment world this fixture is imitating. Both are exercised by the suite at once —
     * unlike the edge, where the mode is a deployed constant and only one branch can be live. The
     * server code is not supposed to be able to tell them apart, so both have to stay covered.
     */
    public enum Mode {
        /** Accepts anything and answers nothing: the original behaviour, for tests that predate this. */
        PASSIVE,
        /** Accepts an unauthenticated connection and hands back a credential, as open signup does. */
        ENROLL_ALLOWED,
        /**
         * Refuses at the handshake unless an unused invite code or an issued token is presented, as
         * INVITE_ONLY does. A code is redeemed on REGISTER for a credential of its own and is then dead.
         */
        INVITE_ONLY
    }

    private volatile Mode mode = Mode.PASSIVE;
    /** Unused invite code → the {serverId, token} it is redeemed for. */
    private final Map<String, String[]> inviteCodes = new ConcurrentHashMap<>();
    /** Issued token → its serverId: what an authed connection may present. */
    private final Map<String, String> licences = new ConcurrentHashMap<>();
    private volatile String lastPresented;
    private volatile String issuedServerId = "fake0123456789ab";
    private volatile String issuedToken = "efl_fake_token_for_tests";
    private volatile boolean serverIdTaken = false;

    private volatile Runnable onClientConnected;
    private volatile Consumer<String> onMessageReceived;
    private volatile Consumer<Integer> onClientClose;
    private volatile int maxFrameSize = 0; // 0 = no limit
    private volatile WebSocket currentConnection;

    public FakeTunnelServer(InetSocketAddress address) {
        super(address);
        setReuseAddr(true);
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        currentConnection = conn;
        if (onClientConnected != null) onClientConnected.run();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        if (onClientClose != null) onClientClose.accept(code);
    }

    /**
     * Refuses the upgrade when invite-only and the token is missing or wrong. Throwing here makes the
     * library answer with a non-101 response, which is the shape the deployed tunnel produces (it
     * sends 403 where this sends 404). Tests assert on "never opened, close code 1002" rather than on
     * the status number, so the difference does not matter — and nothing in the client may start
     * depending on it.
     */
    @Override
    public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(WebSocket conn, Draft draft,
            ClientHandshake request) throws InvalidDataException {
        ServerHandshakeBuilder builder = super.onWebsocketHandshakeReceivedAsServer(conn, draft, request);
        String auth = request.getFieldValue("Authorization");
        String presented = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : null;
        lastPresented = presented;
        if (mode == Mode.INVITE_ONLY) {
            if (presented == null
                    || !(inviteCodes.containsKey(presented) || licences.containsKey(presented))) {
                throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "not invited");
            }
            conn.setAttachment(presented);
        }
        return builder;
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        if (maxFrameSize > 0 && message.length() > maxFrameSize) {
            conn.close(CloseFrame.TOOBIG,
                "Max frame length of " + maxFrameSize + " has been exceeded.");
            return;
        }
        if (onMessageReceived != null) onMessageReceived.accept(message);

        if (mode != Mode.PASSIVE && message.contains("\"REGISTER\"")) {
            if (serverIdTaken) {
                // The real edge leaves the socket open after rejecting — the client closes it itself,
                // which is why a rejection cannot be recognised from the close code alone.
                conn.send("{\"type\":\"REGISTRATION_REJECTED\",\"reason\":\"SERVER_ID_TAKEN\"}");
            } else if (mode == Mode.INVITE_ONLY) {
                String presented = conn.getAttachment();
                String[] redeemed = inviteCodes.remove(presented);
                if (redeemed != null) {
                    licences.put(redeemed[1], redeemed[0]);
                    sendRegistered(conn, redeemed[0], redeemed[1]);
                } else if (licences.containsKey(presented)) {
                    sendRegistered(conn, licences.get(presented), presented);
                } else {
                    // Passed the handshake, but a racing connection redeemed the code first.
                    conn.send("{\"type\":\"REGISTRATION_REJECTED\",\"reason\":\"INVITE_REDEEMED\"}");
                }
            } else {
                sendRegistered(conn, issuedServerId, issuedToken);
            }
        }
    }

    private static void sendRegistered(WebSocket conn, String serverId, String token) {
        conn.send("{\"type\":\"REGISTERED\",\"serverId\":\"" + serverId
            + "\",\"token\":\"" + token + "\"}");
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {}

    @Override
    public void onStart() {}

    /** Open signup: any connection is accepted and handed this credential. */
    public void setEnrollmentAllowed(String serverId, String token) {
        this.mode = Mode.ENROLL_ALLOWED;
        this.issuedServerId = serverId;
        this.issuedToken = token;
    }

    /** Invite only, with one unused code that is redeemed for the given credential. */
    public void setInviteOnly(String inviteCode, String serverId, String token) {
        this.mode = Mode.INVITE_ONLY;
        addInviteCode(inviteCode, serverId, token);
    }

    /** Issues another invite code; credentials already issued stay valid. */
    public void addInviteCode(String inviteCode, String serverId, String token) {
        inviteCodes.put(inviteCode, new String[] {serverId, token});
    }

    /** The bearer value on the most recent handshake, or null when there was none. */
    public String getLastPresented() { return lastPresented; }

    /** Answer REGISTER with SERVER_ID_TAKEN, as the edge does when another tunnel holds the id. */
    public void setServerIdTaken(boolean taken) { this.serverIdTaken = taken; }

    public void setOnClientConnected(Runnable r) { this.onClientConnected = r; }
    public void setOnMessageReceived(Consumer<String> c) { this.onMessageReceived = c; }
    public void setOnClientClose(Consumer<Integer> c) { this.onClientClose = c; }
    public void setMaxFrameSize(int bytes) { this.maxFrameSize = bytes; }

    public void sendToClient(String message) {
        WebSocket conn = currentConnection;
        if (conn != null && conn.isOpen()) {
            conn.send(message);
        }
    }
}
