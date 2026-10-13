package enterfind.server.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
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
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.DataSourceType;
import enterfind.server.model.LocalFileSystemDataSource;

/**
 * A folder we failed to list must keep its subtree indexed and be retried on the next scan —
 * never be read as "empty, therefore deleted". These tests pin that property down, along with
 * the count that reports it and the deliberately different handling of the scan root.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ScanFolderProblemIntegrationTest extends BaseDatabaseTest {

    @Autowired
    private Scanner scanner;
    @Autowired
    private DataSourceDao dataSourceDao;
    @Autowired
    private ItemDao itemDao;

    private Path tempTestDir;
    private LocalFileSystemDataSource testDataSource;

    /**
     * Delegates to the real local provider but refuses to list one chosen path. The suite has no
     * mocking framework, and this is the only failure a scan cannot be made to produce from the
     * filesystem alone (tests run as root, so chmod 000 is ignored).
     */
    private static class FailingPathDataProvider implements DataProviderInterface {
        private final DataProviderInterface delegate;
        private final String failingPath;

        FailingPathDataProvider(DataProviderInterface delegate, String failingPath) {
            this.delegate = delegate;
            this.failingPath = failingPath;
        }

        private void failIfTargeted(String path) {
            if (failingPath == null ? path == null : failingPath.equals(path)) {
                throw new RuntimeException("Simulated listing failure for path: " + path);
            }
        }

        @Override
        public List<Item> getItems(String path) {
            failIfTargeted(path);
            return delegate.getItems(path);
        }

        @Override
        public List<Folder> getFolders(String path) {
            failIfTargeted(path);
            return delegate.getFolders(path);
        }

        @Override
        public InputStream getInputStream(Item item) {
            return delegate.getInputStream(item);
        }

        @Override
        public void writeFile(Item item, InputStream data) {
            delegate.writeFile(item, data);
        }

        @Override
        public void deleteFile(Item item) {
            delegate.deleteFile(item);
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }
    }

    @BeforeEach
    protected void setUp() {
        super.setUp();
        try {
            tempTestDir = Files.createTempDirectory("scan-folder-problem-test-");
            TestFileUtils.copyTestShareToTempDir(tempTestDir);
            testDataSource = new LocalFileSystemDataSource(
                    null,
                    "Test Folder Problem FS",
                    "0 0 * * *",
                    LocalDateTime.now(),
                    null,
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
        DataSourceType.shutdownAllProviders();
        if (tempTestDir != null && Files.exists(tempTestDir)) {
            TestFileUtils.deleteDirectoryRecursively(tempTestDir);
        }
    }

    private void failOn(String path) {
        DataProviderInterface real = new LocalFilesDataProvider(testDataSource);
        DataSourceType.registerProviderForTesting(testDataSource.id(), new FailingPathDataProvider(real, path));
    }

    private int foldersWithProblems() {
        return dataSourceDao.getDataSourceStats(testDataSource.id()).foldersWithProblems();
    }

    @Test
    void failedSubfolderIsCountedAndItsSubtreeSurvives() {
        scanner.scan(testDataSource);
        List<Item> afterCleanScan = itemDao.findItemsByDatasource(testDataSource.id());
        assertEquals(8, afterCleanScan.size(), "baseline: testshare has 8 files");
        assertEquals(0, foldersWithProblems(), "a clean scan reports no problems");

        // 'subf' now fails to list. The scan must still complete.
        failOn("subf");
        scanner.scan(testDataSource);

        assertEquals(1, foldersWithProblems(), "the one folder that failed to list is counted");

        // The whole point: nothing under the failed folder was interpreted as deleted.
        List<Item> afterFailedScan = itemDao.findItemsByDatasource(testDataSource.id());
        assertEquals(8, afterFailedScan.size(), "a failed listing must not delete the subtree");
        assertTrue(afterFailedScan.stream()
                .anyMatch(i -> "text.txt".equals(i.getName()) && "subf".equals(i.getPath())),
                "items under the failed folder are still indexed");
        assertTrue(itemDao.findFoldersByDatasource(testDataSource.id()).stream()
                .anyMatch(f -> "subf".equals(f.getName())),
                "the failed folder itself is still indexed");

        // cleanupOrphanedData ran, and had nothing to remove.
        assertTrue(itemDao.findOrphanedFolders(testDataSource.id()).isEmpty(),
                "a skipped subtree must not leave orphans behind");

        // And the scan itself succeeded: status restored, timestamp advanced.
        DataSourceInterface reloaded = dataSourceDao.findById(testDataSource.id()).orElseThrow();
        assertEquals(DataSourceStatus.IDLE, reloaded.getStatus());
        assertNotNull(reloaded.getDateLastScanned(), "a subtree failure does not fail the scan");
    }

    @Test
    void siblingsOfAFailedFolderStillReconcile() throws IOException {
        scanner.scan(testDataSource);

        Files.createDirectories(tempTestDir.resolve("sibling"));
        Files.writeString(tempTestDir.resolve("sibling").resolve("new.txt"), "added while subf was broken");

        failOn("subf");
        scanner.scan(testDataSource);

        assertEquals(1, foldersWithProblems());
        assertTrue(itemDao.findItemsByDatasource(testDataSource.id()).stream()
                .anyMatch(i -> "new.txt".equals(i.getName()) && "sibling".equals(i.getPath())),
                "the rest of the share reconciled normally");
    }

    @Test
    void successfulListingClearsTheCount() {
        scanner.scan(testDataSource);
        failOn("subf");
        scanner.scan(testDataSource);
        assertEquals(1, foldersWithProblems());

        // Provider recovers.
        DataSourceType.invalidateProvider(testDataSource.id());
        scanner.scan(testDataSource);
        assertEquals(0, foldersWithProblems(), "a folder that lists again is no longer a problem");
    }

    @Test
    void repeatedFailureCountsTheFolderOnce() {
        scanner.scan(testDataSource);
        failOn("subf");
        scanner.scan(testDataSource);
        scanner.scan(testDataSource);

        assertEquals(1, foldersWithProblems(),
                "the count is folders with a problem, not total failed attempts");
    }

    @Test
    void rootFailureAbortsTheScanAndLeavesTheTimestampUnstamped() {
        // Root is deliberately different: nothing was compared, so there is no partial result to
        // protect and no FOLDERS row to count against.
        failOn(null);

        assertThrows(Exception.class, () -> scanner.scan(testDataSource),
                "a root listing failure aborts the scan");

        DataSourceInterface reloaded = dataSourceDao.findById(testDataSource.id()).orElseThrow();
        assertEquals(DataSourceStatus.IDLE, reloaded.getStatus(),
                "the finally block still restores IDLE");
        assertNull(reloaded.getDateLastScanned(),
                "date_last_scanned is not stamped, which is the visible signal for a dead root");
        assertEquals(0, foldersWithProblems(), "root failure is not counted as a folder problem");
    }

    @Test
    void newFoldersStartWithNoProblem() {
        scanner.scan(testDataSource);
        assertEquals(0, foldersWithProblems());
        assertTrue(itemDao.findFoldersByDatasource(testDataSource.id()).size() > 0,
                "sanity: folders were actually created");
    }
}
