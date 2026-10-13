package enterfind.server.scan.treatment;

import static enterfind.server.model.FieldNameConstants.FIELD_DETECTED_FACE;
import static enterfind.server.model.FieldNameConstants.FIELD_TREATMENT_TYPE;

import java.awt.image.BufferedImage;
import java.io.InputStream;
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
import org.springframework.stereotype.Component;

import ai.djl.inference.Predictor;
import ai.djl.modality.cv.Image;
import ai.djl.modality.cv.ImageFactory;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;
import enterfind.server.dao.FaceGroupDao;
import enterfind.server.dao.FaceDao;
import enterfind.server.dao.UserSettingsDao;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.FaceDetection;
import enterfind.server.model.FaceEmbedding;
import enterfind.server.model.FaceGroup;
import enterfind.server.model.UserSettingName;
import enterfind.server.scan.DataProviderInterface;
import enterfind.server.scan.Item;
import enterfind.server.scan.TreatmentOutput;
import enterfind.server.scan.TreatmentResult;
import enterfind.server.scan.treatment.FaceEmbeddingModelService.ResolvedEmbeddingModel;
import enterfind.server.util.NamedLockRegistry;
import enterfind.server.util.ResizableSemaphore;

import jakarta.annotation.PostConstruct;

/**
 * Treatment handler that embeds each detected face, groups it, and hands the results and group
 * upserts back for the save transaction to persist.
 *
 * <p>The second half of the face pipeline: {@code FACE_DETECTION} finds faces and persists their
 * geometry; this treatment re-aligns each face from the persisted landmarks, embeds it under the
 * operator-chosen model, and runs the greedy nearest-centroid grouping ({@link FaceGrouping}). It
 * <b>owns the grouping decision</b> — cosine, threshold, centroid math, {@code "Person N"} labels —
 * and the DAO just applies what it returns.
 *
 * <p><b>Serialization.</b> FACE_GROUPS is instance-global, so concurrent runs on different images
 * would otherwise race to duplicate a person's group or lose a centroid update. This handler
 * embeds <em>outside</em> the lock (the slow part stays parallel), then takes a single
 * {@link NamedLockRegistry} grouping lock, reads the current groups, decides, and returns the lock
 * key in the {@link TreatmentOutput}. {@code saveItemTreatmentResult} releases it <em>after the
 * transaction commits</em>, so the lock spans the decision and its persistence — on one processor
 * thread — closing the stale-read window. If the handler throws after acquiring, it releases the
 * lock itself.
 *
 * <p>Model lifecycle is copied from {@link FaceDetectionTreatmentHandler}, plus a pull-based reload:
 * the model hash lives in {@code SYSTEM_SETTINGS}, which handlers do not observe, so before
 * inference this handler compares the loaded hash to the current one and hot-swaps on mismatch.
 */
@Component
public class FaceRecognitionTreatmentHandler implements TreatmentHandler {

    private static final Logger logger = LoggerFactory.getLogger(FaceRecognitionTreatmentHandler.class);

    @SuppressWarnings("rawtypes")
    private volatile ZooModel embeddingModel = null;
    private volatile String loadedModelHash = null;
    private final Object embeddingModelLock = new Object();

    private final ReentrantReadWriteLock modelUsageLock = new ReentrantReadWriteLock();

    // @VisibleForTesting: injected in the race window between acquiring the model and inference.
    volatile Runnable inferenceStartHook = null;

    private volatile long embeddingModelLastAccess = 0;
    private static final long DEFAULT_MODEL_IDLE_TIMEOUT_MS = 30 * 60 * 1000;
    private volatile long idleTimeoutMs = DEFAULT_MODEL_IDLE_TIMEOUT_MS;
    private static final long UNLOAD_LOCK_TIMEOUT_MS = 5_000;

    private final ResizableSemaphore semaphore = new ResizableSemaphore(2);

    /**
     * Detection-confidence floor for embedding a face, on the <em>detector's</em> scale.
     *
     * <p>Calibrated against YuNet at its 0.7 detection floor over a 40k-photo corpus: it removes
     * ~73% of the faces that were forming identity-free groups, at the cost of ~1.9% of faces that
     * were grouping correctly. Marginal return holds near 17:1 up to here and collapses to 1.3:1 by
     * 0.85, so this is the last defensible point rather than a round number.
     *
     * <p><b>Why the detector's confidence and not the embedding model's output.</b> The failure this
     * gates against is an embedding-model property: a compact model maps faces too degraded to carry
     * identity onto a single "null response" direction, and because those vectors are mutually
     * similar they accrete into one enormous bogus person. But the embedding model is the half an
     * operator swaps ({@link FaceEmbeddingModelService}), while detection is fixed and licence-clean.
     * Calibrating on the stable half keeps this number meaningful across model changes; the cost of
     * applying it to a model that would not have needed it is the ~1.9%.
     */
    public static final float DEFAULT_MIN_CONFIDENCE = 0.80f;

    private volatile float minConfidence = DEFAULT_MIN_CONFIDENCE;

    private final FaceEmbeddingTranslator translator =
            new FaceEmbeddingTranslator(FaceEmbeddingModelService.BUILTIN_PREPROC);

    private final FaceDao faceDao;
    private final FaceGroupDao faceGroupDao;
    private final FaceEmbeddingModelService modelService;
    private final UserSettingsDao userSettingsDao;
    private final NamedLockRegistry namedLockRegistry;

    public FaceRecognitionTreatmentHandler(FaceDao faceDao, FaceGroupDao faceGroupDao,
            FaceEmbeddingModelService modelService, UserSettingsDao userSettingsDao,
            NamedLockRegistry namedLockRegistry) {
        this.faceDao = faceDao;
        this.faceGroupDao = faceGroupDao;
        this.modelService = modelService;
        this.userSettingsDao = userSettingsDao;
        this.namedLockRegistry = namedLockRegistry;
    }

    @PostConstruct
    void registerSettingObservers() {
        updateSemaphoreFromValue(userSettingsDao.getSetting(UserSettingName.CONCURRENT_FACE_RECOGNITION_HANDLERS)
                .orElse("2"));
        userSettingsDao.addSettingObserver(UserSettingName.CONCURRENT_FACE_RECOGNITION_HANDLERS,
                this::updateSemaphoreFromValue);
        updateMinConfidence(userSettingsDao.getSetting(UserSettingName.FACE_RECOGNITION_MIN_CONFIDENCE)
                .orElse(Float.toString(DEFAULT_MIN_CONFIDENCE)));
        userSettingsDao.addSettingObserver(UserSettingName.FACE_RECOGNITION_MIN_CONFIDENCE,
                this::updateMinConfidence);
    }

    /**
     * Takes effect on the next item processed. It does not re-queue anything: like
     * {@code FaceDetectionConfidenceThreshold}, raising it leaves already-embedded faces in place and
     * lowering it does not go back for the ones previously skipped. A rescan or a model change is
     * what re-derives those.
     */
    private void updateMinConfidence(String value) {
        try {
            float parsed = Float.parseFloat(value);
            if (parsed < 0.0f || parsed > 1.0f) {
                logger.warn("Invalid FaceRecognitionMinConfidence value (must be 0..1): {}", value);
                return;
            }
            minConfidence = parsed;
            logger.info("Face recognition minimum detection confidence set to {}", minConfidence);
        } catch (NumberFormatException e) {
            logger.warn("Invalid FaceRecognitionMinConfidence value: {}", value);
        }
    }

    private void updateSemaphoreFromValue(String value) {
        int limit = 2;
        try {
            limit = Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            logger.warn("Invalid ConcurrentFaceRecognitionHandlers value: {}", value);
        }
        semaphore.resize(limit);
        logger.info("Face recognition concurrency limit set to {}", limit);
    }

    @Override
    public boolean canProcess(Item item) {
        if (!TreatmentUtils.isSupportedImageExtension(TreatmentUtils.getFileExtension(item.getName()))) {
            return false;
        }
        Long sizeBytes = item.getSizeBytes();
        if (sizeBytes != null && sizeBytes > HandlerLimits.MAX_IMAGE_SIZE_BYTES) {
            return false;
        }
        return true;
    }

    @Override
    public TreatmentOutput apply(Item item, DataProviderInterface dataProvider,
            DataSourceInterface dataSource, String treatmentName) {
        try {
            semaphore.acquire();
            try {
                return processImageFile(item, dataProvider, treatmentName);
            } finally {
                semaphore.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Face recognition handler interrupted while waiting for concurrency slot", e);
            throw new IllegalStateException("Face recognition interrupted", e);
        }
    }

    private TreatmentOutput processImageFile(Item item, DataProviderInterface dataProvider, String treatmentName) {
        // No usable model should ever reach here — the claim query hands out no FACE_RECOGNITION rows
        // when the mode is NONE. If one does (a model set to NONE mid-drain), throw so the row stays
        // FAILED-then-retried rather than clearing prior embeddings.
        ResolvedEmbeddingModel model = modelService.currentModel().orElseThrow(
                () -> new IllegalStateException("No embedding model configured; cannot run FACE_RECOGNITION"));

        List<FaceDetection> detections = faceDao.getFaceDetectionsByItemId(item.getId());

        List<TreatmentResult> results = new ArrayList<>();
        results.add(new TreatmentResult(FIELD_TREATMENT_TYPE, treatmentName));

        // Gate before the download, not inside the embedding loop: an item whose every face is too
        // degraded to identify needs no image at all, which is most of the saving on a photo corpus.
        List<FaceDetection> identifiable = retainIdentifiable(detections, item);

        if (identifiable.isEmpty()) {
            // No usable faces: empty (not null) embeddings clears any a previous run left. The
            // detection rows and their crops are untouched — a gated face renders unlabeled.
            return TreatmentOutput.ofRecognition(results, List.of(), List.of(), List.of());
        }

        Path tempFile = null;
        try (InputStream inputStream = dataProvider.getInputStream(item)) {
            if (inputStream == null) {
                throw new IllegalStateException("Unable to read input stream for " + item.getFullPath());
            }
            tempFile = Files.createTempFile("face-embed-", "-" + item.getName());
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);

            BufferedImage sourceImage = ImageIO.read(tempFile.toFile());
            if (sourceImage == null) {
                throw new IllegalStateException("Unable to decode image " + item.getFullPath());
            }

            // Embed OUTSIDE the grouping lock — the slow part stays parallel across threads.
            List<FaceGrouping.Candidate> candidates = embedFaces(model, sourceImage, identifiable);
            sourceImage.flush();

            if (candidates.isEmpty()) {
                // Every face was unusable (no landmarks / degenerate embedding): clear, no groups.
                return TreatmentOutput.ofRecognition(results, List.of(), List.of(), List.of());
            }

            return groupUnderLock(model, candidates, results);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Face recognition failed for " + item.getFullPath(), e);
        } finally {
            TreatmentUtils.cleanupTempFile(tempFile);
        }
    }

    /**
     * Take the grouping lock, read the current groups, decide, and return the output <b>still
     * holding the lock</b> — the save releases it after commit. If anything here throws, release the
     * lock before propagating, since the output never reaches the save that would have released it.
     */
    private TreatmentOutput groupUnderLock(ResolvedEmbeddingModel model,
            List<FaceGrouping.Candidate> candidates, List<TreatmentResult> results) {
        namedLockRegistry.lock(FaceGrouping.LOCK_KEY);
        try {
            List<FaceGroup> currentGroups = faceGroupDao.getGroupsByModelHash(model.modelHash());
            FaceGrouping.Result grouping =
                    FaceGrouping.assign(currentGroups, candidates, model.threshold(), model.modelHash());

            // Map faceIndex -> embedding bytes, to build FACE_EMBEDDINGS rows with the assigned group.
            List<FaceEmbedding> embeddings = new ArrayList<>(grouping.assignments().size());
            for (FaceGrouping.Assignment assignment : grouping.assignments()) {
                float[] vector = candidateVector(candidates, assignment.faceIndex());
                embeddings.add(new FaceEmbedding(null, assignment.faceIndex(), assignment.groupId(),
                        FaceEmbeddingUtils.toBytes(vector), model.modelHash(), null));
                results.add(new TreatmentResult(FIELD_DETECTED_FACE, assignment.label()));
            }

            return TreatmentOutput.ofRecognition(results, embeddings, grouping.mutations(),
                    List.of(FaceGrouping.LOCK_KEY));
        } catch (RuntimeException e) {
            namedLockRegistry.unlock(FaceGrouping.LOCK_KEY);
            throw e;
        }
    }

    private static float[] candidateVector(List<FaceGrouping.Candidate> candidates, int faceIndex) {
        for (FaceGrouping.Candidate candidate : candidates) {
            if (candidate.faceIndex() == faceIndex) {
                return candidate.embedding();
            }
        }
        throw new IllegalStateException("No embedding for face index " + faceIndex);
    }

    /**
     * The faces worth embedding, logging what was dropped so an operator tuning the floor can see
     * its effect without a database query.
     */
    private List<FaceDetection> retainIdentifiable(List<FaceDetection> detections, Item item) {
        float floor = minConfidence;
        List<FaceDetection> kept = filterByConfidence(detections, floor);
        if (kept.size() < detections.size()) {
            logger.info("Not embedding {} of {} detected faces below the {} confidence floor for {}",
                    detections.size() - kept.size(), detections.size(), floor, item.getFullPath());
        }
        return kept;
    }

    /**
     * The detections at or above the floor, order preserved.
     *
     * <p>Pure and package-visible so the decision can be tested at its boundary against fabricated
     * detections, with no model, no Spring and no database — the same reason
     * {@code FaceModelProbe.evaluate} and {@link FaceGrouping#assign} are pure.
     */
    static List<FaceDetection> filterByConfidence(List<FaceDetection> detections, float minConfidence) {
        List<FaceDetection> kept = new ArrayList<>(detections.size());
        for (FaceDetection detection : detections) {
            if (detection.getConfidence() >= minConfidence) {
                kept.add(detection);
            }
        }
        return kept;
    }

    /**
     * Align and embed every face with usable landmarks, skipping any whose embedding is degenerate
     * (NaN/all-zeros) — the runtime backstop to the set-time probe, never persisting an untrusted
     * result. Retries once if the model is pulled (idle-unloaded or swapped) in the gap between load
     * and read-lock.
     */
    private List<FaceGrouping.Candidate> embedFaces(ResolvedEmbeddingModel model,
            BufferedImage sourceImage, List<FaceDetection> detections) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            ZooModel<Image, float[]> loaded = getEmbeddingModelForHash(model);

            modelUsageLock.readLock().lock();
            try {
                if (embeddingModel != loaded) {
                    continue; // closed or swapped in the gap; reload and retry
                }
                Runnable hook = inferenceStartHook;
                if (hook != null) {
                    try {
                        hook.run();
                    } catch (Exception e) {
                        logger.debug("inferenceStartHook threw unexpectedly", e);
                    }
                }

                List<FaceGrouping.Candidate> candidates = new ArrayList<>();
                try (Predictor<Image, float[]> predictor = loaded.newPredictor()) {
                    for (FaceDetection detection : detections) {
                        float[][] landmarks = detection.getLandmarks();
                        if (landmarks == null) {
                            logger.warn("Face {} has no landmarks; skipping embedding", detection.getFaceIndex());
                            continue;
                        }
                        BufferedImage aligned = FaceAlignmentUtil.alignFace(sourceImage, landmarks);
                        float[] vector = predictor.predict(ImageFactory.getInstance().fromImage(aligned));
                        if (FaceEmbeddingUtils.isDegenerate(vector)) {
                            logger.warn("Degenerate embedding for face {} of a corpus image; dropping it",
                                    detection.getFaceIndex());
                            continue;
                        }
                        candidates.add(new FaceGrouping.Candidate(detection.getFaceIndex(), vector));
                    }
                }
                return candidates;
            } finally {
                modelUsageLock.readLock().unlock();
            }
        }
        throw new IllegalStateException("Embedding model was repeatedly pulled during inference; will retry");
    }

    // The raw embeddingModel field is narrowed back to its element types on return; the field is raw
    // because closing and reloading it must not care what it was parameterised with.
    @SuppressWarnings("unchecked")
    private ZooModel<Image, float[]> getEmbeddingModelForHash(ResolvedEmbeddingModel model) throws Exception {
        if (embeddingModel != null && model.modelHash().equals(loadedModelHash)) {
            embeddingModelLastAccess = System.currentTimeMillis();
            return embeddingModel;
        }
        modelUsageLock.writeLock().lock();
        try {
            if (embeddingModel != null && !model.modelHash().equals(loadedModelHash)) {
                logger.info("Embedding model hash changed ({} -> {}); reloading",
                        loadedModelHash, model.modelHash());
                closeEmbeddingModelHoldingWriteLock();
            }
            if (embeddingModel == null) {
                loadEmbeddingModel(model);
            }
            embeddingModelLastAccess = System.currentTimeMillis();
            return embeddingModel;
        } finally {
            modelUsageLock.writeLock().unlock();
        }
    }

    private void loadEmbeddingModel(ResolvedEmbeddingModel model) throws Exception {
        synchronized (embeddingModelLock) {
            if (embeddingModel != null) {
                return;
            }
            DjlBootstrap.ensureInitialized();
            logger.info("Loading face embedding model (sha256 {}, dim {}, preproc {})...",
                    model.modelHash(), model.outputDim(), model.preproc());
            translator.setPreproc(model.preproc());
            Path modelDir = TreatmentUtils.ensureModelCachedByHash(model.url(), model.modelHash());
            Criteria<Image, float[]> criteria = Criteria.builder()
                    .setTypes(Image.class, float[].class)
                    .optModelPath(modelDir)
                    .optModelName(TreatmentUtils.HASH_CACHED_MODEL_NAME)
                    .optEngine("OnnxRuntime")
                    .optTranslator(translator)
                    .build();
            embeddingModel = ModelZoo.loadModel(criteria);
            loadedModelHash = model.modelHash();
            logger.info("Face embedding model loaded successfully");
        }
    }

    public void closeEmbeddingModel() {
        modelUsageLock.writeLock().lock();
        try {
            closeEmbeddingModelHoldingWriteLock();
        } finally {
            modelUsageLock.writeLock().unlock();
        }
    }

    private void closeEmbeddingModelHoldingWriteLock() {
        synchronized (embeddingModelLock) {
            if (embeddingModel != null) {
                logger.info("Closing face embedding model to free memory...");
                embeddingModel.close();
                embeddingModel = null;
                loadedModelHash = null;
                embeddingModelLastAccess = 0;
                logger.info("Face embedding model closed");
            }
        }
    }

    private boolean tryWithWriteLock(long timeoutMs, Runnable closeAction, String what) {
        try {
            if (!modelUsageLock.writeLock().tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
                logger.warn("Could not acquire embedding model write lock within {}ms; skipping {} unload.",
                        timeoutMs, what);
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

    /** Close without blocking indefinitely; used by the concurrent-close test and shutdown. */
    public boolean tryCloseAllModels(long timeoutMs) {
        return tryWithWriteLock(timeoutMs, this::closeEmbeddingModelHoldingWriteLock, "embedding model");
    }

    public void closeAllModels() {
        closeEmbeddingModel();
    }

    public void closeIdleModels() {
        long now = System.currentTimeMillis();
        if (embeddingModel != null && embeddingModelLastAccess > 0) {
            long idleTime = now - embeddingModelLastAccess;
            if (idleTime > idleTimeoutMs) {
                logger.info("Face embedding model idle for {} minutes, unloading...", idleTime / 60000);
                tryWithWriteLock(UNLOAD_LOCK_TIMEOUT_MS,
                        this::closeEmbeddingModelHoldingWriteLock, "embedding model");
            }
        }
    }

    boolean isModelLoaded() {
        synchronized (embeddingModelLock) {
            return embeddingModel != null;
        }
    }

    void setIdleTimeoutMsForTest(long idleTimeoutMs) {
        this.idleTimeoutMs = idleTimeoutMs;
    }
}
