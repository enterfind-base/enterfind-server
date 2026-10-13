package enterfind.server.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.model.FaceDetection;
import enterfind.server.model.FaceGroup;
import enterfind.server.model.ItemFace;
import enterfind.server.scan.Treatments;
import enterfind.server.util.Utilities;

/**
 * Integration coverage for {@link FaceDao} against the real schema: the two per-item-treatment face
 * tables, their cascades, and the detection-to-label join the item view reads.
 *
 * <p>Split out of {@code TreatmentDaoTest} when the face rows moved to their own DAO. What stayed
 * there is {@code saveItemTreatmentResult}'s contract <em>over</em> these rows — null versus empty,
 * and the dependency invalidation — because that is the treatment pipeline's behaviour, not
 * storage.
 */
class FaceDaoTest extends BaseDatabaseTest {

    @Autowired
    private FaceDao faceDao;

    @Autowired
    private FaceGroupDao faceGroupDao;

    @Autowired
    private TreatmentDao treatmentDao;

    private String testDataSourceId;

    @BeforeEach
    void setUpTestData() {
        super.setUp();
        testDataSourceId = Utilities.generateHexId();
        dbAccess.update("""
                INSERT INTO DATA_SOURCES (id, name, cron_pattern, type, config, date_created)
                VALUES (?, 'test-datasource', '0 0 * * *', 'LOCAL_FILESYSTEM', '{}'::jsonb, ?)
                """, testDataSourceId, LocalDateTime.now());
    }

    // ==================== FACE_DETECTIONS storage ====================
    //
    // The write methods under test are package-private on purpose: production's only path in is
    // TreatmentDao.saveItemTreatmentResult, so that geometry and the facecount addressing it commit
    // together. These tests share the package and call them directly to exercise the storage itself.

    private static final String FACE_ITEM_ID = "FDITEM0000000001";
    private static final String OTHER_FACE_ITEM_ID = "FDITEM0000000002";

    private String faceDatasourceTreatmentId;
    private String faceItemTreatmentId;
    private String otherFaceItemTreatmentId;

    /**
     * Seed two items and the FACE_DETECTION item treatments that own their detections. Both items
     * hang off one datasource treatment, as they would in production: a datasource has at most one
     * FACE_DETECTION treatment, so each item has at most one item treatment able to store faces.
     */
    private void seedFaceItems() {
        DaoTestFixtures fixtures = new DaoTestFixtures(dbAccess, testDataSourceId);
        fixtures.insertItem(FACE_ITEM_ID, "test-photo.jpg", "photos");
        fixtures.insertItem(OTHER_FACE_ITEM_ID, "other-photo.jpg", "photos");
        faceDatasourceTreatmentId = fixtures.insertDatasourceTreatment(
            Utilities.generateHexId(), Treatments.FACE_DETECTION.name());
        faceItemTreatmentId = fixtures.insertItemTreatment(
            Utilities.generateHexId(), faceDatasourceTreatmentId, FACE_ITEM_ID);
        otherFaceItemTreatmentId = fixtures.insertItemTreatment(
            Utilities.generateHexId(), faceDatasourceTreatmentId, OTHER_FACE_ITEM_ID);
    }

    /** Landmarks in the fixed order: left eye, right eye, nose, left mouth, right mouth. */
    private static float[][] landmarks(float offset) {
        return new float[][] {
            {38.25f + offset, 51.5f},
            {73.5f + offset, 51.25f},
            {56.0f + offset, 71.75f},
            {41.5f + offset, 92.25f},
            {70.75f + offset, 92.0f},
        };
    }

    private FaceDetection createFace(String itemTreatmentId, int faceIndex) {
        return faceDao.createFaceDetection(itemTreatmentId, faceIndex, 0.9f,
            0.1f, 0.2f, 0.3f, 0.4f, landmarks(0));
    }

    @Test
    void createFaceDetection_returnsPopulatedRow() {
        seedFaceItems();

        FaceDetection detection = faceDao.createFaceDetection(faceItemTreatmentId, 0, 0.875f,
            0.1f, 0.2f, 0.3f, 0.4f, landmarks(0));

        assertThat(detection.getId()).isNotNull().hasSize(16);
        assertThat(detection.getFaceIndex()).isZero();
        assertThat(detection.getConfidence()).isEqualTo(0.875f);
        assertThat(detection.getDateCreated()).isNotNull();
    }

    @Test
    void createThenRead_preservesGeometryAndLandmarks() {
        seedFaceItems();

        faceDao.createFaceDetection(faceItemTreatmentId, 0, 0.875f, 0.1f, 0.2f, 0.3f, 0.4f, landmarks(0));

        FaceDetection stored = faceDao.getFaceDetectionsByItemId(FACE_ITEM_ID).get(0);

        assertThat(stored.getConfidence()).isEqualTo(0.875f);
        assertThat(stored.getBboxX()).isEqualTo(0.1f);
        assertThat(stored.getBboxY()).isEqualTo(0.2f);
        assertThat(stored.getBboxW()).isEqualTo(0.3f);
        assertThat(stored.getBboxH()).isEqualTo(0.4f);
        // Landmarks must survive the BYTEA round trip exactly - FACE_RECOGNITION re-aligns faces from these
        // rather than re-detecting, so any loss here degrades embeddings later with no signal.
        assertThat(stored.getLandmarks()).isDeepEqualTo(landmarks(0));
    }

    @Test
    void createFaceDetection_toleratesNullLandmarks() {
        seedFaceItems();

        faceDao.createFaceDetection(faceItemTreatmentId, 0, 0.9f, 0.1f, 0.2f, 0.3f, 0.4f, null);

        assertThat(faceDao.getFaceDetectionsByItemId(FACE_ITEM_ID).get(0).getLandmarks()).isNull();
    }

    @Test
    void createFaceDetection_rejectsWrongLandmarkCountBeforeWritingAnything() {
        seedFaceItems();

        assertThatThrownBy(() -> faceDao.createFaceDetection(faceItemTreatmentId, 0, 0.9f,
                0.1f, 0.2f, 0.3f, 0.4f, new float[][] {{1, 2}, {3, 4}}))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("5 landmarks");

        // The codec's own rejection is FaceLandmarkUtilsTest's; what belongs here is that the DAO
        // neither swallows it nor leaves a row behind with the geometry but no usable landmarks.
        assertThat(faceDao.getFaceDetectionsByItemId(FACE_ITEM_ID)).isEmpty();
    }

    @Test
    void getFaceDetectionsByItemId_ordersByFaceIndex() {
        seedFaceItems();

        // Inserted out of order on purpose: the UI iterates 0..N-1 and asks for one crop per index,
        // so the read path must not depend on insertion or id order.
        createFace(faceItemTreatmentId, 2);
        createFace(faceItemTreatmentId, 0);
        createFace(faceItemTreatmentId, 1);

        List<FaceDetection> detections = faceDao.getFaceDetectionsByItemId(FACE_ITEM_ID);

        assertThat(detections).extracting(FaceDetection::getFaceIndex).containsExactly(0, 1, 2);
    }

    @Test
    void getFaceDetectionsByItemId_isScopedToTheItem() {
        seedFaceItems();

        createFace(faceItemTreatmentId, 0);
        createFace(otherFaceItemTreatmentId, 0);

        assertThat(faceDao.getFaceDetectionsByItemId(FACE_ITEM_ID)).hasSize(1);
        assertThat(faceDao.getFaceDetectionsByItemId("no-such-item")).isEmpty();
    }

    @Test
    void getFaceDetectionByItemAndIndex_findsTheRequestedFace() {
        seedFaceItems();

        createFace(faceItemTreatmentId, 0);
        FaceDetection second = faceDao.createFaceDetection(faceItemTreatmentId, 1, 0.5f,
            0.5f, 0.6f, 0.1f, 0.1f, landmarks(10));

        Optional<FaceDetection> found = faceDao.getFaceDetectionByItemAndIndex(FACE_ITEM_ID, 1);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(second.getId());
        assertThat(found.get().getBboxX()).isEqualTo(0.5f);
    }

    @Test
    void getFaceDetectionByItemAndIndex_missingIndexIsEmpty() {
        seedFaceItems();

        createFace(faceItemTreatmentId, 0);

        // A miss is expected and benign while an item is reprocessed; the endpoint 404s and the
        // item view's onerror handler hides the thumbnail.
        assertThat(faceDao.getFaceDetectionByItemAndIndex(FACE_ITEM_ID, 5)).isEmpty();
        assertThat(faceDao.getFaceDetectionByItemAndIndex("no-such-item", 0)).isEmpty();
    }

    @Test
    void duplicateFaceIndexWithinAnItemIsRejected() {
        seedFaceItems();

        createFace(faceItemTreatmentId, 0);

        // UNIQUE (item_id, face_index) is what turns a duplicate into a database error instead of
        // an ambiguous lookup serving a silently wrong cutout.
        assertThatThrownBy(() -> createFace(faceItemTreatmentId, 0)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void sameFaceIndexInDifferentItemsIsAllowed() {
        seedFaceItems();

        createFace(faceItemTreatmentId, 0);
        createFace(otherFaceItemTreatmentId, 0);

        assertThat(faceDao.getFaceDetectionsByItemId(FACE_ITEM_ID)).hasSize(1);
        assertThat(faceDao.getFaceDetectionsByItemId(OTHER_FACE_ITEM_ID)).hasSize(1);
    }

    @Test
    void deleteFaceDetectionsByItemTreatmentId_removesOnlyThatTreatmentsFaces() {
        seedFaceItems();

        createFace(faceItemTreatmentId, 0);
        createFace(faceItemTreatmentId, 1);
        createFace(otherFaceItemTreatmentId, 0);

        int deleted = faceDao.deleteFaceDetectionsByItemTreatmentId(faceItemTreatmentId);

        assertThat(deleted).isEqualTo(2);
        assertThat(faceDao.getFaceDetectionsByItemId(FACE_ITEM_ID)).isEmpty();
        assertThat(faceDao.getFaceDetectionsByItemId(OTHER_FACE_ITEM_ID)).hasSize(1);
    }

    @Test
    void deleteFaceDetectionsByItemTreatmentId_withNoFacesDeletesNothing() {
        seedFaceItems();

        assertThat(faceDao.deleteFaceDetectionsByItemTreatmentId(faceItemTreatmentId)).isZero();
    }

    @Test
    void deletingTheItemTreatmentCascadesToItsDetections() {
        seedFaceItems();

        createFace(faceItemTreatmentId, 0);
        createFace(otherFaceItemTreatmentId, 0);

        treatmentDao.deleteItemTreatment(faceDatasourceTreatmentId, FACE_ITEM_ID);

        assertThat(faceDao.getFaceDetectionsByItemId(FACE_ITEM_ID)).isEmpty();
        assertThat(faceDao.getFaceDetectionsByItemId(OTHER_FACE_ITEM_ID)).hasSize(1);
    }

    @Test
    void removingTheTreatmentCascadesToEveryDetectionItProduced() {
        seedFaceItems();

        createFace(faceItemTreatmentId, 0);
        createFace(otherFaceItemTreatmentId, 0);

        // The reason detections hang off ITEM_TREATMENTS at all. Before the foreign key existed,
        // turning the treatment off deleted its results but stranded the geometry: rows the UI
        // could no longer reach, still served by the crop endpoint, alive until the item was.
        assertThat(treatmentDao.removeTreatment(testDataSourceId, Treatments.FACE_DETECTION)).isTrue();

        assertThat(faceDao.getFaceDetectionsByItemId(FACE_ITEM_ID)).isEmpty();
        assertThat(faceDao.getFaceDetectionsByItemId(OTHER_FACE_ITEM_ID)).isEmpty();
    }

    @Test
    void reinsertingAfterDeleteReusesTheSameIndices() {
        seedFaceItems();

        createFace(faceItemTreatmentId, 0);
        createFace(faceItemTreatmentId, 1);

        faceDao.deleteFaceDetectionsByItemTreatmentId(faceItemTreatmentId);
        createFace(faceItemTreatmentId, 0);
        createFace(faceItemTreatmentId, 1);

        assertThat(faceDao.getFaceDetectionsByItemId(FACE_ITEM_ID))
            .extracting(FaceDetection::getFaceIndex).containsExactly(0, 1);
    }

    @Test
    void deletingTheItemCascadesToItsDetections() {
        seedFaceItems();

        createFace(faceItemTreatmentId, 0);
        createFace(otherFaceItemTreatmentId, 0);

        dbAccess.update("DELETE FROM ITEMS WHERE id = ?", FACE_ITEM_ID);

        assertThat(faceDao.getFaceDetectionsByItemId(FACE_ITEM_ID)).isEmpty();
        assertThat(faceDao.getFaceDetectionsByItemId(OTHER_FACE_ITEM_ID)).hasSize(1);
    }

    /**
     * Completing a detection re-drives the same item's recognition. Pinned because the statement
     * that does it is a multi-table UPDATE whose shape Postgres rejects if the target table is
     * referenced from a FROM-clause join — it threw on every save until this covered it.
     */

    // ==================== Face + label pairing for the item view ====================

    /**
     * The pairing the item view captions its crops with. Detection and recognition are different
     * item treatments, so this is the join no client can do: the detectedface results carry the
     * names but no face index.
     */
    @Test
    void getFacesByItemId_pairsEveryDetectionWithItsLabelAndNullForTheRest() {
        seedFaceItems();
        createFace(faceItemTreatmentId, 0);
        createFace(faceItemTreatmentId, 1);
        createFace(faceItemTreatmentId, 2);

        DaoTestFixtures fixtures = new DaoTestFixtures(dbAccess, testDataSourceId);
        String recognitionDatasourceTreatmentId = fixtures.insertDatasourceTreatment(
            Utilities.generateHexId(), Treatments.FACE_RECOGNITION.name());
        String recognitionItemTreatmentId = fixtures.insertItemTreatment(
            Utilities.generateHexId(), recognitionDatasourceTreatmentId, FACE_ITEM_ID);

        FaceGroup alice = faceGroupDao.createFaceGroup("Alice", new byte[] {1, 2, 3, 4}, "hash-1");
        FaceGroup bob = faceGroupDao.createFaceGroup("Bob", new byte[] {5, 6, 7, 8}, "hash-1");
        // Face 1 was detected but never embedded - no landmarks, or a degenerate vector.
        faceDao.createFaceEmbedding(recognitionItemTreatmentId, 0, alice.getId(),
            new byte[] {1, 2, 3, 4}, "hash-1");
        faceDao.createFaceEmbedding(recognitionItemTreatmentId, 2, bob.getId(),
            new byte[] {5, 6, 7, 8}, "hash-1");

        assertThat(faceDao.getFacesByItemId(FACE_ITEM_ID))
            .extracting(ItemFace::faceIndex, ItemFace::label)
            .containsExactly(tuple(0, "Alice"), tuple(1, null), tuple(2, "Bob"));
    }

    @Test
    void getFacesByItemId_returnsEveryDetectionUnlabeledWhenRecognitionNeverRan() {
        seedFaceItems();
        createFace(faceItemTreatmentId, 0);
        createFace(faceItemTreatmentId, 1);

        // The detection-only instance: no embedding model selected, so the crops render with no
        // captions rather than not rendering at all.
        assertThat(faceDao.getFacesByItemId(FACE_ITEM_ID))
            .extracting(ItemFace::faceIndex, ItemFace::label)
            .containsExactly(tuple(0, null), tuple(1, null));
    }

    @Test
    void getFacesByItemId_doesNotLeakAnotherItemsFaces() {
        seedFaceItems();
        createFace(faceItemTreatmentId, 0);
        createFace(otherFaceItemTreatmentId, 0);

        assertThat(faceDao.getFacesByItemId(OTHER_FACE_ITEM_ID)).hasSize(1);
    }
}
