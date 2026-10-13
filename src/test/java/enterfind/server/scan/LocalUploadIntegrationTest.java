package enterfind.server.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.DirtiesContext;

import enterfind.server.TestFileUtils;
import enterfind.server.dao.DataSourceDao;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.LocalFileSystemDataSource;

/**
 * Upload / delete integration tests for the local file system data provider.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class LocalUploadIntegrationTest extends AbstractUploadIntegrationTest {

    @Autowired
    private DataSourceDao dataSourceDao;

    private Path tempDir;
    private LocalFileSystemDataSource testDataSource;

    @BeforeEach
    protected void setUp() {
        super.setUp();
        try {
            tempDir = Files.createTempDirectory("upload-test-local-");
            testDataSource = (LocalFileSystemDataSource) dataSourceDao.create(
                new LocalFileSystemDataSource(
                    null, "Upload Test Local", "0 0 * * *",
                    LocalDateTime.now(), null, DataSourceStatus.IDLE,
                    tempDir.toString(), null
                ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to set up local upload test", e);
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        super.shutdownProviders();
        if (tempDir != null && Files.exists(tempDir)) {
            TestFileUtils.deleteDirectoryRecursively(tempDir);
        }
    }

    @Override protected DataSourceInterface getTestDataSource() { return testDataSource; }
}
