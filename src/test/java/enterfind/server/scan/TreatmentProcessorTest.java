package enterfind.server.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.TreatmentDao;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.DataSourceTreatment;
import enterfind.server.model.ItemTreatmentResult;
import enterfind.server.model.ItemTreatmentStatus;
import enterfind.server.model.LocalFileSystemDataSource;

public class TreatmentProcessorTest extends BaseDatabaseTest {

    @Autowired
    private TreatmentDao treatmentDao;

    @Autowired
    private DataSourceDao dataSourceDao;

    @Autowired
    private TreatmentProcessor treatmentProcessor;

    private String testDataSourceId;
    private String testDataSourceTreatmentId;
    private String testItemId;
    private String testItemTreatmentId;

    @BeforeEach
    protected void setUp() {
        super.setUp();
        
        // Create test data source
        testDataSourceId = enterfind.server.util.Utilities.generateHexId();
        LocalFileSystemDataSource testDataSource = new LocalFileSystemDataSource(
            testDataSourceId,
            "test-datasource",
            "0 0 * * *",
            LocalDateTime.now(),
            null,
            DataSourceStatus.IDLE,
            "/test/path",
            null
        );
        
        // Insert data source into database first (NAME_AND_PATH treatment is auto-added)
        LocalFileSystemDataSource createdDataSource = (LocalFileSystemDataSource) dataSourceDao.create(testDataSource);
        testDataSourceId = createdDataSource.id(); // Use the ID from the created datasource
        
        // Get the auto-created NAME_AND_PATH treatment
        DataSourceTreatment treatment = treatmentDao.getTreatment(testDataSourceId, Treatments.NAME_AND_PATH)
            .orElseThrow(() -> new RuntimeException("NAME_AND_PATH treatment should be auto-created"));
        testDataSourceTreatmentId = treatment.getId();
        
        // Create test item
        testItemId = enterfind.server.util.Utilities.generateHexId();
        String itemSql = """
                INSERT INTO ITEMS (id, datasource_id, name, path, time_created, time_modified,
                                  size_bytes, content_hash, mime_type, date_scanned)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        LocalDateTime now = LocalDateTime.now();
        dbAccess.update(itemSql, testItemId, testDataSourceId, "test-file.txt", "/test/path",
                now, now, 1024L, "abc123", "text/plain", now);
        
        // Create item treatment
        testItemTreatmentId = enterfind.server.util.Utilities.generateHexId();
        String itemTreatmentSql = """
                INSERT INTO ITEM_TREATMENTS (id, datasource_treatment_id, item_id, status, date_created)
                VALUES (?, ?, ?, ?, ?)
                """;
        dbAccess.update(itemTreatmentSql, testItemTreatmentId, testDataSourceTreatmentId, 
                testItemId, ItemTreatmentStatus.NEW.toString(), now);
    }

    @Test
    void testProcessTreatmentsWithNewStatus() {
        // Given - item treatment with NEW status (already created in setup)
        
        // When
        treatmentProcessor.processTreatments();
        
        // Then - verify treatment results were inserted
        List<ItemTreatmentResult> results = treatmentDao.getItemTreatmentResults(testItemTreatmentId);
        assertThat(results).isNotEmpty();
        
        // Verify the results contain expected data from NAME_AND_PATH treatment
        assertThat(results).extracting(ItemTreatmentResult::getKey)
            .contains("name");
        assertThat(results).extracting(ItemTreatmentResult::getValue)
            .contains("test-file.txt");
        
        // Verify that the item treatment status was updated to DONE
        var updatedItemTreatment = treatmentDao.getItemTreatmentById(testItemTreatmentId);
        assertThat(updatedItemTreatment).isPresent();
        assertThat(updatedItemTreatment.get().getStatus()).isEqualTo(ItemTreatmentStatus.DONE);
    }

    @Test
    void testProcessTreatmentsWithUpdatedStatus() {
        // Given - first create some existing results
        treatmentProcessor.processTreatments();
        List<ItemTreatmentResult> initialResults = treatmentDao.getItemTreatmentResults(testItemTreatmentId);
        assertThat(initialResults).isNotEmpty();
        
        // Update the item treatment status to UPDATED
        String updateSql = """
                UPDATE ITEM_TREATMENTS 
                SET status = ?, date_modified = ?
                WHERE id = ?
                """;
        dbAccess.update(updateSql, ItemTreatmentStatus.UPDATED.toString(), 
                LocalDateTime.now(), testItemTreatmentId);
        
        // When - process treatments again
        treatmentProcessor.processTreatments();
        
        // Then - verify old results were deleted and new ones inserted
        List<ItemTreatmentResult> finalResults = treatmentDao.getItemTreatmentResults(testItemTreatmentId);
        assertThat(finalResults).isNotEmpty();
        
        // The results should still contain the expected data (they were re-inserted)
        assertThat(finalResults).extracting(ItemTreatmentResult::getKey)
            .contains("name");
        assertThat(finalResults).extracting(ItemTreatmentResult::getValue)
            .contains("test-file.txt");
        
        // Verify that all result IDs are different (indicating they were deleted and re-created)
        assertThat(finalResults).extracting(ItemTreatmentResult::getId)
            .doesNotContainAnyElementsOf(initialResults.stream()
                .map(ItemTreatmentResult::getId)
                .toList());
        
        // Verify that the item treatment status was updated to DONE
        var updatedItemTreatment = treatmentDao.getItemTreatmentById(testItemTreatmentId);
        assertThat(updatedItemTreatment).isPresent();
        assertThat(updatedItemTreatment.get().getStatus()).isEqualTo(ItemTreatmentStatus.DONE);
    }

    @Test
    void testProcessTreatmentsWithFailedStatusGetsReprocessed() {
        // Given - first process to get DONE status
        treatmentProcessor.processTreatments();
        List<ItemTreatmentResult> initialResults = treatmentDao.getItemTreatmentResults(testItemTreatmentId);
        assertThat(initialResults).isNotEmpty();
        
        // Verify initial status is DONE
        var doneItemTreatment = treatmentDao.getItemTreatmentById(testItemTreatmentId);
        assertThat(doneItemTreatment).isPresent();
        assertThat(doneItemTreatment.get().getStatus()).isEqualTo(ItemTreatmentStatus.DONE);
        
        // Update the item treatment status to FAILED (simulating a previous failure)
        String updateSql = """
                UPDATE ITEM_TREATMENTS 
                SET status = ?, date_modified = ?, version = version + 1
                WHERE id = ?
                """;
        dbAccess.update(updateSql, ItemTreatmentStatus.FAILED.toString(), 
                LocalDateTime.now(), testItemTreatmentId);
        
        // When - process treatments again (FAILED should be picked up for reprocessing)
        treatmentProcessor.processTreatments();
        
        // Then - verify results were created (reprocessed)
        List<ItemTreatmentResult> finalResults = treatmentDao.getItemTreatmentResults(testItemTreatmentId);
        assertThat(finalResults).isNotEmpty();
        
        // Verify that the item treatment status was updated to DONE after reprocessing
        var reprocessedItemTreatment = treatmentDao.getItemTreatmentById(testItemTreatmentId);
        assertThat(reprocessedItemTreatment).isPresent();
        assertThat(reprocessedItemTreatment.get().getStatus()).isEqualTo(ItemTreatmentStatus.DONE);
    }



   
}