package enterfind.server.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.exception.NotFoundException;
import enterfind.server.model.FaceGroup;
import enterfind.server.model.FaceGroupMutation;
import enterfind.server.scan.Treatments;
import enterfind.server.scan.treatment.FaceEmbeddingUtils;
import enterfind.server.util.Utilities;

/**
 * Integration coverage for {@link FaceGroupDao} against the real schema — persistence only, since
 * the grouping <em>decision</em> now lives in {@code FaceGrouping} (see {@code FaceGroupingTest}).
 * Covers {@link FaceGroupDao#upsertGroup} (insert + update), model-hash-scoped reads, rename, and
 * clear, exercising the FACE_GROUPS DDL end to end.
 */
class FaceGroupDaoTest extends BaseDatabaseTest {

    @Autowired
    private FaceGroupDao faceGroupDao;

    private static final String HASH = "modelhash-aaaa";

    private static byte[] unit(float... values) {
        return FaceEmbeddingUtils.toBytes(FaceEmbeddingUtils.l2Normalize(values));
    }

    private FaceGroupMutation newGroup(String label, byte[] centroid, String hash) {
        return new FaceGroupMutation(Utilities.generateHexId(), label, centroid, hash, true);
    }

    @Test
    void upsertInsertsNewGroupsAndUpdatesExisting() {
        FaceGroupMutation g1 = newGroup("Person 1", unit(1f, 0f, 0f, 0f), HASH);
        faceGroupDao.upsertGroup(g1);
        faceGroupDao.upsertGroup(newGroup("Person 2", unit(0f, 1f, 0f, 0f), HASH));

        List<FaceGroup> groups = faceGroupDao.getGroupsByModelHash(HASH);
        assertThat(groups).hasSize(2);

        // Update the first group's centroid. The count is not part of an upsert any more: it is
        // counted from FACE_EMBEDDINGS on read, so a group with no members reports 0 however many
        // faces the run that produced it thought it had.
        byte[] movedCentroid = unit(0.9f, 0.1f, 0f, 0f);
        faceGroupDao.upsertGroup(new FaceGroupMutation(g1.id(), g1.label(), movedCentroid, HASH, false));
        FaceGroup reloaded = faceGroupDao.getFaceGroupById(g1.id()).orElseThrow();
        assertThat(reloaded.getCentroid()).isEqualTo(movedCentroid);
        assertThat(reloaded.getFaceCount()).isZero();
    }

    @Test
    void readsAreScopedToModelHash() {
        faceGroupDao.upsertGroup(newGroup("Person 1", unit(1f, 0f, 0f, 0f), "hash-old"));
        faceGroupDao.upsertGroup(newGroup("Person 2", unit(1f, 0f, 0f, 0f), "hash-new"));

        assertThat(faceGroupDao.getGroupsByModelHash("hash-old")).hasSize(1);
        assertThat(faceGroupDao.getGroupsByModelHash("hash-new")).hasSize(1);
        assertThat(faceGroupDao.getAllFaceGroups()).hasSize(2);
    }

    /**
     * A collision is a <b>merge</b>, not an error: a label may name several groups, which is how one
     * person's age-separated clusters are unified when the API exposes no group id. Only an unknown
     * old label still throws.
     */
    @Test
    void renameUpdatesLabelAndTreatsACollisionAsAMerge() {
        faceGroupDao.upsertGroup(newGroup("Person 5", unit(1f, 0f, 0f, 0f), HASH));

        faceGroupDao.renameFaceLabel("Person 5", "Alice");
        assertThat(faceGroupDao.getFaceGroupByLabel("Alice")).isPresent();
        assertThat(faceGroupDao.getFaceGroupByLabel("Person 5")).isEmpty();

        faceGroupDao.upsertGroup(newGroup("Person 9", unit(0f, 1f, 0f, 0f), HASH));
        faceGroupDao.renameFaceLabel("Person 9", "Alice");

        assertThat(faceGroupDao.getAllFaceGroups())
                .as("two distinct clusters, one shared identity")
                .hasSize(2)
                .allSatisfy(group -> assertThat(group.getLabel()).isEqualTo("Alice"));
        assertThat(faceGroupDao.getFaceGroupByLabel("Person 9")).isEmpty();

        assertThatThrownBy(() -> faceGroupDao.renameFaceLabel("Nobody", "Bob"))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * Both writers of a group label must carry it onto the {@code detectedface} results, which are
     * what the facet and the item view actually read. The carryover pass relabels by id rather than
     * by old name, and it silently skipped the results until this pinned it: the cluster read
     * "Alice" while every photo still said "Person 12", so searching for Alice found nothing.
     */
    @Test
    void relabelingByIdAlsoRewritesTheDetectedFaceResults() {
        FaceGroupMutation group = newGroup("Person 12", unit(1f, 0f, 0f, 0f), HASH);
        faceGroupDao.upsertGroup(group);
        String itemTreatmentId = seedDetectedFaceResult("Person 12");

        // What the post-drain label-carryover pass does once the plurality vote picks a name.
        faceGroupDao.setLabel(group.id(), "Alice");

        assertThat(faceGroupDao.getFaceGroupById(group.id()).orElseThrow().getLabel())
                .isEqualTo("Alice");
        assertThat(detectedFaceValues(itemTreatmentId))
                .as("the denormalized copy the search facet and item view read")
                .containsExactly("Alice");
    }

    @Test
    void renameRewritesTheDetectedFaceResultsToo() {
        faceGroupDao.upsertGroup(newGroup("Person 5", unit(1f, 0f, 0f, 0f), HASH));
        String itemTreatmentId = seedDetectedFaceResult("Person 5");

        faceGroupDao.renameFaceLabel("Person 5", "Alice");

        assertThat(detectedFaceValues(itemTreatmentId)).containsExactly("Alice");
    }

    /**
     * What a merge is actually <em>for</em>: the search facet and the item view read the
     * {@code detectedface} results, so photographs of the two clusters must end up carrying the same
     * name. Without this the groups would agree on a label while the photos still disagreed, and
     * searching the merged name would find only half the person.
     */
    @Test
    void mergingTwoClustersUnifiesWhatSearchAndTheItemViewRead() {
        faceGroupDao.upsertGroup(newGroup("Person 5", unit(1f, 0f, 0f, 0f), HASH));
        faceGroupDao.upsertGroup(newGroup("Person 9", unit(0f, 1f, 0f, 0f), HASH));
        // Two different photographs, one of each cluster — a young and an old picture of one person.
        String youngPhoto = seedDetectedFaceResult("Person 5");
        String oldPhoto = seedDetectedFaceResult("Person 9");

        faceGroupDao.renameFaceLabel("Person 5", "Dina");
        faceGroupDao.renameFaceLabel("Person 9", "Dina");

        assertThat(detectedFaceValues(youngPhoto)).containsExactly("Dina");
        assertThat(detectedFaceValues(oldPhoto))
                .as("the second cluster's photographs answer to the same name")
                .containsExactly("Dina");
    }

    /**
     * A group must report the members it actually has, not the number it was last told it had.
     *
     * <p><b>The bug this pins</b>, found by the manual sanity test: `face_count` was a stored
     * counter that only ever went up. Removing a datasource's face treatments cascades
     * DATASOURCE_TREATMENTS → ITEM_TREATMENTS → FACE_EMBEDDINGS <em>in the database</em>, so no Java
     * code sees those members leave; the group survives (identity is instance-global, which is what
     * lets a re-added datasource re-attach to the same people) still claiming its old count. The
     * re-scan then re-embeds those faces and increments the surviving count again — two groups
     * claiming four members each while four embeddings existed in total.
     *
     * <p>It is not a cosmetic number: {@code face_count} is the weight {@code n} in the running-mean
     * centroid update {@code c' = normalise((c·n + e)/(n+1))}, so an inflated count makes every
     * later face move the centroid less than it should and the cluster ossifies around whoever
     * joined first.
     *
     * <p>A cascade is the sharpest way to state it — no application code runs at all — but the same
     * drift follows any re-embed of an already-grouped item.
     */
    @Test
    void faceCountReportsRealMembershipAfterItsEmbeddingsAreCascadedAway() {
        FaceGroupMutation group = newGroup("Person 3", unit(1f, 0f, 0f, 0f), HASH);
        faceGroupDao.upsertGroup(group);

        String datasourceId = Utilities.generateHexId();
        DaoTestFixtures fixtures = new DaoTestFixtures(dbAccess, datasourceId);
        fixtures.insertDataSource("face-count-" + datasourceId);
        String datasourceTreatmentId = fixtures.insertDatasourceTreatment(Utilities.generateHexId(),
                Treatments.FACE_RECOGNITION.name());
        String itemId = Utilities.generateHexId();
        fixtures.insertItem(itemId, "photo.jpg", "photos");
        String itemTreatmentId = fixtures.insertItemTreatment(Utilities.generateHexId(),
                datasourceTreatmentId, itemId);
        for (int faceIndex = 0; faceIndex < 2; faceIndex++) {
            dbAccess.update("""
                    INSERT INTO FACE_EMBEDDINGS (id, item_treatment_id, face_index, face_group_id,
                        embedding, model_hash)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, Utilities.generateHexId(), itemTreatmentId, faceIndex, group.id(),
                    unit(1f, 0f, 0f, 0f), HASH);
        }

        assertThat(faceGroupDao.getGroupsByModelHash(HASH))
                .singleElement()
                .extracting(FaceGroup::getFaceCount)
                .isEqualTo(2);

        // Removing the datasource treatment is a pure database cascade — this is the whole point.
        dbAccess.update("DELETE FROM DATASOURCE_TREATMENTS WHERE id = ?", datasourceTreatmentId);
        assertThat(dbAccess.queryForObject(
                "SELECT count(*) FROM FACE_EMBEDDINGS WHERE face_group_id = ?", Integer.class,
                group.id()))
                .as("the cascade took the embeddings with it")
                .isZero();

        assertThat(faceGroupDao.getGroupsByModelHash(HASH))
                .as("the group survives, so a re-added datasource can re-attach to it")
                .singleElement()
                .extracting(FaceGroup::getFaceCount)
                .as("but it must not still claim members it no longer has")
                .isEqualTo(0);
    }

    /** Seed an item and its recognition treatment carrying one {@code detectedface} result. */
    private String seedDetectedFaceResult(String label) {
        String datasourceId = Utilities.generateHexId();
        DaoTestFixtures fixtures = new DaoTestFixtures(dbAccess, datasourceId);
        fixtures.insertDataSource("face-label-" + datasourceId);
        String itemId = Utilities.generateHexId();
        fixtures.insertItem(itemId, "photo.jpg", "photos");
        String itemTreatmentId = fixtures.insertItemTreatment(Utilities.generateHexId(),
                fixtures.insertDatasourceTreatment(Utilities.generateHexId(),
                        Treatments.FACE_RECOGNITION.name()),
                itemId);
        dbAccess.update("""
                INSERT INTO ITEM_TREATMENT_RESULTS (id, item_treatment_id, key, value)
                VALUES (?, ?, 'detectedface', ?)
                """, Utilities.generateHexId(), itemTreatmentId, label);
        return itemTreatmentId;
    }

    private List<String> detectedFaceValues(String itemTreatmentId) {
        return dbAccess.query("""
                SELECT value FROM ITEM_TREATMENT_RESULTS
                WHERE item_treatment_id = ? AND key = 'detectedface'
                """, (rs, n) -> rs.getString("value"), itemTreatmentId);
    }

    @Test
    void clearAllRemovesEveryGroup() {
        faceGroupDao.upsertGroup(newGroup("Person 1", unit(1f, 0f, 0f, 0f), HASH));
        faceGroupDao.upsertGroup(newGroup("Person 2", unit(0f, 1f, 0f, 0f), HASH));
        assertThat(faceGroupDao.getAllFaceGroups()).isNotEmpty();

        faceGroupDao.clearAllFaceGroups();
        assertThat(faceGroupDao.getAllFaceGroups()).isEmpty();
    }
}
