package enterfind.server.scan;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;

import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.DataSourceType;
import enterfind.server.model.RemoteFileSystemDataSource;

@SpringBootTest
class SMBDataProviderCommaTest extends AbstractDataProviderCommaTest {

    private String smbPath;
    private RemoteFileSystemDataProvider provider;

    @BeforeEach
    void setup() throws IOException {
        smbPath = SmbTestHelper.newTestPath();

        SmbTestHelper.withConnection(share -> {
            SmbTestHelper.mkdir(share, smbPath);

            String commaFolder = smbPath + "/Rust Servers, Services, and Apps";
            SmbTestHelper.mkdir(share, commaFolder);
            SmbTestHelper.writeFile(share, commaFolder + "/chapter1.pdf", "chapter1 content");

            String manningBooks = smbPath + "/Manning Books";
            SmbTestHelper.mkdir(share, manningBooks);
            String nestedComma = manningBooks + "/Rust Servers, Services, and Apps";
            SmbTestHelper.mkdir(share, nestedComma);
            SmbTestHelper.writeFile(share, nestedComma + "/chapter2.pdf", "chapter2 content");
        });

        RemoteFileSystemDataSource dataSource = new RemoteFileSystemDataSource(
            "test-id", "test-datasource", null, null, null, DataSourceStatus.IDLE,
            SmbTestHelper.HOST, null, SmbTestHelper.USER, SmbTestHelper.PASSWORD,
            SmbTestHelper.SHARE, smbPath, null);
        provider = new RemoteFileSystemDataProvider(dataSource);
    }

    @AfterEach
    void tearDown() {
        DataSourceType.shutdownAllProviders();
        if (smbPath == null) return;
        try {
            SmbTestHelper.withConnection(share -> SmbTestHelper.deleteRecursively(share, smbPath));
        } catch (Exception e) {
            System.err.println("SMB cleanup failed: " + e.getMessage());
        }
    }

    @Override
    protected DataProviderInterface getProvider() {
        return provider;
    }
}
