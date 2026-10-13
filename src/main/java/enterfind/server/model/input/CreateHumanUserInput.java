package enterfind.server.model.input;

import enterfind.server.model.UserLevel;

/**
 * Input type for creating a human user.
 */
public record CreateHumanUserInput(
    String nickname,
    UserLevel userLevel
) {
}
