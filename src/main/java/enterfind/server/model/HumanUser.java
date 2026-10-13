package enterfind.server.model;

import java.time.LocalDateTime;

/**
 * Represents a human user for authentication.
 */
public record HumanUser(
    String id,
    String nickname,
    String totpSecret,
    String recoveryCodes,
    UserLevel userLevel,
    LocalDateTime dateCreated,
    LocalDateTime dateLastLogin,
    boolean isActive
) {
    /**
     * Creates a copy with updated last login time.
     */
    public HumanUser withLastLogin(LocalDateTime lastLogin) {
        return new HumanUser(id, nickname, totpSecret, recoveryCodes, userLevel, dateCreated, lastLogin, isActive);
    }

    /**
     * Creates a copy with updated recovery codes.
     */
    public HumanUser withRecoveryCodes(String newRecoveryCodes) {
        return new HumanUser(id, nickname, totpSecret, newRecoveryCodes, userLevel, dateCreated, dateLastLogin, isActive);
    }
}
