package enterfind.server.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import enterfind.server.TestFileUtils;
import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.ItemDao;
import enterfind.server.dao.ItemPreviewDao;
import enterfind.server.dao.TreatmentDao;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.ItemPreview;
import enterfind.server.model.ItemPreviewStatus;
import enterfind.server.model.ItemTreatment;
import enterfind.server.model.ItemTreatmentStatus;
import enterfind.server.model.LocalFileSystemDataSource;

/**
 * Integration tests for Scanner functionality with local file system scanning
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ScannerIntegrationTest extends AbstractScannerIntegrationTest {
    // Only keep environment-specific setup, teardown, and required overrides
    @Override
    protected Scanner getScanner() {
        return scanner;
    }

    @Override
    protected ItemDao getItemDao() {
        return itemDao;
    }

    @Override
    protected DataSourceInterface getTestDataSource() {
        return testDataSource;
    }

    @Override
    protected String getTestDataSourceId() {
        return testDataSource.id();
    }

    @Override
    protected Path getTempTestDir() {
        return tempTestDir;
    }

    @Override
    protected void deleteFile(String filename) throws IOException {
        java.nio.file.Path filePath = getTempTestDir().resolve(filename);
        if (java.nio.file.Files.exists(filePath)) {
            java.nio.file.Files.delete(filePath);
        }
    }

    @Override
    protected void deleteFolder(String folderName) throws IOException {
        java.nio.file.Path folderPath = getTempTestDir().resolve(folderName);
        if (java.nio.file.Files.exists(folderPath)) {
            deleteDirectoryRecursively(folderPath);
        }
    }

    @Override
    protected List<Folder> getFoldersByDatasource(String datasourceId) {
        return itemDao.findFoldersByDatasource(datasourceId);
    }

    @Override
    protected void deleteDirectoryRecursively(Path directory) throws IOException {
        TestFileUtils.deleteDirectoryRecursively(directory);
    }

    @Override
    protected void createNewFile(String filename, String content) throws IOException {
        Path newFile = tempTestDir.resolve(filename);
        Files.writeString(newFile, content);
    }

    @Override
    protected void createEmptyFolder(String folderName) throws IOException {
        Path emptyFolder = tempTestDir.resolve(folderName);
        Files.createDirectories(emptyFolder);
    }

    @Override
    protected void createNewFolderWithFile(String folderName, String fileName, String content) throws IOException {
        Path newFolder = tempTestDir.resolve(folderName);
        Files.createDirectories(newFolder);
        Path fileInNewFolder = newFolder.resolve(fileName);
        Files.writeString(fileInNewFolder, content);
    }

    @Override
    protected void modifyFile(String filename, String newContent) throws IOException {
        // Always modify subf/text.txt for the modification test
        java.nio.file.Path filePath = getTempTestDir().resolve("subf").resolve("text.txt");
        java.nio.file.Files.writeString(filePath, newContent);
    }

    @Override
    protected void createNestedFoldersWithFiles(String[] folderNames, String[] fileNames, String[] fileContents)
            throws IOException {
        // Assumes folderNames = ["level1", "level2"], fileNames = ["file1.txt",
        // "file2.txt"], fileContents = ["File at level 1", "File at level 2"]
        Path level1 = getTempTestDir().resolve(folderNames[0]);
        Path level2 = level1.resolve(folderNames[1]);
        Files.createDirectories(level2);
        Files.writeString(level1.resolve(fileNames[0]), fileContents[0]);
        Files.writeString(level2.resolve(fileNames[1]), fileContents[1]);
    }

    @Override
    protected DataSourceInterface createDataSourceWithSkipFiles(String skipFiles) throws IOException {
        LocalFileSystemDataSource ds = new LocalFileSystemDataSource(
                null,
                "Test FS with SkipFiles",
                "0 0 * * *",
                LocalDateTime.now(),
                null,
                DataSourceStatus.IDLE,
                tempTestDir.toString(),
                skipFiles);
        return dataSourceDao.create(ds);
    }

    @Override
    protected void createNestedFoldersWithMultipleLevels(String[] folderStructure, String[] filePaths,
            String[] fileContents) throws IOException {
        // Create all files at their specified paths
        for (int i = 0; i < filePaths.length; i++) {
            Path filePath = getTempTestDir().resolve(filePaths[i]);
            // Create parent directories if they don't exist
            Files.createDirectories(filePath.getParent());
            // Write the file content
            Files.writeString(filePath, fileContents[i]);
        }
    }

    @Autowired
    private Scanner scanner;
    @Autowired
    private DataSourceDao dataSourceDao;
    @Autowired
    private ItemDao itemDao;
    @Autowired
    private TreatmentDao treatmentDao;
    @Autowired
    private TreatmentProcessor treatmentProcessor;
    @Autowired
    private ItemPreviewDao itemPreviewDao;
    private Path tempTestDir;
    private LocalFileSystemDataSource testDataSource;

    @Autowired
    private PreviewGenerator previewGenerator;

    @BeforeEach
    protected void setUp() {
        super.setUp();
        try {
            tempTestDir = Files.createTempDirectory("scanner-test-");
            TestFileUtils.copyTestShareToTempDir(tempTestDir);
            testDataSource = new LocalFileSystemDataSource(
                    null, // ID will be generated
                    "Test Local FS",
                    "0 0 * * *", // Cron pattern
                    LocalDateTime.now(), // Date created
                    null, // Date last scanned
                    DataSourceStatus.IDLE,
                    tempTestDir.toString(),
                    null);
            testDataSource = (LocalFileSystemDataSource) dataSourceDao.create(testDataSource);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set up test", e);
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        super.tearDown();
        if (tempTestDir != null && Files.exists(tempTestDir)) {
            deleteDirectoryRecursively(tempTestDir);
        }
    }

    @Test
    void testSkipFilesFunctionality() throws IOException {
        // First, create a regular datasource without skipFiles to establish baseline
        LocalFileSystemDataSource baseDataSource = new LocalFileSystemDataSource(
                null, // ID will be generated
                "Test Local FS Base",
                "0 0 * * *", // Cron pattern
                LocalDateTime.now(), // Date created
                null, // Date last scanned
                DataSourceStatus.IDLE,
                tempTestDir.toString(),
                null // No skipFiles
        );
        baseDataSource = (LocalFileSystemDataSource) dataSourceDao.create(baseDataSource);

        // Scan without skipFiles - should index all files
        getScanner().scan(baseDataSource);

        // Get all items - should have all 8 files
        List<Item> allItems = getItemDao().findItemsByDatasource(baseDataSource.id());
        assertEquals(8, allItems.size(), "Should index all files without skipFiles");

        // Now create a datasource with skipFiles pattern that matches .jpg files
        LocalFileSystemDataSource skipDataSource = new LocalFileSystemDataSource(
                null, // ID will be generated
                "Test Local FS with Skip",
                "0 0 * * *", // Cron pattern
                LocalDateTime.now(), // Date created
                null, // Date last scanned
                DataSourceStatus.IDLE,
                tempTestDir.toString(),
                ".*\\.jpg" // Skip all .jpg files
        );
        skipDataSource = (LocalFileSystemDataSource) dataSourceDao.create(skipDataSource);

        // Scan with skipFiles
        getScanner().scan(skipDataSource);

        // Get items from the datasource with skipFiles - should have 6 items (8 total -
        // 2 .jpg files)
        List<Item> items = getItemDao().findItemsByDatasource(skipDataSource.id());
        assertEquals(6, items.size(), "Should skip .jpg files and not create items for them");

        // Verify that non-.jpg files are still present
        assertTrue(items.stream().anyMatch(item -> "placeholder-binary.exe".equals(item.getName())),
                "Should contain exe file");
        assertTrue(items.stream().anyMatch(item -> "ocrable.pdf".equals(item.getName())),
                "Should contain pdf file");
        assertTrue(items.stream().anyMatch(item -> "text.txt".equals(item.getName())),
                "Should contain txt file");

        // Verify that .jpg files are NOT present
        assertFalse(items.stream().anyMatch(item -> item.getName().endsWith(".jpg")),
                "Should not contain any .jpg files");

        // Now test item deletion - scan again with different skip pattern
        LocalFileSystemDataSource updatedDataSource = new LocalFileSystemDataSource(
                skipDataSource.id(),
                skipDataSource.name(),
                skipDataSource.cronPattern(),
                skipDataSource.dateCreated(),
                skipDataSource.dateLastScanned(),
                skipDataSource.status(),
                skipDataSource.localPath(),
                ".*\\.pdf" // Now skip .pdf files instead
        );
        dataSourceDao.update(skipDataSource.id(), updatedDataSource);

        // Scan again - should now skip .pdf files (delete .pdf items) and re-add .jpg
        // files
        getScanner().scan(updatedDataSource);
        List<Item> updatedItems = getItemDao().findItemsByDatasource(skipDataSource.id());

        // Should have: 6 items from first scan - 3 .pdf items (deleted) + 2 .jpg items
        // (re-added) = 5 items
        assertEquals(5, updatedItems.size(),
                "Items matching new skipFiles should be deleted, previously skipped items should be re-added");

        // .jpg files should be back since they're no longer skipped
        assertTrue(updatedItems.stream().anyMatch(item -> item.getName().endsWith(".jpg")),
                "Should contain .jpg files after skipFiles update");

        // .pdf files should be gone since they're now skipped
        assertFalse(updatedItems.stream().anyMatch(item -> item.getName().endsWith(".pdf")),
                "Should not contain .pdf files after skipFiles changed to exclude them");
    }

    @Test
    void testPreviewGenerationIntegration() throws IOException, InterruptedException {
        // Scan the datasource - in alpha environment, this will also generate previews
        // automatically
        getScanner().scan(testDataSource);
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);
        previewGenerator.generatePreviews();

        // Verify items were created
        List<Item> items = getItemDao().findItemsByDatasource(testDataSource.id());
        assertEquals(8, items.size(), "Should have 8 items from test share");

        // Find the image items
        List<Item> imageItems = items.stream()
                .filter(item -> item.getName().endsWith(".jpg") || item.getName().endsWith(".jpeg"))
                .collect(Collectors.toList());
        assertTrue(imageItems.size() >= 2, "Should have at least 2 image files");

        // Verify previews were generated automatically (alpha environment behavior)
        for (Item item : imageItems) {
            ItemPreview preview = itemPreviewDao.getByItemId(item.getId()).orElse(null);
            assertTrue(preview != null, "Preview should exist for item: " + item.getName());
            assertEquals(ItemPreviewStatus.DONE, preview.getStatus(),
                    "Preview should be DONE after scan in alpha environment for item: " + item.getName());
            assertTrue(preview.getPreviewData() != null && preview.getPreviewData().length > 0,
                    "Preview data should be generated for item: " + item.getName());
            assertEquals("image/jpeg", preview.getContentType(),
                    "Preview content type should be image/jpeg for item: " + item.getName());
        }

        // Find PDF items and verify their previews
        List<Item> pdfItems = items.stream()
                .filter(item -> item.getName().endsWith(".pdf"))
                .collect(Collectors.toList());
        assertTrue(pdfItems.size() >= 1, "Should have at least 1 PDF file");

        // Verify PDF previews were generated
        for (Item item : pdfItems) {
            ItemPreview preview = itemPreviewDao.getByItemId(item.getId()).orElse(null);
            assertTrue(preview != null, "Preview should exist for PDF item: " + item.getName());
            assertEquals(ItemPreviewStatus.DONE, preview.getStatus(),
                    "Preview should be DONE for PDF item: " + item.getName());
            assertTrue(preview.getPreviewData() != null && preview.getPreviewData().length > 0,
                    "Preview data should be generated for PDF item: " + item.getName());
            assertEquals("image/jpeg", preview.getContentType(),
                    "Preview content type should be image/jpeg for PDF item: " + item.getName());
        }

        // Test file modification - modify an image file
        Path imageFile = getTempTestDir().resolve("subf").resolve("beam.jpg");
        // Touch the file to update its modification time
        Files.setLastModifiedTime(imageFile,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10000));

        // Rescan - should regenerate preview automatically in alpha environment
        getScanner().scan(testDataSource);
        previewGenerator.generatePreviews();

        Item beamImage = items.stream()
                .filter(item -> "beam.jpg".equals(item.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("beam.jpg not found"));

        ItemPreview beamPreview = itemPreviewDao.getByItemId(beamImage.getId()).orElse(null);
        assertTrue(beamPreview != null, "Preview should exist for modified beam.jpg");
        assertEquals(ItemPreviewStatus.DONE, beamPreview.getStatus(),
                "Preview should be DONE after rescan in alpha environment");
    }

    @Test
    public void testSkipFilesAddedAfterInitialScan() throws IOException {
        // Create a datasource without skipFiles initially
        LocalFileSystemDataSource initialDataSource = new LocalFileSystemDataSource(
                null, // ID will be generated
                "Test Local FS Initial Scan",
                "0 0 * * *", // Cron pattern
                LocalDateTime.now(), // Date created
                null, // Date last scanned
                DataSourceStatus.IDLE,
                tempTestDir.toString(),
                null // No skipFiles initially
        );
        initialDataSource = (LocalFileSystemDataSource) dataSourceDao.create(initialDataSource);

        // Initial scan - should index all files and process treatments
        getScanner().scan(initialDataSource);

        // Verify all files were indexed
        List<Item> initialItems = getItemDao().findItemsByDatasource(initialDataSource.id());
        assertEquals(8, initialItems.size(), "Should index all files initially");

        // Get treatments for .jpg files before adding skipFiles
        List<String> jpgItemIds = initialItems.stream()
                .filter(item -> item.getName().endsWith(".jpg"))
                .map(Item::getId)
                .collect(Collectors.toList());
        assertEquals(2, jpgItemIds.size(), "Should have 2 .jpg files");

        // Verify treatments exist for .jpg files
        for (String itemId : jpgItemIds) {
            List<ItemTreatment> treatments = treatmentDao.getItemTreatmentsByItem(itemId);
            assertFalse(treatments.isEmpty(), "Should have treatments for .jpg file: " + itemId);
        }

        // Now update the datasource to add skipFiles pattern
        LocalFileSystemDataSource updatedDataSource = new LocalFileSystemDataSource(
                initialDataSource.id(),
                initialDataSource.name(),
                initialDataSource.cronPattern(),
                initialDataSource.dateCreated(),
                initialDataSource.dateLastScanned(),
                initialDataSource.status(),
                initialDataSource.localPath(),
                ".*\\.jpg" // Now skip .jpg files
        );
        dataSourceDao.update(initialDataSource.id(), updatedDataSource);

        // Scan again - should delete .jpg items entirely since they now match skipFiles
        getScanner().scan(updatedDataSource);

        // Items matching skipFiles should be deleted from database
        List<Item> updatedItems = getItemDao().findItemsByDatasource(initialDataSource.id());
        assertEquals(6, updatedItems.size(), "Items matching skipFiles should be deleted from database");

        // Verify .jpg files are no longer in database
        assertFalse(updatedItems.stream().anyMatch(item -> item.getName().endsWith(".jpg")),
                "Should not contain any .jpg files after skipFiles added");

        // Verify non-.jpg files are still present
        assertTrue(updatedItems.stream().anyMatch(item -> "placeholder-binary.exe".equals(item.getName())),
                "Should contain exe file");
        assertTrue(updatedItems.stream().anyMatch(item -> "ocrable.pdf".equals(item.getName())),
                "Should contain pdf file");
        assertTrue(updatedItems.stream().anyMatch(item -> "text.txt".equals(item.getName())),
                "Should contain txt file");

        // Verify treatments still exist for remaining non-.jpg files
        for (Item item : updatedItems) {
            List<ItemTreatment> treatments = treatmentDao.getItemTreatmentsByItem(item.getId());
            assertFalse(treatments.isEmpty(), "Should still have treatments for remaining files: " + item.getName());
        }
    }

    @Test
    void testSkipFilesMatchesPathFragments() throws IOException {
        // Create a datasource with skipFiles pattern that matches path fragment (subf
        // folder)
        // Pattern .*subf.* should match any file whose full path contains "subf"
        LocalFileSystemDataSource skipPathDataSource = new LocalFileSystemDataSource(
                null, // ID will be generated
                "Test Local FS Skip Path Fragment",
                "0 0 * * *", // Cron pattern
                LocalDateTime.now(), // Date created
                null, // Date last scanned
                DataSourceStatus.IDLE,
                tempTestDir.toString(),
                ".*subf.*" // Skip files with "subf" in their path
        );
        skipPathDataSource = (LocalFileSystemDataSource) dataSourceDao.create(skipPathDataSource);

        // Scan with path fragment pattern
        getScanner().scan(skipPathDataSource);

        // Get items - should skip the 2 files in subf folder (beam.jpg, text.txt)
        // Should have 6 files (8 total - 2 in subf folder)
        List<Item> items = getItemDao().findItemsByDatasource(skipPathDataSource.id());
        assertEquals(6, items.size(), "Should skip files in subf folder based on path fragment match");

        // Verify files in subf folder are NOT present
        assertFalse(items.stream().anyMatch(item -> item.getFullPath().contains("subf")),
                "Should not contain any files from subf folder");

        // Verify root level files are still present
        assertTrue(items.stream().anyMatch(item -> "placeholder-binary.exe".equals(item.getName())),
                "Should contain exe file at root");
        assertTrue(items.stream().anyMatch(item -> "ocrable.pdf".equals(item.getName())),
                "Should contain pdf file at root");
        assertTrue(items.stream().anyMatch(item -> "ocrable.jpg".equals(item.getName())),
                "Should contain jpg file at root");
    }

    @Test
    void testSkipFilesMatchesNestedPathFragments() throws IOException {
        // Create nested folder structure: archive/2020/report.txt and
        // archive/2021/data.txt
        Path archiveDir = tempTestDir.resolve("archive");
        Path archive2020 = archiveDir.resolve("2020");
        Path archive2021 = archiveDir.resolve("2021");
        Files.createDirectories(archive2020);
        Files.createDirectories(archive2021);
        Files.writeString(archive2020.resolve("report.txt"), "Report 2020");
        Files.writeString(archive2021.resolve("data.txt"), "Data 2021");

        // Create datasource that skips files with "archive" in the path
        LocalFileSystemDataSource skipArchiveDataSource = new LocalFileSystemDataSource(
                null,
                "Test Skip Archive Path",
                "0 0 * * *",
                LocalDateTime.now(),
                null,
                DataSourceStatus.IDLE,
                tempTestDir.toString(),
                ".*archive.*" // Skip files with "archive" in their path
        );
        skipArchiveDataSource = (LocalFileSystemDataSource) dataSourceDao.create(skipArchiveDataSource);

        // Scan
        getScanner().scan(skipArchiveDataSource);

        // Get items - should have original 8 files, NOT the 2 new archive files
        List<Item> items = getItemDao().findItemsByDatasource(skipArchiveDataSource.id());
        assertEquals(8, items.size(), "Should skip files in archive folder hierarchy");

        // Verify no archive files present
        assertFalse(items.stream().anyMatch(item -> item.getFullPath().contains("archive")),
                "Should not contain any files from archive folder hierarchy");
        assertFalse(items.stream().anyMatch(item -> "report.txt".equals(item.getName())),
                "Should not contain report.txt from archive/2020");
        assertFalse(items.stream().anyMatch(item -> "data.txt".equals(item.getName())),
                "Should not contain data.txt from archive/2021");
    }

    @Test
    void testSkipFilesCombinesFilenameAndPathPatterns() throws IOException {
        // Test that both filename patterns AND path patterns work together
        // Pattern 1: .*\.jpg - matches filename ending in .jpg (3 jpg files)
        // Pattern 2: .*subf.* - matches path containing "subf" (2 files: beam.jpg,
        // text.txt)
        // Combined should skip: ocrable.jpg, 98594...jpeg (root jpg), beam.jpg (subf),
        // text.txt (subf)
        // Note: beam.jpg matches BOTH patterns

        LocalFileSystemDataSource combinedDataSource = new LocalFileSystemDataSource(
                null,
                "Test Combined Skip Patterns",
                "0 0 * * *",
                LocalDateTime.now(),
                null,
                DataSourceStatus.IDLE,
                tempTestDir.toString(),
                ".*\\.jpg,.*subf.*" // Skip .jpg files AND files in subf folder
        );
        combinedDataSource = (LocalFileSystemDataSource) dataSourceDao.create(combinedDataSource);

        getScanner().scan(combinedDataSource);

        List<Item> items = getItemDao().findItemsByDatasource(combinedDataSource.id());

        // Original 8 files:
        // - ocrable.jpg (skipped by .jpg pattern)
        // - 98594...jpeg (NOT skipped - pattern is .jpg not .jpeg)
        // - beam.jpg in subf (skipped by both patterns)
        // - text.txt in subf (skipped by subf pattern)
        // Remaining: 3 pdfs + 1 exe + 1 jpeg = 5 files
        assertEquals(5, items.size(), "Should skip files matching either filename or path pattern");

        // Verify no .jpg files
        assertFalse(items.stream().anyMatch(item -> item.getName().endsWith(".jpg")),
                "Should not contain any .jpg files");

        // Verify no subf files
        assertFalse(items.stream().anyMatch(item -> item.getFullPath().contains("subf")),
                "Should not contain any files from subf folder");

        // Verify .jpeg file IS present (pattern was .jpg, not .jpeg)
        assertTrue(items.stream().anyMatch(item -> item.getName().endsWith(".jpeg")),
                "Should still contain .jpeg file (different extension)");
    }

    @Test
    void testFileRename() throws IOException {
        // Initial scan to establish baseline
        getScanner().scan(getTestDataSource());
        List<Item> initialItems = getItemDao().findItemsByDatasource(getTestDataSourceId());
        int initialItemCount = initialItems.size();

        // Find the original item by name
        Item originalItem = initialItems.stream()
                .filter(item -> "ocrable.pdf".equals(item.getName()) && item.getPath() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ocrable.pdf not found in initial scan"));

        String originalItemId = originalItem.getId();

        // Process treatments to move them from NEW to DONE status (batch size is 1,
        // drain the queue)
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);

        // Verify the original item has treatments that have been processed
        List<ItemTreatment> originalTreatments = treatmentDao.getItemTreatmentsByItem(originalItemId);
        assertFalse(originalTreatments.isEmpty(), "Original item should have treatments before rename");

        // Verify treatments are NOT in NEW status (they should have been processed to
        // DONE)
        // This ensures we're testing that processed treatment state is lost on rename
        assertTrue(originalTreatments.stream().noneMatch(t -> t.getStatus() == ItemTreatmentStatus.NEW),
                "Original item treatments should not be in NEW status before rename (should have been processed to DONE)");

        // Rename the file: ocrable.pdf -> renamed-file.pdf
        Path oldFilePath = tempTestDir.resolve("ocrable.pdf");
        Path newFilePath = tempTestDir.resolve("renamed-file.pdf");
        Files.move(oldFilePath, newFilePath);

        // Scan again after rename
        getScanner().scan(getTestDataSource());
        List<Item> itemsAfterRename = getItemDao().findItemsByDatasource(getTestDataSourceId());

        // Item count should remain the same (old deleted, new created)
        assertEquals(initialItemCount, itemsAfterRename.size(),
                "Item count should remain the same after rename");

        // Original item (old fullPath) should be deleted from database
        assertFalse(itemsAfterRename.stream().anyMatch(item -> originalItemId.equals(item.getId())),
                "Original item ID should no longer exist after rename");
        assertFalse(itemsAfterRename.stream().anyMatch(item -> "ocrable.pdf".equals(item.getName())),
                "Old filename should not exist in database after rename");

        // New item (new fullPath) should be created
        Item renamedItem = itemsAfterRename.stream()
                .filter(item -> "renamed-file.pdf".equals(item.getName()) && item.getPath() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("renamed-file.pdf not found after rename"));

        // Renamed item should have a different ID (it's a new item)
        assertFalse(originalItemId.equals(renamedItem.getId()),
                "Renamed file should have a new item ID (rename = delete + create)");

        // Verify the renamed item has NEW treatments (status should be NEW for new
        // items)
        List<ItemTreatment> renamedTreatments = treatmentDao.getItemTreatmentsByItem(renamedItem.getId());
        assertFalse(renamedTreatments.isEmpty(), "Renamed item should have new treatments");

        // All treatments for the new item should have NEW status (not UPDATED, DONE,
        // etc.)
        for (ItemTreatment treatment : renamedTreatments) {
            assertEquals(ItemTreatmentStatus.NEW, treatment.getStatus(),
                    String.format("Treatment %s should have NEW status but has: %s",
                            treatment.getId(), treatment.getStatus()));
        }

        // Verify that old item's treatments are no longer in the database
        // (this depends on cascade delete behavior - treatments should be deleted with
        // the item)
        List<ItemTreatment> oldItemTreatmentsAfterRename = treatmentDao.getItemTreatmentsByItem(originalItemId);
        assertTrue(oldItemTreatmentsAfterRename.isEmpty(),
                "Old item's treatments should be deleted along with the item");
    }

    // Curly apostrophe U+2019 (UTF-8: e2 80 99) and em dash U+2014 (e2 80 94) —
    // the exact characters seen corrupted in production LoC titles.
    private static final String UTF8_NAME = "Uganda\u2019s Report \u2014 Final.pdf";

    @Test
    void testUtf8FilenamePreservedThroughScanAndPersist() throws IOException, InterruptedException {
        // Assert the JVM's filename encoding is UTF-8 before doing anything else.
        // In Docker containers that lack LANG/LC_ALL=*.UTF-8, sun.jnu.encoding falls
        // back to ANSI_X3.4-1968 (ASCII).  Path.getFileName().toString() then
        // produces U+FFFD for every non-ASCII byte — the exact corruption reported in
        // production.  This check turns that silent data-corruption into a loud
        // environment misconfiguration failure, pointing straight at the Dockerfile.
        String jnuEncoding = System.getProperty("sun.jnu.encoding", "unknown");
        assertEquals("UTF-8", jnuEncoding,
                "sun.jnu.encoding must be UTF-8; got '" + jnuEncoding + "'. "
                + "The container / JVM is missing LANG=*.UTF-8 (or LC_ALL). "
                + "Add ENV LANG=C.UTF-8 and ENV LC_ALL=C.UTF-8 to the Dockerfile.");

        // Create the fixture file via the OS shell, not via Java's Path API.
        // This bypasses sun.jnu.encoding on the WRITE side and exactly mirrors the
        // production scenario: a file whose name was created by some external agent
        // and already exists on disk with correct UTF-8 bytes before the scanner runs.
        ProcessBuilder pb = new ProcessBuilder("touch",
                getTempTestDir().resolve(UTF8_NAME).toString());
        Process proc = pb.start();
        int exitCode = proc.waitFor();
        assertEquals(0, exitCode, "touch command failed — UTF-8 filename could not be created on disk");

        // Guard: confirm the bytes landed correctly (rules out filesystem or shell issues).
        try (var entries = Files.list(getTempTestDir())) {
            assertTrue(entries.anyMatch(p -> UTF8_NAME.equals(p.getFileName().toString())),
                    "Fixture name not round-tripped on disk — unexpected filesystem issue");
        }

        // Write non-empty content so Tika treatments have something to process
        Files.writeString(getTempTestDir().resolve(UTF8_NAME), "content");

        // Full pipeline: walk provider -> persist to DB
        getScanner().scan(getTestDataSource());

        // Read back from the database, the way the UI/MCP see it
        List<Item> items = getItemDao().findItemsByDatasource(getTestDataSourceId());
        Item item = items.stream()
                .filter(i -> i.getName() != null && i.getName().startsWith("Uganda") && i.getPath() == null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Item with UTF-8 name not found after scan"));

        assertFalse(item.getName().contains("\uFFFD"),
                "Persisted filename must not contain U+FFFD (sun.jnu.encoding not UTF-8)");
        assertEquals(UTF8_NAME, item.getName(),
                "Persisted filename must round-trip the curly apostrophe and em dash exactly");
    }
}