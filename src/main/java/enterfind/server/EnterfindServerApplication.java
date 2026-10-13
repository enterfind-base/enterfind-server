package enterfind.server;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.websocket.autoconfigure.servlet.WebSocketMessagingAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

import enterfind.server.model.DataSourceType;
import enterfind.server.scan.treatment.FaceDetectionTreatmentHandler;
import enterfind.server.scan.treatment.ObjectDetectionTreatmentHandler;

@SpringBootApplication(exclude = {
    WebSocketMessagingAutoConfiguration.class,
    org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration.class,
    org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration.class,
    org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration.class,
    org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration.class,
    org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration.class,
    org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration.class,
    org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration.class,
    org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration.class,
    org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration.class
})
@EnableScheduling
public class EnterfindServerApplication {

	private static final Logger logger = LoggerFactory.getLogger(EnterfindServerApplication.class);

	// Kept well under the container runtime's stop grace period so data provider cleanup still runs.
	private static final long SHUTDOWN_MODEL_CLOSE_TIMEOUT_MS = 3_000;

	public static void main(String[] args) {
		ConfigurableApplicationContext context = SpringApplication.run(EnterfindServerApplication.class, args);
		String[] activeProfiles = context.getEnvironment().getActiveProfiles();
		String[] defaultProfiles = context.getEnvironment().getDefaultProfiles();
		
		if (activeProfiles.length > 0) {
			logger.info("Enterfind Server is running with active profiles: {}", Arrays.toString(activeProfiles));
		} else {
			logger.info("Enterfind Server is running with default profiles: {}", Arrays.toString(defaultProfiles));
		}
		
		// Register shutdown hook to cleanup data providers and ML models on JVM exit
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			logger.info("JVM shutting down, closing all data providers and ML models");
			try {
				// Close ML models to free native memory. Bounded: an inference stuck in native
				// code holds the read lock forever, and blocking here would hang shutdown until
				// the container runtime SIGKILLs us, skipping the data provider cleanup below.
				// The handlers own their model lifecycle as beans, so resolve them from the context
				// rather than reaching through statics; this preserves the bounded, ordered cleanup.
				context.getBean(FaceDetectionTreatmentHandler.class).tryCloseAllModels(SHUTDOWN_MODEL_CLOSE_TIMEOUT_MS);
				context.getBean(ObjectDetectionTreatmentHandler.class).tryCloseModel(SHUTDOWN_MODEL_CLOSE_TIMEOUT_MS);

				// Close data providers
				DataSourceType.shutdownAllProviders();
				logger.info("All data providers and ML models closed successfully");
			} catch (Exception e) {
				logger.error("Error occurred during shutdown cleanup", e);
			}
		}, "ShutdownCleanupThread"));
	}

}
