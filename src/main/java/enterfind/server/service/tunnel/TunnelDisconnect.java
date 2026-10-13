package enterfind.server.service.tunnel;

/**
 * Why a tunnel connection ended, classified from what was observed on the wire — never from which
 * enrollment mode the edge is running. The client cannot tell an uninvited instance from a revoked
 * one: the authorizer answers both with the same 403, so both arrive here as {@link Kind#REFUSED}.
 *
 * <p>The close code alone is not enough. A {@code REGISTRATION_REJECTED} message leaves the socket
 * open at the edge and {@link TunnelClient} closes it itself, which is indistinguishable from a
 * clean shutdown — so that case is recognised by the message, not the code.
 */
public record TunnelDisconnect(Kind kind, String detail) {

    public enum Kind {
        /** An HTTP response arrived that was not 101 — the edge refused us before the socket opened. */
        REFUSED,
        /** The socket never opened and no HTTP response arrived: DNS, TCP or TLS failure. */
        UNREACHABLE,
        /** The socket opened, but the edge rejected registration. {@code detail} carries its reason. */
        REJECTED,
        /** An ordinary disconnect of a connection that had been working. */
        NORMAL
    }

    public static TunnelDisconnect normal() {
        return new TunnelDisconnect(Kind.NORMAL, null);
    }

    public boolean isNormal() {
        return kind == Kind.NORMAL;
    }
}
