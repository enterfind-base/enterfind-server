package enterfind.server.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the label-carryover plurality vote — the mechanics, against fabricated
 * tallies, with no model or database. Covers each row of the doc's carryover table.
 *
 * <p>Input is {@code groupId -> (oldLabel -> count among that group's members)}; output is the
 * {@code groupId -> label} the propagation pass would write.
 */
class FaceLabelCarryoverTest {

    private static Map<String, Integer> counts(Object... pairs) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return m;
    }

    @Test
    void keptGroupCarriesTheNameCleanly() {
        Map<String, Map<String, Integer>> tally = new HashMap<>();
        tally.put("g1", counts("Alice", 5));
        Map<String, String> result = FaceGroupDao.computeLabelAssignments(tally);
        assertEquals("Alice", result.get("g1"));
    }

    @Test
    void splitKeepsNameOnLargerPieceSmallerStaysUnlabeled() {
        // Alice's old group split into g1 (larger) and g2 (smaller); both vote Alice.
        Map<String, Map<String, Integer>> tally = new HashMap<>();
        tally.put("g1", counts("Alice", 8));
        tally.put("g2", counts("Alice", 3));
        Map<String, String> result = FaceGroupDao.computeLabelAssignments(tally);
        assertEquals("Alice", result.get("g1"));
        assertNull(result.get("g2"), "smaller split piece should surface unlabeled");
    }

    @Test
    void mergeWithUnnamedCarriesTheName() {
        // One cluster mixing named Alice faces with previously-unnamed ones (unnamed contribute no
        // snapshot label, so they simply do not appear in the tally).
        Map<String, Map<String, Integer>> tally = new HashMap<>();
        tally.put("g1", counts("Alice", 4));
        Map<String, String> result = FaceGroupDao.computeLabelAssignments(tally);
        assertEquals("Alice", result.get("g1"));
    }

    @Test
    void differentlyNamedMergeTakesTheMajority() {
        // "Alice" + "Alicia" judged one person by the new model: majority wins, the other dropped.
        Map<String, Map<String, Integer>> tally = new HashMap<>();
        tally.put("g1", counts("Alice", 6, "Alicia", 2));
        Map<String, String> result = FaceGroupDao.computeLabelAssignments(tally);
        assertEquals("Alice", result.get("g1"));
    }

    @Test
    void withinGroupTieYieldsNoLabel() {
        Map<String, Map<String, Integer>> tally = new HashMap<>();
        tally.put("g1", counts("Alice", 3, "Bob", 3));
        Map<String, String> result = FaceGroupDao.computeLabelAssignments(tally);
        assertNull(result.get("g1"), "an exact within-group tie assigns no label");
    }

    @Test
    void clusterWithNoLabeledMembersStaysUnlabeled() {
        // A genuinely new person: no snapshot labels among its members, so it never enters the tally.
        Map<String, Map<String, Integer>> tally = new HashMap<>();
        Map<String, String> result = FaceGroupDao.computeLabelAssignments(tally);
        assertFalse(result.containsKey("g_new"));
    }

    /**
     * The merge undo. Because the API exposes no group id, a merge is performed by giving several
     * clusters one label, and it cannot be reversed by renaming — renaming would move all of them.
     * The escape hatch is a recluster: this global-uniqueness rule then hands the label to one
     * claimant only, so the merged clusters come back as unlabeled "Person N" to be re-curated.
     *
     * <p><b>That makes this rule load-bearing, not tidiness.</b> Relaxing it so every claimant keeps
     * the label would let merges survive a recluster — and would leave no way back at all.
     */
    @Test
    void aMergedLabelCollapsesToOneClusterOnRecluster() {
        // Three clusters of one person, merged by the operator under a single label. After a
        // recluster each still votes "Dina"; only the largest keeps the name.
        Map<String, Map<String, Integer>> merged = new HashMap<>();
        merged.put("g_big", counts("Dina", 900));
        merged.put("g_mid", counts("Dina", 300));
        merged.put("g_small", counts("Dina", 30));

        Map<String, String> result = FaceGroupDao.computeLabelAssignments(merged);

        assertEquals("Dina", result.get("g_big"));
        assertNull(result.get("g_mid"), "the merge is undone, not preserved");
        assertNull(result.get("g_small"), "the merge is undone, not preserved");
    }

    @Test
    void contestedLabelGoesToLargerClusterAndTiesBreakByIdSoTheNameSurvives() {
        // Two clusters each plurality-vote Alice with EQUAL counts — what splitting a person looks
        // like. Exactly one wins, chosen by id, because losing the name entirely is worse than
        // putting it on an arbitrary half the operator can see and rename.
        Map<String, Map<String, Integer>> equal = new HashMap<>();
        equal.put("g1", counts("Alice", 4));
        equal.put("g2", counts("Alice", 4));
        Map<String, String> equalResult = FaceGroupDao.computeLabelAssignments(equal);
        assertEquals("Alice", equalResult.get("g1"));
        assertNull(equalResult.get("g2"));

        // Same tally, opposite insertion order: the winner must not depend on iteration order.
        Map<String, Map<String, Integer>> reversed = new HashMap<>();
        reversed.put("g2", counts("Alice", 4));
        reversed.put("g1", counts("Alice", 4));
        assertEquals("Alice", FaceGroupDao.computeLabelAssignments(reversed).get("g1"));

        // Unequal: strictly larger wins.
        Map<String, Map<String, Integer>> unequal = new HashMap<>();
        unequal.put("g1", counts("Alice", 5));
        unequal.put("g2", counts("Alice", 4));
        Map<String, String> unequalResult = FaceGroupDao.computeLabelAssignments(unequal);
        assertEquals("Alice", unequalResult.get("g1"));
        assertNull(unequalResult.get("g2"));
    }
}
