package enterfind.server.scan;

import java.util.List;

import enterfind.server.model.FaceDetection;
import enterfind.server.model.FaceEmbedding;
import enterfind.server.model.FaceGroupMutation;

/**
 * Everything a treatment handler produces for one item, and the channel by which a handler
 * influences the save transaction that persists it.
 *
 * <p>Most treatments produce only {@code results} — the key/value pairs that become
 * ITEM_TREATMENT_RESULTS rows. The face treatments produce typed side data as well, all of which
 * must land in the one save transaction:
 *
 * <ul>
 *   <li>FACE_DETECTION → {@code faceDetections} geometry, committed with {@code facecount}.</li>
 *   <li>FACE_RECOGNITION → {@code faceEmbeddings} (each already carrying its assigned group id),
 *       {@code groupMutations} (the FACE_GROUPS upserts its grouping decided), and the
 *       {@code detectedface} labels in {@code results}. The handler makes the grouping decision; the
 *       DAO is a dumb applier. All three commit together.</li>
 * </ul>
 *
 * <p><b>{@code locks}</b> carries the names of {@code NamedLockRegistry} locks the handler acquired
 * and is still holding on return. The {@code TreatmentProcessor} releases them in the {@code finally}
 * around the apply→save lifecycle — after the save's external {@code @Transactional} call returns,
 * i.e. <em>after it commits</em> — so a lock's lifetime spans the handler's decision and the
 * persistence of that decision. FACE_RECOGNITION holds one grouping lock so its read-decide-persist
 * of the global FACE_GROUPS store is serialized against concurrent runs; every other treatment holds
 * none, so the release is a no-op for them. Acquire is a domain decision (handler); release is
 * mechanical (processor). The DAO knows nothing about locks.
 *
 * <p>An empty side-data list means "the run produced none" and clears whatever a previous run
 * stored; the FAILED "leave-alone" case is a {@code null} passed to {@code saveItemTreatmentResult},
 * not represented here.
 *
 * @param results        key/value results, never null
 * @param faceDetections face geometry produced by this run, never null
 * @param faceEmbeddings face embeddings produced by this run (with assigned group ids), never null
 * @param groupMutations FACE_GROUPS upserts this run decided, never null
 * @param locks          NamedLockRegistry keys held on return, to be released after the save commits
 */
public record TreatmentOutput(List<TreatmentResult> results, List<FaceDetection> faceDetections,
                              List<FaceEmbedding> faceEmbeddings, List<FaceGroupMutation> groupMutations,
                              List<String> locks) {

    public TreatmentOutput {
        results = results == null ? List.of() : results;
        faceDetections = faceDetections == null ? List.of() : faceDetections;
        faceEmbeddings = faceEmbeddings == null ? List.of() : faceEmbeddings;
        groupMutations = groupMutations == null ? List.of() : groupMutations;
        locks = locks == null ? List.of() : locks;
    }

    /** Output carrying results and face geometry (FACE_DETECTION), no embeddings. */
    public TreatmentOutput(List<TreatmentResult> results, List<FaceDetection> faceDetections) {
        this(results, faceDetections, List.of(), List.of(), List.of());
    }

    /** Output for a treatment that produces key/value results only. */
    public static TreatmentOutput of(List<TreatmentResult> results) {
        return new TreatmentOutput(results, List.of(), List.of(), List.of(), List.of());
    }

    /** Output carrying results, embeddings, group upserts and held locks (FACE_RECOGNITION). */
    public static TreatmentOutput ofRecognition(List<TreatmentResult> results,
            List<FaceEmbedding> faceEmbeddings, List<FaceGroupMutation> groupMutations, List<String> locks) {
        return new TreatmentOutput(results, List.of(), faceEmbeddings, groupMutations, locks);
    }
}
