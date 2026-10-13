package enterfind.server.model.input;

import enterfind.server.model.UserLevel;

/**
 * Input type for updating a human user.
 */
public record UpdateHumanUserInput(
    String id,
    String nickname,
    UserLevel userLevel,
    Boolean isActive
) {
}
