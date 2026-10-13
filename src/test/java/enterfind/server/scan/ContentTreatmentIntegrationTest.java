package enterfind.server.scan;

import static enterfind.server.model.FieldNameConstants.FIELD_CONTENT;
import static enterfind.server.model.FieldNameConstants.FIELD_CONTENT_TRUNCATED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import enterfind.server.dao.SearchDAO;
import enterfind.server.dao.TreatmentDao;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.DataSourceTreatment;
import enterfind.server.model.ItemTreatment;
import enterfind.server.model.ItemTreatmentResult;
import enterfind.server.model.ItemTreatmentStatus;
import enterfind.server.model.LocalFileSystemDataSource;
import enterfind.server.scan.treatment.TreatmentUtils;

/**
 * Integration tests for CONTENT treatment with real files
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ContentTreatmentIntegrationTest extends BaseDatabaseTest {

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
    private SearchDAO searchDao;

    /** The document-text keys: bare "content" or a numbered chunk, not metadata such as "content-type". */
    private static final Pattern CONTENT_CHUNK_KEY = Pattern.compile("^" + FIELD_CONTENT + "(-\\d+)?$");

    private Path tempTestDir;
    private LocalFileSystemDataSource testDataSource;

    @BeforeEach
    void setUpTest() throws Exception {
        // Wipe database tables before each test
        wipeDatabase();
        
        // Create temporary test directory
        tempTestDir = Files.createTempDirectory("content-treatment-test-");
        
        // Copy testshare contents to temp directory
        TestFileUtils.copyTestShareToTempDir(tempTestDir);
        
        // Create test datasource
        testDataSource = new LocalFileSystemDataSource(
            null, // ID will be generated
            "Test Content Extraction",
            "0 0 * * *", // Cron pattern
            LocalDateTime.now(), // Date created
            null, // Date last scanned
            DataSourceStatus.IDLE,
            tempTestDir.toString(),
            null
        );
        
        // Save datasource to database
        testDataSource = (LocalFileSystemDataSource) dataSourceDao.create(testDataSource);
        
        // Add CONTENT treatment to datasource
        treatmentDao.addTreatment(testDataSource.id(), Treatments.CONTENT, null);
        
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


    /**
     * Count the number of files (not directories) in the test directory
     */
    private long countFilesInTestDirectory() throws IOException {
        try (Stream<Path> files = Files.walk(tempTestDir)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    @Test
    void testContentTreatmentExtractsTextFileContent() throws IOException {
        // Given - verify all files were scanned
        List<Item> items = itemDao.findItemsByDatasource(testDataSource.id());
        assertEquals(8, items.size(), "Should find 8 items (6 files in root + 2 files in subf/)");
        
        // Assert that number of scanned items equals number of files in test directory
        long filesInDirectory = countFilesInTestDirectory();
        assertEquals(filesInDirectory, items.size(), 
                "Number of files with treatment results should equal number of files in test directory");
        
        // When - process treatments for all files
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);
        
        // Then - verify all files are successfully processed with treatment results
        List<DataSourceTreatment> dsToolTreatments = treatmentDao.getTreatmentsByDataSource(testDataSource.id());
        DataSourceTreatment contentDSTreatment = dsToolTreatments.stream()
                .filter(dst -> dst.getTreatment() == Treatments.CONTENT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Should have CONTENT datasource treatment"));
        
        // Count successful treatments (should be all 8 files)
        int totalItemTreatments = 0;
        int completedTreatments = 0;
        for (Item item : items) {
            ItemTreatment itemTreatment = treatmentDao.getItemTreatment(contentDSTreatment.getId(), item.getId()).orElse(null);
            if (itemTreatment != null) {
                totalItemTreatments++;
                if (ItemTreatmentStatus.DONE.equals(itemTreatment.getStatus())) {
                    completedTreatments++;
                }
            }
        }
        
        assertEquals(8, totalItemTreatments, "Should have item treatments for all 8 files");
        assertEquals(8, completedTreatments, "All 8 files should be successfully processed");
        
        // Assert that number of files with treatment results equals number of files in test directory
        assertEquals(filesInDirectory, totalItemTreatments, 
                "Number of files with treatment results should equal number of files in test directory");
        
        // Verify specific text.txt file content extraction (in subf folder)
        Item textItem = items.stream()
                .filter(item -> "text.txt".equals(item.getName()) && "subf".equals(item.getPath()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("text.txt item not found in subf folder after scan"));
        
        ItemTreatment contentTreatment = treatmentDao.getItemTreatment(contentDSTreatment.getId(), textItem.getId())
                .orElseThrow(() -> new AssertionError("Should have CONTENT item treatment"));
        
        List<ItemTreatmentResult> results = treatmentDao.getItemTreatmentResults(contentTreatment.getId());
        assertFalse(results.isEmpty(), "Should have treatment results");
        
        // Should have content
        assertTrue(results.stream().anyMatch(r -> FIELD_CONTENT.equals(r.getKey())),
                "Should extract content");
        
        ItemTreatmentResult contentResult = results.stream()
                .filter(r -> FIELD_CONTENT.equals(r.getKey()))
                .findFirst()
                .orElse(null);
        
        assertNotNull(contentResult, "Content result should not be null");
        assertFalse(contentResult.getValue().isEmpty(), "Content should not be empty");
        
        // Assert exact content from text.txt file (contains only "aabaa")
        assertEquals("aabaa", contentResult.getValue().trim(), 
                "Content should be exactly 'aabaa' from text.txt file");
        
        // Should have metadata (check for various possible content-type keys)
        List<String> keys = results.stream().map(ItemTreatmentResult::getKey).toList();
        boolean hasContentType = keys.stream().anyMatch(key -> 
            key.toLowerCase().contains("content") && key.toLowerCase().contains("type"));
        
        assertTrue(hasContentType, "Should have Content-Type metadata. Available keys: " + keys);
    }

    @Test
    void testContentTreatmentExtractsPdfMetadata() {
        // Given - find a PDF item that was scanned
        List<Item> items = itemDao.findItemsByDatasource(testDataSource.id());
        Item pdfItem = items.stream()
                .filter(item -> item.getName().endsWith(".pdf"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PDF item not found after scan"));
        
        // When - process treatments
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);
        
        // Then - verify content extraction results
        List<DataSourceTreatment> dsToolTreatments = treatmentDao.getTreatmentsByDataSource(testDataSource.id());
        DataSourceTreatment contentDSTreatment = dsToolTreatments.stream()
                .filter(dst -> dst.getTreatment() == Treatments.CONTENT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Should have CONTENT datasource treatment"));
        
        ItemTreatment contentTreatment = treatmentDao.getItemTreatment(contentDSTreatment.getId(), pdfItem.getId())
                .orElseThrow(() -> new AssertionError("Should have CONTENT item treatment"));
        
        // Verify treatment was processed successfully
        assertEquals(ItemTreatmentStatus.DONE, contentTreatment.getStatus(), 
                "Treatment should be completed with DONE status");
        
        List<ItemTreatmentResult> results = treatmentDao.getItemTreatmentResults(contentTreatment.getId());
        assertFalse(results.isEmpty(), "Should have treatment results");
        
        // Should have content
        assertTrue(results.stream().anyMatch(r -> FIELD_CONTENT.equals(r.getKey())),
                "Should extract content from PDF");
        
        // Check for specific content based on PDF file
        ItemTreatmentResult pdfContentResult = results.stream()
                .filter(r -> FIELD_CONTENT.equals(r.getKey()))
                .findFirst()
                .orElse(null);
        
        assertNotNull(pdfContentResult, "PDF content result should not be null");
        String content = pdfContentResult.getValue();
        assertFalse(content.isEmpty(), "PDF content should not be empty");
        
        // Assert specific content based on which PDF file we're testing
        if (pdfItem.getName().contains("Resume") || pdfItem.getName().contains("Mikhail")) {
            // Resume PDF should contain name and resume-related content
            assertTrue(content.contains("Mikhail") || content.contains("Garber"), 
                    "Resume PDF should contain 'Mikhail' or 'Garber'. Content starts with: " + 
                    content.substring(0, Math.min(200, content.length())));
        } else if (pdfItem.getName().contains("USGS") || pdfItem.getName().contains("Mineral")) {
            // USGS Mineral Commodity Summaries PDF should contain mineral-commodity-related content
            assertTrue(content.contains("Mineral") || content.contains("Commodity") || content.contains("USGS"),
                    "USGS report PDF should contain 'Mineral', 'Commodity', or 'USGS'. Content starts with: " +
                    content.substring(0, Math.min(200, content.length())));
        }
        
        // Should have metadata (check for various possible content-type keys)
        List<String> keys = results.stream().map(ItemTreatmentResult::getKey).toList();
        boolean hasContentType = keys.stream().anyMatch(key -> 
            key.toLowerCase().contains("content") && key.toLowerCase().contains("type"));
        
        assertTrue(hasContentType, "Should have Content-Type metadata. Available keys: " + keys);
        
        // Verify the Content-Type metadata
        ItemTreatmentResult contentTypeResult = results.stream()
                .filter(r -> r.getKey().toLowerCase().contains("content") && r.getKey().toLowerCase().contains("type"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(contentTypeResult, "Content-Type result should not be null");
        assertFalse(contentTypeResult.getValue().isEmpty(), "Content-Type should not be empty");
    }

    @Test
    void testContentTreatmentExtractsMultipleMetadataFields() {
        // Given - find a text or PDF file that should have extractable content
        List<Item> items = itemDao.findItemsByDatasource(testDataSource.id());
        assertFalse(items.isEmpty(), "Should have scanned items");
        
        // Find a file that should have text content (PDF or text file)
        Item testItem = items.stream()
                .filter(item -> item.getName().endsWith(".pdf") || item.getName().endsWith(".txt"))
                .findFirst()
                .orElse(items.get(0)); // fallback to first item if no PDF/text found
        
        // When - process treatments
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);
        
        // Then - check that we get multiple metadata fields
        List<DataSourceTreatment> dsToolTreatments = treatmentDao.getTreatmentsByDataSource(testDataSource.id());
        DataSourceTreatment contentDSTreatment = dsToolTreatments.stream()
                .filter(dst -> dst.getTreatment() == Treatments.CONTENT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Should have CONTENT datasource treatment"));
        
        ItemTreatment contentTreatment = treatmentDao.getItemTreatment(contentDSTreatment.getId(), testItem.getId())
                .orElseThrow(() -> new AssertionError("Should have CONTENT item treatment"));
        
        // Verify treatment was processed successfully
        assertEquals(ItemTreatmentStatus.DONE, contentTreatment.getStatus(), 
                "Treatment should be completed with DONE status");
        
        List<ItemTreatmentResult> results = treatmentDao.getItemTreatmentResults(contentTreatment.getId());
        assertFalse(results.isEmpty(), "Should have treatment results");
        
        // Should have both content and various metadata fields
        List<String> keys = results.stream()
                .map(ItemTreatmentResult::getKey)
                .toList();
        
        // Only expect content for files that should have extractable text
        if (testItem.getName().endsWith(".pdf") || testItem.getName().endsWith(".txt")) {
            assertTrue(keys.contains(FIELD_CONTENT), "Should extract content from " + testItem.getName());
        }
        
        // Should have some standard Tika metadata fields
        assertTrue(keys.stream().anyMatch(key -> key.toLowerCase().contains("content") && key.toLowerCase().contains("type")),
                "Should have Content-Type metadata fields");
        
        System.out.println("Extracted metadata keys for " + testItem.getName() + ": " + keys);
        
        // Print all results for verification
        results.forEach(result -> 
            System.out.println(result.getKey() + " = " + result.getValue())
        );
    }
    
    @Test
    void testContentTreatmentProcessesAllFiles() {
        // Get all items
        List<Item> items = itemDao.findItemsByDatasource(testDataSource.id());
        assertFalse(items.isEmpty(), "Should have scanned items");
        
        // When - process treatments
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);
        
        // Then - verify all items have content treatments
        List<DataSourceTreatment> dsToolTreatments = treatmentDao.getTreatmentsByDataSource(testDataSource.id());
        DataSourceTreatment contentDSTreatment = dsToolTreatments.stream()
                .filter(dst -> dst.getTreatment() == Treatments.CONTENT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Should have CONTENT datasource treatment"));
        
        for (Item item : items) {
            ItemTreatment contentTreatment = treatmentDao.getItemTreatment(contentDSTreatment.getId(), item.getId())
                    .orElseThrow(() -> new AssertionError("Should have CONTENT item treatment for " + item.getName()));
            
            // Verify treatment was processed successfully
            assertEquals(ItemTreatmentStatus.DONE, contentTreatment.getStatus(), 
                    "Treatment should be completed with DONE status for " + item.getName());
            
            List<ItemTreatmentResult> results = treatmentDao.getItemTreatmentResults(contentTreatment.getId());
            assertFalse(results.isEmpty(), "Should have treatment results for " + item.getName());
            
            // Files that should have text content (text files, some PDFs) should have content key
            // Some PDFs might be scanned without text content, images, executables may only have metadata
            List<String> keys = results.stream().map(ItemTreatmentResult::getKey).toList();
            boolean hasContent = results.stream().anyMatch(r -> FIELD_CONTENT.equals(r.getKey()));
            
            if (item.getName().endsWith(".txt")) {
                // Text files should always have content
                assertTrue(hasContent, "Should extract content for " + item.getName());
            } else if (item.getName().endsWith(".pdf")) {
                // PDFs may or may not have extractable content (e.g., scanned PDFs)
                // Log info about PDFs without content but don't fail the test
                if (!hasContent) {
                    System.out.println("INFO: PDF " + item.getName() + " has no extractable text content. Available keys: " + keys);
                }
            }
            
            // All files should have Content-Type metadata
            boolean hasContentType = keys.stream().anyMatch(key -> 
                key.toLowerCase().contains("content") && key.toLowerCase().contains("type"));
            assertTrue(hasContentType, "Should have Content-Type for " + item.getName() + ". Available keys: " + keys);
        }
    }
    
    @Test
    void testContentTreatmentHandlesImageFiles() {
        // Given - find an image item that was scanned
        List<Item> items = itemDao.findItemsByDatasource(testDataSource.id());
        Item imageItem = items.stream()
                .filter(item -> item.getName().endsWith(".jpg") || item.getName().endsWith(".jpeg") || item.getName().endsWith(".png"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Image item not found after scan"));
        
        // When - process treatments
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);
        
        // Then - verify content extraction results for image
        List<DataSourceTreatment> dsToolTreatments = treatmentDao.getTreatmentsByDataSource(testDataSource.id());
        DataSourceTreatment contentDSTreatment = dsToolTreatments.stream()
                .filter(dst -> dst.getTreatment() == Treatments.CONTENT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Should have CONTENT datasource treatment"));
        
        ItemTreatment contentTreatment = treatmentDao.getItemTreatment(contentDSTreatment.getId(), imageItem.getId())
                .orElseThrow(() -> new AssertionError("Should have CONTENT item treatment"));
        
        // Verify treatment was processed successfully
        assertEquals(ItemTreatmentStatus.DONE, contentTreatment.getStatus(), 
                "Treatment should be completed with DONE status");
        
        List<ItemTreatmentResult> results = treatmentDao.getItemTreatmentResults(contentTreatment.getId());
        assertFalse(results.isEmpty(), "Should have treatment results");
        
        // Should have metadata even if content is empty
        List<String> keys = results.stream().map(ItemTreatmentResult::getKey).toList();
        boolean hasContentType = keys.stream().anyMatch(key -> 
            key.toLowerCase().contains("content") && key.toLowerCase().contains("type"));
        assertTrue(hasContentType, "Should have Content-Type metadata for image. Available keys: " + keys);
        
        // Verify Content-Type is image-related
        ItemTreatmentResult contentTypeResult = results.stream()
                .filter(r -> r.getKey().toLowerCase().contains("content") && r.getKey().toLowerCase().contains("type"))
                .findFirst()
                .orElse(null);
        
        assertNotNull(contentTypeResult, "Content-Type result should not be null");
        assertTrue(contentTypeResult.getValue().startsWith("image/"),
                "Content-Type should be image type, got: " + contentTypeResult.getValue());
    }

    /** Matches Tika's scratch file name, e.g. apache-tika-2719714954678636044.tmp */
    private static final Pattern TIKA_TEMP_FILE_NAME = Pattern.compile("apache-tika-\\d+\\.tmp");

    /**
     * Reproduces Bug 1: Tika temp-file fields leak into image attributes.
     *
     * <p>When Tika parses an image it spools the input stream to a temporary file
     * ({@code apache-tika-<digits>.tmp}) because the image parser needs a seekable file.
     * The drewnoakes {@code metadata-extractor} library then reads that temp file's
     * {@code FileSystemDirectory} and emits "File Name" / "File Size" / "File Modified Date"
     * tags describing the <em>temp file</em>, not the original image. These get stored as
     * {@code file_name} / {@code file_size} / {@code file_modified_date} attributes — pure
     * noise, and {@code file_modified_date} is actively misleading (it is the ingest time,
     * not the photo's date).
     *
     * <p>The real values already live in their own correct fields ({@code name},
     * {@code image_width}/{@code image_height}, {@code exif_*}), so the temp-file fields
     * should never be stored. This test fails against the current code (the bug is present)
     * and should pass once the leak is suppressed.
     */
    @Test
    void testContentTreatmentDoesNotLeakTikaTempFileMetadata() {
        // Given - find a JPEG image item that was scanned (image parser path uses a temp file)
        List<Item> items = itemDao.findItemsByDatasource(testDataSource.id());
        Item imageItem = items.stream()
                .filter(item -> item.getName().toLowerCase().endsWith(".jpg")
                        || item.getName().toLowerCase().endsWith(".jpeg"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("JPEG image item not found after scan"));

        // When - process treatments
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);

        // Then - locate the CONTENT treatment results for the image
        List<DataSourceTreatment> dsToolTreatments = treatmentDao.getTreatmentsByDataSource(testDataSource.id());
        DataSourceTreatment contentDSTreatment = dsToolTreatments.stream()
                .filter(dst -> dst.getTreatment() == Treatments.CONTENT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Should have CONTENT datasource treatment"));

        ItemTreatment contentTreatment = treatmentDao.getItemTreatment(contentDSTreatment.getId(), imageItem.getId())
                .orElseThrow(() -> new AssertionError("Should have CONTENT item treatment"));

        assertEquals(ItemTreatmentStatus.DONE, contentTreatment.getStatus(),
                "Treatment should be completed with DONE status for " + imageItem.getName());

        List<ItemTreatmentResult> results = treatmentDao.getItemTreatmentResults(contentTreatment.getId());
        assertFalse(results.isEmpty(), "Should have treatment results for image " + imageItem.getName());

        // Sanity: the real image metadata was extracted (so this is a genuine image parse,
        // and absence of the temp-file fields below is the fix working, not an empty result).
        List<String> keys = results.stream().map(ItemTreatmentResult::getKey).toList();
        assertTrue(keys.stream().anyMatch(k -> k.toLowerCase().contains("content")
                        && k.toLowerCase().contains("type")),
                "Should have Content-Type metadata for image. Keys: " + keys);

        // Premise: the genuine source-file modification date is captured at scan time from the
        // real file's BasicFileAttributes.lastModifiedTime() onto the Item itself (exposed via
        // GraphQL as dateModified), entirely independent of Tika. So dropping the leaked
        // file_modified_date attribute below loses nothing — this is the value callers should use.
        assertNotNull(imageItem.getTimeModified(),
                "Real source-file modification date should be available on the Item (time_modified), "
                        + "independent of the Tika temp-file attribute");

        // 1) No attribute value may be Tika's scratch file name. This is the headline leak:
        //    file_name = apache-tika-<digits>.tmp.
        ItemTreatmentResult leakedTempName = results.stream()
                .filter(r -> TIKA_TEMP_FILE_NAME.matcher(r.getValue()).matches())
                .findFirst()
                .orElse(null);
        assertNull(leakedTempName,
                "Tika temp-file name leaked into attributes: "
                        + (leakedTempName == null ? "" : leakedTempName.getKey() + " = " + leakedTempName.getValue())
                        + ". Keys: " + keys);

        // 2) file_size and file_modified_date describe the temp file on the image path and
        //    are indistinguishable from real metadata by value, so they must not be stored.
        assertFalse(keys.contains("file_size"),
                "file_size (Tika temp-file size) should not be stored for image "
                        + imageItem.getName() + ". Keys: " + keys);
        assertFalse(keys.contains("file_modified_date"),
                "file_modified_date (Tika ingest time, not the photo's date) should not be stored for image "
                        + imageItem.getName() + ". Keys: " + keys);
    }

    @Test
    void testLargeContentIsStoredAsNumberedChunksInDocumentOrder() throws IOException {
        // Given - a text file well over one FTS chunk, with a marker word only at the start and only at the end
        StringBuilder sb = new StringBuilder("chunkmarkeralpha");
        for (int line = 0; sb.length() < TreatmentUtils.FTS_CHUNK_SIZE * 2.4; line++) {
            sb.append('\n').append("Line ").append(line).append(" of a document too large for one full-text chunk.");
        }
        sb.append("\nchunkmarkeromega");
        String text = sb.toString();
        Files.writeString(tempTestDir.resolve("large.txt"), text);
        scanner.scan(testDataSource);

        // When
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);

        // Then - numbered keys only, no bare "content"
        Item largeItem = itemDao.findItemsByDatasource(testDataSource.id()).stream()
                .filter(item -> "large.txt".equals(item.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("large.txt not found after rescan"));
        DataSourceTreatment contentDSTreatment = treatmentDao.getTreatmentsByDataSource(testDataSource.id()).stream()
                .filter(dst -> dst.getTreatment() == Treatments.CONTENT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Should have CONTENT datasource treatment"));
        ItemTreatment contentTreatment = treatmentDao.getItemTreatment(contentDSTreatment.getId(), largeItem.getId())
                .orElseThrow(() -> new AssertionError("Should have CONTENT item treatment for large.txt"));
        assertEquals(ItemTreatmentStatus.DONE, contentTreatment.getStatus());

        List<ItemTreatmentResult> chunks = treatmentDao.getItemTreatmentResults(contentTreatment.getId()).stream()
                .filter(r -> CONTENT_CHUNK_KEY.matcher(r.getKey()).matches())
                // A bare "content" sorts first, so the key assertion below reports it rather than this crashing
                .sorted(Comparator.comparingInt(r -> r.getKey().equals(FIELD_CONTENT)
                        ? -1 : Integer.parseInt(r.getKey().substring(FIELD_CONTENT.length() + 1))))
                .toList();
        assertEquals(3, chunks.size(), "2.4 chunk sizes of text should split into 3 chunks");
        assertEquals(List.of("content-0", "content-1", "content-2"),
                chunks.stream().map(ItemTreatmentResult::getKey).toList());

        // Every chunk is in the content table, where the full-text index is maintained
        Integer contentRows = dbAccess.queryForObject(
                "SELECT count(*) FROM ITEM_TREATMENT_RESULTS_CONTENT WHERE item_treatment_id = ? AND key LIKE 'content-%'",
                Integer.class, contentTreatment.getId());
        assertEquals(3, contentRows);

        // The numbered chunks reassemble to the document, in order
        assertEquals(text, chunks.stream().map(ItemTreatmentResult::getValue).collect(Collectors.joining()));

        // Text that exists only in the last chunk is searchable
        List<Item> found = searchDao.search(List.of(), null, null, "chunkmarkeromega", 0, 100);
        assertEquals(List.of("large.txt"), found.stream().map(Item::getName).toList());
    }

    @Test
    void testNoTruncationFieldWhenContentFitsWithinLimit() throws IOException {
        // Given - find the text.txt file which has small content ("aabaa")
        // With fixed 20MB limit, this should not be truncated
        List<Item> items = itemDao.findItemsByDatasource(testDataSource.id());
        Item textItem = items.stream()
                .filter(item -> "text.txt".equals(item.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("text.txt item not found after scan"));

        // When - process treatments
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);

        // Then - verify no truncation field is present
        List<DataSourceTreatment> dsToolTreatments = treatmentDao.getTreatmentsByDataSource(testDataSource.id());
        DataSourceTreatment contentDSTreatment = dsToolTreatments.stream()
                .filter(dst -> dst.getTreatment() == Treatments.CONTENT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Should have CONTENT datasource treatment"));

        ItemTreatment contentTreatment = treatmentDao.getItemTreatment(contentDSTreatment.getId(), textItem.getId())
                .orElseThrow(() -> new AssertionError("Should have CONTENT item treatment"));

        List<ItemTreatmentResult> results = treatmentDao.getItemTreatmentResults(contentTreatment.getId());

        // Should NOT have truncation field
        boolean hasTruncatedField = results.stream()
                .anyMatch(r -> FIELD_CONTENT_TRUNCATED.equals(r.getKey()));
        assertFalse(hasTruncatedField,
                "Should NOT have content_truncated field for small text file");

        // Should have content
        ItemTreatmentResult contentResult = results.stream()
                .filter(r -> FIELD_CONTENT.equals(r.getKey()))
                .findFirst()
                .orElse(null);

        assertNotNull(contentResult, "Should have content result");
        assertEquals("aabaa", contentResult.getValue().trim(), "Content should be complete");
    }

    @Test
    void testContentTreatmentUpdatesMimeType() {
        // Given - find a PDF file that was scanned with MIME type from extension
        List<Item> items = itemDao.findItemsByDatasource(testDataSource.id());
        Item pdfItem = items.stream()
                .filter(item -> item.getName().endsWith(".pdf"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("PDF item not found after scan"));

        // Verify initial MIME type from scan (should be set by extension detection)
        assertEquals("application/pdf", pdfItem.getMimeType(),
                "PDF should have MIME type from extension detection");

        // Manually clear the MIME type to simulate missing/incorrect data
        pdfItem.setMimeType(null);
        itemDao.updateItem(pdfItem);

        // Verify MIME type was cleared
        Item clearedItem = itemDao.findItemByDatasourceAndPath(
                testDataSource.id(), pdfItem.getPath(), pdfItem.getName())
                .orElseThrow();
        assertEquals(null, clearedItem.getMimeType(), "MIME type should be cleared");

        // When - process CONTENT treatment (which uses Tika)
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);

        // Then - verify MIME type was updated by Tika
        Item updatedItem = itemDao.findItemByDatasourceAndPath(
                testDataSource.id(), pdfItem.getPath(), pdfItem.getName())
                .orElseThrow();
        assertNotNull(updatedItem.getMimeType(), "MIME type should be updated by Tika");
        assertEquals("application/pdf", updatedItem.getMimeType(),
                "Tika should detect PDF MIME type");
    }

    @Test
    void testContentTreatmentCorrectsMimeType() {
        // Given - find a text file that was scanned
        List<Item> items = itemDao.findItemsByDatasource(testDataSource.id());
        Item textItem = items.stream()
                .filter(item -> "text.txt".equals(item.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("text.txt not found after scan"));

        // Verify initial MIME type from scan
        assertEquals("text/plain", textItem.getMimeType(),
                "Text file should have correct MIME type from scan");

        // Manually set incorrect MIME type
        textItem.setMimeType("application/octet-stream");
        itemDao.updateItem(textItem);

        // Verify incorrect MIME type was set
        Item incorrectItem = itemDao.findItemByDatasourceAndPath(
                testDataSource.id(), textItem.getPath(), textItem.getName())
                .orElseThrow();
        assertEquals("application/octet-stream", incorrectItem.getMimeType(),
                "MIME type should be set to incorrect value");

        // When - process CONTENT treatment (which uses Tika)
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);

        // Then - verify MIME type was corrected by Tika
        Item correctedItem = itemDao.findItemByDatasourceAndPath(
                testDataSource.id(), textItem.getPath(), textItem.getName())
                .orElseThrow();
        assertNotNull(correctedItem.getMimeType(), "MIME type should exist");
        assertEquals("text/plain", correctedItem.getMimeType(),
                "Tika should correct MIME type to text/plain");
    }
}