package enterfind.server.scan.treatment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import enterfind.server.model.FaceGroup;
import enterfind.server.model.FaceGroupMutation;

/**
 * Pure unit tests for the greedy nearest-centroid grouping decision — no database, no model. This is
 * the "fresh assignment" algorithm the recognition handler runs; here it is exercised directly on
 * fabricated embeddings, which is what lets the handler own the decision and the DAO stay dumb.
 */
class FaceGroupingTest {

    private static final String HASH = "m1";
    private static final double THRESHOLD = 0.6;

    private static float[] unit(float... v) {
        return FaceEmbeddingUtils.l2Normalize(v);
    }

    private static FaceGroup group(String id, String label, float[] centroid, int faceCount) {
        return new FaceGroup(id, label, FaceEmbeddingUtils.toBytes(centroid), HASH, faceCount,
                LocalDateTime.now(), null);
    }

    private static List<FaceGrouping.Candidate> faces(float[]... vectors) {
        List<FaceGrouping.Candidate> list = new ArrayList<>();
        for (int i = 0; i < vectors.length; i++) {
            list.add(new FaceGrouping.Candidate(i, vectors[i]));
        }
        return list;
    }

    /** The groups a call produced, as input for the next call — i.e. the next photograph. */
    private static List<FaceGroup> asGroups(FaceGrouping.Result result) {
        List<FaceGroup> groups = new ArrayList<>();
        for (FaceGroupMutation m : result.mutations()) {
            groups.add(new FaceGroup(m.id(), m.label(), m.centroid(), m.modelHash(), 1,
                    LocalDateTime.now(), null));
        }
        return groups;
    }

    @Test
    void similarFacesInDifferentPhotosClusterWhileDissimilarOnesDoNot() {
        // One face in the first photograph.
        FaceGrouping.Result photo1 = FaceGrouping.assign(List.of(),
                faces(unit(1f, 0f, 0f, 0f)), THRESHOLD, HASH);
        String personA = photo1.assignments().get(0).groupId();

        // Second photograph: a near-identical face plus an orthogonal one.
        FaceGrouping.Result photo2 = FaceGrouping.assign(asGroups(photo1),
                faces(unit(0.97f, 0.05f, 0f, 0f), unit(0f, 0f, 1f, 0f)), THRESHOLD, HASH);

        assertEquals(personA, photo2.assignments().get(0).groupId(),
                "the same person across two photographs should cluster together");
        assertNotEquals(personA, photo2.assignments().get(1).groupId(),
                "the orthogonal face should start a new group");

        // One new group, and the existing one updated. Membership is not carried on the mutation —
        // it is counted from the embeddings the same transaction writes — so what a mutation asserts
        // is the group's identity and its final centroid, not a tally.
        assertEquals(2, photo2.mutations().size());
        FaceGroupMutation rejoined = photo2.mutations().stream()
                .filter(m -> m.id().equals(personA)).findFirst().orElseThrow();
        assertTrue(!rejoined.isNew());
        assertEquals(HASH, rejoined.modelHash());
    }

    /**
     * The reversal. Two near-identical faces in ONE photograph were previously one group, on the
     * reasoning that they were the same new person seen twice. They are now two, because the far
     * commoner cause of two near-identical vectors in one frame is a model emitting its null
     * response for two different, unidentifiable people.
     */
    @Test
    void twoNearIdenticalFacesInOnePhotoDoNotShareAGroup() {
        FaceGrouping.Result result = FaceGrouping.assign(List.of(),
                faces(unit(1f, 0f, 0f, 0f), unit(0.999f, 0.01f, 0f, 0f)), THRESHOLD, HASH);

        assertNotEquals(result.assignments().get(0).groupId(), result.assignments().get(1).groupId(),
                "one photograph cannot show the same person twice");
        assertEquals(2, result.mutations().size(), "each face created its own group");
    }

    /** The constraint is per photograph, not global — otherwise nobody could ever be recognised. */
    @Test
    void theSameTwoEmbeddingsInSeparatePhotosDoShareAGroup() {
        float[] face = unit(1f, 0f, 0f, 0f);

        FaceGrouping.Result photo1 = FaceGrouping.assign(List.of(), faces(face), THRESHOLD, HASH);
        FaceGrouping.Result photo2 =
                FaceGrouping.assign(asGroups(photo1), faces(face), THRESHOLD, HASH);

        assertEquals(photo1.assignments().get(0).groupId(), photo2.assignments().get(0).groupId(),
                "the same face in a second photograph belongs to the same person");
    }

    /**
     * With Alice's group claimed by an earlier face, Bob must still reach Bob's group rather than
     * being pushed into a new one — the reason claimed groups are skipped inside the search rather
     * than rejected after it.
     */
    @Test
    void aFaceWhoseBestGroupIsClaimedFallsToItsNextBestAboveThreshold() {
        FaceGroup alice = group("g-alice", "Alice", unit(1f, 0f, 0f, 0f), 3);
        FaceGroup bob = group("g-bob", "Bob", unit(0f, 1f, 0f, 0f), 3);

        // Face 0 is overwhelmingly Alice. Face 1 is nearer Alice (0.8) than Bob (0.6), but both
        // clear the threshold — so with Alice taken it must land on Bob, not on a new group.
        FaceGrouping.Result result = FaceGrouping.assign(List.of(alice, bob),
                faces(unit(1f, 0.1f, 0f, 0f), unit(0.8f, 0.6f, 0f, 0f)), THRESHOLD, HASH);

        assertEquals("g-alice", result.assignments().get(0).groupId());
        assertEquals("g-bob", result.assignments().get(1).groupId(),
                "the second face should fall through to its best unclaimed group");
        assertEquals("Bob", result.assignments().get(1).label());
    }

    @Test
    void aFaceWithNoUnclaimedGroupAboveThresholdStartsANewOne() {
        FaceGroup alice = group("g-alice", "Alice", unit(1f, 0f, 0f, 0f), 3);

        // Both faces are close to Alice, but only one may have her.
        FaceGrouping.Result result = FaceGrouping.assign(List.of(alice),
                faces(unit(1f, 0.1f, 0f, 0f), unit(0.9f, 0.2f, 0f, 0f)), THRESHOLD, HASH);

        assertEquals("g-alice", result.assignments().get(0).groupId());
        String second = result.assignments().get(1).groupId();
        assertNotEquals("g-alice", second, "Alice was already claimed by face 0");
        FaceGroupMutation created = result.mutations().stream()
                .filter(m -> m.id().equals(second)).findFirst().orElseThrow();
        assertTrue(created.isNew(), "the displaced face starts a genuinely new group");
    }

    @Test
    void faceJoinsAnExistingGroupAboveThreshold() {
        FaceGroup existing = group("g-existing", "Alice", unit(1f, 0f, 0f, 0f), 4);
        FaceGrouping.Result result = FaceGrouping.assign(List.of(existing),
                faces(unit(0.98f, 0.02f, 0f, 0f)), THRESHOLD, HASH);

        assertEquals("g-existing", result.assignments().get(0).groupId());
        assertEquals("Alice", result.assignments().get(0).label());
        // One mutation: the existing group updated, not new.
        assertEquals(1, result.mutations().size());
        FaceGroupMutation m = result.mutations().get(0);
        assertEquals("g-existing", m.id());
        assertTrue(!m.isNew());

        // The group's reported membership is the weight in the running mean, so a group of 4 must
        // move a quarter as far as a group of 1 would. Asserting the resulting centroid pins that
        // the count was actually used — the reason an inflated count was a correctness bug and not
        // just a wrong number on a screen.
        float[] expected = FaceEmbeddingUtils.updateCentroid(
                unit(1f, 0f, 0f, 0f), unit(0.98f, 0.02f, 0f, 0f), 4);
        assertArrayEquals(expected, FaceEmbeddingUtils.fromBytes(m.centroid()), 1e-6f);
    }

    @Test
    void faceBelowThresholdStartsNewGroupNotJoiningTheDistantOne() {
        FaceGroup existing = group("g-existing", "Alice", unit(1f, 0f, 0f, 0f), 4);
        FaceGrouping.Result result = FaceGrouping.assign(List.of(existing),
                faces(unit(0f, 1f, 0f, 0f)), THRESHOLD, HASH);

        assertNotEquals("g-existing", result.assignments().get(0).groupId());
        FaceGroupMutation m = result.mutations().get(0);
        assertTrue(m.isNew());
        assertTrue(m.label().startsWith("Person "));
    }

    @Test
    void newLabelsAvoidExistingLabels() {
        // An existing group already named "Person 5"; a new distant face must not reuse that label.
        FaceGroup existing = group("g5", "Person 5", unit(1f, 0f, 0f, 0f), 1);
        FaceGrouping.Result result = FaceGrouping.assign(List.of(existing),
                faces(unit(0f, 1f, 0f, 0f)), THRESHOLD, HASH);
        assertNotEquals("Person 5", result.assignments().get(0).label());
    }
}
