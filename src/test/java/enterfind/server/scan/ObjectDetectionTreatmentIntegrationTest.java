package enterfind.server.scan;

import static enterfind.server.model.FieldNameConstants.FIELD_OBJECT_DETECTED;
import static enterfind.server.model.FieldNameConstants.FIELD_OBJECT_DETECTION_CONFIDENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.TestFileUtils;
import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.ItemDao;
import enterfind.server.dao.TreatmentDao;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.DataSourceTreatment;
import enterfind.server.model.ItemTreatment;
import enterfind.server.model.ItemTreatmentResult;
import enterfind.server.model.ItemTreatmentStatus;
import enterfind.server.model.LocalFileSystemDataSource;

/**
 * Integration tests for OBJECT_DETECTION treatment with real image files
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ObjectDetectionTreatmentIntegrationTest extends BaseDatabaseTest {

    @Autowired
    private Scanner scanner;

    @Autowired
    private TreatmentProcessor treatmentProcessor;

    @Autowired
    private DataSourceDao dataSourceDao;

    @Autowired
    private ItemDao itemDao;

    @Autowired
    private TreatmentDao treatmentDao;

    private Path tempTestDir;
    private LocalFileSystemDataSource testDataSource;

    @BeforeEach
    void setUpTest() throws Exception {
        // Wipe database tables before each test
        wipeDatabase();

        // Create temporary test directory
        tempTestDir = Files.createTempDirectory("object-detection-treatment-test-");

        // Copy testshare contents to temp directory
        TestFileUtils.copyTestShareToTempDir(tempTestDir);

        // Create test datasource
        testDataSource = new LocalFileSystemDataSource(
            null, // ID will be generated
            "Test Object Detection",
            "0 0 * * *", // Cron pattern
            LocalDateTime.now(), // Date created
            null, // Date last scanned
            DataSourceStatus.IDLE,
            tempTestDir.toString(),
            null
        );

        // Save datasource to database
        testDataSource = (LocalFileSystemDataSource) dataSourceDao.create(testDataSource);

        // Add OBJECT_DETECTION treatment to datasource
        treatmentDao.addTreatment(testDataSource.id(), Treatments.OBJECT_DETECTION, null);

        // Run initial scan to populate items
        scanner.scan(testDataSource);
    }

    @AfterEach
    void tearDownTest() throws IOException {
        // Clean up temporary directory
        if (tempTestDir != null && Files.exists(tempTestDir)) {
            TestFileUtils.deleteDirectoryRecursively(tempTestDir);
        }
    }

    @Test
    void testObjectDetectionOnImageFile() throws Exception {
        // Find the specific image that has objects detected
        List<Item> items = itemDao.findItemsByDatasource(testDataSource.id());
        Item imageItem = items.stream()
            .filter(item -> item.getName().equals("98594ed9-8559-4607-a0c0-b4ce7df43a26-2020-10-06T15_00_00.000Z_4.jpeg"))
            .findFirst()
            .orElse(null);

        assertNotNull(imageItem, "Should have the specific test image with objects");

        // Get the item treatment for OBJECT_DETECTION
        DataSourceTreatment dsTreatment = treatmentDao.getTreatment(testDataSource.id(), Treatments.OBJECT_DETECTION).orElse(null);
        assertNotNull(dsTreatment, "OBJECT_DETECTION treatment should exist");

        ItemTreatment itemTreatment = treatmentDao.getItemTreatment(dsTreatment.getId(), imageItem.getId()).orElse(null);
        assertNotNull(itemTreatment, "Item treatment should exist");

        // Process the treatment
        int processed;
        int totalProcessed = 0;
        do {
            processed = treatmentProcessor.processTreatments();
            totalProcessed += processed;
        } while (processed > 0);
        assertTrue(totalProcessed > 0, "Should have processed at least one treatment");

        // Verify treatment was completed
        ItemTreatment updatedTreatment = treatmentDao.getItemTreatmentById(itemTreatment.getId()).orElse(null);
        assertNotNull(updatedTreatment, "Updated treatment should exist");
        assertEquals(ItemTreatmentStatus.DONE, updatedTreatment.getStatus(), "Treatment should be DONE");

        // Check treatment results
        List<ItemTreatmentResult> results = treatmentDao.getItemTreatmentResults(itemTreatment.getId());
        assertNotNull(results, "Should have treatment results");

        // For object detection, we may or may not detect objects depending on the image content
        // The important thing is that the treatment ran without error
        // Results may be empty if no objects were detected, which is valid
        long objectDetectedCount = results.stream()
            .mapToLong(result -> FIELD_OBJECT_DETECTED.equals(result.getKey()) ? 1 : 0)
            .sum();
        
        long confidenceCount = results.stream()
            .mapToLong(result -> FIELD_OBJECT_DETECTION_CONFIDENCE.equals(result.getKey()) ? 1 : 0)
            .sum();

        // Both counts should be equal (each detected object has both a name and confidence)
        assertEquals(objectDetectedCount, confidenceCount, "Should have equal number of object names and confidence scores");

        // This specific image should have detected objects
        assertTrue(objectDetectedCount > 0, "This test image should have detected objects");

        // Print detected objects for debugging
        System.out.println("Detected objects in " + imageItem.getName() + ":");
        results.stream()
            .filter(result -> FIELD_OBJECT_DETECTED.equals(result.getKey()))
            .forEach(result -> System.out.println("  Object: " + result.getValue()));

        // Assert that the expected object "person" was detected
        boolean personDetected = results.stream()
            .filter(result -> FIELD_OBJECT_DETECTED.equals(result.getKey()))
            .anyMatch(result -> "person".equals(result.getValue()));
        assertTrue(personDetected, "Should have detected a 'person' object in the test image");

        // Verify that confidence values are valid numbers
        results.stream()
            .filter(result -> FIELD_OBJECT_DETECTION_CONFIDENCE.equals(result.getKey()))
            .forEach(confidenceResult -> {
                String confidenceValue = confidenceResult.getValue();
                assertNotNull(confidenceValue, "Confidence value should not be null");
                // Should be parseable as double
                Double.parseDouble(confidenceValue);
            });
    }

    @Test
    void testObjectDetectionSkippedOnNonImageFile() throws Exception {
        // Find a non-image file in the test data
        List<Item> items = itemDao.findItemsByDatasource(testDataSource.id());
        Item textItem = items.stream()
            .filter(item -> item.getName().toLowerCase().endsWith(".txt"))
            .findFirst()
            .orElse(null);

        assertNotNull(textItem, "Should have at least one text file in test data");

        // Get the item treatment for OBJECT_DETECTION
        DataSourceTreatment dsTreatment = treatmentDao.getTreatment(testDataSource.id(), Treatments.OBJECT_DETECTION).orElse(null);
        assertNotNull(dsTreatment, "OBJECT_DETECTION treatment should exist");

        // canProcess() returns false for non-image files — no item_treatment row is created
        assertTrue(treatmentDao.getItemTreatment(dsTreatment.getId(), textItem.getId()).isEmpty(),
            "text file should have no OBJECT_DETECTION item_treatment row");
    }

}