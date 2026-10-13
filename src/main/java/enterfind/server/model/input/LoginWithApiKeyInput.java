package enterfind.server.model.input;

/**
 * Input for logging in with an API key.
 */
public record LoginWithApiKeyInput(
    String nickname,
    String keyValue
) {}
