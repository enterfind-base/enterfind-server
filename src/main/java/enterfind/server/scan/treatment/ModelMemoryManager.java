package enterfind.server.scan.treatment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled task that automatically unloads ML models after periods of inactivity
 * to free native memory (DJL/ONNX allocations outside JVM heap).
 * 
 * Models are lazy-loaded on first use and will be reloaded automatically if needed.
 * This helps prevent memory exhaustion on servers with limited RAM.
 */
@Component
public class ModelMemoryManager {

    private static final Logger logger = LoggerFactory.getLogger(ModelMemoryManager.class);

    private final FaceDetectionTreatmentHandler faceDetectionHandler;
    private final ObjectDetectionTreatmentHandler objectDetectionHandler;

    public ModelMemoryManager(FaceDetectionTreatmentHandler faceDetectionHandler,
                              ObjectDetectionTreatmentHandler objectDetectionHandler) {
        this.faceDetectionHandler = faceDetectionHandler;
        this.objectDetectionHandler = objectDetectionHandler;
    }

    /**
     * Check all ML models every 10 minutes and unload those that have been idle
     * for longer than their configured timeout (default: 30 minutes).
     *
     * This frees native memory (ONNX Runtime allocations) that is not managed
     * by the JVM garbage collector.
     */
    @Scheduled(fixedRate = 10 * 60 * 1000) // Every 10 minutes
    public void checkAndUnloadIdleModels() {
        logger.debug("Checking for idle ML models to unload...");

        try {
            faceDetectionHandler.closeIdleModels();
            objectDetectionHandler.closeIdleModel();
        } catch (Exception e) {
            logger.error("Error while checking idle models", e);
        }
    }
}
