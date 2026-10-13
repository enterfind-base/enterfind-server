package enterfind.server.model;

/**
 * Supported AI providers for AI Helper functionality.
 */
public enum AiProvider {
    /**
     * None - No AI provider configured/disabled.
     */
    NONE("None", "AI features disabled"),

    /**
     * Ollama - Self-hosted, privacy-focused AI models.
     * Runs locally or on private infrastructure.
     */
    OLLAMA("Ollama", "Self-hosted, privacy-focused"),

    /**
     * OpenAI - Cloud-based AI models (GPT-4, GPT-4o, etc.).
     * Requires API key.
     */
    OPENAI("OpenAI", "GPT-4, GPT-4o models"),

    /**
     * Anthropic - Cloud-based Claude models.
     * Requires API key.
     */
    ANTHROPIC("Anthropic", "Claude models");

    private final String displayName;
    private final String description;

    AiProvider(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Check if this provider requires an API key.
     */
    public boolean requiresApiKey() {
        return this != OLLAMA && this != NONE;
    }

    /**
     * Check if this provider is cloud-based (sends data externally).
     */
    public boolean isCloudBased() {
        return this != OLLAMA && this != NONE;
    }
}
