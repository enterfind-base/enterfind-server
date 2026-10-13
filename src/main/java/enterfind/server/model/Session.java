package enterfind.server.model;

import java.time.LocalDateTime;

/**
 * Represents an authentication session.
 */
public record Session(
    String token,
    String userId,
    LocalDateTime dateCreated,
    LocalDateTime dateExpires,
    boolean isRememberMe,
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
