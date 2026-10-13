package enterfind.server.model;

import java.time.LocalDateTime;

/**
 * Represents an API authentication session.
 */
public record ApiSession(
    String token,
    String apiUserId,
    LocalDateTime dateCreated,
    LocalDateTime dateExpires,
    boolean isValid
) {
    /**
     * Checks if this session has expired.
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(dateExpires);
    }

    /**
     * Checks if this session is currently valid (not expired and not invalidated).
     */
    public boolean isCurrentlyValid() {
        return isValid && !isExpired();
    }
}
