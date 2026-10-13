package enterfind.server.scan.treatment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import enterfind.server.model.FaceGroup;
import enterfind.server.model.FaceGroupMutation;
import enterfind.server.util.Utilities;

/**
 * The greedy, online, nearest-centroid grouping algorithm used for every fresh assignment: cosine to
 * each current centroid, best ≥ threshold joins that group (running-mean L2-normalised centroid
 * update, {@code face_count++}), otherwise a new auto-labelled "Person N" group.
 *
 * <p><b>Pure by design.</b> It takes the current groups as data and returns the decisions as data
 * (per-face assignment + the group upserts) — no database, no locks, no models. That makes it unit
 * testable against fabricated embeddings, and it is what lets the handler own the decision while the
 * DAO stays a dumb applier. Serialization against concurrent runs is the caller's job (it holds the
 * grouping lock around the read-decide-persist span); within a single call the faces are processed
 * sequentially against an in-memory working set.
 *
 * <p><b>One group per image.</b> Every call carries the faces of a single item, and a group is
 * claimable at most once within it — two faces in one photograph are two different people. This
 * deliberately reverses the earlier behaviour, under which two faces of the same new person in one
 * image landed in one group. It costs the genuine cases (a mirror, a collage, a framed photo on the
 * wall, a panorama stitch), which now yield two groups with no way for the algorithm to tell them
 * from two different people.
 *
 * <p>That price buys the invariant back. A compact embedding model maps faces too degraded to carry
 * identity onto a single "null response" direction; those vectors match each other at ~0.999, so
 * without this rule they accrete into one enormous bogus person — observed on a 40k-photo corpus as
 * a group of 943 faces drawn from 557 photos. No similarity-based exemption is safe for the mirror
 * case, because that is exactly the similarity degraded faces exhibit. Note this bounds the damage
 * rather than removing it: the volume of unidentifiable faces is the quality gate's job (see
 * {@code FaceRecognitionTreatmentHandler.DEFAULT_MIN_CONFIDENCE}), and what this prevents is their
 * collapse into one identity.
 *
 * <p>Faces arrive in descending detection confidence, so the most reliable face in an image gets
 * first claim on a contested group.
 */
public final class FaceGrouping {

    /**
     * The lock key serializing every read-decide-persist span that touches grouping: the recognition
     * handler's per-item assignment and the threshold recluster alike. It names the shared resource —
     * the group store — which is why it lives here beside the algorithm rather than on either caller.
     */
    public static final String LOCK_KEY = "face-grouping";

    private FaceGrouping() {}

    /** One face to be grouped: its stable index within the item and its embedding vector. */
    public record Candidate(int faceIndex, float[] embedding) {}

    /** Where one face landed. */
    public record Assignment(int faceIndex, String groupId, String label) {}

    /** The decision: per-face assignments plus the group rows to upsert. */
    public record Result(List<Assignment> assignments, List<FaceGroupMutation> mutations) {}

    /** Mutable working copy of a group, so within-image faces see each other's effect. */
    private static final class WorkingGroup {
        final String id;
        final String label;
        float[] centroid;
        int faceCount;
        final boolean isNew;
        boolean dirty;

        WorkingGroup(String id, String label, float[] centroid, int faceCount, boolean isNew) {
            this.id = id;
            this.label = label;
            this.centroid = centroid;
            this.faceCount = faceCount;
            this.isNew = isNew;
        }
    }

    /**
     * Assign every candidate to a group under the given model, returning the assignments and the
     * group upserts. {@code currentGroups} must already be filtered to the current model's hash (the
     * caller reads them under the grouping lock); {@code modelHash} tags any new/updated centroid.
     */
    public static Result assign(List<FaceGroup> currentGroups, List<Candidate> faces,
            double threshold, String modelHash) {
        List<WorkingGroup> working = new ArrayList<>();
        Set<String> usedLabels = new HashSet<>();
        for (FaceGroup group : currentGroups) {
            if (group.getCentroid() == null) {
                continue;
            }
            working.add(new WorkingGroup(group.getId(), group.getLabel(),
                    FaceEmbeddingUtils.fromBytes(group.getCentroid()), group.getFaceCount(), false));
            usedLabels.add(group.getLabel());
        }

        // One photo cannot show the same person twice, so a group is claimable at most once per
        // call. Skipping claimed groups inside the search rather than rejecting after it means a
        // face falls through to its best *unclaimed* group: with Alice's group taken, Bob still
        // joins Bob's rather than being forced into a new one.
        Set<String> claimedByThisItem = new HashSet<>();

        List<Assignment> assignments = new ArrayList<>();
        for (Candidate face : faces) {
            WorkingGroup best = null;
            double bestSimilarity = -1.0;
            for (WorkingGroup group : working) {
                if (claimedByThisItem.contains(group.id)) {
                    continue; // already taken by an earlier face of this same image
                }
                if (group.centroid.length != face.embedding().length) {
                    continue; // different-dim centroid cannot be the current model's space
                }
                double similarity = FaceEmbeddingUtils.cosineSimilarity(face.embedding(), group.centroid);
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    best = group;
                }
            }

            if (best != null && bestSimilarity >= threshold) {
                best.centroid = FaceEmbeddingUtils.updateCentroid(best.centroid, face.embedding(), best.faceCount);
                best.faceCount++;
                best.dirty = true;
                claimedByThisItem.add(best.id);
                assignments.add(new Assignment(face.faceIndex(), best.id, best.label));
            } else {
                String id = Utilities.generateHexId();
                String label = generateLabel(usedLabels);
                usedLabels.add(label);
                WorkingGroup created = new WorkingGroup(id, label,
                        FaceEmbeddingUtils.l2Normalize(face.embedding()), 1, true);
                created.dirty = true;
                working.add(created);
                // Claiming a *newly created* group matters most: without it two degraded faces in
                // one photo still merge, the first creating the group and the second matching it at
                // ~0.999. That is the runaway group in miniature.
                claimedByThisItem.add(id);
                assignments.add(new Assignment(face.faceIndex(), id, label));
            }
        }

        List<FaceGroupMutation> mutations = new ArrayList<>();
        for (WorkingGroup group : working) {
            if (group.dirty) {
                mutations.add(new FaceGroupMutation(group.id, group.label,
                        FaceEmbeddingUtils.toBytes(group.centroid), modelHash, group.isNew));
            }
        }
        return new Result(assignments, mutations);
    }

    /**
     * A "Person N" label not already taken in this run's working set. The caller holds the grouping
     * lock and passed in every current-model group, so avoiding {@code used} here is enough to keep
     * labels distinct across concurrent runs too — the next run reads this run's committed groups.
     */
    private static String generateLabel(Set<String> used) {
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = "Person " + (int) (Math.random() * 10_000_000);
            if (!used.contains(candidate)) {
                return candidate;
            }
        }
        return "Person " + (int) (Math.random() * 10_000_000);
    }
}
