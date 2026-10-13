package enterfind.server.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import org.springframework.core.io.ClassPathResource;

import enterfind.server.TestFileUtils;
import enterfind.server.dao.DBAccess;
import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.ItemDao;
import enterfind.server.dao.TreatmentDao;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.DataSourceTreatment;
import enterfind.server.model.FieldNameConstants;
import enterfind.server.model.ItemTreatment;
import enterfind.server.model.ItemTreatmentResult;
import enterfind.server.model.ItemTreatmentStatus;
import enterfind.server.model.LocalFileSystemDataSource;

/**
 * Integration tests for OCR treatment with real files.
 * Uses per-class lifecycle to run setup and treatment processing only once.
 */
@SpringBootTest
@TestInstance(Lifecycle.PER_CLASS)
class OcrTreatmentIntegrationTest {

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
    
    @Autowired
    private DBAccess dbAccess;

    private Path tempTestDir;
    private LocalFileSystemDataSource testDataSource;
    private List<Item> items;
    private DataSourceTreatment ocrDSTreatment;

    @BeforeAll
    void setUpOnce() throws Exception {
        // Wipe database tables once before all tests
        wipeDatabase();
        
        // Create temporary test directory
        tempTestDir = Files.createTempDirectory("ocr-treatment-test-");
        
        // Copy testshare contents to temp directory
        TestFileUtils.copyTestShareToTempDir(tempTestDir);

        // Copy russian-example.png from ocr-test-images into the temp directory
        ClassPathResource russianImage = new ClassPathResource("ocr-test-images/russian-example.png");
        Files.copy(russianImage.getFile().toPath(), tempTestDir.resolve("russian-example.png"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        
        // Create test datasource
        testDataSource = new LocalFileSystemDataSource(
            null, // ID will be generated
            "Test OCR Extraction",
            "0 0 * * *", // Cron pattern
            LocalDateTime.now(), // Date created
            null, // Date last scanned
            DataSourceStatus.IDLE,
            tempTestDir.toString(),
            null
        );
        
        // Save datasource to database
        testDataSource = (LocalFileSystemDataSource) dataSourceDao.create(testDataSource);
        
        // Add OCR treatment to datasource
        treatmentDao.addTreatment(testDataSource.id(), Treatments.OCR, null);
        
        // Run initial scan to populate items
        scanner.scan(testDataSource);
        
        // Process all treatments (batch size is 1, drain the queue)
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);
        
        // Cache items and OCR treatment for use in tests
        items = itemDao.findItemsByDatasource(testDataSource.id());
        List<DataSourceTreatment> dsToolTreatments = treatmentDao.getTreatmentsByDataSource(testDataSource.id());
        ocrDSTreatment = dsToolTreatments.stream()
                .filter(dst -> dst.getTreatment() == Treatments.OCR)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Should have OCR datasource treatment"));
    }

    @AfterAll
    void tearDownOnce() throws IOException {
        // Clean up temporary directory
        if (tempTestDir != null && Files.exists(tempTestDir)) {
            TestFileUtils.deleteDirectoryRecursively(tempTestDir);
        }
    }
    
    private void wipeDatabase() {
        dbAccess.execute("DELETE FROM ITEM_TREATMENT_RESULTS");
        dbAccess.execute("DELETE FROM ITEM_TREATMENTS");
        dbAccess.execute("DELETE FROM ITEMS");
        dbAccess.execute("DELETE FROM FOLDERS");
        dbAccess.execute("DELETE FROM DATASOURCE_TREATMENTS");
        dbAccess.execute("DELETE FROM DATA_SOURCES");
    }

    @Test
    void testOcrTreatmentExtractsTextFromImage() {
        // Find the ocrable.jpg image item
        Item imageItem = items.stream()
                .filter(item -> "ocrable.jpg".equals(item.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ocrable.jpg not found after scan"));
        
        ItemTreatment ocrTreatment = treatmentDao.getItemTreatment(ocrDSTreatment.getId(), imageItem.getId())
                .orElseThrow(() -> new AssertionError("Should have OCR item treatment for ocrable.jpg"));
        
        // Verify treatment was processed successfully
        assertEquals(ItemTreatmentStatus.DONE, ocrTreatment.getStatus(), 
                "OCR treatment should be completed with DONE status");
        
        // Verify OCR content was extracted
        List<ItemTreatmentResult> results = treatmentDao.getItemTreatmentResults(ocrTreatment.getId());
        assertFalse(results.isEmpty(), "Should have OCR treatment results for ocrable.jpg");
        
        // Verify ocr-content field was created
        ItemTreatmentResult ocrContentResult = results.stream()
                .filter(r -> FieldNameConstants.FIELD_OCR_CONTENT.equals(r.getKey()))
                .findFirst()
                .orElse(null);
        
        assertNotNull(ocrContentResult, "Should have ocr-content field for ocrable.jpg");
        assertFalse(ocrContentResult.getValue().isEmpty(), "OCR content should not be empty");
        
        // Assert specific content from the scanned Apple Genius Bar repair document
        String ocrContent = ocrContentResult.getValue();
        assertTrue(ocrContent.contains("Genius Bar"), 
                "OCR should extract 'Genius Bar' from Apple repair document");
        assertTrue(ocrContent.contains("Mikhail Garber"), 
                "OCR should extract customer name 'Mikhail Garber'");
        assertTrue(ocrContent.contains("Apple"), 
                "OCR should extract 'Apple' from the document");
        assertTrue(ocrContent.contains("MACBOOK PRO") || ocrContent.contains("MacBook") || ocrContent.contains("PCBA"), 
                "OCR should extract product info from the document");
    }

    @Test
    void testOcrTreatmentExtractsTextFromPdf() {
        // Find the ocrable.pdf item
        Item pdfItem = items.stream()
                .filter(item -> "ocrable.pdf".equals(item.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("ocrable.pdf not found after scan"));
        
        ItemTreatment ocrTreatment = treatmentDao.getItemTreatment(ocrDSTreatment.getId(), pdfItem.getId())
                .orElseThrow(() -> new AssertionError("Should have OCR item treatment for ocrable.pdf"));
        
        // Verify treatment was processed successfully
        assertEquals(ItemTreatmentStatus.DONE, ocrTreatment.getStatus(), 
                "OCR treatment should be completed with DONE status");
        
        // Verify OCR content was extracted from PDF
        List<ItemTreatmentResult> results = treatmentDao.getItemTreatmentResults(ocrTreatment.getId());
        assertFalse(results.isEmpty(), "Should have OCR treatment results for ocrable.pdf");
        
        // Check for ocr-content field
        ItemTreatmentResult ocrContentResult = results.stream()
                .filter(r -> FieldNameConstants.FIELD_OCR_CONTENT.equals(r.getKey()))
                .findFirst()
                .orElse(null);
        
        assertNotNull(ocrContentResult, "Should have ocr-content field for ocrable.pdf");
        assertFalse(ocrContentResult.getValue().isEmpty(), "OCR content should not be empty for PDF");
        
        // Assert specific content from the scanned Chrysler Capital statement
        String ocrContent = ocrContentResult.getValue();
        assertTrue(ocrContent.contains("CHRYSLER") || ocrContent.contains("Chrysler"), 
                "OCR should extract 'CHRYSLER' from the statement");
        assertTrue(ocrContent.contains("MIKHAIL GARBER") || ocrContent.contains("Mikhail Garber"), 
                "OCR should extract customer name");
        assertTrue(ocrContent.contains("SAMMAMISH") || ocrContent.contains("WA"), 
                "OCR should extract address information");
    }

    @Test
    void testOcrTreatmentSkipsNonImageNonPdfFiles() {
        // Find a text file — canProcess() returns false, so no item_treatment row is created at all
        Item textItem = items.stream()
                .filter(item -> "text.txt".equals(item.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("text.txt not found after scan"));

        assertTrue(treatmentDao.getItemTreatment(ocrDSTreatment.getId(), textItem.getId()).isEmpty(),
                "text.txt should have no OCR item_treatment row");

        Item exeItem = items.stream()
                .filter(item -> "placeholder-binary.exe".equals(item.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("placeholder-binary.exe not found after scan"));

        assertTrue(treatmentDao.getItemTreatment(ocrDSTreatment.getId(), exeItem.getId()).isEmpty(),
                "placeholder-binary.exe should have no OCR item_treatment row");
    }

    @Test
    void testOcrTreatmentExtractsRussianText() {
        Item russianItem = items.stream()
                .filter(item -> "russian-example.png".equals(item.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("russian-example.png not found after scan"));

        ItemTreatment ocrTreatment = treatmentDao.getItemTreatment(ocrDSTreatment.getId(), russianItem.getId())
                .orElseThrow(() -> new AssertionError("Should have OCR item treatment for russian-example.png"));

        assertEquals(ItemTreatmentStatus.DONE, ocrTreatment.getStatus(),
                "OCR treatment should be completed for russian-example.png");

        List<ItemTreatmentResult> results = treatmentDao.getItemTreatmentResults(ocrTreatment.getId());

        ItemTreatmentResult ocrContentResult = results.stream()
                .filter(r -> FieldNameConstants.FIELD_OCR_CONTENT.equals(r.getKey()))
                .findFirst()
                .orElse(null);

        assertNotNull(ocrContentResult, "Should have ocr-content field for russian-example.png");
        assertFalse(ocrContentResult.getValue().isEmpty(), "OCR content should not be empty");

        String ocrContent = ocrContentResult.getValue();
        assertTrue(ocrContent.contains("Дочь") || ocrContent.contains("дочь"),
                "OCR should extract Russian word 'Дочь' (daughter) from the dictionary entry");
        assertTrue(ocrContent.contains("матери") || ocrContent.contains("Матери"),
                "OCR should extract Russian word 'матери' (mother) from the dictionary entry");
    }

    @Test
    void testOcrTreatmentHandlesRegularJpgImage() {
        // Find the beam.jpg image in subf folder
        Item beamItem = items.stream()
                .filter(item -> "beam.jpg".equals(item.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("beam.jpg not found after scan"));
        
        ItemTreatment ocrTreatment = treatmentDao.getItemTreatment(ocrDSTreatment.getId(), beamItem.getId())
                .orElseThrow(() -> new AssertionError("Should have OCR item treatment for beam.jpg"));
        
        // Treatment should be completed
        assertEquals(ItemTreatmentStatus.DONE, ocrTreatment.getStatus(), 
                "OCR treatment should complete for image file");
    }

    @Test
    void testOcrTreatmentProcessesAllFilesInDatasource() {
        int totalItems = items.size();
        assertTrue(totalItems > 0, "Should have items in datasource");
        
        int processedCount = 0;
        for (Item item : items) {
            ItemTreatment treatment = treatmentDao.getItemTreatment(ocrDSTreatment.getId(), item.getId()).orElse(null);
            if (treatment != null && treatment.getStatus() == ItemTreatmentStatus.DONE) {
                processedCount++;
            }
        }
        
        // canProcess() skips non-image/non-PDF files, so not all items get OCR treatments
        long ocrApplicableCount = items.stream()
                .filter(item -> treatmentDao.getItemTreatment(ocrDSTreatment.getId(), item.getId()).isPresent())
                .count();
        assertEquals(ocrApplicableCount, processedCount, "All OCR-applicable items should be processed");
    }

    @Test
    void testOcrTreatmentProducesCorrectNumberOfResults() {
        // Count how many items have OCR content results
        int itemsWithOcrContent = 0;
        int itemsWithoutOcrContent = 0;
        
        for (Item item : items) {
            ItemTreatment treatment = treatmentDao.getItemTreatment(ocrDSTreatment.getId(), item.getId()).orElse(null);
            if (treatment != null) {
                List<ItemTreatmentResult> results = treatmentDao.getItemTreatmentResults(treatment.getId());
                boolean hasOcrContent = results.stream()
                        .anyMatch(r -> FieldNameConstants.FIELD_OCR_CONTENT.equals(r.getKey()));
                if (hasOcrContent) {
                    itemsWithOcrContent++;
                } else {
                    itemsWithoutOcrContent++;
                }
            }
        }
        
        // Expected items that SHOULD produce OCR content:
        // - ocrable.jpg (image with readable text - scanned document)
        // - ocrable.pdf (scanned PDF with readable text - no extractable text, needs OCR)
        // - russian-example.png (Cyrillic image, OSD detects Russian)
        // Total: 3 items
        //
        // Expected items that should NOT produce OCR content (but still have treatment rows):
        // - USGS Mineral Commodity Summaries 2025 (Abrasives-Copper).pdf (true PDF with extractable text - skipped by isTruePdf)
        // - Resume.pdf (true PDF with extractable text - skipped by isTruePdf)
        // - beam.jpg (image but no readable text, OCR returns empty)
        // - 98594ed9...jpeg (image but no readable text, OCR returns empty)
        // Total: 4 items  (text.txt and placeholder-binary.exe have no treatment rows at all)

        assertEquals(3, itemsWithOcrContent,
                "Should have exactly 3 items with OCR content (ocrable.jpg + ocrable.pdf + russian-example.png)");
        assertEquals(4, itemsWithoutOcrContent,
                "Should have exactly 4 items without OCR content (2 true PDFs + 2 images without text)");
        assertEquals(9, items.size(), "Should have 9 total items in test data");
    }
}
