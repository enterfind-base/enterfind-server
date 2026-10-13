package enterfind.server.scan.treatment;

import static enterfind.server.model.FieldNameConstants.FIELD_DETECTED_FACE_CONFIDENCE;
import static enterfind.server.model.FieldNameConstants.FIELD_FACE_COUNT;
import static enterfind.server.model.FieldNameConstants.FIELD_OBJECT_DETECTED;
import static enterfind.server.model.FieldNameConstants.FIELD_OBJECT_DETECTION_CONFIDENCE;
import static enterfind.server.model.FieldNameConstants.FIELD_TREATMENT_TYPE;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.djl.MalformedModelException;
import ai.djl.inference.Predictor;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelNotFoundException;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import enterfind.server.dao.UserSettingsDao;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.FaceDetection;
import enterfind.server.model.UserSettingName;
import enterfind.server.scan.DataProviderInterface;
import enterfind.server.scan.Item;
import enterfind.server.scan.TreatmentOutput;
import enterfind.server.scan.TreatmentResult;
import enterfind.server.util.ResizableSemaphore;
import jakarta.annotation.PostConstruct;

/**
 * Treatment handler that detects faces in images using the YuNet ONNX model.
 *
 * <p>Per face it returns bounding box, 5-point landmarks and confidence for FACE_DETECTIONS, and
 * emits {@code objectdetected=face}, {@code facecount}, {@code objectdetectionconfidence} and
 * {@code detectedfaceconfidence} treatment results so faces are searchable and facetable.
 *
 * <p>It writes nothing itself. Geometry and {@code facecount} address the same faces, so they are
 * handed back together and committed in one transaction by TreatmentProcessor; a handler that wrote
 * its own rows would publish them before the count that makes them reachable.
 *
 * <p>Detection only — no embedding, no grouping, no identities. Those belong to the separate
 * FACE_RECOGNITION treatment, because every high-accuracy face embedding model available to us is
 * trained on data its author restricts to non-commercial research, so recognition runs only behind
 * an operator-chosen model. See {@code docs/treatment-face-recognition.md}.
 *
 * <p>Landmarks are persisted although nothing reads them here: FACE_RECOGNITION re-aligns from them
 * rather than re-running detection over the whole corpus.
 */
@Component
public class FaceDetectionTreatmentHandler implements TreatmentHandler {

    private static final Logger logger = LoggerFactory.getLogger(FaceDetectionTreatmentHandler.class);

    // YuNet on ONNX Runtime. Provenance: Shiqi Yu, MIT licence granted by the author himself,
    // trained on WIDER Face. OpenCV embeds the same
    // model as cv::FaceDetectorYN. This replaces InsightFace's SCRFD det_2.5g, whose author
    // restricts it to non-commercial research.
    // Public so tests can reach it: the probe-fixture generator re-runs the identical YuNet
    // detection rather than a second, divergent path, and the model-picker tests use it as the
    // realistic "operator pasted a URL that is a valid ONNX but not an embedding model" case.
    public static final String DETECTION_MODEL_URL =
            "https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx";
    /**
     * SHA-256 of the YuNet weights. The model cache is keyed on this rather than on a fixed model
     * name, and that matters even for a hardcoded URL: under name keying, repointing
     * {@link #DETECTION_MODEL_URL} at new bytes would leave every install that already cached the
     * old file loading it forever, silently. Verified 2026-07-27 — it is also exactly the ETag
     * raw.githubusercontent.com serves for this file.
     */
    public static final String DETECTION_MODEL_SHA256 =
            "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4";

    // Instance state: this handler is a Spring singleton, so there is exactly one model per bean
    // per application context. Keeping it off static fields means separate contexts (notably in
    // tests) never share native model handles or the hook/lock guarding them.
    @SuppressWarnings("rawtypes")
    private volatile ZooModel detectionModel = null;
    private final Object detectionModelLock = new Object();

    // Guards model lifecycle: read lock held during every inference; write lock acquired by
    // closeDetectionModel() so native resources cannot be freed while predict() is in-flight.
    private final ReentrantReadWriteLock modelUsageLock = new ReentrantReadWriteLock();

    // @VisibleForTesting: called after the detection model reference is acquired but before
    // newPredictor(). Allows tests to inject a concurrent closeAllModels() call in the exact
    // race window. Must be null in production; package-private for test access within this package.
    volatile Runnable inferenceStartHook = null;

    // @VisibleForTesting: coordinate callers around concurrency-slot acquisition without loading a model.
    volatile Runnable beforeSemaphoreAcquireHook = null;
    volatile Runnable semaphoreAcquiredHook = null;

    // Track last access time for idle timeout (in milliseconds)
    private volatile long detectionModelLastAccess = 0;
    // Idle timeout: 30 minutes of inactivity before unloading the model. Instance field (not a
    // constant) so tests can shorten it via setIdleTimeoutMsForTest() rather than waiting.
    private static final long DEFAULT_MODEL_IDLE_TIMEOUT_MS = 30 * 60 * 1000;
    private volatile long idleTimeoutMs = DEFAULT_MODEL_IDLE_TIMEOUT_MS;
    // How long a best-effort unload waits for in-flight inferences before giving up
    private static final long UNLOAD_LOCK_TIMEOUT_MS = 5_000;

    private final ResizableSemaphore semaphore = new ResizableSemaphore(2);

    private final YuNetFaceDetectionTranslator translator = new YuNetFaceDetectionTranslator();

    private final UserSettingsDao userSettingsDao;

    public FaceDetectionTreatmentHandler(UserSettingsDao userSettingsDao) {
        this.userSettingsDao = userSettingsDao;
    }

    @PostConstruct
    void registerSettingObservers() {
        updateSemaphoreFromValue(userSettingsDao.getSetting(UserSettingName.CONCURRENT_FACE_DETECTION_HANDLERS).orElse("2"));
        updateConfidenceThreshold(userSettingsDao.getSetting(UserSettingName.FACE_DETECTION_CONFIDENCE_THRESHOLD).
            orElse(Float.toString(YuNetFaceDetectionTranslator.DEFAULT_CONFIDENCE_THRESHOLD)));
        userSettingsDao.addSettingObserver(UserSettingName.CONCURRENT_FACE_DETECTION_HANDLERS,
                this::updateSemaphoreFromValue);
        userSettingsDao.addSettingObserver(UserSettingName.FACE_DETECTION_CONFIDENCE_THRESHOLD,
            this::updateConfidenceThreshold);
    }

    private void updateConfidenceThreshold(String value) {
        try {
            float threshold = Float.parseFloat(value);
            if (threshold >= 0 && threshold <= 1) {
                translator.setConfidenceThreshold(threshold);
                logger.info("Face detection confidence threshold set to {}", threshold);
            } else {
                logger.warn("Invalid FACE_DETECTION_CONFIDENCE_THRESHOLD value: {}", value);
            }
        } catch (NumberFormatException e) {
            logger.warn("Invalid FACE_DETECTION_CONFIDENCE_THRESHOLD value: {}", value);
        }
    }

    private void updateSemaphoreFromValue(String value) {
        int limit = 2;
        try {
            limit = Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            logger.warn("Invalid ConcurrentFaceDetectionHandlers value: {}", value);
        }
        semaphore.resize(limit);
        logger.info("Face detection concurrency limit set to {}", limit);
    }

    /**
     * Get the face detection model (YuNet), loading it if necessary.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private ZooModel<Image, List<FaceDetectionResult>> getDetectionModel()
            throws ModelNotFoundException, MalformedModelException, java.io.IOException {
        if (detectionModel == null) {
            // Must happen before ModelZoo is touched, and outside detectionModelLock: DJL's zoo
            // bootstrap is global, so it has to be serialized against the other handlers too.
            DjlBootstrap.ensureInitialized();
            synchronized (detectionModelLock) {
                if (detectionModel == null) {
                    logger.info("Loading face detection model (YuNet ONNX)...");
                    Path modelDir = TreatmentUtils.ensureModelCachedByHash(
                            DETECTION_MODEL_URL, DETECTION_MODEL_SHA256);
                    Criteria.Builder builder = Criteria.builder()
                            .setTypes(Image.class, List.class)
                            .optModelPath(modelDir)
                            .optModelName(TreatmentUtils.HASH_CACHED_MODEL_NAME)
                            .optEngine("OnnxRuntime");
                    builder.optTranslator(translator);
                    detectionModel = ModelZoo.loadModel(builder.build());
                    logger.info("Face detection model loaded successfully");
                }
                detectionModelLastAccess = System.currentTimeMillis();
            }
        } else {
            detectionModelLastAccess = System.currentTimeMillis();
        }
        return detectionModel;
    }

    @Override
    public boolean canProcess(Item item) {
        if (!TreatmentUtils.isSupportedImageExtension(TreatmentUtils.getFileExtension(item.getName()))) {
            return false;
        }
        Long sizeBytes = item.getSizeBytes();
        if (sizeBytes != null && sizeBytes > HandlerLimits.MAX_IMAGE_SIZE_BYTES) {
            logger.info("Skipping face detection for {}: file size {}MB exceeds limit of {}MB",
                item.getFullPath(), sizeBytes / (1024 * 1024), HandlerLimits.MAX_IMAGE_SIZE_BYTES / (1024 * 1024));
            return false;
        }
        return true;
    }

    @Override
    public TreatmentOutput apply(Item item, DataProviderInterface dataProvider,
                                        DataSourceInterface dataSource, String treatmentName) {
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
                String extension = TreatmentUtils.getFileExtension(item.getName());
                if (TreatmentUtils.isSupportedImageExtension(extension)) {
                    return processImageFile(item, dataProvider, treatmentName);
                }
                logger.info("Face detection skipped for unsupported file type: {}", item.getName());
                return TreatmentOutput.of(new ArrayList<>());
            } finally {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Face detection handler interrupted while waiting for concurrency slot", e);
            throw new IllegalStateException("Face detection interrupted while waiting for concurrency slot", e);
        }
    }

    private TreatmentOutput processImageFile(Item item, DataProviderInterface dataProvider, String treatmentName) {
        List<TreatmentResult> results = new ArrayList<>();
        List<FaceDetection> detections = new ArrayList<>();
        results.add(new TreatmentResult(FIELD_TREATMENT_TYPE, treatmentName));

        Path tempFile = null;

        try (InputStream inputStream = dataProvider.getInputStream(item)) {
            if (inputStream == null) {
                logger.warn("Unable to get input stream for face detection on item: {}", item.getFullPath());
                throw new IllegalStateException("Unable to get input stream for face detection: "
                    + item.getFullPath());
            }

            tempFile = Files.createTempFile("face-detect-", "-" + item.getName());
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);

            BufferedImage bufferedImage = ImageIO.read(tempFile.toFile());
            if (bufferedImage == null) {
                logger.warn("Unable to read image file: {}", item.getFullPath());
                throw new IllegalStateException("Unable to read image file for face detection: "
                    + item.getFullPath());
            }

            Image image = ImageFactory.getInstance().fromImage(bufferedImage);

            // The read lock is taken around predict() only. Model loading stays outside it, where
            // it cannot stall closeDetectionModel() or (via ReentrantReadWriteLock's queued-writer
            // rule) every other inference thread.

            // Warm the detection model before locking: a cold load is slow.
            getDetectionModel();

            List<FaceDetectionResult> detectedFaces;
            modelUsageLock.readLock().lock();
            try {
                ZooModel<Image, List<FaceDetectionResult>> detModel = getDetectionModel();

                // Invoke test hook (null in production) to allow race-condition tests to
                // pause here while a concurrent closeAllModels() call is injected.
                Runnable hook = inferenceStartHook;
                if (hook != null) {
                    try {
                        hook.run();
                    } catch (Exception e) {
                        logger.debug("inferenceStartHook threw unexpectedly", e);
                    }
                }

                try (Predictor<Image, List<FaceDetectionResult>> predictor = detModel.newPredictor()) {
                    detectedFaces = predictor.predict(image);
                }
            } finally {
                modelUsageLock.readLock().unlock();
            }

            if (detectedFaces.isEmpty()) {
                // Returning an empty detection list is not a no-op: it is what clears rows a
                // previous run left behind, for an image that no longer contains a face.
                logger.info("No faces detected in image: {}", item.getName());
                bufferedImage.flush();
                return new TreatmentOutput(results, detections);
            }

            // Index assignment rule, stated once and implemented only here: confidence descending,
            // ties broken by bbox x ascending. Re-detecting an unchanged image therefore reproduces
            // the same indices, which is what makes the index-keyed crop URL stable across rescans.
            List<FaceDetectionResult> ordered = new ArrayList<>(detectedFaces);
            ordered.sort(Comparator.comparingDouble(FaceDetectionResult::confidence).reversed()
                    .thenComparingDouble(FaceDetectionResult::x));

            for (int faceIndex = 0; faceIndex < ordered.size(); faceIndex++) {
                FaceDetectionResult face = ordered.get(faceIndex);

                // Bbox in normalized coordinates for storage
                float bboxX = face.x() / bufferedImage.getWidth();
                float bboxY = face.y() / bufferedImage.getHeight();
                float bboxW = face.width() / bufferedImage.getWidth();
                float bboxH = face.height() / bufferedImage.getHeight();

                // id and dateCreated are assigned by the DAO at insert time, inside the same
                // transaction that writes facecount below.
                detections.add(new FaceDetection(null, faceIndex, face.confidence(),
                        bboxX, bboxY, bboxW, bboxH, face.landmarks(), null));

                // objectdetected=face reuses the object-detection key on purpose: COCO has no
                // "face" class, so this adds "face" to the unified "what's in this image" facet
                // rather than colliding with it.
                results.add(new TreatmentResult(FIELD_OBJECT_DETECTED, "face"));

                // The confidence is emitted under both keys, and both are wanted:
                //
                //  - objectdetectionconfidence, because a face now appears in the shared
                //    `objectdetected` list. Without this, an image with faces and objects shows
                //    more entries under `objectdetected` than under `objectdetectionconfidence`
                //    and the item view reads as though confidences went missing.
                //  - detectedfaceconfidence, which keeps a face-only axis to search and facet on.
                results.add(new TreatmentResult(FIELD_OBJECT_DETECTION_CONFIDENCE,
                        String.format("%.4f", face.confidence())));
                results.add(new TreatmentResult(FIELD_DETECTED_FACE_CONFIDENCE,
                        String.format("%.4f", face.confidence())));
            }

            results.add(new TreatmentResult(FIELD_FACE_COUNT, String.valueOf(ordered.size())));

            bufferedImage.flush();

            logger.info("Face detection found {} face(s) in image: {}", ordered.size(), item.getName());
            return new TreatmentOutput(results, detections);

        } catch (TranslateException e) {
            logger.error("Error during face detection inference: {}", e.getMessage());
            throw new IllegalStateException("Face detection inference failed for " + item.getFullPath(), e);
        } catch (Exception e) {
            logger.error("Error performing face detection on file {}: {}", item.getFullPath(), e.getMessage(), e);
            throw new IllegalStateException("Face detection failed for " + item.getFullPath(), e);
        } finally {
            TreatmentUtils.cleanupTempFile(tempFile);
        }
    }

    /**
     * Close and unload the face detection model to free native memory.
     * The model will be lazy-loaded again if needed.
     */
    public void closeDetectionModel() {
        modelUsageLock.writeLock().lock();
        try {
            closeDetectionModelHoldingWriteLock();
        } finally {
            modelUsageLock.writeLock().unlock();
        }
    }

    private void closeDetectionModelHoldingWriteLock() {
        synchronized (detectionModelLock) {
            if (detectionModel != null) {
                logger.info("Closing face detection model to free memory...");
                detectionModel.close();
                detectionModel = null;
                detectionModelLastAccess = 0;
                logger.info("Face detection model closed");
            }
        }
    }

    /**
     * Run a close action under the write lock, giving up if it cannot be acquired within the
     * timeout.
     *
     * <p>For callers that must not block indefinitely: an inference thread wedged in native code
     * holds the read lock forever, and an unbounded write-lock wait would take the caller's thread
     * down with it. Freeing a model is best-effort, so skipping is always safe.
     *
     * @return true if the action ran, false on timeout or interruption
     */
    private boolean tryWithWriteLock(long timeoutMs, Runnable closeAction, String what) {
        try {
            if (!modelUsageLock.writeLock().tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
                logger.warn("Could not acquire face model write lock within {}ms; skipping {} unload. "
                        + "An inference may be stuck holding the read lock.", timeoutMs, what);
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            closeAction.run();
            return true;
        } finally {
            modelUsageLock.writeLock().unlock();
        }
    }

    /**
     * Close the model without blocking indefinitely. See {@link #tryWithWriteLock}.
     *
     * @return true only if the model was closed
     */
    public boolean tryCloseAllModels(long timeoutMs) {
        return tryWithWriteLock(
                timeoutMs, this::closeDetectionModelHoldingWriteLock, "detection model");
    }

    /**
     * Close and unload all face models to free native memory.
     * This method can be called during application shutdown or when memory is low.
     */
    public void closeAllModels() {
        closeDetectionModel();
    }

    /**
     * Check and unload the model if it has been idle for longer than the idle timeout.
     * This is called periodically by a scheduled task to free memory when the model is not in use.
     */
    public void closeIdleModels() {
        long now = System.currentTimeMillis();

        // Bounded wait: this runs on the shared scheduling thread, which also drives the treatment
        // scheduler and its stuck-thread governor.
        if (detectionModel != null && detectionModelLastAccess > 0) {
            long idleTime = now - detectionModelLastAccess;
            if (idleTime > idleTimeoutMs) {
                logger.info("Face detection model idle for {} minutes, unloading...", idleTime / 60000);
                tryWithWriteLock(UNLOAD_LOCK_TIMEOUT_MS,
                        this::closeDetectionModelHoldingWriteLock, "detection model");
            }
        }
    }

    /**
     * Whether the native detection model is currently loaded. Read under the model monitor so it
     * reflects a completed load/close rather than a half-published reference.
     */
    boolean isModelLoaded() {
        synchronized (detectionModelLock) {
            return detectionModel != null;
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
