package enterfind.server.scan;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.jspecify.annotations.NonNull;
import org.springframework.test.annotation.DirtiesContext;

import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.ItemDao;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.S3BucketDataSource;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Integration tests for Scanner functionality with S3 (MinIO) data provider.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class S3ScannerIntegrationTest extends AbstractScannerIntegrationTest {

    @Autowired
    private Scanner scanner;
    @Autowired
    private DataSourceDao dataSourceDao;
    @Autowired
    private ItemDao itemDao;

    private S3BucketDataSource testDataSource;
    private String s3Path;
    private S3Client s3Client;

    @BeforeEach
    protected void setUp() {
        super.setUp();
        s3Client = S3TestHelper.getS3Client();
        S3TestHelper.createBucketIfAbsent(s3Client);

        s3Path = S3TestHelper.newTestPath();
        try {
            copyClasspathDirToS3("testshare", s3Path);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set up S3 test data", e);
        }

        testDataSource = new S3BucketDataSource(
            null,
            "Test S3",
            "0 0 * * *",
            LocalDateTime.now(),
            null,
            DataSourceStatus.IDLE,
            S3TestHelper.ENDPOINT + "/" + S3TestHelper.BUCKET,
            S3TestHelper.ACCESS_KEY,
            S3TestHelper.SECRET_KEY,
            s3Path,
            null,  // region — defaults to us-east-1 inside provider
            null   // skipFiles
        );
        testDataSource = (S3BucketDataSource) dataSourceDao.create(testDataSource);
    }

    @AfterEach
    void tearDown() throws IOException {
        super.tearDown();
        try {
            S3TestHelper.deletePrefix(s3Client, s3Path);
        } catch (Exception e) {
            System.err.println("S3 cleanup failed: " + e.getMessage());
        }
        if (s3Client != null) {
            s3Client.close();
        }
    }

    // -----------------------------------------------------------------------
    // AbstractScannerIntegrationTest contract
    // -----------------------------------------------------------------------

    @Override
    protected Scanner getScanner() { return scanner; }

    @Override
    protected ItemDao getItemDao() { return itemDao; }

    @Override
    protected DataSourceInterface getTestDataSource() { return testDataSource; }

    @Override
    protected String getTestDataSourceId() { return testDataSource.id(); }

    @Override
    protected Path getTempTestDir() { return null; }

    @Override
    protected List<Folder> getFoldersByDatasource(String datasourceId) {
        return itemDao.findFoldersByDatasource(datasourceId);
    }

    @Override
    protected void createNewFile(String filename, String content) {
        S3TestHelper.putObject(s3Client, s3Path + "/" + filename, content);
    }

    @Override
    protected void createNewFolderWithFile(String folderName, String fileName, String content) {
        S3TestHelper.putObject(s3Client, s3Path + "/" + folderName + "/" + fileName, content);
    }

    @Override
    protected void modifyFile(String filename, String newContent) {
        S3TestHelper.putObject(s3Client, s3Path + "/" + filename, newContent);
    }

    @Override
    protected void deleteFile(String filename) {
        deleteS3Object(s3Path + "/" + filename);
    }

    @Override
    protected void deleteFolder(String folderName) {
        S3TestHelper.deletePrefix(s3Client, s3Path + "/" + folderName);
    }

    @Override
    protected void createNestedFoldersWithFiles(String[] folderNames, String[] fileNames, String[] fileContents) {
        String level1Key = s3Path + "/" + folderNames[0] + "/" + fileNames[0];
        String level2Key = s3Path + "/" + folderNames[0] + "/" + folderNames[1] + "/" + fileNames[1];
        S3TestHelper.putObject(s3Client, level1Key, fileContents[0]);
        S3TestHelper.putObject(s3Client, level2Key, fileContents[1]);
    }

    @Override
    protected void createEmptyFolder(String folderName) {
        // S3 has no real "empty folder" concept; upload a zero-byte placeholder key
        // that ends with "/" to simulate a directory entry.
        S3TestHelper.putObject(s3Client, s3Path + "/" + folderName + "/", "");
    }

    @Override
    protected void createNestedFoldersWithMultipleLevels(String[] folderStructure, String[] filePaths, String[] fileContents) {
        for (int i = 0; i < filePaths.length; i++) {
            S3TestHelper.putObject(s3Client, s3Path + "/" + filePaths[i], fileContents[i]);
        }
    }

    @Override
    protected void deleteDirectoryRecursively(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) return;
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted((a, b) -> b.compareTo(a))
                 .forEach(path -> {
                     try { Files.delete(path); } catch (IOException e) {
                         throw new RuntimeException("Failed to delete: " + path, e);
                     }
                 });
        }
    }

    @Override
    protected DataSourceInterface createDataSourceWithSkipFiles(String skipFiles) {
        S3BucketDataSource ds = new S3BucketDataSource(
            null,
            "Test S3 with SkipFiles",
            "0 0 * * *",
            LocalDateTime.now(),
            null,
            DataSourceStatus.IDLE,
            S3TestHelper.ENDPOINT + "/" + S3TestHelper.BUCKET,
            S3TestHelper.ACCESS_KEY,
            S3TestHelper.SECRET_KEY,
            s3Path,
            null,
            skipFiles
        );
        return dataSourceDao.create(ds);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void deleteS3Object(String key) {
        software.amazon.awssdk.services.s3.model.DeleteObjectRequest req =
            software.amazon.awssdk.services.s3.model.DeleteObjectRequest.builder()
                .bucket(S3TestHelper.BUCKET)
                .key(key)
                .build();
        s3Client.deleteObject(req);
    }

    /**
     * Copies the classpath resource directory {@code resourceDir} to S3 under {@code s3BasePath}.
     */
    private void copyClasspathDirToS3(@NonNull String resourceDir, String s3BasePath) throws IOException {
        ClassPathResource testShare = new ClassPathResource(resourceDir);
        if (!testShare.exists()) {
            throw new IOException("Resource directory not found: " + resourceDir);
        }
        Path testSharePath = testShare.getFile().toPath();
        try (Stream<Path> files = Files.walk(testSharePath)) {
            files.forEach(source -> {
                if (Files.isDirectory(source)) return; // S3 has no real directories
                try {
                    String relative = testSharePath.relativize(source).toString().replace("\\", "/");
                    String key = s3BasePath + "/" + relative;
                    long size = Files.size(source);
                    try (InputStream in = Files.newInputStream(source)) {
                        S3TestHelper.putObject(s3Client, key, in, size);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to upload to S3: " + source, e);
                }
            });
        }
    }
}
