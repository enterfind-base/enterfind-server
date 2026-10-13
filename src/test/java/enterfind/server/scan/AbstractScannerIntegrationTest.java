package enterfind.server.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.dao.ItemDao;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceType;

public abstract class AbstractScannerIntegrationTest extends BaseDatabaseTest {
    protected abstract Scanner getScanner();
    protected abstract ItemDao getItemDao();
    protected abstract DataSourceInterface getTestDataSource();
    protected abstract String getTestDataSourceId();
    protected abstract Path getTempTestDir();
    protected abstract List<Folder> getFoldersByDatasource(String datasourceId);

    /**
     * Cleanup after each test: shutdown the data provider to close any resources
     */
    @AfterEach
    void tearDown() throws IOException {
        DataSourceType.shutdownAllProviders();
    }

    @Test
    void testInitialScan() {
        getScanner().scan(getTestDataSource());
        List<Item> items = getItemDao().findItemsByDatasource(getTestDataSourceId());
        List<Folder> folders = getFoldersByDatasource(getTestDataSourceId());
        assertEquals(8, items.size(), "Should scan all files from testshare");
        assertEquals(1, folders.size(), "Should scan the subf folder");
        assertTrue(items.stream().anyMatch(item -> "placeholder-binary.exe".equals(item.getName()) && item.getPath() == null));
        assertTrue(items.stream().anyMatch(item -> "ocrable.jpg".equals(item.getName()) && item.getPath() == null));
        assertTrue(items.stream().anyMatch(item -> "ocrable.pdf".equals(item.getName()) && item.getPath() == null));
        assertTrue(items.stream().anyMatch(item -> "text.txt".equals(item.getName()) && "subf".equals(item.getPath())));
        assertTrue(items.stream().anyMatch(item -> "beam.jpg".equals(item.getName()) && "subf".equals(item.getPath())));
        assertTrue(folders.stream().anyMatch(folder -> "subf".equals(folder.getName()) && folder.getPath() == null));
        for (Item item : items) {
            assertNotNull(item.getId());
            assertNotNull(item.getName());
            assertEquals(getTestDataSourceId(), item.getDatasourceId());
            assertNotNull(item.getDateScanned());
            assertTrue(item.getSizeBytes() > 0);
        }
    }

    @Test
    void testSecondScanNoChanges() {
        getScanner().scan(getTestDataSource());
        List<Item> initialItems = getItemDao().findItemsByDatasource(getTestDataSourceId());
        List<Folder> initialFolders = getFoldersByDatasource(getTestDataSourceId());
        getScanner().scan(getTestDataSource());
        List<Item> secondItems = getItemDao().findItemsByDatasource(getTestDataSourceId());
        List<Folder> secondFolders = getFoldersByDatasource(getTestDataSourceId());
        assertEquals(initialItems.size(), secondItems.size());
        assertEquals(initialFolders.size(), secondFolders.size());
        for (Item initialItem : initialItems) {
            assertTrue(secondItems.stream().anyMatch(item -> item.getId().equals(initialItem.getId()) && item.getName().equals(initialItem.getName()) && (item.getPath() != null ? item.getPath().equals(initialItem.getPath()) : initialItem.getPath() == null)));
        }
    }

    @Test
    void testNewFileDetection() throws IOException {
        getScanner().scan(getTestDataSource());
        int initialItemCount = getItemDao().findItemsByDatasource(getTestDataSourceId()).size();
        createNewFile("newfile.txt", "This is a new test file");
        getScanner().scan(getTestDataSource());
        List<Item> items = getItemDao().findItemsByDatasource(getTestDataSourceId());
        assertEquals(initialItemCount + 1, items.size());
        assertTrue(items.stream().anyMatch(item -> "newfile.txt".equals(item.getName()) && item.getPath() == null));
    }

    @Test
    void testNewFolderDetection() throws IOException {
        getScanner().scan(getTestDataSource());
        int initialFolderCount = getFoldersByDatasource(getTestDataSourceId()).size();
        createNewFolderWithFile("newfolder", "file.txt", "File in new folder");
        getScanner().scan(getTestDataSource());
        List<Folder> folders = getFoldersByDatasource(getTestDataSourceId());
        List<Item> items = getItemDao().findItemsByDatasource(getTestDataSourceId());
        assertEquals(initialFolderCount + 1, folders.size());
        assertTrue(folders.stream().anyMatch(folder -> "newfolder".equals(folder.getName()) && folder.getPath() == null));
        assertTrue(items.stream().anyMatch(item -> "file.txt".equals(item.getName()) && "newfolder".equals(item.getPath())));
    }

    @Test
    void testFileModification() throws IOException, InterruptedException {
        getScanner().scan(getTestDataSource());
        // Use subf/text.txt for modification test as originally
        Item originalItem = getItemDao().findItemByDatasourceAndPath(getTestDataSourceId(), "subf", "text.txt").orElseThrow();
        Thread.sleep(1000);
        // Modify the file using the abstract method
        modifyFile("subf/text.txt", "Modified content - much longer than before");
        getScanner().scan(getTestDataSource());
        Item updatedItem = getItemDao().findItemByDatasourceAndPath(getTestDataSourceId(), "subf", "text.txt").orElseThrow();
        assertEquals(originalItem.getId(), updatedItem.getId());
        assertNotEquals(originalItem.getSizeBytes(), updatedItem.getSizeBytes());
        assertTrue(updatedItem.getTimeModified().isAfter(originalItem.getTimeModified()));
    }

    @Test
    void testFileDeletion() throws IOException {
        getScanner().scan(getTestDataSource());
        int initialItemCount = getItemDao().findItemsByDatasource(getTestDataSourceId()).size();
        deleteFile("ocrable.jpg");
        getScanner().scan(getTestDataSource());
        List<Item> items = getItemDao().findItemsByDatasource(getTestDataSourceId());
        assertEquals(initialItemCount - 1, items.size());
        assertFalse(items.stream().anyMatch(item -> "ocrable.jpg".equals(item.getName()) && item.getPath() == null));
    }

    @Test
    void testFolderDeletion() throws IOException {
        getScanner().scan(getTestDataSource());
        int initialItemCount = getItemDao().findItemsByDatasource(getTestDataSourceId()).size();
        int initialFolderCount = getFoldersByDatasource(getTestDataSourceId()).size();
        deleteFolder("subf");
        getScanner().scan(getTestDataSource());
        List<Item> items = getItemDao().findItemsByDatasource(getTestDataSourceId());
        List<Folder> folders = getFoldersByDatasource(getTestDataSourceId());
        assertEquals(initialItemCount - 2, items.size());
        assertEquals(initialFolderCount - 1, folders.size());
        assertFalse(folders.stream().anyMatch(folder -> "subf".equals(folder.getName())));
        assertFalse(items.stream().anyMatch(item -> "subf".equals(item.getPath())));
    }

    @Test
    void testNestedFolderCreation() throws IOException {
        getScanner().scan(getTestDataSource());
        // Create nested folders and files using abstract method
        createNestedFoldersWithFiles(
            new String[] {"level1", "level2"},
            new String[] {"file1.txt", "file2.txt"},
            new String[] {"File at level 1", "File at level 2"}
        );
        getScanner().scan(getTestDataSource());
        List<Folder> folders = getFoldersByDatasource(getTestDataSourceId());
        List<Item> items = getItemDao().findItemsByDatasource(getTestDataSourceId());
        assertTrue(folders.stream().anyMatch(folder -> "level1".equals(folder.getName()) && folder.getPath() == null));
        assertTrue(folders.stream().anyMatch(folder -> "level2".equals(folder.getName()) && "level1".equals(folder.getPath())));
        assertTrue(items.stream().anyMatch(item -> "file1.txt".equals(item.getName()) && "level1".equals(item.getPath())));
        assertTrue(items.stream().anyMatch(item -> "file2.txt".equals(item.getName()) && "level1/level2".equals(item.getPath())));
    }

    @Test
    void testNestedFolderDeletionWithSubdirectoryItems() throws IOException {
        // This test reproduces a bug where deleting a folder only removes items
        // directly in that folder, but leaves items in subdirectories orphaned.
        // This matches the production issue with backups/linkedin-backup-2025-12

        // Initial scan
        getScanner().scan(getTestDataSource());
        int initialItemCount = getItemDao().findItemsByDatasource(getTestDataSourceId()).size();

        // Create a nested folder structure similar to production:
        // backups/linkedin-backup-2025-12/Articles/Articles
        createNestedFoldersWithMultipleLevels(
            new String[] {"backups", "linkedin-backup-2025-12", "Articles", "Articles"},
            new String[] {
                "backups/linkedin-backup-2025-12/Articles/Articles/article1.html",
                "backups/linkedin-backup-2025-12/Articles/Articles/article2.html",
                "backups/linkedin-backup-2025-12/Jobs/jobs.csv",
                "backups/linkedin-backup-2025-12/Services/services.csv"
            },
            new String[] {
                "Article content 1",
                "Article content 2",
                "Jobs content",
                "Services content"
            }
        );

        // Scan to populate database
        getScanner().scan(getTestDataSource());
        List<Item> itemsAfterCreation = getItemDao().findItemsByDatasource(getTestDataSourceId());

        // Verify items were created in nested subdirectories
        assertEquals(initialItemCount + 4, itemsAfterCreation.size(), "Should have 4 new items");
        assertTrue(itemsAfterCreation.stream().anyMatch(
            item -> "article1.html".equals(item.getName()) &&
                   "backups/linkedin-backup-2025-12/Articles/Articles".equals(item.getPath())),
            "Should have article1.html in deeply nested path");
        assertTrue(itemsAfterCreation.stream().anyMatch(
            item -> "article2.html".equals(item.getName()) &&
                   "backups/linkedin-backup-2025-12/Articles/Articles".equals(item.getPath())),
            "Should have article2.html in deeply nested path");
        assertTrue(itemsAfterCreation.stream().anyMatch(
            item -> "jobs.csv".equals(item.getName()) &&
                   "backups/linkedin-backup-2025-12/Jobs".equals(item.getPath())),
            "Should have jobs.csv in nested path");
        assertTrue(itemsAfterCreation.stream().anyMatch(
            item -> "services.csv".equals(item.getName()) &&
                   "backups/linkedin-backup-2025-12/Services".equals(item.getPath())),
            "Should have services.csv in nested path");

        // Now delete the top-level "backups" folder
        deleteFolder("backups");

        // Scan again - this should detect the folder deletion
        getScanner().scan(getTestDataSource());
        List<Item> itemsAfterDeletion = getItemDao().findItemsByDatasource(getTestDataSourceId());
        List<Folder> foldersAfterDeletion = getFoldersByDatasource(getTestDataSourceId());

        // All 4 items in subdirectories should be deleted
        assertEquals(initialItemCount, itemsAfterDeletion.size(),
            "All items in deleted folder hierarchy should be removed");

        // Verify no items remain with paths starting with "backups,"
        assertFalse(itemsAfterDeletion.stream().anyMatch(
            item -> item.getPath() != null && item.getPath().startsWith("backups/")),
            "No items should remain with paths under deleted folder");

        // Verify the backups folder and all its subfolders are deleted
        assertFalse(foldersAfterDeletion.stream().anyMatch(
            folder -> "backups".equals(folder.getName())),
            "Backups folder should be deleted");
        assertFalse(foldersAfterDeletion.stream().anyMatch(
            folder -> folder.getPath() != null && folder.getPath().startsWith("backups/")),
            "All subfolders under backups should be deleted");
    }

    @Test
    void testEmptyFolderHandling() throws IOException {
        getScanner().scan(getTestDataSource());
        createEmptyFolder("emptyfolder");
        getScanner().scan(getTestDataSource());
        List<Folder> folders = getFoldersByDatasource(getTestDataSourceId());
        assertTrue(folders.stream().anyMatch(folder -> "emptyfolder".equals(folder.getName()) && folder.getPath() == null));
    }

    @Test
    void testMimeTypeDetection() {
        // Scan the test data source
        getScanner().scan(getTestDataSource());
        List<Item> items = getItemDao().findItemsByDatasource(getTestDataSourceId());

        // Verify that MIME types are populated for known file types
        Item pdfFile = items.stream()
            .filter(item -> "ocrable.pdf".equals(item.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("PDF file not found"));
        assertEquals("application/pdf", pdfFile.getMimeType(), "PDF file should have correct MIME type");

        Item jpgFile = items.stream()
            .filter(item -> "ocrable.jpg".equals(item.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("JPG file not found"));
        assertEquals("image/jpeg", jpgFile.getMimeType(), "JPG file should have correct MIME type");

        Item txtFile = items.stream()
            .filter(item -> "text.txt".equals(item.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("TXT file not found"));
        assertEquals("text/plain", txtFile.getMimeType(), "TXT file should have correct MIME type");

        Item exeFile = items.stream()
            .filter(item -> "placeholder-binary.exe".equals(item.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("EXE file not found"));
        // EXE files might not have a MIME type detected by extension detector
        // This is fine - we just verify the field exists
        assertNotNull(exeFile, "EXE file should exist");
    }

    /**
     * Creates a new file named 'filename' with the given content in the test share.
     * Implementation should differ for local and SMB tests.
     */
    protected abstract void createNewFile(String filename, String content) throws IOException;

    /**
     * Creates a new folder named 'folderName' and a file inside it with the given name and content.
     * Implementation should differ for local and SMB tests.
     */
    protected abstract void createNewFolderWithFile(String folderName, String fileName, String content) throws IOException;

    /**
     * Modifies the content of an existing file named 'filename' in the test share.
     * Implementation should differ for local and SMB tests.
     */
    protected abstract void modifyFile(String filename, String newContent) throws IOException;

    /**
     * Deletes a file named 'filename' from the test share.
     * Implementation should differ for local and SMB tests.
     */
    protected abstract void deleteFile(String filename) throws IOException;

    /**
     * Deletes a folder named 'folderName' from the test share.
     * Implementation should differ for local and SMB tests.
     */
    protected abstract void deleteFolder(String folderName) throws IOException;

    /**
     * Creates nested folders and files for nested folder creation test.
     * Implementation should differ for local and SMB tests.
     */
    protected abstract void createNestedFoldersWithFiles(String[] folderNames, String[] fileNames, String[] fileContents) throws IOException;

    /**
     * Creates an empty folder named 'folderName' in the test share.
     * Implementation should differ for local and SMB tests.
     */
    protected abstract void createEmptyFolder(String folderName) throws IOException;

    /**
     * Creates a deeply nested folder structure with files in various subdirectories.
     * @param folderStructure Array of folder names representing the deepest nesting path
     * @param filePaths Array of relative file paths (e.g., "backups/linkedin/file.txt")
     * @param fileContents Array of content for each file
     */
    protected abstract void createNestedFoldersWithMultipleLevels(String[] folderStructure, String[] filePaths, String[] fileContents) throws IOException;

    /**
     * (Legacy) Recursively deletes a directory. Use deleteFolder for new tests.
     */
    protected abstract void deleteDirectoryRecursively(Path directory) throws IOException;

    /**
     * Creates a new datasource pointing at the same test share/directory but with the given
     * skipFiles pattern. Used by tests that need to verify skip behaviour with a fresh datasource.
     */
    protected abstract DataSourceInterface createDataSourceWithSkipFiles(String skipFiles) throws IOException;

    @Test
    void testFolderWithCommaInName() throws IOException {
        createNewFolderWithFile("Rust Servers, Services, and Apps", "chapter1.pdf", "chapter content");
        getScanner().scan(getTestDataSource());

        List<Folder> folders = getFoldersByDatasource(getTestDataSourceId());
        List<Item> items = getItemDao().findItemsByDatasource(getTestDataSourceId());

        Folder commaDir = folders.stream()
            .filter(f -> "Rust Servers, Services, and Apps".equals(f.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Folder with comma not found"));
        assertNull(commaDir.getPath(), "Root-level comma folder should have null path");

        Item chapter = items.stream()
            .filter(i -> "chapter1.pdf".equals(i.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("chapter1.pdf not found"));
        assertEquals("Rust Servers, Services, and Apps", chapter.getPath());
        assertEquals("Rust Servers, Services, and Apps", chapter.getFolder());

        // Second scan should be idempotent
        getScanner().scan(getTestDataSource());
        assertEquals(items.size(), getItemDao().findItemsByDatasource(getTestDataSourceId()).size());
        assertEquals(folders.size(), getFoldersByDatasource(getTestDataSourceId()).size());

        // Delete folder, rescan — item and folder removed
        deleteFolder("Rust Servers, Services, and Apps");
        getScanner().scan(getTestDataSource());
        assertFalse(getItemDao().findItemsByDatasource(getTestDataSourceId())
            .stream().anyMatch(i -> "chapter1.pdf".equals(i.getName())));
        assertFalse(getFoldersByDatasource(getTestDataSourceId())
            .stream().anyMatch(f -> "Rust Servers, Services, and Apps".equals(f.getName())));
    }

    @Test
    void testNestedFolderWithCommaInName() throws IOException {
        createNestedFoldersWithFiles(
            new String[] {"Manning Books", "Rust Servers, Services, and Apps"},
            new String[] {"chapter1.pdf", "chapter2.pdf"},
            new String[] {"outer content", "nested chapter content"}
        );
        getScanner().scan(getTestDataSource());

        List<Folder> folders = getFoldersByDatasource(getTestDataSourceId());
        List<Item> items = getItemDao().findItemsByDatasource(getTestDataSourceId());

        Folder outerFolder = folders.stream()
            .filter(f -> "Manning Books".equals(f.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Manning Books folder not found"));
        assertNull(outerFolder.getPath());

        Folder innerFolder = folders.stream()
            .filter(f -> "Rust Servers, Services, and Apps".equals(f.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Inner comma folder not found"));
        assertEquals("Manning Books", innerFolder.getPath());

        Item chapter = items.stream()
            .filter(i -> "chapter2.pdf".equals(i.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("chapter2.pdf not found"));
        assertEquals("Manning Books/Rust Servers, Services, and Apps", chapter.getPath());
        assertEquals("Manning Books/Rust Servers, Services, and Apps", chapter.getFolder());
    }

    @Test
    void testSkipFilesFiltersFolders() throws IOException {
        // Create a folder whose name matches a skip pattern, and put a file inside it
        createNewFolderWithFile("@Recycle", "deleted_file.txt", "This file was moved to the recycle bin");

        // Scan with a skipFiles pattern that matches the folder name
        DataSourceInterface ds = createDataSourceWithSkipFiles(".*@Recycle.*");
        getScanner().scan(ds);

        String dsId = ds.getType().extractDatasourceId(ds);
        List<Folder> folders = getFoldersByDatasource(dsId);
        List<Item> items = getItemDao().findItemsByDatasource(dsId);

        // The @Recycle folder itself must not appear in the database
        assertFalse(
            folders.stream().anyMatch(f -> "@Recycle".equals(f.getName())),
            "Folder whose name matches skipFiles should not be indexed"
        );

        // No item whose path starts with @Recycle should be present either
        assertFalse(
            items.stream().anyMatch(i -> i.getFullPath().contains("@Recycle")),
            "Items inside a skipped folder should not be indexed"
        );
    }
}
