package enterfind.server.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import enterfind.server.BaseDatabaseTest;

/**
 * Tests for {@code Schema.dropLegacyFaceTables()} — the one-shot clean slate that drops the
 * pre-split FACE_DETECTIONS/FACE_GROUPS tables and removes the retired FACE_RECOGNITION treatment.
 *
 * <p>Unlike the DML migration tests, this one leaves the schema itself changed, so each test puts
 * it back in {@link #restoreSchema()}. {@code wipeDatabase()} clears rows, not tables.
 */
class FaceTableMigrationTest extends BaseDatabaseTest {

    @Autowired
    private Schema schema;

    private static final String DS_ID = "FACEMIG01";

    private DaoTestFixtures fixtures;

    @BeforeEach
    void createDataSource() {
        fixtures = new DaoTestFixtures(dbAccess, DS_ID);
        fixtures.insertDataSource("Face Migration Test DS");
    }

    @AfterEach
    void restoreSchema() {
        // Leave the database in the shape the rest of the suite expects.
        schema.createSchema();
    }

    /**
     * Rebuild the legacy face schema so the migration has something to find.
     *
     * <p>Uses {@code dbAccess.execute}, as every other test does, rather than the
     * {@code executeAdmin} helper the migration itself uses: that one exists for production schema
     * work that must escape the query timeout, and borrowing it here would put fixture setup on a
     * different connection from the assertions for no reason.
     */
    private void createLegacyFaceTables() {
        dbAccess.execute("DROP TABLE IF EXISTS FACE_DETECTIONS");
        // FACE_GROUPS has to go first: it is also a current table, and createSchema()
        // has already built it by the time any test runs. Nothing references it by foreign key —
        // FACE_EMBEDDINGS.face_group_id is a soft reference — so a plain drop is enough.
        dbAccess.execute("DROP TABLE IF EXISTS FACE_GROUPS");
        dbAccess.execute("""
            CREATE TABLE FACE_GROUPS (
                id VARCHAR(16) PRIMARY KEY,
                label VARCHAR(100) NOT NULL UNIQUE,
                centroid_embedding BYTEA NOT NULL,
                face_count INTEGER NOT NULL DEFAULT 1,
                date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                date_modified TIMESTAMP
            )
            """);
        dbAccess.execute("""
            CREATE TABLE FACE_DETECTIONS (
                id VARCHAR(16) PRIMARY KEY,
                item_id VARCHAR(16) NOT NULL REFERENCES ITEMS(id) ON DELETE CASCADE,
                face_group_id VARCHAR(16) REFERENCES FACE_GROUPS(id) ON DELETE CASCADE,
                embedding BYTEA NOT NULL,
                confidence REAL NOT NULL,
                bbox_x REAL, bbox_y REAL, bbox_w REAL, bbox_h REAL,
                date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """);
    }

    /** Seed a group in the legacy shape (label + centroid), as the old grouping step wrote it. */
    private void insertLegacyFaceGroup(String id, String label) {
        dbAccess.update("INSERT INTO FACE_GROUPS (id, label, centroid_embedding) VALUES (?, ?, ?)",
            id, label, new byte[]{1, 2, 3, 4});
    }

    /** Seed a detection in the legacy shape: embedding NOT NULL, linked straight to a group. */
    private void insertLegacyFaceDetection(String id, String itemId, String faceGroupId) {
        dbAccess.update("""
            INSERT INTO FACE_DETECTIONS (id, item_id, face_group_id, embedding, confidence)
            VALUES (?, ?, ?, ?, ?)
            """, id, itemId, faceGroupId, new byte[]{1, 2, 3, 4}, 0.9f);
    }

    /** Seed a detection in the new shape: owned by an item treatment, indexed within it. */
    private void insertFaceDetection(String id, String itemTreatmentId, int faceIndex) {
        dbAccess.update("""
            INSERT INTO FACE_DETECTIONS (id, item_treatment_id, face_index, confidence)
            VALUES (?, ?, ?, ?)
            """, id, itemTreatmentId, faceIndex, 0.95f);
    }

    /**
     * Seed a FACE_DETECTIONS in the intermediate shape — the current columns minus
     * {@code item_treatment_id} — which existed only on an unreleased development branch. The
     * migration must recognise this as stale too, not just the legacy shape.
     */
    private void createItemKeyedFaceDetections() {
        dbAccess.execute("DROP TABLE IF EXISTS FACE_DETECTIONS");
        dbAccess.execute("""
            CREATE TABLE FACE_DETECTIONS (
                id VARCHAR(16) PRIMARY KEY,
                item_id VARCHAR(16) NOT NULL REFERENCES ITEMS(id) ON DELETE CASCADE,
                face_index INTEGER NOT NULL,
                confidence REAL NOT NULL,
                bbox_x REAL, bbox_y REAL, bbox_w REAL, bbox_h REAL,
                landmarks BYTEA,
                date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE (item_id, face_index)
            )
            """);
    }

    @Test
    void dropLegacyFaceTables_dropsOldShapeAndRemovesFaceRecognitionTreatments() {
        createLegacyFaceTables();
        fixtures.insertItem("face1", "portrait.jpg", null);
        insertLegacyFaceGroup("fg1", "Alice");
        insertLegacyFaceDetection("fd1", "face1", "fg1");

        String frDt = fixtures.insertDatasourceTreatment("dt-lg-fr", "FACE_RECOGNITION");
        fixtures.insertItemTreatment("it-lg-fr", frDt, "face1");
        String contentDt = fixtures.insertDatasourceTreatment("dt-lg-cont", "CONTENT");
        fixtures.insertItemTreatment("it-lg-cont", contentDt, "face1");

        schema.dropLegacyFaceTables();

        assertFalse(fixtures.tableExists("face_detections"), "Legacy FACE_DETECTIONS should be dropped");
        assertFalse(fixtures.tableExists("face_groups"), "FACE_GROUPS should be dropped");

        // The datasource_treatments delete is mandatory, not cleanup: a surviving FACE_RECOGNITION
        // row would throw on Treatments.valueOf(...) now that the enum value is gone. It cascades to
        // item_treatments and their results.
        assertEquals(0, fixtures.count(
            "SELECT count(*) FROM DATASOURCE_TREATMENTS WHERE treatment = 'FACE_RECOGNITION'"));
        assertEquals(1, fixtures.countItemTreatments(),
            "Only the CONTENT item treatment should survive the cascade");
    }

    @Test
    void dropLegacyFaceTables_isIdempotent() {
        createLegacyFaceTables();
        fixtures.insertItem("face2", "portrait2.jpg", null);
        fixtures.insertDatasourceTreatment("dt-lg-fr2", "FACE_RECOGNITION");

        schema.dropLegacyFaceTables();
        schema.createSchema();

        assertTrue(fixtures.tableExists("face_detections"),
            "createSchema should build the new FACE_DETECTIONS");
        String dt = fixtures.insertDatasourceTreatment("dt-fd2", "FACE_DETECTION");
        String it = fixtures.insertItemTreatment("it-fd2", dt, "face2");
        insertFaceDetection("fd2", it, 0);

        // Second pass must be a no-op: the guard keys on `item_treatment_id`, which the current
        // shape has, so freshly created data is not re-wiped.
        schema.dropLegacyFaceTables();

        assertTrue(fixtures.tableExists("face_detections"), "New FACE_DETECTIONS must survive a rerun");
        assertEquals(1, fixtures.count("SELECT count(*) FROM FACE_DETECTIONS"),
            "Rerunning the migration must not delete new detections");
    }

    @Test
    void dropLegacyFaceTables_alsoDropsTheItemKeyedIntermediateShape() {
        createItemKeyedFaceDetections();
        fixtures.insertItem("face3", "portrait3.jpg", null);
        dbAccess.update("""
            INSERT INTO FACE_DETECTIONS (id, item_id, face_index, confidence)
            VALUES (?, ?, ?, ?)
            """, "fd3", "face3", 0, 0.95f);

        // This shape has no `embedding` column, so a guard keyed on that would have left it in
        // place and createSchema's CREATE TABLE IF NOT EXISTS would have silently kept a table with
        // no item_treatment_id - every insert failing thereafter.
        schema.dropLegacyFaceTables();

        assertFalse(fixtures.tableExists("face_detections"),
            "A FACE_DETECTIONS without item_treatment_id is stale and must be dropped");
    }

    @Test
    void dropLegacyFaceTables_alsoDropsTheDoublyKeyedIntermediateShape() {
        // Keyed by both item treatment and item — the shape between "owned by the treatment" and
        // "not duplicating the item link". A guard that only asked for item_treatment_id accepted
        // this one as current, so CREATE TABLE IF NOT EXISTS left item_id NOT NULL in place and
        // every insert failed from then on.
        dbAccess.execute("DROP TABLE IF EXISTS FACE_DETECTIONS");
        dbAccess.execute("""
            CREATE TABLE FACE_DETECTIONS (
                id VARCHAR(16) PRIMARY KEY,
                item_treatment_id VARCHAR(16) NOT NULL REFERENCES ITEM_TREATMENTS(id) ON DELETE CASCADE,
                item_id VARCHAR(16) NOT NULL REFERENCES ITEMS(id) ON DELETE CASCADE,
                face_index INTEGER NOT NULL,
                confidence REAL NOT NULL,
                bbox_x REAL, bbox_y REAL, bbox_w REAL, bbox_h REAL,
                landmarks BYTEA,
                date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                UNIQUE (item_id, face_index)
            )
            """);

        schema.dropLegacyFaceTables();

        assertFalse(fixtures.tableExists("face_detections"),
            "A FACE_DETECTIONS still carrying item_id is stale and must be dropped");
    }

    @Test
    void columnExists_reportsShapeCorrectly() {
        assertTrue(schema.columnExists("face_detections", "face_index"), "new shape has face_index");
        assertTrue(schema.columnExists("face_detections", "item_treatment_id"), "new shape is treatment-owned");
        assertFalse(schema.columnExists("face_detections", "embedding"), "new shape has no embedding");
        assertFalse(schema.columnExists("no_such_table", "whatever"), "missing table is not a column");
    }
}
