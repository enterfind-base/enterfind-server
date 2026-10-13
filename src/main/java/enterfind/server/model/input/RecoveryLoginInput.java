package enterfind.server.model.input;

/**
 * Input type for login using a recovery code.
 */
public record RecoveryLoginInput(
    String nickname,
    String recoveryCode
) {
}
