package enterfind.server.model;

import java.time.LocalDateTime;

/**
 * Represents an API user for programmatic authentication.
 */
public record ApiUser(
    String id,
    String nickname,
    String keyValue,           // encrypted, format: efk_...
    UserLevel userLevel,
    String createdByUserId,    // references HUMAN_USERS.id
    LocalDateTime dateCreated,
    LocalDateTime dateExpires,
    LocalDateTime dateLastUsed,
    boolean isActive
) {
    /**
     * Checks if this API key has expired.
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(dateExpires);
    }

    /**
     * Checks if this API key is currently valid (not expired and active).
     */
    public boolean isCurrentlyValid() {
        return isActive && !isExpired();
    }

    /**
     * Returns preview of key value (last 4 characters) for display.
     */
    public String getKeyValuePreview() {
        if (keyValue == null || keyValue.length() < 4) {
            return "****";
        }
        return "..." + keyValue.substring(keyValue.length() - 4);
    }

    /**
     * Creates a copy with updated last used time.
     */
    public ApiUser withLastUsed(LocalDateTime lastUsed) {
        return new ApiUser(id, nickname, keyValue, userLevel, createdByUserId,
            dateCreated, dateExpires, lastUsed, isActive);
    }
}
