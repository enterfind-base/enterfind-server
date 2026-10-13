package enterfind.server.model.input;

import enterfind.server.model.UserLevel;

/**
 * Input for creating an API user.
 */
public record CreateApiUserInput(
    String nickname,
    UserLevel userLevel,
    int expirationDays  // 1, 7, 30, 120, 365, 730
) {}
