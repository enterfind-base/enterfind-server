package enterfind.server.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

import enterfind.server.dao.SystemSettingsDao;
import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.model.AiProvider;
import enterfind.server.model.AiSettingName;

/**
 * Factory for creating AI provider ChatModel instances based on user settings.
 * Caches the chat model and recreates when settings change.
 */
@Component
public class AiProviderFactory {

    private static final Logger logger = LoggerFactory.getLogger(AiProviderFactory.class);

    private final SystemSettingsDao systemSettingsDao;

    // Cache for chat models (recreated when settings change)
    private volatile ChatModel cachedChatModel;
    private volatile String cachedProviderConfig;

    public AiProviderFactory(SystemSettingsDao systemSettingsDao) {
        this.systemSettingsDao = systemSettingsDao;
    }

    /**
     * Get the configured ChatModel based on current user settings.
     * Creates and caches the model if settings haven't changed.
     */
    public ChatModel getChatModel() {
        String currentConfig = buildConfigFingerprint();

        if (cachedChatModel == null || !currentConfig.equals(cachedProviderConfig)) {
            cachedChatModel = createChatModel();
            cachedProviderConfig = currentConfig;
            logger.info("Created new ChatModel for provider: {}", getConfiguredProvider());
        }

        return cachedChatModel;
    }

    /**
     * Get the currently configured AI provider.
     */
    public AiProvider getConfiguredProvider() {
        return systemSettingsDao.getSetting(AiSettingName.AI_PROVIDER)
                .map(value -> {
                    try {
                        return AiProvider.valueOf(value);
                    } catch (IllegalArgumentException e) {
                        logger.warn("Invalid AI provider: {}, defaulting to NONE", value);
                        return AiProvider.NONE;
                    }
                })
                .orElse(AiProvider.NONE);
    }

    /**
     * Check if any AI provider is properly configured.
     */
    public boolean isConfigured() {
        AiProvider provider = getConfiguredProvider();
        return switch (provider) {
            case NONE -> false;
            case OLLAMA -> isOllamaConfigured();
            case OPENAI -> isOpenAiConfigured();
            case ANTHROPIC -> isAnthropicConfigured();
        };
    }

    /**
     * Clear the cached chat model (call when settings change).
     */
    public void clearCache() {
        cachedChatModel = null;
        cachedProviderConfig = null;
        logger.info("Cleared ChatModel cache");
    }

    private ChatModel createChatModel() {
        AiProvider provider = getConfiguredProvider();

        return switch (provider) {
            case NONE -> throw new InvalidArgumentException("No AI provider configured");
            case OLLAMA -> createOllamaChatModel();
            case OPENAI -> createOpenAiChatModel();
            case ANTHROPIC -> createAnthropicChatModel();
        };
    }

    private ChatModel createOllamaChatModel() {
        String baseUrl = systemSettingsDao.getSetting(AiSettingName.OLLAMA_API_URL)
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new InvalidArgumentException("Ollama URL not configured"));
        String model = systemSettingsDao.getSetting(AiSettingName.OLLAMA_MODEL_NAME)
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new InvalidArgumentException("Ollama model not configured"));

        logger.info("Creating Ollama ChatModel: baseUrl={}, model={}", baseUrl, model);

        OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).build();
        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(model)
                .build();

        return OllamaChatModel.builder()
                .ollamaApi(api)
                .options(options)
                // Fail fast: 0 retries == single attempt (matches the previous maxAttempts(1)).
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                .build();
    }

    private ChatModel createOpenAiChatModel() {
        String apiKey = systemSettingsDao.getSetting(AiSettingName.OPENAI_API_KEY)
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new InvalidArgumentException("OpenAI API key not configured"));
        String model = systemSettingsDao.getSetting(AiSettingName.OPENAI_MODEL_NAME)
                .filter(s -> !s.isBlank())
                .orElse("gpt-4o-mini");

        // Support custom base URL for OpenAI-compatible APIs
        String baseUrl = systemSettingsDao.getSetting(AiSettingName.OPENAI_BASE_URL)
                .filter(s -> !s.isBlank())
                .orElse(null);

        logger.info("Creating OpenAI ChatModel: model={}, customBaseUrl={}", model, baseUrl != null);

        // Spring AI 2.0 builds both a sync and an async openai-java client internally from
        // OpenAiChatOptions when no clients are supplied explicitly. Passing only a manually
        // built sync client here (via .openAiClient()) leaves the async client to be
        // auto-created with no apiKey source, which fails fast with an IllegalStateException.
        // So the apiKey/baseUrl/maxRetries must be set on the options instead, letting
        // OpenAiChatModel.Builder build both clients consistently.
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(model)
                .apiKey(apiKey)
                .maxRetries(0);
        if (baseUrl != null) {
            optionsBuilder.baseUrl(baseUrl);
        }

        return OpenAiChatModel.builder()
                .options(optionsBuilder.build())
                .build();
    }

    private ChatModel createAnthropicChatModel() {
        String apiKey = systemSettingsDao.getSetting(AiSettingName.ANTHROPIC_API_KEY)
                .filter(s -> !s.isBlank())
                .orElseThrow(() -> new InvalidArgumentException("Anthropic API key not configured"));
        String model = systemSettingsDao.getSetting(AiSettingName.ANTHROPIC_MODEL_NAME)
                .filter(s -> !s.isBlank())
                .orElse("claude-sonnet-4-20250514");

        logger.info("Creating Anthropic ChatModel: model={}", model);

        // Spring AI 2.0: AnthropicApi was removed in favor of the official anthropic-java
        // SDK; the client is built explicitly and passed to the model builder. Retries are
        // configured on the SDK client (maxRetries(0) == single attempt).
        AnthropicClient client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .maxRetries(0)
                .build();

        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .model(model)
                .build();

        return AnthropicChatModel.builder()
                .anthropicClient(client)
                .options(options)
                .build();
    }

    // Configuration check methods
    private boolean isOllamaConfigured() {
        return systemSettingsDao.getSetting(AiSettingName.OLLAMA_API_URL)
                        .filter(s -> !s.isBlank()).isPresent()
            && systemSettingsDao.getSetting(AiSettingName.OLLAMA_MODEL_NAME)
                        .filter(s -> !s.isBlank()).isPresent();
    }

    private boolean isOpenAiConfigured() {
        return systemSettingsDao.getSetting(AiSettingName.OPENAI_API_KEY)
                .filter(s -> !s.isBlank()).isPresent();
    }

    private boolean isAnthropicConfigured() {
        return systemSettingsDao.getSetting(AiSettingName.ANTHROPIC_API_KEY)
                .filter(s -> !s.isBlank()).isPresent();
    }

    private String buildConfigFingerprint() {
        // Build a fingerprint of all AI settings for cache invalidation
        return String.join("|",
                systemSettingsDao.getSetting(AiSettingName.AI_PROVIDER).orElse(""),
                systemSettingsDao.getSetting(AiSettingName.OLLAMA_API_URL).orElse(""),
                systemSettingsDao.getSetting(AiSettingName.OLLAMA_MODEL_NAME).orElse(""),
                systemSettingsDao.getSetting(AiSettingName.OPENAI_API_KEY).orElse(""),
                systemSettingsDao.getSetting(AiSettingName.OPENAI_MODEL_NAME).orElse(""),
                systemSettingsDao.getSetting(AiSettingName.OPENAI_BASE_URL).orElse(""),
                systemSettingsDao.getSetting(AiSettingName.ANTHROPIC_API_KEY).orElse(""),
                systemSettingsDao.getSetting(AiSettingName.ANTHROPIC_MODEL_NAME).orElse(""),
                systemSettingsDao.getSetting(AiSettingName.ANTHROPIC_BASE_URL).orElse("")
        );
    }
}
