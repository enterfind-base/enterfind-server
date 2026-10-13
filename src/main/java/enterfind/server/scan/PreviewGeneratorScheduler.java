package enterfind.server.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import enterfind.server.config.ApplianceMode;

/**
 * Scheduler for preview generation in non-alpha environments.
 * In alpha, Scanner calls PreviewGenerator.generatePreviews() directly after scanning.
 */
@Configuration
@EnableScheduling
@Profile("!alpha")
public class PreviewGeneratorScheduler {
    
    private static final Logger logger = LoggerFactory.getLogger(PreviewGeneratorScheduler.class);
    
    private final PreviewGenerator previewGenerator;
    private final ApplianceMode applianceMode;
    
    public PreviewGeneratorScheduler(PreviewGenerator previewGenerator, ApplianceMode applianceMode) {
        this.previewGenerator = previewGenerator;
        this.applianceMode = applianceMode;
    }
    
    /**
     * Process previews every 5 seconds
     */
    @Scheduled(fixedDelay = 5000)
    public void processPreviews() {
        if (applianceMode.isEnabled()) {
            logger.warn("Appliance mode: skipping scheduled preview generation");
            return;
        }
        try {
            int processed = previewGenerator.generatePreviews();
            if (processed > 0) {
                logger.info("Generated {} previews", processed);
            }
        } catch (Exception e) {
            logger.error("Error in preview generation scheduler", e);
        }
    }
}
