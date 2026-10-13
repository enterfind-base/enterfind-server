package enterfind.server.model;

import java.util.Set;

public enum AiSettingName {

    AI_PROVIDER("AiProvider"),

    OLLAMA_API_URL("OllamaApiUrl"),
    OLLAMA_MODEL_NAME("OllamaModelName"),
    OLLAMA_API_CODE("OllamaApiCode"),

    OPENAI_API_KEY("OpenAiApiKey"),
    OPENAI_MODEL_NAME("OpenAiModelName"),
    OPENAI_BASE_URL("OpenAiBaseUrl"),

    ANTHROPIC_API_KEY("AnthropicApiKey"),
    ANTHROPIC_MODEL_NAME("AnthropicModelName"),
    ANTHROPIC_BASE_URL("AnthropicBaseUrl");

    public static final Set<AiSettingName> SENSITIVE = Set.of(
        OLLAMA_API_CODE,
        OPENAI_API_KEY,
        ANTHROPIC_API_KEY
    );

    private final String dbName;

    AiSettingName(String dbName) {
        this.dbName = dbName;
    }

    public static AiSettingName fromString(String name) {
        for (AiSettingName s : values()) {
            if (s.dbName.equalsIgnoreCase(name) || s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown AI setting name: " + name);
    }

    @Override
    public String toString() {
        return dbName;
    }
}
