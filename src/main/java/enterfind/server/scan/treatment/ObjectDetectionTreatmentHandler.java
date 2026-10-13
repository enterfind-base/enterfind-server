package enterfind.server.scan.treatment;

import static enterfind.server.model.FieldNameConstants.FIELD_OBJECT_DETECTED;
import static enterfind.server.model.FieldNameConstants.FIELD_OBJECT_DETECTION_CONFIDENCE;
import static enterfind.server.model.FieldNameConstants.FIELD_TREATMENT_TYPE;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.djl.ModelException;
import ai.djl.inference.Predictor;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.modality.cv.output.DetectedObjects;
import ai.djl.modality.cv.translator.YoloV5Translator;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;

/**
 * Treatment handler that performs object detection on images using DJL with YOLO model.
 */

import org.springframework.stereotype.Component;

import enterfind.server.dao.UserSettingsDao;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.UserSettingName;
import enterfind.server.scan.DataProviderInterface;
import enterfind.server.scan.Item;
import enterfind.server.scan.TreatmentOutput;
import enterfind.server.scan.TreatmentResult;
import enterfind.server.util.ResizableSemaphore;

import jakarta.annotation.PostConstruct;

@Component
public class ObjectDetectionTreatmentHandler implements TreatmentHandler {

    private static final Logger logger = LoggerFactory.getLogger(ObjectDetectionTreatmentHandler.class);

    // YOLOX-tiny on ONNX Runtime. Provenance: Megvii, Apache-2.0 weights, trained on COCO
    // (CC BY 4.0 annotations). Official export from Megvii's own release. ONNX Runtime is used
    // instead of PyTorch because libtorch's aarch64 build crashes (SIGILL) on pre-ARMv8.1 CPUs.
    private static final String YOLOX_MODEL_URL =
        "https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_tiny.onnx";
    /**
     * SHA-256 of the YOLOX-tiny weights. The model cache is keyed on this rather than on a fixed
     * model name — see {@code FaceDetectionTreatmentHandler.DETECTION_MODEL_SHA256} for why a
     * hardcoded URL is not enough on its own. Verified 2026-07-27.
     */
    private static final String YOLOX_MODEL_SHA256 =
        "427cc366d34e27ff7a03e2899b5e3671425c262ea2291f88bb942bc1cc70b0f7";
    // Classpath resource: 80 COCO class names, byte-identical (same index) to the previous
    // ssd_300_resnet50 classes.txt, so existing item_treatment_results rows stay valid.
    private static final String COCO_SYNSET_RESOURCE = "/models/yolox_coco_classes.txt";

    // Cached model instance (loaded once and reused across all image processing).
    // Instance state: this handler is a Spring singleton, so there is exactly one model per bean
    // per application context. Keeping it off static fields means separate contexts (notably in
    // tests) never share native model handles or the hook/lock guarding them.
    private volatile ZooModel<Image, DetectedObjects> cachedModel = null;
    private final Object modelLock = new Object();

    // Guards model lifecycle: read lock held during every inference; write lock acquired
    // by closeModel() so it cannot free native resources while predict() is in-flight.
    private final ReentrantReadWriteLock modelUsageLock = new ReentrantReadWriteLock();

    // @VisibleForTesting: called after the model reference is acquired but before newPredictor().
    // Allows tests to inject a concurrent closeModel() call in the exact race window.
    // Must be null in production; package-private for test access within this package.
    volatile Runnable inferenceStartHook = null;

    // @VisibleForTesting: coordinate callers around concurrency-slot acquisition without loading a model.
    volatile Runnable beforeSemaphoreAcquireHook = null;
    volatile Runnable semaphoreAcquiredHook = null;

    // Track last access time for idle timeout (in milliseconds)
    private volatile long modelLastAccess = 0;
    // Idle timeout: 30 minutes of inactivity before unloading model. Instance field (not a
    // constant) so tests can shorten it via setIdleTimeoutMsForTest() rather than waiting.
    private static final long DEFAULT_MODEL_IDLE_TIMEOUT_MS = 30 * 60 * 1000;
    private volatile long idleTimeoutMs = DEFAULT_MODEL_IDLE_TIMEOUT_MS;
    // How long a best-effort unload waits for in-flight inferences before giving up
    private static final long UNLOAD_LOCK_TIMEOUT_MS = 5_000;

    private final ResizableSemaphore semaphore = new ResizableSemaphore(2);

    private final UserSettingsDao userSettingsDao;

    public ObjectDetectionTreatmentHandler(UserSettingsDao userSettingsDao) {
        this.userSettingsDao = userSettingsDao;
    }

    @PostConstruct
    void registerSettingObservers() {
        updateSemaphoreFromValue(userSettingsDao.getSetting(UserSettingName.CONCURRENT_OBJECT_DETECTION_HANDLERS).orElse("2"));
        userSettingsDao.addSettingObserver(UserSettingName.CONCURRENT_OBJECT_DETECTION_HANDLERS, this::updateSemaphoreFromValue);
    }

    private void updateSemaphoreFromValue(String value) {
        int limit = 2;
        try {
            limit = Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            logger.warn("Invalid ConcurrentObjectDetectionHandlers value: {}", value);
        }
        semaphore.resize(limit);
        logger.info("Object detection concurrency limit set to {}", limit);
    }

    /**
     * Get the cached model instance, loading it if necessary.
     * Thread-safe lazy initialization using double-checked locking.
     *
     * @return the cached model instance
     * @throws ModelException if model loading fails
     * @throws java.io.IOException if model file cannot be read
     */
    private ZooModel<Image, DetectedObjects> getModel() throws ModelException, java.io.IOException {
        if (cachedModel == null) {
            // Must happen before ModelZoo is touched, and outside modelLock: DJL's zoo bootstrap is
            // global, so it has to be serialized against the other handlers, not just against us.
            DjlBootstrap.ensureInitialized();
            synchronized (modelLock) {
                if (cachedModel == null) {
                    logger.info("Loading object detection model (one-time initialization)...");
                    // The YOLOX path resolves at runtime (the .onnx is downloaded on first use),
                    // so the Criteria is built here rather than in a static field.
                    Path modelDir = TreatmentUtils.ensureModelCachedByHash(YOLOX_MODEL_URL, YOLOX_MODEL_SHA256);
                    Criteria<Image, DetectedObjects> criteria = Criteria.builder()
                            .setTypes(Image.class, DetectedObjects.class)
                            .optModelPath(modelDir)
                            .optModelName(TreatmentUtils.HASH_CACHED_MODEL_NAME)
                            .optEngine("OnnxRuntime")
                            // YoloXTranslator does its own pure-Java pre/post-processing and NMS
                            // (no NDArray-op fallback engine); the builder only supplies the class
                            // synset and the confidence threshold it reads.
                            .optTranslator(new YoloXTranslator(
                                    YoloV5Translator.builder()
                                            .optSynset(loadCocoSynset())
                                            .optThreshold(0.25f)))
                            .build();
                    cachedModel = ModelZoo.loadModel(criteria);
                    logger.info("Object detection model loaded successfully");
                }
                modelLastAccess = System.currentTimeMillis();
            }
        } else {
            modelLastAccess = System.currentTimeMillis();
        }
        return cachedModel;
    }

    /**
     * Load the 80 COCO class names bundled as a classpath resource. A raw {@code .onnx} carries
     * no synset, so the translator must be handed the class list explicitly; without it detections
     * come back with numeric class ids instead of names.
     */
    private static List<String> loadCocoSynset() throws java.io.IOException {
        try (InputStream in = ObjectDetectionTreatmentHandler.class.getResourceAsStream(COCO_SYNSET_RESOURCE)) {
            if (in == null) {
                throw new java.io.IOException("Missing COCO synset resource on classpath: " + COCO_SYNSET_RESOURCE);
            }
            List<String> classes = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String name = line.strip();
                if (!name.isEmpty()) {
                    classes.add(name);
                }
            }
            return classes;
        }
    }

    @Override
    public boolean canProcess(Item item) {
        if (!TreatmentUtils.isSupportedImageExtension(TreatmentUtils.getFileExtension(item.getName()))) {
            return false;
        }
        Long sizeBytes = item.getSizeBytes();
        if (sizeBytes != null && sizeBytes > HandlerLimits.MAX_IMAGE_SIZE_BYTES) {
            logger.info("Skipping object detection for {}: file size {}MB exceeds limit of {}MB",
                item.getFullPath(), sizeBytes / (1024 * 1024), HandlerLimits.MAX_IMAGE_SIZE_BYTES / (1024 * 1024));
            return false;
        }
        return true;
    }

    @Override
    public TreatmentOutput apply(Item item, DataProviderInterface dataProvider, DataSourceInterface dataSource, String treatmentName) {
        return TreatmentOutput.of(applyResults(item, dataProvider, dataSource, treatmentName));
    }

    private List<TreatmentResult> applyResults(Item item, DataProviderInterface dataProvider, DataSourceInterface dataSource, String treatmentName) {
        try {
            Runnable beforeAcquireHook = beforeSemaphoreAcquireHook;
            if (beforeAcquireHook != null) {
                beforeAcquireHook.run();
            }
            semaphore.acquire();
            try {
                Runnable acquiredHook = semaphoreAcquiredHook;
                if (acquiredHook != null) {
                    acquiredHook.run();
                }
                List<TreatmentResult> results = new ArrayList<>();
                String extension = TreatmentUtils.getFileExtension(item.getName());
                if (TreatmentUtils.isSupportedImageExtension(extension)) {
                    results.addAll(processImageFile(item, dataProvider, treatmentName));
                } else {
                    logger.info("Object detection treatment skipped for unsupported file type: {}", item.getName());
                }
                return results;
            } finally {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Object detection handler interrupted while waiting for concurrency slot", e);
            throw new IllegalStateException("Object detection interrupted while waiting for concurrency slot", e);
        }
    }

    private List<TreatmentResult> processImageFile(Item item, DataProviderInterface dataProvider, String treatmentName) {
        List<TreatmentResult> results = new ArrayList<>();

        // Add treatment type identifier
        results.add(new TreatmentResult(FIELD_TREATMENT_TYPE, treatmentName));

        Path tempFile = null;

        try (InputStream inputStream = dataProvider.getInputStream(item)) {
            if (inputStream == null) {
                logger.warn("Unable to get input stream for object detection on item: {}", item.getFullPath());
                throw new IllegalStateException("Unable to get input stream for object detection: "
                    + item.getFullPath());
            }

            // Create temporary file for image processing
            tempFile = Files.createTempFile("obj-detect-", "-" + item.getName());
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);

            // Load image using DJL
            BufferedImage bufferedImage = ImageIO.read(tempFile.toFile());
            if (bufferedImage == null) {
                logger.warn("Unable to read image file: {}", item.getFullPath());
                throw new IllegalStateException("Unable to read image file for object detection: "
                    + item.getFullPath());
            }

            Image image = ImageFactory.getInstance().fromImage(bufferedImage);

            // Release native image buffers immediately to prevent memory leak
            bufferedImage.flush();

            // Warm the cache before taking the read lock. A cold load is slow (native init, and
            // potentially a download); holding the read lock across it would stall closeModel()
            // and, because ReentrantReadWriteLock blocks new readers once a writer is queued,
            // every other inference thread behind it.
            getModel();

            // Read lock: held only for the model-use window, so closeModel() (write lock) cannot
            // free native DJL resources while predict() is executing.
            modelUsageLock.readLock().lock();
            try {
                // Re-read under the lock: this is the reference we are guaranteed to hold open.
                // Normally a cache hit; reloads only if closeModel() ran since the warm-up above.
                ZooModel<Image, DetectedObjects> model = getModel();

                // Invoke test hook (null in production) to allow race-condition tests to
                // pause here while a concurrent closeModel() call is injected.
                Runnable hook = inferenceStartHook;
                if (hook != null) {
                    try {
                        hook.run();
                    } catch (Exception e) {
                        logger.debug("inferenceStartHook threw unexpectedly", e);
                    }
                }

                try (Predictor<Image, DetectedObjects> predictor = model.newPredictor()) {

                    // Perform object detection
                    DetectedObjects detectedObjects = predictor.predict(image);

                    // Process detection results
                    if (!detectedObjects.items().isEmpty()) {
                        for (var detectedObject : detectedObjects.items()) {
                            String className = detectedObject.getClassName();
                            double probability = detectedObject.getProbability();

                            // Create result for object name
                            results.add(new TreatmentResult(FIELD_OBJECT_DETECTED, className));

                            // Create result for confidence score
                            results.add(new TreatmentResult(FIELD_OBJECT_DETECTION_CONFIDENCE, String.format("%.4f", probability)));
                        }

                        logger.info("Object detection found {} objects in image: {}",
                                detectedObjects.items().size(), item.getName());
                    } else {
                        logger.info("No objects detected in image: {}", item.getName());
                    }

                } catch (TranslateException e) {
                    logger.error("Error during object detection inference: {}", e.getMessage());
                    throw new IllegalStateException("Object detection inference failed for " + item.getFullPath(), e);
                }
            } finally {
                modelUsageLock.readLock().unlock();
            }

        } catch (Exception e) {
            logger.error("Error performing object detection on file {}: {}", item.getFullPath(), e.getMessage(), e);
            throw new IllegalStateException("Object detection failed for " + item.getFullPath(), e);
        } finally {
            // Clean up temp file
            TreatmentUtils.cleanupTempFile(tempFile);
        }

        return results;
    }

    /**
     * Close and unload the object detection model to free native memory.
     * The model will be lazy-loaded again if needed.
     */
    public void closeModel() {
        // Write lock: blocks until all in-flight inferences (read-lock holders) complete,
        // ensuring no predict() call is using the native model handle when we free it.
        modelUsageLock.writeLock().lock();
        try {
            closeModelHoldingWriteLock();
        } finally {
            modelUsageLock.writeLock().unlock();
        }
    }

    /**
     * Close the model, but give up if the write lock cannot be acquired within the timeout.
     *
     * <p>For callers that must not block indefinitely: an inference thread wedged in native code
     * holds the read lock forever, and an unbounded write-lock wait would take the caller's thread
     * down with it. Freeing the model is best-effort, so skipping is always safe.
     *
     * @return true if the model was closed (or was already closed), false on timeout
     */
    public boolean tryCloseModel(long timeoutMs) {
        try {
            if (!modelUsageLock.writeLock().tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
                logger.warn("Could not acquire object detection model write lock within {}ms; "
                        + "skipping unload. An inference may be stuck holding the read lock.", timeoutMs);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            closeModelHoldingWriteLock();
            return true;
        } finally {
            modelUsageLock.writeLock().unlock();
        }
    }

    private void closeModelHoldingWriteLock() {
        synchronized (modelLock) {
            if (cachedModel != null) {
                logger.info("Closing object detection model to free memory...");
                cachedModel.close();
                cachedModel = null;
                modelLastAccess = 0;
                logger.info("Object detection model closed");
            }
        }
    }

    /**
     * Check and unload model if it has been idle for longer than the idle timeout.
     * This is called periodically by a scheduled task to free memory when the model is not in use.
     */
    public void closeIdleModel() {
        if (cachedModel != null && modelLastAccess > 0) {
            long idleTime = System.currentTimeMillis() - modelLastAccess;
            if (idleTime > idleTimeoutMs) {
                logger.info("Object detection model idle for {} minutes, unloading...", idleTime / 60000);
                // Bounded: this runs on the shared scheduling thread, which also drives the
                // treatment scheduler and its stuck-thread governor.
                tryCloseModel(UNLOAD_LOCK_TIMEOUT_MS);
            }
        }
    }

    /**
     * Whether the native model is currently loaded. Read under the model monitor so it reflects a
     * completed load/close rather than a half-published reference.
     */
    boolean isModelLoaded() {
        synchronized (modelLock) {
            return cachedModel != null;
        }
    }

    /**
     * Shorten (or restore) the idle-unload timeout. Test-only seam so idle-unload behaviour can be
     * exercised without waiting the production 30 minutes.
     */
    void setIdleTimeoutMsForTest(long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
    }
}