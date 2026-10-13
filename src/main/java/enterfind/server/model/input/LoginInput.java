package enterfind.server.model.input;

/**
 * Input type for user login.
 */
public record LoginInput(
    String nickname,
    String totpCode,
    Boolean rememberMe
) {
    /**
     * Returns whether "remember me" is enabled, defaulting to false if null.
     */
    public boolean isRememberMe() {
        return rememberMe != null && rememberMe;
    }
}
