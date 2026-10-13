package enterfind.server.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Regression test for a Spring AI 2.0 / openai-java upgrade pitfall: {@link OpenAiChatModel.Builder#build()}
 * requires both a sync and an async openai-java client. If only a sync client is supplied via
 * {@code .openAiClient(...)}, the builder auto-creates a default async client from
 * {@link OpenAiChatOptions}; if that options object has no apiKey, building fails with
 * {@code IllegalStateException: At least one credential source must be specified}.
 * {@link AiProviderFactory#createOpenAiChatModel()} avoids this by setting apiKey/baseUrl on the
 * options and never building a client manually, so both clients are created consistently.
 * This test builds no client and makes no network call; it only verifies the builder succeeds.
 */
class OpenAiChatModelBuilderRegressionTest {

    @Test
    void buildsWithApiKeyOnOptionsAndNoManualClient() {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model("gpt-4o-mini")
                .apiKey("sk-fake-test-key-for-build-verification")
                .maxRetries(0)
                .build();

        assertDoesNotThrow(() -> OpenAiChatModel.builder()
                .options(options)
                .build());
    }
}
