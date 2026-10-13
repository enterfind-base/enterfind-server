package enterfind.server.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.model.DataSourceTreatment;
import enterfind.server.model.DataSourceType;
import enterfind.server.model.FaceDetection;
import enterfind.server.model.FaceEmbedding;
import enterfind.server.model.FaceGroupMutation;
import enterfind.server.model.FieldNameConstants;
import enterfind.server.model.ItemTreatmentStatus;
import enterfind.server.model.ItemTreatmentWithDetails;
import enterfind.server.scan.TreatmentResult;
import enterfind.server.scan.Treatments;
import enterfind.server.util.Utilities;

class TreatmentDaoTest extends BaseDatabaseTest {

    @Autowired
    private TreatmentDao treatmentDao;

    @Autowired
    private FaceDao faceDao;

    @Autowired
    private FaceGroupDao faceGroupDao;

    @Autowired
    private ObjectMapper objectMapper;

    private String testDataSourceId;

    @BeforeEach
    void setUpTestData() {
        super.setUp();


        // Create a test datasource
        testDataSourceId = Utilities.generateHexId();
        String sql = """
                INSERT INTO DATA_SOURCES (id, name, cron_pattern, type, config, date_created)
                VALUES (?, 'test-datasource', '0 0 * * *', 'LOCAL_FILESYSTEM', '{}'::jsonb, ?)
                """;
        dbAccess.update(sql, testDataSourceId, LocalDateTime.now());
    }

    @Test
    void testAddTreatment() {
        // Given
        JsonNode config = objectMapper.createObjectNode()
                .put("enabled", true)
                .put("maxDepth", 10);

        // When
        DataSourceTreatment treatment = treatmentDao.addTreatment(testDataSourceId, Treatments.CONTENT, config);

        // Then
        assertThat(treatment).isNotNull();
        assertThat(treatment.getId()).isNotNull();
        assertThat(treatment.getDataSourceId()).isEqualTo(testDataSourceId);
        assertThat(treatment.getTreatment()).isEqualTo(Treatments.CONTENT);
        assertThat(treatment.getConfig()).isEqualTo(config);
        assertThat(treatment.getDateCreated()).isNotNull();

        // Verify in database
        String sql = "SELECT id, datasource_id, treatment, config, date_created FROM DATASOURCE_TREATMENTS WHERE id = ?";
        List<DataSourceTreatment> dbResults = dbAccess.query(sql, (rs, rowNum) -> {
            DataSourceTreatment dbTreatment = new DataSourceTreatment();
            dbTreatment.setId(rs.getString("id"));
            dbTreatment.setDataSourceId(rs.getString("datasource_id"));
            dbTreatment.setTreatment(Treatments.valueOf(rs.getString("treatment")));
            dbTreatment.setDateCreated(rs.getTimestamp("date_created").toLocalDateTime());
            
            String configJson = rs.getString("config");
            if (configJson != null) {
                try {
                    dbTreatment.setConfig(objectMapper.readTree(configJson));
                } catch (Exception e) {
                    throw new RuntimeException("Failed to parse config JSON", e);
                }
            }
            return dbTreatment;
        }, treatment.getId());

        assertThat(dbResults).hasSize(1);
        DataSourceTreatment dbTreatment = dbResults.get(0);
        assertThat(dbTreatment.getId()).isEqualTo(treatment.getId());
        assertThat(dbTreatment.getDataSourceId()).isEqualTo(testDataSourceId);
        assertThat(dbTreatment.getTreatment()).isEqualTo(Treatments.CONTENT);
        assertThat(dbTreatment.getConfig()).isEqualTo(config);
        assertThat(dbTreatment.getDateCreated()).isNotNull();
    }

    @Test
    void testAddTreatmentWithoutConfig() {
        // When
        DataSourceTreatment treatment = treatmentDao.addTreatment(testDataSourceId, Treatments.NAME_AND_PATH, null);

        // Then
        assertThat(treatment).isNotNull();
        assertThat(treatment.getDataSourceId()).isEqualTo(testDataSourceId);
        assertThat(treatment.getTreatment()).isEqualTo(Treatments.NAME_AND_PATH);
        assertThat(treatment.getConfig()).isNull();

        // Verify in database
        String sql = "SELECT config FROM DATASOURCE_TREATMENTS WHERE id = ?";
        String dbConfig = dbAccess.queryForObject(sql, String.class, treatment.getId());
        assertThat(dbConfig).isNull();
        
        // Verify count
        String countSql = "SELECT COUNT(*) FROM DATASOURCE_TREATMENTS WHERE datasource_id = ? AND treatment = ?";
        Integer count = dbAccess.queryForObject(countSql, Integer.class, testDataSourceId, "NAME_AND_PATH");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void testGetTreatmentsByDataSource() {
        // Given
        treatmentDao.addTreatment(testDataSourceId, Treatments.CONTENT, null);
        treatmentDao.addTreatment(testDataSourceId, Treatments.OCR, null);

        // When
        List<DataSourceTreatment> treatments = treatmentDao.getTreatmentsByDataSource(testDataSourceId);

        // Then
        assertThat(treatments).hasSize(2);
        assertThat(treatments)
                .extracting(DataSourceTreatment::getTreatment)
                .containsExactlyInAnyOrder(Treatments.CONTENT, Treatments.OCR);

        // Verify database directly
        String sql = "SELECT treatment FROM DATASOURCE_TREATMENTS WHERE datasource_id = ? ORDER BY treatment";
        List<String> dbTreatments = dbAccess.queryForList(sql, String.class, testDataSourceId);
        assertThat(dbTreatments).hasSize(2);
        assertThat(dbTreatments).containsExactly("CONTENT", "OCR");
        
        // Verify total count in database matches
        String countSql = "SELECT COUNT(*) FROM DATASOURCE_TREATMENTS WHERE datasource_id = ?";
        Integer dbCount = dbAccess.queryForObject(countSql, Integer.class, testDataSourceId);
        assertThat(dbCount).isEqualTo(treatments.size());
    }

    @Test
    void testGetTreatment() {
        // Given
        treatmentDao.addTreatment(testDataSourceId, Treatments.CONTENT, null);

        // When
        Optional<DataSourceTreatment> treatment = treatmentDao.getTreatment(testDataSourceId, Treatments.CONTENT);

        // Then
        assertThat(treatment).isPresent();
        assertThat(treatment.get().getTreatment()).isEqualTo(Treatments.CONTENT);

        // Verify the same record exists in database
        String sql = "SELECT COUNT(*) FROM DATASOURCE_TREATMENTS WHERE datasource_id = ? AND treatment = ?";
        Integer count = dbAccess.queryForObject(sql, Integer.class, testDataSourceId, "CONTENT");
        assertThat(count).isEqualTo(1);
        
        // Verify the record details match
        String detailSql = "SELECT id, datasource_id FROM DATASOURCE_TREATMENTS WHERE datasource_id = ? AND treatment = ?";
        dbAccess.queryForObject(detailSql, (rs, rowNum) -> {
            assertThat(rs.getString("id")).isEqualTo(treatment.get().getId());
            assertThat(rs.getString("datasource_id")).isEqualTo(testDataSourceId);
            return null;
        }, testDataSourceId, "CONTENT");
    }

    @Test
    void testGetTreatmentNotFound() {
        // When
        Optional<DataSourceTreatment> treatment = treatmentDao.getTreatment(testDataSourceId, Treatments.CONTENT);

        // Then
        assertThat(treatment).isEmpty();

        // Verify no record exists in database
        String sql = "SELECT COUNT(*) FROM DATASOURCE_TREATMENTS WHERE datasource_id = ? AND treatment = ?";
        Integer count = dbAccess.queryForObject(sql, Integer.class, testDataSourceId, "CONTENT");
        assertThat(count).isEqualTo(0);
    }

    @Test
    void testRemoveTreatment() {
        // Given
        treatmentDao.addTreatment(testDataSourceId, Treatments.CONTENT, null);

        // Verify record exists before removal
        String preRemovalSql = "SELECT COUNT(*) FROM DATASOURCE_TREATMENTS WHERE datasource_id = ? AND treatment = ?";
        Integer preCount = dbAccess.queryForObject(preRemovalSql, Integer.class, testDataSourceId, "CONTENT");
        assertThat(preCount).isEqualTo(1);

        // When
        boolean removed = treatmentDao.removeTreatment(testDataSourceId, Treatments.CONTENT);

        // Then
        assertThat(removed).isTrue();
        assertThat(treatmentDao.getTreatment(testDataSourceId, Treatments.CONTENT)).isEmpty();

        // Verify record is actually deleted from database
        String postRemovalSql = "SELECT COUNT(*) FROM DATASOURCE_TREATMENTS WHERE datasource_id = ? AND treatment = ?";
        Integer postCount = dbAccess.queryForObject(postRemovalSql, Integer.class, testDataSourceId, "CONTENT");
        assertThat(postCount).isEqualTo(0);
    }

    @Test
    void testRemoveTreatmentNotFound() {
        // Verify no record exists initially
        String preSql = "SELECT COUNT(*) FROM DATASOURCE_TREATMENTS WHERE datasource_id = ? AND treatment = ?";
        Integer preCount = dbAccess.queryForObject(preSql, Integer.class, testDataSourceId, "CONTENT");
        assertThat(preCount).isEqualTo(0);

        // When
        boolean removed = treatmentDao.removeTreatment(testDataSourceId, Treatments.CONTENT);

        // Then
        assertThat(removed).isFalse();

        // Verify count remains zero
        String postSql = "SELECT COUNT(*) FROM DATASOURCE_TREATMENTS WHERE datasource_id = ? AND treatment = ?";
        Integer postCount = dbAccess.queryForObject(postSql, Integer.class, testDataSourceId, "CONTENT");
        assertThat(postCount).isEqualTo(0);
    }

    @Test
    void testUpdateTreatmentConfig() {
        // Given
        treatmentDao.addTreatment(testDataSourceId, Treatments.CONTENT, null);
        JsonNode newConfig = objectMapper.createObjectNode().put("updated", true);

        // When
        Optional<DataSourceTreatment> updated = treatmentDao.updateTreatmentConfig(
                testDataSourceId, Treatments.CONTENT, newConfig);

        // Then
        assertThat(updated).isPresent();
        assertThat(updated.get().getConfig()).isEqualTo(newConfig);
        assertThat(updated.get().getDateModified()).isNotNull();

        // Verify config and date_modified are updated in database
        String sql = "SELECT config, date_modified FROM DATASOURCE_TREATMENTS WHERE datasource_id = ? AND treatment = ?";
        dbAccess.queryForObject(sql, (rs, rowNum) -> {
            String dbConfigJson = rs.getString("config");
            assertThat(dbConfigJson).isNotNull();
            
            try {
                JsonNode dbConfig = objectMapper.readTree(dbConfigJson);
                assertThat(dbConfig).isEqualTo(newConfig);
                assertThat(dbConfig.get("updated").asBoolean()).isTrue();
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse config JSON", e);
            }
            
            assertThat(rs.getTimestamp("date_modified")).isNotNull();
            return null;
        }, testDataSourceId, "CONTENT");
    }

    @Test
    void testTreatmentExists() {
        // Given
        treatmentDao.addTreatment(testDataSourceId, Treatments.CONTENT, null);

        // When
        boolean exists = treatmentDao.treatmentExists(testDataSourceId, Treatments.CONTENT);
        boolean notExists = treatmentDao.treatmentExists(testDataSourceId, Treatments.OCR);

        // Then
        assertThat(exists).isTrue();
        assertThat(notExists).isFalse();

        // Verify database state directly
        String existsSql = "SELECT COUNT(*) FROM DATASOURCE_TREATMENTS WHERE datasource_id = ? AND treatment = ?";
        Integer contentCount = dbAccess.queryForObject(existsSql, Integer.class, testDataSourceId, "CONTENT");
        Integer ocrCount = dbAccess.queryForObject(existsSql, Integer.class, testDataSourceId, "OCR");
        
        assertThat(contentCount).isEqualTo(1);  // CONTENT treatment exists
        assertThat(ocrCount).isEqualTo(0);      // OCR treatment does not exist
    }

    @Test
    void testGetItemTreatmentsReadyForProcessing() {
       
        // Given - create test items and treatments
        String item1Id = createTestItem("file1.txt", "test/path1");
        String item2Id = createTestItem("file2.pdf", "test/path2");
        String item3Id = createTestItem("file3.doc", null); // root level item
        
        // Create datasource treatments
        DataSourceTreatment contentTreatment = treatmentDao.addTreatment(testDataSourceId, Treatments.CONTENT, null);
        DataSourceTreatment ocrTreatment = treatmentDao.addTreatment(testDataSourceId, Treatments.OCR, null);
        
        // Create item treatments with different statuses
        treatmentDao.addItemTreatment(contentTreatment.getId(), item1Id, ItemTreatmentStatus.NEW);
        treatmentDao.addItemTreatment(ocrTreatment.getId(), item1Id, ItemTreatmentStatus.UPDATED);
        treatmentDao.addItemTreatment(contentTreatment.getId(), item2Id, ItemTreatmentStatus.NEW);
        treatmentDao.addItemTreatment(ocrTreatment.getId(), item2Id, ItemTreatmentStatus.DONE); // Should not be included
        treatmentDao.addItemTreatment(contentTreatment.getId(), item3Id, ItemTreatmentStatus.UPDATED);
        treatmentDao.addItemTreatment(ocrTreatment.getId(), item3Id, ItemTreatmentStatus.INPROGRESS); // Should not be included
        
        // When
        List<ItemTreatmentWithDetails> results = treatmentDao.getItemTreatmentsReadyForProcessing(200, Integer.MAX_VALUE, true);
        
        // Then
        assertThat(results).hasSize(4); // Only NEW and UPDATED statuses should be included
        
        // Verify results are sorted by item id - check that sorting is working properly
        // Since IDs are generated randomly, we need to check the actual items match
        Set<String> expectedIds = Set.of(item1Id, item2Id, item3Id);
        Set<String> actualIds = results.stream().map(r -> r.getItem().getId()).collect(Collectors.toSet());
        assertThat(actualIds).isEqualTo(expectedIds);
        
        // Verify results are properly sorted by checking order
        for (int i = 1; i < results.size(); i++) {
            String currentId = results.get(i).getItem().getId();
            String previousId = results.get(i - 1).getItem().getId();
            assertThat(currentId.compareTo(previousId)).isGreaterThanOrEqualTo(0);
        }
        
        // Verify treatment names are correctly mapped
        assertThat(results).extracting(r -> r.getTreatmentName())
            .containsExactlyInAnyOrder(Treatments.CONTENT, Treatments.OCR, Treatments.CONTENT, Treatments.CONTENT);
        
        // Verify item details are correctly populated - find file1.txt result
        ItemTreatmentWithDetails file1Result = results.stream()
            .filter(r -> r.getItem().getName().equals("file1.txt"))
            .findFirst()
            .orElse(null);
        assertThat(file1Result).isNotNull();
        assertThat(file1Result.getItemTreatmentId()).isNotNull();
        assertThat(file1Result.getItem().getPath()).isEqualTo("test/path1");
        assertThat(file1Result.getItem().getDatasourceId()).isEqualTo(testDataSourceId);
        
        // Verify root level item (null path)
        ItemTreatmentWithDetails rootLevelResult = results.stream()
            .filter(r -> r.getItem().getName().equals("file3.doc"))
            .findFirst()
            .orElse(null);
        assertThat(rootLevelResult).isNotNull();
        assertThat(rootLevelResult.getItem().getPath()).isNull();
        
        // Verify that datasource type and ID are correctly populated
        for (ItemTreatmentWithDetails result : results) {
            assertThat(result.getDataSourceType()).isEqualTo(DataSourceType.LOCAL_FILESYSTEM);
            assertThat(result.getDataSourceId()).isEqualTo(testDataSourceId);
        }
        
        // Database verification - ensure we're only getting NEW and UPDATED statuses
        String statusCheckSql = """
            SELECT COUNT(*) FROM ITEM_TREATMENTS it
            JOIN DATASOURCE_TREATMENTS dt ON it.datasource_treatment_id = dt.id
            JOIN ITEMS i ON it.item_id = i.id
            WHERE it.status IN ('NEW', 'UPDATED')
            """;
        Integer dbCount = dbAccess.queryForObject(statusCheckSql, Integer.class);
        assertThat(dbCount).isEqualTo(4);
    }

    @Test
    void testGetItemTreatmentsReadyForProcessingWithLimit() {
       
        
        
        
        // Given - Create treatment first
        DataSourceTreatment treatment = treatmentDao.addTreatment(testDataSourceId, Treatments.CONTENT, null);
        List<String> itemIds = new ArrayList<>();
        
        // Create 5 item treatments (using small limit of 3 for testing)
        for (int i = 1; i <= 5; i++) {
            String itemId = createTestItem("item" + i + ".txt", "/test/path" + i);
            itemIds.add(itemId);
            
            // Add treatment for each item using the same treatment type - correct parameter order!
            treatmentDao.addItemTreatment(treatment.getId(), itemId, ItemTreatmentStatus.NEW);
        }
        
        // When - use limit of 3
        List<ItemTreatmentWithDetails> results = treatmentDao.getItemTreatmentsReadyForProcessing(3, Integer.MAX_VALUE, true);

        // Then - should return exactly 3 (the limit)
        assertThat(results.size()).isEqualTo(3);
        
        // All returned results should be sorted by item id
        for (int i = 1; i < results.size(); i++) {
            String currentItemId = results.get(i).getItem().getId();
            String previousItemId = results.get(i - 1).getItem().getId();
            assertThat(currentItemId.compareTo(previousItemId)).isGreaterThanOrEqualTo(0);
        }
        
        // All results should have the correct treatment name
        results.forEach(result -> {
            assertThat(result.getTreatmentName()).isEqualTo(Treatments.CONTENT);
            assertThat(result.getItem()).isNotNull();
            assertThat(result.getItemTreatmentId()).isNotNull();
        });
    }
    
    @Test
    void testGetItemTreatmentsReadyForProcessingLastItemRemoval() {
        
       
        
        // Given - Create treatments first
        DataSourceTreatment contentTreatment = treatmentDao.addTreatment(testDataSourceId, Treatments.CONTENT, null);
        DataSourceTreatment ocrTreatment = treatmentDao.addTreatment(testDataSourceId, Treatments.OCR, null);
        DataSourceTreatment objectDetectionTreatment = treatmentDao.addTreatment(testDataSourceId, Treatments.OBJECT_DETECTION, null);
        
        List<String> itemIds = new ArrayList<>();
        
        // Create 2 items with single treatments
        for (int i = 1; i <= 2; i++) {
            String itemId = createTestItem("item" + i + ".txt", "/test/path" + i);
            itemIds.add(itemId);
            
            treatmentDao.addItemTreatment(contentTreatment.getId(), itemId, ItemTreatmentStatus.NEW);
        }
        // Create a last item with multiple treatments
        String lastItemId = createTestItem("lastItem.txt", "/test/last");
        
        treatmentDao.addItemTreatment(contentTreatment.getId(), lastItemId, ItemTreatmentStatus.NEW);
        treatmentDao.addItemTreatment(ocrTreatment.getId(), lastItemId, ItemTreatmentStatus.UPDATED);
        treatmentDao.addItemTreatment(objectDetectionTreatment.getId(), lastItemId, ItemTreatmentStatus.NEW);
        
        // When - use limit of 4
        List<ItemTreatmentWithDetails> results = treatmentDao.getItemTreatmentsReadyForProcessing(4, Integer.MAX_VALUE, true);

        // Then - should return exactly 4 (the limit), out of 5 total treatments
        assertThat(results.size()).isEqualTo(4);

        // Verify all 5 treatments exist in total
        List<ItemTreatmentWithDetails> allResults = treatmentDao.getItemTreatmentsReadyForProcessing(100, Integer.MAX_VALUE, true);
        assertThat(allResults.size()).isEqualTo(5);
        
        // Verify that results are still sorted by item ID
        for (int i = 1; i < results.size(); i++) {
            String currentItemId = results.get(i).getItem().getId();
            String previousItemId = results.get(i - 1).getItem().getId();
            assertThat(currentItemId.compareTo(previousItemId)).isGreaterThanOrEqualTo(0);
        }
    }
    
    private String createTestItem(String name, String path) {
        String itemId = Utilities.generateHexId();
        String sql = """
            INSERT INTO ITEMS (id, datasource_id, name, path, time_created, time_modified, 
                              size_bytes, content_hash, mime_type, date_scanned)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
        dbAccess.update(sql, itemId, testDataSourceId, name, path, 
            LocalDateTime.now(), LocalDateTime.now(), 1024L, "hash123", "text/plain", 
            LocalDateTime.now());
        return itemId;
    }

    @Test
    void testUniqueTreatmentConstraint() {
        // Given - first treatment added successfully
        DataSourceTreatment firstTreatment = treatmentDao.addTreatment(testDataSourceId, Treatments.CONTENT, null);
        assertThat(firstTreatment).isNotNull();

        // Verify first record exists in database
        String sql = "SELECT COUNT(*) FROM DATASOURCE_TREATMENTS WHERE datasource_id = ? AND treatment = ?";
        Integer initialCount = dbAccess.queryForObject(sql, Integer.class, testDataSourceId, "CONTENT");
        assertThat(initialCount).isEqualTo(1);

        // When/Then - should throw exception for duplicate treatment
        Exception exception = assertThrows(Exception.class, () -> {
            treatmentDao.addTreatment(testDataSourceId, Treatments.CONTENT, null);
        });

        // Verify the exception is database-related (constraint violation)
        assertThat(exception).isNotNull();
        // Note: After constraint violation exception, transaction is marked for rollback
        // so we cannot perform additional database assertions in this test method
    }

    // ==================== Face geometry written with the treatment results ====================
    //
    // The rows themselves belong to FaceDao (see FaceDaoTest); what is pinned here is
    // saveItemTreatmentResult's contract over them: a null face-detection list means "this treatment
    // does not own the item's faces", an empty one means "it does, and found none". Confusing the
    // two deletes another treatment's data with no error and no log line. FaceDao is autowired only
    // to read back what the save path did.

    /** Seed an item, its item_treatment and one stored face; returns the item_treatment id. */
    private String seedItemWithOneFace(String itemId, Treatments treatment) {
        DaoTestFixtures fixtures = new DaoTestFixtures(dbAccess, testDataSourceId);
        fixtures.insertItem(itemId, itemId + ".jpg", "photos");
        String datasourceTreatmentId = fixtures.insertDatasourceTreatment(
                Utilities.generateHexId(), treatment.name());
        String itemTreatmentId = fixtures.insertItemTreatment(
                Utilities.generateHexId(), datasourceTreatmentId, itemId);
        faceDao.createFaceDetection(itemTreatmentId, 0, 0.9f, 0.1f, 0.2f, 0.3f, 0.4f, null);
        return itemTreatmentId;
    }

    @Test
    void saveItemTreatmentResultLeavesFaceDetectionsAloneWhenListIsNull() {
        String itemId = Utilities.generateHexId();
        String itemTreatmentId = seedItemWithOneFace(itemId, Treatments.OCR);

        // OCR saving results for an image that FACE_DETECTION has already processed.
        treatmentDao.saveItemTreatmentResult(itemTreatmentId, 1, ItemTreatmentStatus.DONE,
                List.of(), false, null);

        assertThat(faceDao.getFaceDetectionsByItemId(itemId))
                .as("a treatment that does not own face geometry must not touch it")
                .hasSize(1);
    }

    @Test
    void saveItemTreatmentResultClearsFaceDetectionsWhenListIsEmpty() {
        String itemId = Utilities.generateHexId();
        String itemTreatmentId = seedItemWithOneFace(itemId, Treatments.FACE_DETECTION);

        // The owning treatment re-ran and found nothing: the previous face is now stale.
        treatmentDao.saveItemTreatmentResult(itemTreatmentId, 1, ItemTreatmentStatus.DONE,
                List.of(), false, List.of());

        assertThat(faceDao.getFaceDetectionsByItemId(itemId))
                .as("an empty list from the owning treatment clears stale rows")
                .isEmpty();
    }

    @Test
    void saveItemTreatmentResultReplacesFaceDetectionsRatherThanAppending() {
        String itemId = Utilities.generateHexId();
        String itemTreatmentId = seedItemWithOneFace(itemId, Treatments.FACE_DETECTION);

        FaceDetection redetected = new FaceDetection(
                null, 0, 0.75f, 0.5f, 0.6f, 0.7f, 0.8f, null, null);
        treatmentDao.saveItemTreatmentResult(itemTreatmentId, 1, ItemTreatmentStatus.DONE,
                List.of(), false, List.of(redetected));

        // Face indices are positional, so a re-run must not leave the old row at index 0 next to
        // the new one - that would breach UNIQUE (item_id, face_index) or serve the wrong crop.
        List<FaceDetection> stored = faceDao.getFaceDetectionsByItemId(itemId);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getConfidence()).isEqualTo(0.75f);
        assertThat(stored.get(0).getBboxX()).isEqualTo(0.5f);
    }

    /**
     * The face rows moved to {@link FaceDao}, so the save now writes them through a collaborating
     * bean. This pins that the collaborator still runs <b>inside this method's transaction</b>: a
     * failure raised while writing face rows must take the treatment results written earlier in the
     * same call down with it. A {@code FaceDao} method annotated {@code REQUIRES_NEW}, or reaching
     * for {@code DBAccess.executeAdmin}, would break exactly this and nothing else — leaving a
     * facecount with no geometry behind it, which is the failure the atomic write exists to prevent.
     */
    @Test
    void aFailureWritingFaceRowsRollsBackTheResultsWrittenInTheSameCall() {
        String itemId = Utilities.generateHexId();
        String itemTreatmentId = seedItemWithOneFace(itemId, Treatments.FACE_DETECTION);

        // Two faces claiming index 0: the second insert breaches UNIQUE (item_treatment_id,
        // face_index), after the results rows above it have already been inserted.
        FaceDetection first = new FaceDetection(null, 0, 0.9f, 0.1f, 0.2f, 0.3f, 0.4f, null, null);
        FaceDetection duplicate = new FaceDetection(null, 0, 0.8f, 0.5f, 0.6f, 0.7f, 0.8f, null, null);

        assertThatThrownBy(() -> treatmentDao.saveItemTreatmentResult(itemTreatmentId, 1,
                ItemTreatmentStatus.DONE,
                List.of(new TreatmentResult(FieldNameConstants.FIELD_FACE_COUNT, "2")),
                false, List.of(first, duplicate)))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(treatmentDao.getItemTreatmentResults(itemTreatmentId))
                .as("results written before the face failure must not survive it")
                .isEmpty();
        assertThat(faceDao.getFaceDetectionsByItemId(itemId))
                .as("the pre-existing row is restored too - the whole call is one transaction")
                .hasSize(1);
    }

    /**
     * Completing a detection re-drives the same item's recognition. Pinned because the statement
     * that does it is a multi-table UPDATE whose shape Postgres rejects if the target table is
     * referenced from a FROM-clause join - it threw on every save until this covered it.
     */
    @Test
    void completingDetectionFlipsTheSiblingRecognitionRowToUpdated() {
        String itemId = Utilities.generateHexId();
        String detectionItemTreatmentId = seedItemWithOneFace(itemId, Treatments.FACE_DETECTION);

        DaoTestFixtures fixtures = new DaoTestFixtures(dbAccess, testDataSourceId);
        String recognitionItemTreatmentId = fixtures.insertItemTreatment(
            Utilities.generateHexId(),
            fixtures.insertDatasourceTreatment(Utilities.generateHexId(),
                Treatments.FACE_RECOGNITION.name()),
            itemId);
        dbAccess.update("UPDATE ITEM_TREATMENTS SET status = 'DONE' WHERE id = ?",
            recognitionItemTreatmentId);

        treatmentDao.saveItemTreatmentResult(detectionItemTreatmentId, 1, ItemTreatmentStatus.DONE,
            List.of(), false, List.of());

        assertThat(dbAccess.queryForObject("SELECT status FROM ITEM_TREATMENTS WHERE id = ?",
                String.class, recognitionItemTreatmentId))
            .as("a re-detection invalidates the embeddings taken from the old geometry")
            .isEqualTo("UPDATED");
    }


    /**
     * The recognition-side mirror of
     * {@link #aFailureWritingFaceRowsRollsBackTheResultsWrittenInTheSameCall}.
     *
     * <p>A recognition save writes three things in one call, in this order: the FACE_GROUPS upserts
     * the handler decided, the {@code detectedface} results, and the embeddings. A failure in the
     * last must take the first two down with it. Otherwise the instance is left with a group nobody
     * belongs to, and a name shown on a photograph with no vector behind it — state no later run
     * would ever reconcile, because a group's membership is only ever rewritten by the items that
     * join it.
     */
    @Test
    void aFailureWritingEmbeddingsRollsBackTheGroupsAndResultsFromTheSameCall() {
        String itemId = Utilities.generateHexId();
        // Pre-existing geometry from the detection treatment, to prove it survives untouched.
        seedItemWithOneFace(itemId, Treatments.FACE_DETECTION);

        DaoTestFixtures fixtures = new DaoTestFixtures(dbAccess, testDataSourceId);
        String recognitionItemTreatmentId = fixtures.insertItemTreatment(
                Utilities.generateHexId(),
                fixtures.insertDatasourceTreatment(Utilities.generateHexId(),
                        Treatments.FACE_RECOGNITION.name()),
                itemId);

        FaceGroupMutation newGroup = new FaceGroupMutation(Utilities.generateHexId(), "Person 7",
                new byte[] {0, 0, 0, 0}, "model-hash", true);
        // Two embeddings claiming index 0: the second breaches UNIQUE (item_treatment_id,
        // face_index), after the group upsert and the detectedface result have already been written.
        FaceEmbedding first = new FaceEmbedding(null, 0, newGroup.id(),
                new byte[] {0, 0, 0, 0}, "model-hash", null);
        FaceEmbedding duplicate = new FaceEmbedding(null, 0, newGroup.id(),
                new byte[] {0, 0, 0, 0}, "model-hash", null);

        assertThatThrownBy(() -> treatmentDao.saveItemTreatmentResult(recognitionItemTreatmentId, 1,
                ItemTreatmentStatus.DONE,
                List.of(new TreatmentResult(FieldNameConstants.FIELD_DETECTED_FACE, "Person 7")),
                false, null, List.of(first, duplicate), List.of(newGroup)))
                .isInstanceOf(DuplicateKeyException.class);

        assertThat(faceGroupDao.getAllFaceGroups())
                .as("a group upserted before the failure must not outlive it - it would have no members")
                .isEmpty();
        assertThat(treatmentDao.getItemTreatmentResults(recognitionItemTreatmentId))
                .as("no detectedface name for a face with no vector behind it")
                .isEmpty();
        assertThat(faceDao.getFaceEmbeddingsByItemTreatmentId(recognitionItemTreatmentId))
                .as("and no partial set of embeddings")
                .isEmpty();
        assertThat(faceDao.getFaceDetectionsByItemId(itemId))
                .as("prior geometry, owned by a different treatment, is untouched")
                .hasSize(1);
    }


    /** Seed a recognition item treatment already carrying one embedding. */
    private String seedItemWithOneEmbedding(String itemId) {
        DaoTestFixtures fixtures = new DaoTestFixtures(dbAccess, testDataSourceId);
        fixtures.insertItem(itemId, itemId + ".jpg", "photos");
        String itemTreatmentId = fixtures.insertItemTreatment(Utilities.generateHexId(),
                fixtures.insertDatasourceTreatment(Utilities.generateHexId(),
                        Treatments.FACE_RECOGNITION.name()),
                itemId);
        faceDao.createFaceEmbedding(itemTreatmentId, 0, null, new byte[] {0, 0, 0, 0}, "model-hash");
        return itemTreatmentId;
    }

    /**
     * Null means "this save has nothing to say about embeddings" — the case every non-recognition
     * treatment takes. The same contract the detection rows follow, extended to the identity side.
     */
    @Test
    void saveItemTreatmentResultLeavesFaceEmbeddingsAloneWhenListIsNull() {
        String itemId = Utilities.generateHexId();
        String itemTreatmentId = seedItemWithOneEmbedding(itemId);

        treatmentDao.saveItemTreatmentResult(itemTreatmentId, 1, ItemTreatmentStatus.DONE,
                List.of(), false, null, null);

        assertThat(faceDao.getFaceEmbeddingsByItemTreatmentId(itemTreatmentId))
                .as("a save carrying no embedding side data must not disturb identity")
                .hasSize(1);
    }

    /**
     * Empty is the opposite claim: recognition ran, looked, and found nothing usable. The handler
     * returns an empty list precisely so a previous run's vectors are cleared — an image whose faces
     * have gone must not keep answering with the identities they had.
     */
    @Test
    void saveItemTreatmentResultClearsFaceEmbeddingsWhenListIsEmpty() {
        String itemId = Utilities.generateHexId();
        String itemTreatmentId = seedItemWithOneEmbedding(itemId);

        treatmentDao.saveItemTreatmentResult(itemTreatmentId, 1, ItemTreatmentStatus.DONE,
                List.of(), false, null, List.of());

        assertThat(faceDao.getFaceEmbeddingsByItemTreatmentId(itemTreatmentId))
                .as("an explicit empty result clears what the last run left")
                .isEmpty();
    }

    // ==================== FACE_RECOGNITION's dependency on FACE_DETECTION ====================

    /** One image with both face treatments enabled and an item treatment for each. */
    private record FacePipeline(String itemId, String detectionRow, String recognitionRow) {}

    private FacePipeline seedFacePipeline() {
        String itemId = createTestItem("portrait.jpg", "photos");
        // Enabling recognition pulls detection in with it - the auto-enable asserted separately below.
        DataSourceTreatment recognition =
                treatmentDao.addTreatment(testDataSourceId, Treatments.FACE_RECOGNITION, null);
        DataSourceTreatment detection =
                treatmentDao.getTreatment(testDataSourceId, Treatments.FACE_DETECTION).orElseThrow();
        treatmentDao.addItemTreatment(detection.getId(), itemId, ItemTreatmentStatus.NEW);
        treatmentDao.addItemTreatment(recognition.getId(), itemId, ItemTreatmentStatus.NEW);
        return new FacePipeline(itemId,
                treatmentDao.getItemTreatment(detection.getId(), itemId).orElseThrow().getId(),
                treatmentDao.getItemTreatment(recognition.getId(), itemId).orElseThrow().getId());
    }

    private List<Treatments> claimable(boolean embeddingModelReady) {
        return treatmentDao.getItemTreatmentsReadyForProcessing(200, Integer.MAX_VALUE, embeddingModelReady)
                .stream().map(ItemTreatmentWithDetails::getTreatmentName).toList();
    }

    private String statusOf(String itemTreatmentId) {
        return dbAccess.queryForObject("SELECT status FROM ITEM_TREATMENTS WHERE id = ?",
                String.class, itemTreatmentId);
    }

    /**
     * Ordering. Recognition reads the landmarks detection persisted, so claiming it first would embed
     * from geometry that does not exist yet. The guard lives in the shared claim query, before LIMIT,
     * so an unclaimable row never occupies the single slot the processor takes per pass.
     */
    @Test
    void recognitionIsNotClaimedUntilItsDetectionIsDone() {
        FacePipeline pipeline = seedFacePipeline();

        assertThat(claimable(true))
                .as("recognition must wait for the geometry it consumes")
                .containsExactly(Treatments.FACE_DETECTION);

        dbAccess.update("UPDATE ITEM_TREATMENTS SET status = 'DONE' WHERE id = ?", pipeline.detectionRow());

        assertThat(claimable(true))
                .as("and becomes claimable the moment its detection is DONE")
                .containsExactly(Treatments.FACE_RECOGNITION);
    }

    /**
     * Readiness. Distinct from the ordering guard above and asserted with detection already DONE, so
     * only the model gate can be what withholds the row. The mode lives in SYSTEM_SETTINGS, which SQL
     * cannot read, hence the bind parameter.
     */
    @Test
    void recognitionIsNotClaimedWhileNoEmbeddingModelIsSelected() {
        FacePipeline pipeline = seedFacePipeline();
        dbAccess.update("UPDATE ITEM_TREATMENTS SET status = 'DONE' WHERE id = ?", pipeline.detectionRow());

        assertThat(claimable(false))
                .as("a finished detection is not enough - there is nothing to embed with")
                .isEmpty();
        assertThat(claimable(true))
                .as("selecting a model releases the very same row")
                .containsExactly(Treatments.FACE_RECOGNITION);
    }

    /**
     * Auto-enable rather than reject: with no detection rows the ordering EXISTS could never become
     * true, so recognition alone would sit NEW forever with nothing to explain why.
     */
    @Test
    void enablingRecognitionEnablesDetectionToo() {
        treatmentDao.addTreatment(testDataSourceId, Treatments.FACE_RECOGNITION, null);

        assertThat(treatmentDao.getTreatment(testDataSourceId, Treatments.FACE_DETECTION))
                .as("recognition cannot run alone, so it is never enabled alone")
                .isPresent();
    }

    @Test
    void enablingRecognitionDoesNotDuplicateAnAlreadyEnabledDetection() {
        treatmentDao.addTreatment(testDataSourceId, Treatments.FACE_DETECTION, null);
        treatmentDao.addTreatment(testDataSourceId, Treatments.FACE_RECOGNITION, null);

        assertThat(treatmentDao.getTreatmentsByDataSource(testDataSourceId))
                .extracting(DataSourceTreatment::getTreatment)
                .filteredOn(Treatments.FACE_DETECTION::equals)
                .hasSize(1);
    }

    /** The mirror of the auto-enable: removing the producer removes the consumer, never strands it. */
    @Test
    void removingDetectionRemovesRecognitionToo() {
        treatmentDao.addTreatment(testDataSourceId, Treatments.FACE_RECOGNITION, null);

        treatmentDao.removeTreatment(testDataSourceId, Treatments.FACE_DETECTION);

        assertThat(treatmentDao.getTreatment(testDataSourceId, Treatments.FACE_DETECTION)).isEmpty();
        assertThat(treatmentDao.getTreatment(testDataSourceId, Treatments.FACE_RECOGNITION))
                .as("recognition left behind would be permanently unclaimable")
                .isEmpty();
    }

    /**
     * Terminal failure. A detection that has exhausted its retries will never produce geometry, so its
     * recognition row must fail with it rather than remain NEW forever - a zombie the claim query
     * keeps skipping and no counter ever explains.
     */
    @Test
    void aDetectionPastTheFailureCeilingFailsItsRecognitionRowToo() {
        FacePipeline pipeline = seedFacePipeline();
        dbAccess.update("UPDATE ITEM_TREATMENTS SET failure_count = 4 WHERE id = ?", pipeline.detectionRow());
        int version = treatmentDao.getItemTreatmentById(pipeline.detectionRow()).orElseThrow().getVersion();

        treatmentDao.saveItemTreatmentResult(pipeline.detectionRow(), version,
                ItemTreatmentStatus.FAILED, List.of(), false, null);

        assertThat(statusOf(pipeline.recognitionRow())).isEqualTo("FAILED");
        assertThat(dbAccess.queryForObject("SELECT failure_count FROM ITEM_TREATMENTS WHERE id = ?",
                Integer.class, pipeline.recognitionRow()))
                .as("pinned at the ceiling so it is not retried on its own account")
                .isGreaterThanOrEqualTo(4);
    }
}
