package enterfind.server.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

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
import enterfind.server.model.ItemTreatmentStatus;
import enterfind.server.model.LocalFileSystemDataSource;

/**
 * Test for Scanner ItemTreatment functionality
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ScannerItemTreatmentTest extends BaseDatabaseTest {

    @Autowired
    private Scanner scanner;

    @Autowired
    private ItemDao itemDao;

    @Autowired
    private TreatmentDao treatmentDao;

    @Autowired
    private DataSourceDao dataSourceDao;

    private LocalFileSystemDataSource testDataSource;
    private String testDataSourceId;
    private DataSourceTreatment contentTreatment;
    private DataSourceTreatment ocrTreatment;
    private Path tempTestDir;

    @BeforeEach
    protected void setUp() {
        super.setUp();
        
        try {
            // Create temporary directory for test files
            tempTestDir = Files.createTempDirectory("scanner-itemtreatment-test-");
            TestFileUtils.copyTestShareToTempDir(tempTestDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to set up temp directory", e);
        }
        
        // Create test datasource pointing to temp directory
        testDataSource = new LocalFileSystemDataSource(
            null, // ID will be generated
            "Test Scanner ItemTreatment",
            "0 0 * * * *",
            LocalDateTime.now(),
            null,
            DataSourceStatus.IDLE,
            tempTestDir.toString(),
            null
        );
        
        // Persist the datasource using DAO (NAME_AND_PATH treatment is auto-added)
        testDataSource = (LocalFileSystemDataSource) dataSourceDao.create(testDataSource);
        testDataSourceId = testDataSource.id();

        treatmentDao.getTreatment(testDataSourceId, Treatments.NAME_AND_PATH)
            .orElseThrow(() -> new RuntimeException("NAME_AND_PATH treatment should be auto-created"));

        // Add additional treatments to the datasource
        contentTreatment = treatmentDao.addTreatment(testDataSourceId, Treatments.CONTENT, null);
        ocrTreatment = treatmentDao.addTreatment(testDataSourceId, Treatments.OCR, null);
    }
    
    @AfterEach
    void tearDown() throws IOException {
        if (tempTestDir != null && Files.exists(tempTestDir)) {
            TestFileUtils.deleteDirectoryRecursively(tempTestDir);
        }
    }



    // Extensions in testshare that OCR can handle
    private static final Set<String> OCR_APPLICABLE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif", "bmp", "tiff", "tif", "pdf");

    private String extensionOf(Item item) {
        String name = item.getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    @Test
    void testNewItemsCreateItemTreatments() {
        // When - run initial scan
        scanner.scan(testDataSource);

        // Then - verify items were created
        List<Item> items = itemDao.findItemsByDatasource(testDataSourceId);
        assertThat(items).isNotEmpty();

        // Verify ItemTreatment records were created for each item
        for (Item item : items) {
            List<ItemTreatment> itemTreatments = treatmentDao.getItemTreatmentsByItem(item.getId());

            boolean ocrApplicable = OCR_APPLICABLE_EXTENSIONS.contains(extensionOf(item));
            int expectedTreatments = ocrApplicable ? 3 : 2; // NAME_AND_PATH + CONTENT + (OCR if applicable)

            assertThat(itemTreatments)
                .as("Item %s should have %d treatments", item.getName(), expectedTreatments)
                .hasSize(expectedTreatments);

            // Verify all treatments have NEW status
            for (ItemTreatment itemTreatment : itemTreatments) {
                assertThat(itemTreatment.getStatus()).isEqualTo(ItemTreatmentStatus.NEW);
                assertThat(itemTreatment.getItemId()).isEqualTo(item.getId());
            }
        }
    }

    @Test
    void testUpdatedItemsUpdateItemTreatments() throws IOException, InterruptedException {
        // Given - initial scan with items
        scanner.scan(testDataSource);
        
        List<Item> initialItems = itemDao.findItemsByDatasource(testDataSourceId);
        assertThat(initialItems).isNotEmpty();
        
        // Mark some treatment records as DONE
        Item firstItem = initialItems.get(0);
        List<ItemTreatment> firstItemTreatments = treatmentDao.getItemTreatmentsByItem(firstItem.getId());
        assertThat(firstItemTreatments).isNotEmpty();
        
        ItemTreatment treatmentToMarkDone = firstItemTreatments.get(0);
        treatmentDao.updateItemTreatmentStatus(
            treatmentToMarkDone.getId(), 
            ItemTreatmentStatus.DONE,
            treatmentToMarkDone.getVersion()
        );

        // When - modify one of the test files to trigger update detection
        // Find the test file corresponding to the item
        // Build the correct path: path is comma-separated folder hierarchy, name is the file
        Path fileToModify = tempTestDir;
        if (firstItem.getPath() != null && !firstItem.getPath().isEmpty()) {
            // Path is comma-separated folder hierarchy
            String[] pathComponents = firstItem.getPath().split(",");
            for (String component : pathComponents) {
                fileToModify = fileToModify.resolve(component.trim());
            }
        }
        fileToModify = fileToModify.resolve(firstItem.getName());
        
        if (Files.exists(fileToModify)) {
            // Append content to the file to trigger size change
            Files.writeString(fileToModify, "modified", StandardOpenOption.APPEND);
        }
        
        // Run second scan
        scanner.scan(testDataSource);

        // Then - verify the DONE treatment was changed to UPDATED
        List<ItemTreatment> updatedTreatments = treatmentDao.getItemTreatmentsByItem(firstItem.getId());
        
        ItemTreatment updatedTreatment = updatedTreatments.stream()
            .filter(it -> it.getDatasourceTreatmentId().equals(treatmentToMarkDone.getDatasourceTreatmentId()))
            .findFirst()
            .orElse(null);
            
        assertThat(updatedTreatment).isNotNull();
        assertThat(updatedTreatment.getStatus()).isEqualTo(ItemTreatmentStatus.UPDATED);
    }

    @Test
    void testNewTreatmentAddedToExistingItems() {
        // Given - initial scan with items
        scanner.scan(testDataSource);

        List<Item> items = itemDao.findItemsByDatasource(testDataSourceId);
        assertThat(items).isNotEmpty();

        // Use a known image item (ocrable.jpg) — OBJECT_DETECTION only applies to images
        Item imageItem = items.stream()
            .filter(i -> i.getName().equals("ocrable.jpg"))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("ocrable.jpg not found in test items"));

        // Use a known non-image item (text.txt) — OBJECT_DETECTION should NOT be added
        Item nonImageItem = items.stream()
            .filter(i -> i.getName().equals("text.txt"))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("text.txt not found in test items"));

        assertThat(treatmentDao.getItemTreatmentsByItem(imageItem.getId())).hasSize(3);
        assertThat(treatmentDao.getItemTreatmentsByItem(nonImageItem.getId())).hasSize(2);

        // When - add OBJECT_DETECTION treatment to the datasource
        DataSourceTreatment objectDetectionTreatment = treatmentDao.addTreatment(testDataSourceId, Treatments.OBJECT_DETECTION, null);

        // Run scan again to trigger fill-missing-treatments logic
        scanner.scan(testDataSource);

        // Then - image item gets the new treatment (4 total)
        List<ItemTreatment> imageItemTreatments = treatmentDao.getItemTreatmentsByItem(imageItem.getId());
        assertThat(imageItemTreatments).hasSize(4);
        assertThat(imageItemTreatments).anyMatch(it ->
            it.getDatasourceTreatmentId().equals(objectDetectionTreatment.getId()) &&
            it.getStatus() == ItemTreatmentStatus.NEW);

        // Non-image item does NOT get OBJECT_DETECTION (still 2)
        assertThat(treatmentDao.getItemTreatmentsByItem(nonImageItem.getId())).hasSize(2);
    }

    @Test
    void testDeletedItemsRemoveItemTreatments() {
        // Given - initial scan with items
        scanner.scan(testDataSource);
        
        List<Item> items = itemDao.findItemsByDatasource(testDataSourceId);
        assertThat(items).isNotEmpty();
        
        Item itemToDelete = items.get(0);
        String itemId = itemToDelete.getId();
        
        // Verify item treatments exist
        List<ItemTreatment> treatmentsBeforeDelete = treatmentDao.getItemTreatmentsByItem(itemId);
        assertThat(treatmentsBeforeDelete).isNotEmpty();

        // When - delete the item directly (simulating item deletion)
        itemDao.deleteItemById(itemId);

        // Then - verify item treatments were cascade deleted
        List<ItemTreatment> treatmentsAfterDelete = treatmentDao.getItemTreatmentsByItem(itemId);
        assertThat(treatmentsAfterDelete).isEmpty();
    }

    @Test
    void testUpdatedItemsWithNonUpdatableStatus() throws IOException, InterruptedException {
        // Given - scan to create initial items with treatments
        scanner.scan(testDataSource);

        List<Item> items = itemDao.findItemsByDatasource(testDataSourceId);
        assertThat(items).isNotEmpty();

        // Use a known image item so it has exactly 3 treatments (NAME_AND_PATH + CONTENT + OCR)
        Item itemToUpdate = items.stream()
            .filter(i -> i.getName().equals("ocrable.jpg"))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("ocrable.jpg not found in test items"));
        String itemId = itemToUpdate.getId();

        // Verify initial item treatments exist and are in NEW status
        List<ItemTreatment> initialTreatments = treatmentDao.getItemTreatmentsByItem(itemId);
        assertThat(initialTreatments).hasSize(3); // NAME_AND_PATH + CONTENT + OCR
        assertThat(initialTreatments).allMatch(it -> it.getStatus() == ItemTreatmentStatus.NEW);
        
        // Manually set one treatment to INPROGRESS (non-updatable status)
        ItemTreatment contentTreatmentRecord = initialTreatments.stream()
            .filter(it -> it.getDatasourceTreatmentId().equals(contentTreatment.getId()))
            .findFirst()
            .orElseThrow();
            
        treatmentDao.updateItemTreatmentStatus(
            contentTreatmentRecord.getId(), 
            ItemTreatmentStatus.INPROGRESS,
            contentTreatmentRecord.getVersion()
        );

        // When - modify one of the test files to trigger update detection
        // Build the correct path: path is comma-separated folder hierarchy, name is the file
        Path fileToModify = tempTestDir;
        if (itemToUpdate.getPath() != null && !itemToUpdate.getPath().isEmpty()) {
            // Path is comma-separated folder hierarchy
            String[] pathComponents = itemToUpdate.getPath().split(",");
            for (String component : pathComponents) {
                fileToModify = fileToModify.resolve(component.trim());
            }
        }
        fileToModify = fileToModify.resolve(itemToUpdate.getName());
        
        if (Files.exists(fileToModify)) {
            // Append content to the file to trigger size change
            Files.writeString(fileToModify, "modified", StandardOpenOption.APPEND);
        }
        
        // Scan again
        scanner.scan(testDataSource);

        // Then - verify behavior:
        // 1. OCR treatment should be updated to UPDATED status (was NEW)
        // 2. CONTENT treatment should remain INPROGRESS (skipped, not updated)
        // 3. NAME_AND_PATH treatment should be updated to UPDATED status (was NEW)
        List<ItemTreatment> updatedTreatments = treatmentDao.getItemTreatmentsByItem(itemId);
        
        // Should still have exactly 3 treatments (no new records created)
        assertThat(updatedTreatments).hasSize(3);
        
        // Verify CONTENT treatment remains INPROGRESS (unchanged)
        assertThat(updatedTreatments).anyMatch(it -> 
            it.getDatasourceTreatmentId().equals(contentTreatment.getId()) && 
            it.getStatus() == ItemTreatmentStatus.INPROGRESS);
            
        // Verify OCR treatment was updated to UPDATED status
        assertThat(updatedTreatments).anyMatch(it -> 
            it.getDatasourceTreatmentId().equals(ocrTreatment.getId()) && 
            it.getStatus() == ItemTreatmentStatus.UPDATED);
    }

    @Test
    void testFailedItemTreatmentsCanBeUpdatedForReprocessing() throws IOException {
        // Given - initial scan with items
        scanner.scan(testDataSource);
        
        List<Item> initialItems = itemDao.findItemsByDatasource(testDataSourceId);
        assertThat(initialItems).isNotEmpty();
        
        // Mark a treatment record as FAILED (simulating a failed processing attempt)
        Item firstItem = initialItems.get(0);
        List<ItemTreatment> firstItemTreatments = treatmentDao.getItemTreatmentsByItem(firstItem.getId());
        assertThat(firstItemTreatments).isNotEmpty();
        
        ItemTreatment treatmentToMarkFailed = firstItemTreatments.stream()
            .filter(it -> it.getDatasourceTreatmentId().equals(contentTreatment.getId()))
            .findFirst()
            .orElseThrow();
            
        treatmentDao.updateItemTreatmentStatus(
            treatmentToMarkFailed.getId(), 
            ItemTreatmentStatus.FAILED,
            treatmentToMarkFailed.getVersion()
        );
        
        // Verify treatment is now FAILED
        ItemTreatment failedTreatment = treatmentDao.getItemTreatmentById(treatmentToMarkFailed.getId())
            .orElseThrow();
        assertThat(failedTreatment.getStatus()).isEqualTo(ItemTreatmentStatus.FAILED);

        // When - modify the file to trigger update detection
        Path fileToModify = tempTestDir;
        if (firstItem.getPath() != null && !firstItem.getPath().isEmpty()) {
            String[] pathComponents = firstItem.getPath().split(",");
            for (String component : pathComponents) {
                fileToModify = fileToModify.resolve(component.trim());
            }
        }
        fileToModify = fileToModify.resolve(firstItem.getName());
        
        if (Files.exists(fileToModify)) {
            Files.writeString(fileToModify, "modified for failed test", StandardOpenOption.APPEND);
        }
        
        // Run second scan
        scanner.scan(testDataSource);

        // Then - verify the FAILED treatment was changed to UPDATED (like DONE treatments)
        List<ItemTreatment> updatedTreatments = treatmentDao.getItemTreatmentsByItem(firstItem.getId());
        
        ItemTreatment updatedTreatment = updatedTreatments.stream()
            .filter(it -> it.getDatasourceTreatmentId().equals(treatmentToMarkFailed.getDatasourceTreatmentId()))
            .findFirst()
            .orElse(null);
            
        assertThat(updatedTreatment).isNotNull();
        assertThat(updatedTreatment.getStatus()).isEqualTo(ItemTreatmentStatus.UPDATED);
    }

    @Test
    void testOcrTreatmentNotCreatedForNonOcrFiles() {
        // When
        scanner.scan(testDataSource);

        List<Item> items = itemDao.findItemsByDatasource(testDataSourceId);

        // .txt file: should have NAME_AND_PATH + CONTENT only, no OCR
        Item txtItem = items.stream()
            .filter(i -> i.getName().equals("text.txt"))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("text.txt not found"));

        List<ItemTreatment> txtTreatments = treatmentDao.getItemTreatmentsByItem(txtItem.getId());
        assertThat(txtTreatments).hasSize(2);
        assertThat(txtTreatments).noneMatch(it -> it.getDatasourceTreatmentId().equals(ocrTreatment.getId()));

        // .exe file: should have NAME_AND_PATH + CONTENT only, no OCR
        Item exeItem = items.stream()
            .filter(i -> i.getName().equals("placeholder-binary.exe"))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("placeholder-binary.exe not found"));

        List<ItemTreatment> exeTreatments = treatmentDao.getItemTreatmentsByItem(exeItem.getId());
        assertThat(exeTreatments).hasSize(2);
        assertThat(exeTreatments).noneMatch(it -> it.getDatasourceTreatmentId().equals(ocrTreatment.getId()));

        // .jpg file: should have NAME_AND_PATH + CONTENT + OCR
        Item jpgItem = items.stream()
            .filter(i -> i.getName().equals("ocrable.jpg"))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("ocrable.jpg not found"));

        List<ItemTreatment> jpgTreatments = treatmentDao.getItemTreatmentsByItem(jpgItem.getId());
        assertThat(jpgTreatments).hasSize(3);
        assertThat(jpgTreatments).anyMatch(it -> it.getDatasourceTreatmentId().equals(ocrTreatment.getId()));

        // .pdf file: should have NAME_AND_PATH + CONTENT + OCR
        Item pdfItem = items.stream()
            .filter(i -> i.getName().equals("ocrable.pdf"))
            .findFirst()
            .orElseThrow(() -> new RuntimeException("ocrable.pdf not found"));

        List<ItemTreatment> pdfTreatments = treatmentDao.getItemTreatmentsByItem(pdfItem.getId());
        assertThat(pdfTreatments).hasSize(3);
        assertThat(pdfTreatments).anyMatch(it -> it.getDatasourceTreatmentId().equals(ocrTreatment.getId()));
    }

    @Test
    void testObjectDetectionAndFaceDetectionOnlyCreatedForImages() {
        // Given - add OBJECT_DETECTION and FACE_DETECTION treatments
        DataSourceTreatment objectDetectionTreatment = treatmentDao.addTreatment(testDataSourceId, Treatments.OBJECT_DETECTION, null);
        DataSourceTreatment faceDetectionTreatment = treatmentDao.addTreatment(testDataSourceId, Treatments.FACE_DETECTION, null);

        // When
        scanner.scan(testDataSource);

        List<Item> items = itemDao.findItemsByDatasource(testDataSourceId);

        // .jpg: image — gets all 5 treatments
        Item jpgItem = items.stream()
            .filter(i -> i.getName().equals("ocrable.jpg"))
            .findFirst()
            .orElseThrow();
        List<ItemTreatment> jpgTreatments = treatmentDao.getItemTreatmentsByItem(jpgItem.getId());
        assertThat(jpgTreatments).hasSize(5); // NAME_AND_PATH + CONTENT + OCR + OBJ_DETECT + FACE_DETECT
        assertThat(jpgTreatments).anyMatch(it -> it.getDatasourceTreatmentId().equals(objectDetectionTreatment.getId()));
        assertThat(jpgTreatments).anyMatch(it -> it.getDatasourceTreatmentId().equals(faceDetectionTreatment.getId()));

        // .pdf: not an image — gets NAME_AND_PATH + CONTENT + OCR only (no obj detection, no face detection)
        Item pdfItem = items.stream()
            .filter(i -> i.getName().equals("ocrable.pdf"))
            .findFirst()
            .orElseThrow();
        List<ItemTreatment> pdfTreatments = treatmentDao.getItemTreatmentsByItem(pdfItem.getId());
        assertThat(pdfTreatments).hasSize(3);
        assertThat(pdfTreatments).noneMatch(it -> it.getDatasourceTreatmentId().equals(objectDetectionTreatment.getId()));
        assertThat(pdfTreatments).noneMatch(it -> it.getDatasourceTreatmentId().equals(faceDetectionTreatment.getId()));

        // .txt: gets NAME_AND_PATH + CONTENT only
        Item txtItem = items.stream()
            .filter(i -> i.getName().equals("text.txt"))
            .findFirst()
            .orElseThrow();
        assertThat(treatmentDao.getItemTreatmentsByItem(txtItem.getId())).hasSize(2);
    }

    @Test
    void testOcrNotAddedOnRescanForNonOcrFiles() {
        // Given - initial scan without OCR treatment (delete it to simulate adding it later)
        dbAccess.update("DELETE FROM DATASOURCE_TREATMENTS WHERE id = ?", ocrTreatment.getId());
        scanner.scan(testDataSource);

        // Add OCR treatment after initial scan
        ocrTreatment = treatmentDao.addTreatment(testDataSourceId, Treatments.OCR, null);

        // When - rescan triggers fill-missing-treatments logic
        scanner.scan(testDataSource);

        List<Item> items = itemDao.findItemsByDatasource(testDataSourceId);

        // Non-OCR files should still not have an OCR treatment row
        Item txtItem = items.stream()
            .filter(i -> i.getName().equals("text.txt"))
            .findFirst()
            .orElseThrow();
        assertThat(treatmentDao.getItemTreatmentsByItem(txtItem.getId()))
            .noneMatch(it -> it.getDatasourceTreatmentId().equals(ocrTreatment.getId()));

        // OCR-applicable files should have the new treatment row
        Item jpgItem = items.stream()
            .filter(i -> i.getName().equals("ocrable.jpg"))
            .findFirst()
            .orElseThrow();
        assertThat(treatmentDao.getItemTreatmentsByItem(jpgItem.getId()))
            .anyMatch(it -> it.getDatasourceTreatmentId().equals(ocrTreatment.getId()));
    }
}