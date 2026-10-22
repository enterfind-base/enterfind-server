package enterfind.server.security;

/**
 * Outcome of turning a connector's Authorization header into a session token.
 */
public sealed interface ApiKeyExchange {

    /** No Authorization header. Kept apart from the other outcomes: Flow 2 answers it with a 401. */
    record Absent() implements ApiKeyExchange {}

    /** A header was sent, but the instance has no users, so there is nothing to authenticate. */
    record Ignored() implements ApiKeyExchange {}

    /** The key could not be exchanged. {@code reason} names the header, never its value. */
    record Failed(String reason) implements ApiKeyExchange {}

    /** The key was exchanged for a valid API session. */
    record Resolved(String sessionToken) implements ApiKeyExchange {}
}
