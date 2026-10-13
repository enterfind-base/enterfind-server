package enterfind.server.scan;

import java.io.IOException;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.annotation.DirtiesContext;

import enterfind.server.dao.DataSourceDao;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.RemoteFileSystemDataSource;

/**
 * Upload / delete integration tests for the SMB (remote file system) data provider.
 * Requires the Samba server to be running (available in the devcontainer).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class SMBUploadIntegrationTest extends AbstractUploadIntegrationTest {

    @Autowired
    private DataSourceDao dataSourceDao;

    private String smbPath;
    private RemoteFileSystemDataSource testDataSource;

    @BeforeEach
    protected void setUp() {
        super.setUp();
        try {
            smbPath = SmbTestHelper.newTestPath();
            SmbTestHelper.withConnection(share -> SmbTestHelper.mkdir(share, smbPath));
            testDataSource = (RemoteFileSystemDataSource) dataSourceDao.create(
                new RemoteFileSystemDataSource(
                    null, "Upload Test SMB", "0 0 * * *",
                    LocalDateTime.now(), null, DataSourceStatus.IDLE,
                    SmbTestHelper.HOST, null,
                    SmbTestHelper.USER, SmbTestHelper.PASSWORD,
                    SmbTestHelper.SHARE, smbPath, null
                ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to set up SMB upload test", e);
        }
    }

    @AfterEach
    void tearDown() {
        super.shutdownProviders();
        if (smbPath == null) return;
        try {
            SmbTestHelper.withConnection(share -> SmbTestHelper.deleteRecursively(share, smbPath));
        } catch (Exception e) {
            System.err.println("SMB cleanup failed: " + e.getMessage());
        }
    }

    /**
     * SMB requires the parent directory to exist before writing into it.
     */
    @Override
    protected void ensureFolderExistsInStorage(String folderPath) throws IOException {
        SmbTestHelper.withConnection(share ->
            SmbTestHelper.mkdir(share, smbPath + "/" + folderPath));
    }

    @Override protected DataSourceInterface getTestDataSource() { return testDataSource; }
}
