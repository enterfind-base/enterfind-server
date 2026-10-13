package enterfind.server.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import enterfind.server.BaseDatabaseTest;

/**
 * Tests for the DML migrations in Schema.java: {@code migratePathSeparatorToSlash},
 * {@code removeInapplicableItemTreatments} and {@code createSettings}.
 *
 * <p>A migration that changes the schema rather than the data needs its own class, because it must
 * put the schema back afterwards — {@link FaceTableMigrationTest} is the worked example.
 */
class SchemaMigrationTest extends BaseDatabaseTest {

    @Autowired
    private Schema schema;

    private DaoTestFixtures fixtures;

    @BeforeEach
    void createDataSource() {
        fixtures = new DaoTestFixtures(dbAccess, "MIGTEST01");
        fixtures.insertDataSource("Migration Test DS");
    }

    private String getPath(String table, String id) {
        return dbAccess.queryForObject(
            "SELECT path FROM " + table + " WHERE id = ?", String.class, id);
    }

    @Test
    void migration_convertsCommaPathsToSlash() {
        // Simulate pre-fix state: items and folders with comma-separated paths.
        // Folder names here are plain (no commas in names) to isolate the separator behaviour.
        fixtures.insertFolder("f1", "documents", null);
        fixtures.insertFolder("f2", "invoices", "documents");
        fixtures.insertItem("i1", "report.pdf", "documents");
        fixtures.insertItem("i2", "receipt.pdf", "documents,invoices");

        schema.migratePathSeparatorToSlash();

        // f1 has null path — unchanged
        assertEquals(null, getPath("FOLDERS", "f1"));
        // f2's path has no comma separator (single segment) — unchanged
        assertEquals("documents", getPath("FOLDERS", "f2"));
        // i1's path has no comma separator (single segment) — unchanged
        assertEquals("documents", getPath("ITEMS", "i1"));
        // i2's path used comma as separator — converted to slash
        assertEquals("documents/invoices", getPath("ITEMS", "i2"));
    }

    @Test
    void migration_isIdempotent() {
        fixtures.insertItem("i3", "nested.pdf", "books,java,chapter1");

        schema.migratePathSeparatorToSlash();
        String afterFirst = getPath("ITEMS", "i3");
        assertEquals("books/java/chapter1", afterFirst);

        // Second run: no rows match LIKE '%,%', so nothing changes
        schema.migratePathSeparatorToSlash();
        String afterSecond = getPath("ITEMS", "i3");
        assertEquals(afterFirst, afterSecond);
    }

    @Test
    void migration_deeplyNestedPath_allSeparatorsConverted() {
        fixtures.insertItem("i4", "deep.pdf", "a,b,c,d,e");
        schema.migratePathSeparatorToSlash();
        assertEquals("a/b/c/d/e", getPath("ITEMS", "i4"));
    }

    @Test
    void migration_itemsWithoutCommas_untouched() {
        fixtures.insertItem("i5", "root.pdf", null);
        fixtures.insertItem("i6", "top.pdf", "Manning Books");
        schema.migratePathSeparatorToSlash();
        assertEquals(null, getPath("ITEMS", "i5"));
        assertEquals("Manning Books", getPath("ITEMS", "i6"));
    }

    // -------------------------------------------------------------------------
    // removeInapplicableItemTreatments() tests
    // -------------------------------------------------------------------------

    @Test
    void removeInapplicable_deletesOcrRowsForNonOcrFiles() {
        String ocrDt = fixtures.insertDatasourceTreatment("dt-ocr", "OCR");

        fixtures.insertItem("img1", "photo.jpg", null);
        fixtures.insertItem("img2", "scan.pdf", null);
        fixtures.insertItem("img3", "archive.rar", null);
        fixtures.insertItem("img4", "doc.txt", null);
        fixtures.insertItem("img5", "image.JPEG", null);  // uppercase extension

        fixtures.insertItemTreatment("it1", ocrDt, "img1"); // jpg  — keep
        fixtures.insertItemTreatment("it2", ocrDt, "img2"); // pdf  — keep
        fixtures.insertItemTreatment("it3", ocrDt, "img3"); // rar  — delete
        fixtures.insertItemTreatment("it4", ocrDt, "img4"); // txt  — delete
        fixtures.insertItemTreatment("it5", ocrDt, "img5"); // JPEG — keep (case-insensitive)

        schema.removeInapplicableItemTreatments();

        assertEquals(3, fixtures.countItemTreatments());
    }

    @Test
    void removeInapplicable_deletesObjectDetectionAndFaceDetectionForNonImages() {
        String odDt = fixtures.insertDatasourceTreatment("dt-od", "OBJECT_DETECTION");
        String frDt = fixtures.insertDatasourceTreatment("dt-fd", "FACE_DETECTION");

        fixtures.insertItem("im1", "photo.jpg", null);
        fixtures.insertItem("im2", "scan.pdf", null);  // PDF valid for OCR but not image-detection
        fixtures.insertItem("im3", "text.txt", null);

        fixtures.insertItemTreatment("it-od1", odDt, "im1"); // jpg — keep
        fixtures.insertItemTreatment("it-od2", odDt, "im2"); // pdf — delete (not an image)
        fixtures.insertItemTreatment("it-od3", odDt, "im3"); // txt — delete
        fixtures.insertItemTreatment("it-fr1", frDt, "im1"); // jpg — keep
        fixtures.insertItemTreatment("it-fr2", frDt, "im2"); // pdf — delete
        fixtures.insertItemTreatment("it-fr3", frDt, "im3"); // txt — delete

        schema.removeInapplicableItemTreatments();

        assertEquals(2, fixtures.countItemTreatments());
    }

    @Test
    void removeInapplicable_doesNotAffectOtherTreatmentTypes() {
        String contentDt = fixtures.insertDatasourceTreatment("dt-content", "CONTENT");
        String nameDt    = fixtures.insertDatasourceTreatment("dt-name",    "NAME_AND_PATH");

        fixtures.insertItem("any1", "readme.txt", null);
        fixtures.insertItem("any2", "archive.zip", null);

        fixtures.insertItemTreatment("it-c1", contentDt, "any1");
        fixtures.insertItemTreatment("it-c2", contentDt, "any2");
        fixtures.insertItemTreatment("it-n1", nameDt, "any1");
        fixtures.insertItemTreatment("it-n2", nameDt, "any2");

        schema.removeInapplicableItemTreatments();

        // CONTENT and NAME_AND_PATH rows are never pruned
        assertEquals(4, fixtures.countItemTreatments());
    }

    @Test
    void removeInapplicable_isIdempotent() {
        String ocrDt = fixtures.insertDatasourceTreatment("dt-ocr2", "OCR");

        fixtures.insertItem("idem1", "archive.rar", null);
        fixtures.insertItem("idem2", "photo.png", null);

        fixtures.insertItemTreatment("it-idem1", ocrDt, "idem1"); // rar — delete
        fixtures.insertItemTreatment("it-idem2", ocrDt, "idem2"); // png — keep

        schema.removeInapplicableItemTreatments();
        assertEquals(1, fixtures.countItemTreatments());

        // Second run should delete nothing and not throw
        schema.removeInapplicableItemTreatments();
        assertEquals(1, fixtures.countItemTreatments());
    }

    // -------------------------------------------------------------------------
    // SYSTEM_SETTINGS table and defaults tests
    // -------------------------------------------------------------------------

    @Test
    void createSchema_createsSystemSettingsTable() {
        dbAccess.update(
            "INSERT INTO SYSTEM_SETTINGS (setting_name, value) VALUES (?, ?)",
            "schema-test-key", "schema-test-value");

        String value = dbAccess.queryForObject(
            "SELECT value FROM SYSTEM_SETTINGS WHERE setting_name = ?",
            String.class, "schema-test-key");
        assertEquals("schema-test-value", value);

        Object dateCreated = dbAccess.queryForObject(
            "SELECT date_created FROM SYSTEM_SETTINGS WHERE setting_name = ?",
            Object.class, "schema-test-key");
        assertNotNull(dateCreated, "date_created should be populated by DEFAULT CURRENT_TIMESTAMP");
    }

    @Test
    void createSettings_insertsRemoteAccessDefaults() {
        // wipeDatabase() removes defaults inserted at startup; re-run createSettings() to repopulate
        schema.createSettings();

        String enabled = dbAccess.queryForObject(
            "SELECT value FROM SYSTEM_SETTINGS WHERE setting_name = 'RemoteAccessEnabled'", String.class);
        String lastConnected = dbAccess.queryForObject(
            "SELECT value FROM SYSTEM_SETTINGS WHERE setting_name = 'RemoteAccessLastConnected'", String.class);

        assertEquals("false", enabled);
        assertEquals("", lastConnected);
    }

    @Test
    void createSettings_insertsAiDefaultsIntoSystemSettings() {
        schema.createSettings();

        String provider = dbAccess.queryForObject(
            "SELECT value FROM SYSTEM_SETTINGS WHERE setting_name = ?",
            String.class, "AI_PROVIDER");
        String openAiModel = dbAccess.queryForObject(
            "SELECT value FROM SYSTEM_SETTINGS WHERE setting_name = ?",
            String.class, "OPENAI_MODEL_NAME");

        Integer userRows = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM USER_SETTINGS WHERE setting_name IN ('AI_PROVIDER', 'OPENAI_MODEL_NAME')",
            Integer.class);

        assertEquals("NONE", provider);
        assertEquals("gpt-4o-mini", openAiModel);
        assertEquals(0, userRows);
    }

    @Test
    void moveAiSettingsToSystemSettings_movesAndDeletesLegacyRows() {
        dbAccess.update("INSERT INTO USER_SETTINGS (setting_name, value) VALUES (?, ?)", "AI_PROVIDER", "OPENAI");
        dbAccess.update("INSERT INTO USER_SETTINGS (setting_name, value) VALUES (?, ?)", "OPENAI_API_KEY", "ENC:test-key");

        schema.moveAiSettingsToSystemSettings();

        String provider = dbAccess.queryForObject(
            "SELECT value FROM SYSTEM_SETTINGS WHERE setting_name = ?",
            String.class, "AI_PROVIDER");
        String apiKey = dbAccess.queryForObject(
            "SELECT value FROM SYSTEM_SETTINGS WHERE setting_name = ?",
            String.class, "OPENAI_API_KEY");
        Integer remainingLegacyRows = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM USER_SETTINGS WHERE setting_name IN ('AI_PROVIDER', 'OPENAI_API_KEY')",
            Integer.class);

        assertEquals("OPENAI", provider);
        assertEquals("ENC:test-key", apiKey);
        assertEquals(0, remainingLegacyRows);
    }

    @Test
    void moveAiSettingsToSystemSettings_isIdempotent() {
        dbAccess.update("INSERT INTO USER_SETTINGS (setting_name, value) VALUES (?, ?)", "AI_PROVIDER", "ANTHROPIC");

        schema.moveAiSettingsToSystemSettings();
        schema.moveAiSettingsToSystemSettings();

        Integer userRows = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM USER_SETTINGS WHERE setting_name = 'AI_PROVIDER'",
            Integer.class);
        Integer systemRows = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM SYSTEM_SETTINGS WHERE setting_name = 'AI_PROVIDER'",
            Integer.class);
        String provider = dbAccess.queryForObject(
            "SELECT value FROM SYSTEM_SETTINGS WHERE setting_name = 'AI_PROVIDER'",
            String.class);

        assertEquals(0, userRows);
        assertEquals(1, systemRows);
        assertEquals("ANTHROPIC", provider);
    }
}
