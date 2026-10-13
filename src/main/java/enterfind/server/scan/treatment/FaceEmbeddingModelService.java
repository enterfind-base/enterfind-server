package enterfind.server.scan.treatment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import enterfind.server.dao.FaceDao;
import enterfind.server.dao.FaceGroupDao;
import enterfind.server.dao.SystemSettingsDao;
import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.model.FaceEmbedding;
import enterfind.server.model.FaceEmbeddingMode;
import enterfind.server.model.FaceGroup;
import enterfind.server.model.FaceGroupMutation;
import enterfind.server.util.NamedLockRegistry;

/**
 * The instance-global face-embedding model configuration: which model is selected, and the values
 * derived from it. Stored in {@code SYSTEM_SETTINGS}, deliberately not {@code USER_SETTINGS} — the
 * GraphQL schema exposes only {@code userSettings}, so {@code SYSTEM_SETTINGS} config is hidden from
 * the general settings UI by construction, with no negative prefix filter to maintain and nothing to
 * leak.
 *
 * <p>This class owns the read side (here) and the {@code setModel} transition —
 * probe, hash, and the total-regroup with label carryover. Keeping both behind one component is what
 * lets "a stored model is a verified model" hold: nothing writes these keys except the transition
 * that has already proven the model.
 *
 * <p><b>Readiness is just {@code mode != NONE}.</b> A stored {@code CUSTOM} model passed the inline
 * probe when it was set (a URL that fails is never stored), and {@code BUILTIN} we ship — so there is
 * no separate verified-hash to compare and no skip state to check.
 */
@Component
public class FaceEmbeddingModelService {

    // SYSTEM_SETTINGS keys. Operator-chosen: MODE (+ URL for custom). Derived at set time: the rest.
    // THRESHOLD is both — derived from the probe when a model is set, and overridable afterwards.
    static final String KEY_MODE = "FaceEmbeddingMode";
    static final String KEY_URL = "FaceEmbeddingUrl";
    static final String KEY_MODEL_HASH = "FaceEmbeddingModelHash";
    static final String KEY_OUTPUT_DIM = "FaceEmbeddingOutputDim";
    static final String KEY_PREPROC = "FaceEmbeddingPreproc";
    static final String KEY_THRESHOLD = "FaceGroupingThreshold";
    /** What the probe implied for the current model, kept so the card can offer a way back to it. */
    static final String KEY_DERIVED_THRESHOLD = "FaceGroupingThresholdDerived";

    // Built-in: EdgeFace-S (Idiap/EdgeFace, CC-BY-NC-SA-4.0). 512-dim, ArcFace-112 aligned, RGB and
    // (x-127.5)/127.5 — a convention we know, so the built-in path hardcodes preproc and runs no
    // probe. Its content hash is still derived at set time (the file is fetched to compute it).
    // The weights live in the repo's "weights" *release*, not in the source tree, so this is a
    // releases/download URL rather than the raw/main path a source-tree file would use.
    static final String BUILTIN_URL =
            "https://github.com/yakhyo/edgeface-onnx/releases/download/weights/edgeface_s_gamma_05.onnx";
    static final int BUILTIN_OUTPUT_DIM = 512;
    static final FaceEmbeddingPreproc BUILTIN_PREPROC = FaceEmbeddingPreproc.RGB_CENTERED;

    /**
     * The built-in's grouping threshold, hardcoded for the same reason as its dimension and
     * preprocessing: the BUILTIN path runs no probe, so there is no measurement to derive from at set
     * time. {@code FaceBuiltinModelProbeTest} is the only thing that checks it against reality, by
     * probing the real weights and re-deriving.
     */
    static final double BUILTIN_THRESHOLD = 0.6;

    /**
     * Fallback grouping threshold when nothing has been derived or chosen yet — the value the whole
     * scale is calibrated around, so it is also what the built-in derives to.
     */
    static final double DEFAULT_THRESHOLD = 0.6;

    /**
     * How far below a model's <em>measured</em> same-person similarity the grouping threshold sits.
     *
     * <p><b>Why a fraction of {@code within} and not a midpoint.</b> The probe reports
     * {@code worstWithinPerson} and {@code bestAcrossPeople} over four crops of two people. The
     * same-person figure generalizes — it is a property of the model's cosine scale. The
     * cross-person figure does not: two obviously different faces say nothing about the closest
     * <em>unrelated</em> pair in a corpus of thousands, which is where a false merge actually comes
     * from. So a midpoint between them (0.35 for the built-in) would read as principled and merge
     * strangers on any real corpus. Scaling {@code within} instead corrects for the one thing the
     * fixtures genuinely measure — how high this model scores the same person — and leaves the
     * corpus-dependent part to the operator.
     *
     * <p>Calibrated so the built-in reproduces the established 0.6 threshold:
     * {@code 0.6 / 0.635 = 0.945}.
     */
    static final double THRESHOLD_SCALE = 0.945;

    /** Bounds for a stored threshold. A 0 groups everyone together; a 1 groups no one with anyone. */
    static final double MIN_THRESHOLD = 0.05;
    static final double MAX_THRESHOLD = 0.95;

    private static final Logger logger = LoggerFactory.getLogger(FaceEmbeddingModelService.class);

    private final SystemSettingsDao systemSettingsDao;
    private final FaceGroupDao faceGroupDao;
    private final FaceDao faceDao;
    private final FaceModelProbe faceModelProbe;
    private final NamedLockRegistry namedLockRegistry;

    /**
     * Used instead of {@code @Transactional} for the recluster alone, so the grouping lock can be
     * released <b>after</b> the commit. An annotated method commits in its proxy, i.e. after the
     * method body's {@code finally} has already let go — the same trap that put the handler's lock
     * release in {@code TreatmentProcessor} rather than in the DAO. Here there is no processor to
     * hold it, so the transaction boundary is made explicit instead.
     */
    private final TransactionTemplate transactionTemplate;

    public FaceEmbeddingModelService(SystemSettingsDao systemSettingsDao, FaceGroupDao faceGroupDao,
            FaceDao faceDao, FaceModelProbe faceModelProbe, NamedLockRegistry namedLockRegistry,
            PlatformTransactionManager transactionManager) {
        this.systemSettingsDao = systemSettingsDao;
        this.faceGroupDao = faceGroupDao;
        this.faceDao = faceDao;
        this.faceModelProbe = faceModelProbe;
        this.namedLockRegistry = namedLockRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** The current selection. Defaults to {@link FaceEmbeddingMode#NONE} on a fresh install. */
    public FaceEmbeddingMode getMode() {
        return FaceEmbeddingMode.fromStored(systemSettingsDao.getSetting(KEY_MODE).orElse(null));
    }

    /**
     * Whether an embedding model is usable on this instance — the {@code embeddingModelReady()}
     * predicate, enforced identically in {@code addTreatment(FACE_RECOGNITION)} and the claim query.
     */
    public boolean isReady() {
        return getMode() != FaceEmbeddingMode.NONE;
    }

    /** The derived model hash tagging the current embedding space, or empty when {@code NONE}. */
    public Optional<String> getModelHash() {
        if (getMode() == FaceEmbeddingMode.NONE) {
            return Optional.empty();
        }
        return systemSettingsDao.getSetting(KEY_MODEL_HASH);
    }

    /** The resolved fetch URL for the current model, or empty when {@code NONE}. */
    public Optional<String> getResolvedUrl() {
        return switch (getMode()) {
            case NONE -> Optional.empty();
            case BUILTIN -> Optional.of(BUILTIN_URL);
            case CUSTOM -> systemSettingsDao.getSetting(KEY_URL);
        };
    }

    public FaceEmbeddingPreproc getPreproc() {
        return FaceEmbeddingPreproc.fromStored(systemSettingsDao.getSetting(KEY_PREPROC).orElse(null));
    }

    public int getOutputDim() {
        return systemSettingsDao.getSetting(KEY_OUTPUT_DIM).map(Integer::parseInt).orElse(0);
    }

    /**
     * The cosine at or above which a face joins an existing group.
     *
     * <p>Read fresh on every call rather than cached behind a settings observer, and deliberately so:
     * the recluster mutation and the items grouped after it must use the same number, and an observer
     * updates a handler's field only <em>after</em> the write commits — leaving in-flight items
     * grouping at the old value. A {@code SELECT} per image is nothing beside the inference it
     * accompanies.
     */
    public double getThreshold() {
        return systemSettingsDao.getSetting(KEY_THRESHOLD)
                .map(value -> {
                    try {
                        return Double.parseDouble(value);
                    } catch (NumberFormatException e) {
                        logger.warn("Invalid stored grouping threshold {}; using {}", value, DEFAULT_THRESHOLD);
                        return DEFAULT_THRESHOLD;
                    }
                })
                .orElse(DEFAULT_THRESHOLD);
    }

    /** The threshold this model's own probe implied, before any operator tuning. */
    public double getDerivedThreshold() {
        return systemSettingsDao.getSetting(KEY_DERIVED_THRESHOLD)
                .map(Double::parseDouble).orElse(DEFAULT_THRESHOLD);
    }

    /**
     * The grouping threshold implied by a probe's measured same-person similarity. See
     * {@link #THRESHOLD_SCALE} for why this scales {@code within} rather than splitting the gap.
     */
    static double deriveThreshold(double withinSimilarity) {
        double derived = withinSimilarity * THRESHOLD_SCALE;
        // Two decimals: this is a starting point an operator will read and adjust, not a precision
        // instrument, and a round number is easier to reason about than 0.5405400000000001.
        derived = Math.round(derived * 100.0) / 100.0;
        return Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, derived));
    }

    /**
     * The full resolved model, or empty when {@code NONE}. This is what the handler loads and tags
     * its embeddings with.
     */
    public Optional<ResolvedEmbeddingModel> currentModel() {
        if (getMode() == FaceEmbeddingMode.NONE) {
            return Optional.empty();
        }
        Optional<String> url = getResolvedUrl();
        Optional<String> hash = getModelHash();
        if (url.isEmpty() || hash.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedEmbeddingModel(url.get(), hash.get(), getOutputDim(), getPreproc(),
                getThreshold()));
    }

    // ==================== Write side: the set-model transition ====================

    /**
     * Set the instance-global embedding model as one atomic transition: for {@code CUSTOM}, fetch
     * and probe the URL (a model that fails is never stored, and the failure carries its similarity
     * numbers); for {@code BUILTIN}, require the non-commercial affirmation <em>before</em> anything
     * is fetched; for {@code NONE}, disable. When the effective model changes this also performs the
     * total regroup — snapshot human labels, clear FACE_GROUPS, mark every recognition row
     * {@code UPDATED} — so the processor re-embeds into the new space with no rescan.
     *
     * <p>Reads and mutates only through this dedicated path, never the generic settings setter,
     * because the values must move together and a half-applied picker would start a rebuild against
     * the wrong config. On any failure it throws and the previous selection stands, with no version
     * bump and no observer fire (there is no observer — the config lives in {@code SYSTEM_SETTINGS}).
     *
     * @throws InvalidArgumentException if built-in is chosen without the affirmation, a custom URL is
     *         blank, or a custom model fails the probe
     */
    @Transactional
    public FaceEmbeddingModelStatus setModel(FaceEmbeddingMode newMode, String url, boolean nonCommercialAck) {
        switch (newMode) {
            case NONE -> {
                // No model, nothing to derive from: the threshold is left exactly as it is, so an
                // operator's tuning is not silently reset by switching recognition off and on.
                applyTransition(FaceEmbeddingMode.NONE, null, "", 0, BUILTIN_PREPROC, null);
                return getStatus();
            }
            case BUILTIN -> {
                // Checked before any fetch: a rejected built-in selection pulls nothing over the
                // network, the property the NONE default exists to protect.
                if (!nonCommercialAck) {
                    throw new InvalidArgumentException(
                            "The built-in model is licensed for non-commercial use only. Selecting it "
                            + "requires affirming that your use is non-commercial.");
                }
                try {
                    TreatmentUtils.FetchedModel fetched = TreatmentUtils.fetchModelByContentHash(BUILTIN_URL);
                    applyTransition(FaceEmbeddingMode.BUILTIN, null, fetched.contentHash(),
                            BUILTIN_OUTPUT_DIM, BUILTIN_PREPROC, BUILTIN_THRESHOLD);
                    return getStatus();
                } catch (java.io.IOException e) {
                    throw new InvalidArgumentException("Failed to download the built-in model: " + e.getMessage());
                }
            }
            case CUSTOM -> {
                if (url == null || url.isBlank()) {
                    throw new InvalidArgumentException("A model URL is required for a custom model");
                }
                String trimmedUrl = url.trim();
                TreatmentUtils.FetchedModel fetched;
                try {
                    fetched = TreatmentUtils.fetchModelByContentHash(trimmedUrl);
                } catch (java.io.IOException e) {
                    throw new InvalidArgumentException("Could not fetch the model from " + url + ": " + e.getMessage());
                }
                FaceModelProbe.ProbeResult probe = faceModelProbe.probe(fetched.modelDir());
                if (!probe.passed()) {
                    // Previous selection stands: this throw rolls back before applyTransition writes.
                    throw new InvalidArgumentException("Model verification failed — " + probe.message());
                }
                // The probe's same-person measurement is the only per-model calibration available, so
                // a custom model brings its own threshold rather than inheriting a number tuned for
                // whatever was selected before — the scales are not comparable across models.
                applyTransition(FaceEmbeddingMode.CUSTOM, trimmedUrl, fetched.contentHash(),
                        probe.outputDim(), probe.preproc(), deriveThreshold(probe.withinSimilarity()));
                return getStatus();
            }
            default -> throw new InvalidArgumentException("Unknown embedding mode: " + newMode);
        }
    }

    /**
     * Write the new config and, when the effective model actually changed, perform the total regroup
     * transactionally. Re-selecting the identical model (same derived hash) writes nothing and skips
     * the regroup, so it neither bumps versions nor drains a needless re-embed.
     */
    private void applyTransition(FaceEmbeddingMode mode, String url, String modelHash, int outputDim,
            FaceEmbeddingPreproc preproc, Double derivedThreshold) {
        String currentHash = systemSettingsDao.getSetting(KEY_MODEL_HASH).orElse("");
        boolean modelChanged = !modelHash.equals(currentHash) || getMode() != mode;

        if (modelChanged) {
            // Snapshot old human labels before the groups vanish. NONE snapshots too, and the
            // snapshot then survives for as long as recognition stays off: turning the picker off
            // and back on reads as reversible in the UI, so the names have to outlive it and be
            // carried onto whatever clusters the re-drain builds. Nothing drains while the mode is
            // NONE, so finalizeCarryoverIfDrained stays a no-op throughout.
            faceGroupDao.refreshSnapshotFromGroups();
            faceGroupDao.clearAllFaceGroups();

            if (mode == FaceEmbeddingMode.NONE) {
                // No drain will come to replace them; see clearDetectedFaceResults.
                faceGroupDao.clearDetectedFaceResults();
            }
        }

        systemSettingsDao.setSetting(KEY_MODE, mode.name());
        systemSettingsDao.setSetting(KEY_URL, url == null ? "" : url);
        systemSettingsDao.setSetting(KEY_MODEL_HASH, modelHash);
        systemSettingsDao.setSetting(KEY_OUTPUT_DIM, Integer.toString(outputDim));
        systemSettingsDao.setSetting(KEY_PREPROC, preproc.name());
        if (derivedThreshold != null) {
            // Both: the live value the operator may then tune, and the derivation itself, so the card
            // can show what this model implied and offer a way back to it.
            systemSettingsDao.setSetting(KEY_THRESHOLD, Double.toString(derivedThreshold));
            systemSettingsDao.setSetting(KEY_DERIVED_THRESHOLD, Double.toString(derivedThreshold));
        }

        if (modelChanged) {
            int drained = faceGroupDao.markAllRecognitionUpdated();
            logger.info("Embedding model set to {} (hash {}); cleared groups and marked {} recognition "
                    + "row(s) for re-embedding", mode, modelHash, drained);
        }
    }

    /**
     * Set the grouping threshold and re-cluster every stored face at the new value, in one
     * transaction.
     *
     * <p><b>Why this is not a model change.</b> The threshold decides how embeddings are clustered,
     * not what they are — every vector stays valid and is re-used as-is. So there is no re-embedding,
     * no {@code UPDATED} re-queue and no drain: the whole regroup happens here, from stored rows,
     * costing cosine arithmetic instead of inference. Human labels are carried by the same snapshot
     * and plurality pass a model change uses, applied immediately since nothing has to drain first.
     *
     * <p>Held under the same {@code face-grouping} lock the handler takes, so a concurrent
     * recognition item cannot assign a face against groups this method is halfway through replacing.
     *
     * @throws InvalidArgumentException if the value is not a number in range, or a model-change drain
     *         is still in flight — reclustering mid-drain would cluster a partial set and be
     *         overwritten by the rows still to come
     */
    public FaceEmbeddingModelStatus setGroupingThreshold(double threshold) {
        if (!Double.isFinite(threshold) || threshold < MIN_THRESHOLD || threshold > MAX_THRESHOLD) {
            throw new InvalidArgumentException(String.format(
                    "Grouping threshold must be between %.2f and %.2f", MIN_THRESHOLD, MAX_THRESHOLD));
        }
        // Acquired outside the transaction and released after it commits, so no recognition item can
        // read half-replaced groups. An item claimed while this runs is not a problem — the lock
        // serializes it behind the recluster, after which it groups against the new clustering like
        // any other new item.
        namedLockRegistry.lock(FaceGrouping.LOCK_KEY);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                // Inside the lock, so this says what it appears to say. Read before it, it would be a
                // check-then-act: a drain beginning in the gap would leave the recluster working over
                // embeddings that are half in the old model's space and half not yet recomputed. The
                // lock does not prevent that — a drain is many transactions, and the guard is about
                // the data being complete, not about who is running.
                if (faceGroupDao.countPendingRecognition() > 0) {
                    throw new InvalidArgumentException(
                            "Face recognition is still processing; wait for it to finish before "
                            + "changing the grouping threshold");
                }

                systemSettingsDao.setSetting(KEY_THRESHOLD, Double.toString(threshold));
                Optional<String> modelHash = getModelHash();
                if (modelHash.isEmpty()) {
                    // No model: nothing is grouped, so nothing to re-cluster. The value stands for
                    // whenever one is selected — the picker overrides it only when it has a probe
                    // measurement of its own.
                    return;
                }
                recluster(modelHash.get(), threshold);
            });
        } finally {
            namedLockRegistry.unlock(FaceGrouping.LOCK_KEY);
        }
        return getStatus();
    }

    /**
     * Rebuild every group from the stored embeddings at the given threshold.
     *
     * <p>Runs the same pure {@link FaceGrouping#assign} the handler runs, one item treatment at a
     * time against the groups built so far — the drain's own semantics, so a recluster and a re-drain
     * at equal thresholds reach the same clustering. Each item's {@code detectedface} results are
     * rewritten as its faces are reassigned, because those rows are a denormalized copy of the group
     * label and nothing else will come along to fix them.
     *
     * <p>The caller holds the grouping lock and owns the transaction.
     */
    private void recluster(String modelHash, double threshold) {
        faceGroupDao.refreshSnapshotFromGroups();
        faceGroupDao.clearAllFaceGroups();

        List<FaceGroup> groups = new ArrayList<>();
        int faceCount = 0;
        for (String itemTreatmentId : faceDao.getItemTreatmentIdsWithEmbeddings(modelHash)) {
            List<FaceEmbedding> embeddings = faceDao.getFaceEmbeddingsByItemTreatmentId(itemTreatmentId);
            List<FaceGrouping.Candidate> candidates = new ArrayList<>(embeddings.size());
            for (FaceEmbedding embedding : embeddings) {
                candidates.add(new FaceGrouping.Candidate(embedding.getFaceIndex(),
                        FaceEmbeddingUtils.fromBytes(embedding.getEmbedding())));
            }

            FaceGrouping.Result result = FaceGrouping.assign(groups, candidates, threshold, modelHash);
            for (FaceGroupMutation mutation : result.mutations()) {
                faceGroupDao.upsertGroup(mutation);
            }

            List<String> labels = new ArrayList<>(result.assignments().size());
            for (FaceGrouping.Assignment assignment : result.assignments()) {
                faceDao.assignFaceGroup(itemTreatmentId, assignment.faceIndex(), assignment.groupId());
                labels.add(assignment.label());
            }
            faceGroupDao.replaceDetectedFaceResults(itemTreatmentId, labels);
            faceCount += labels.size();

            // Re-read so the next item groups against this one's updated centroids, exactly as the
            // drain has each item see the previous item's.
            groups = faceGroupDao.getGroupsByModelHash(modelHash);
        }

        // No drain to wait for — every face has been reassigned already, so the carryover that
        // normally trails a model change can run right now.
        faceGroupDao.propagateSnapshotLabels();
        logger.info("Reclustered {} face(s) into {} group(s) at threshold {}",
                faceCount, groups.size(), threshold);
    }

    /**
     * The post-drain label-carryover pass, safe to call speculatively after each recognition item
     * completes: if a snapshot is pending and no recognition rows remain to process, propagate the
     * plurality labels to the freshly-built clusters and drop the snapshot. A no-op otherwise.
     */
    @Transactional
    public void finalizeCarryoverIfDrained() {
        if (faceGroupDao.snapshotCount() == 0) {
            return;
        }
        if (faceGroupDao.countPendingRecognition() > 0) {
            return;
        }
        faceGroupDao.propagateSnapshotLabels();
    }

    /** Read-side status for the settings card: current selection plus its blast radius. */
    public FaceEmbeddingModelStatus getStatus() {
        return new FaceEmbeddingModelStatus(getMode(),
                getMode() == FaceEmbeddingMode.CUSTOM
                        ? systemSettingsDao.getSetting(KEY_URL).orElse("") : "",
                getOutputDim(),
                faceGroupDao.countDatasourcesWithRecognition(),
                getThreshold(),
                getDerivedThreshold());
    }

    /**
     * A fully-resolved embedding model: where to fetch it, its content-derived identity, its output
     * dimension and its detected preprocessing.
     */
    public record ResolvedEmbeddingModel(String url, String modelHash, int outputDim,
                                         FaceEmbeddingPreproc preproc, double threshold) {}

    /**
     * What the settings card shows: the current mode, the custom URL if any, the embedding dimension,
     * how many datasources have recognition enabled (the blast radius of a model change), and the
     * grouping threshold in force.
     */
    public record FaceEmbeddingModelStatus(FaceEmbeddingMode mode, String url, int outputDim,
                                           int enabledDatasourceCount, double threshold,
                                           double derivedThreshold) {}
}
