package enterfind.server.dao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.model.DataSourceTreatment;
import enterfind.server.model.ItemTreatment;
import enterfind.server.model.ItemTreatmentResult;
import enterfind.server.model.ItemTreatmentStatus;
import enterfind.server.scan.TreatmentResult;
import enterfind.server.scan.Treatments;

/**
 * Unit tests for ItemTreatmentResult DAO operations
 */
class ItemTreatmentResultDaoTest extends BaseDatabaseTest {

    @Autowired
    private TreatmentDao treatmentDao;

    private String testDataSourceId;
    private String testDataSourceTreatmentId;
    private String testItemId;
    private String testItemTreatmentId;

    @BeforeEach
    void setup() {
        super.setUp();
        
        // Create test data source
        testDataSourceId = enterfind.server.util.Utilities.generateHexId();
        String dataSourceSql = """
                INSERT INTO DATA_SOURCES (id, name, cron_pattern, type, config, date_created)
                VALUES (?, 'test-datasource', '0 0 * * *', 'LOCAL_FILESYSTEM', '{}'::jsonb, ?)
                """;
        dbAccess.update(dataSourceSql, testDataSourceId, LocalDateTime.now());
        
        // Add treatment to data source
        DataSourceTreatment treatment = treatmentDao.addTreatment(testDataSourceId, Treatments.CONTENT, null);
        testDataSourceTreatmentId = treatment.getId();
        
        // Create test item
        testItemId = enterfind.server.util.Utilities.generateHexId();
        String itemSql = """
                INSERT INTO ITEMS (id, datasource_id, name, path, time_created, time_modified,
                                 size_bytes, content_hash, mime_type, date_scanned)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        LocalDateTime now = LocalDateTime.now();
        dbAccess.update(itemSql, testItemId, testDataSourceId, "test-file.txt", "/test", 
                          now, now, 1024L, "abc123", "text/plain", now);
        
        // Add item treatment
        ItemTreatment itemTreatment = treatmentDao.addItemTreatment(testDataSourceTreatmentId, testItemId, ItemTreatmentStatus.NEW);
        testItemTreatmentId = itemTreatment.getId();
    }

    @Test
    void testBulkInsertItemTreatmentResults() {
        // Given
        List<TreatmentResult> treatmentResults = List.of(
            new TreatmentResult("name", "test-file.txt"),
            new TreatmentResult("path", "test"),
            new TreatmentResult("content", "This is test content")
        );

        // When
        List<ItemTreatmentResult> insertedResults = treatmentDao.bulkInsertItemTreatmentResults(testItemTreatmentId, treatmentResults);

        // Then
        assertThat(insertedResults).hasSize(3);
        assertThat(insertedResults).extracting(ItemTreatmentResult::getKey)
            .containsExactlyInAnyOrder("name", "path", "content");
        assertThat(insertedResults).extracting(ItemTreatmentResult::getValue)
            .containsExactlyInAnyOrder("test-file.txt", "test", "This is test content");
        
        // Verify all have correct item treatment ID
        insertedResults.forEach(result -> {
            assertThat(result.getItemTreatmentId()).isEqualTo(testItemTreatmentId);
            assertThat(result.getId()).isNotNull();
            assertThat(result.getDateCreated()).isNotNull();
        });
    }

    @Test
    void testGetItemTreatmentResults() {
        // Given - insert some results first
        List<TreatmentResult> treatmentResults = List.of(
            new TreatmentResult("name", "test-file.txt"),
            new TreatmentResult("path", "test")
        );
        treatmentDao.bulkInsertItemTreatmentResults(testItemTreatmentId, treatmentResults);

        // When
        List<ItemTreatmentResult> retrievedResults = treatmentDao.getItemTreatmentResults(testItemTreatmentId);

        // Then
        assertThat(retrievedResults).hasSize(2);
        assertThat(retrievedResults).extracting(ItemTreatmentResult::getKey)
            .containsExactlyInAnyOrder("name", "path");
        assertThat(retrievedResults).extracting(ItemTreatmentResult::getValue)
            .containsExactlyInAnyOrder("test-file.txt", "test");
    }

    @Test
    void testDeleteItemTreatmentResults() {
        // Given - insert some results first
        List<TreatmentResult> treatmentResults = List.of(
            new TreatmentResult("name", "test-file.txt"),
            new TreatmentResult("path", "test")
        );
        treatmentDao.bulkInsertItemTreatmentResults(testItemTreatmentId, treatmentResults);
        
        // Verify they were inserted
        assertThat(treatmentDao.getItemTreatmentResults(testItemTreatmentId)).hasSize(2);

        // When
        int deletedCount = treatmentDao.deleteItemTreatmentResults(testItemTreatmentId);

        // Then
        assertThat(deletedCount).isEqualTo(2);
        assertThat(treatmentDao.getItemTreatmentResults(testItemTreatmentId)).isEmpty();
    }

    @Test
    void testBulkInsertWithEmptyList() {
        // Given
        List<TreatmentResult> emptyList = List.of();

        // When
        List<ItemTreatmentResult> insertedResults = treatmentDao.bulkInsertItemTreatmentResults(testItemTreatmentId, emptyList);

        // Then
        assertThat(insertedResults).isEmpty();
    }

    @Test
    void testBulkInsertWithNullList() {
        // When
        List<ItemTreatmentResult> insertedResults = treatmentDao.bulkInsertItemTreatmentResults(testItemTreatmentId, null);

        // Then
        assertThat(insertedResults).isEmpty();
    }

    @Test
    void testCascadeDeleteFromItemTreatment() {
        // Given - insert some results
        List<TreatmentResult> treatmentResults = List.of(
            new TreatmentResult("name", "test-file.txt"),
            new TreatmentResult("path", "test")
        );
        treatmentDao.bulkInsertItemTreatmentResults(testItemTreatmentId, treatmentResults);

        // Verify results exist
        Integer countBefore = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM ITEM_TREATMENT_RESULTS WHERE item_treatment_id = ?",
            Integer.class, testItemTreatmentId);
        assertThat(countBefore).isEqualTo(2);

        // When - delete the item treatment (which should cascade delete the results)
        treatmentDao.deleteItemTreatment(testDataSourceTreatmentId, testItemId);

        // Then - results should be cascade deleted
        Integer countAfter = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM ITEM_TREATMENT_RESULTS WHERE item_treatment_id = ?",
            Integer.class, testItemTreatmentId);
        assertThat(countAfter).isEqualTo(0);
    }
}