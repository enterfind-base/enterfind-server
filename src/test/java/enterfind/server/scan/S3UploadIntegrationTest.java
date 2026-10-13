package enterfind.server.scan;

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
import enterfind.server.model.S3BucketDataSource;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Upload / delete integration tests for the S3 data provider.
 * Requires MinIO to be running on localhost:9000 (available in the devcontainer).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class S3UploadIntegrationTest extends AbstractUploadIntegrationTest {

    @Autowired
    private DataSourceDao dataSourceDao;

    private S3Client s3Client;
    private String s3Path;
    private S3BucketDataSource testDataSource;

    @BeforeEach
    protected void setUp() {
        super.setUp();
        s3Client = S3TestHelper.getS3Client();
        S3TestHelper.createBucketIfAbsent(s3Client);
        s3Path = S3TestHelper.newTestPath();
        testDataSource = (S3BucketDataSource) dataSourceDao.create(
            new S3BucketDataSource(
                null, "Upload Test S3", "0 0 * * *",
                LocalDateTime.now(), null, DataSourceStatus.IDLE,
                S3TestHelper.ENDPOINT + "/" + S3TestHelper.BUCKET,
                S3TestHelper.ACCESS_KEY, S3TestHelper.SECRET_KEY,
                s3Path, null, null
            ));
    }

    @AfterEach
    void tearDown() {
        super.shutdownProviders();
        try {
            S3TestHelper.deletePrefix(s3Client, s3Path);
        } catch (Exception e) {
            System.err.println("S3 cleanup failed: " + e.getMessage());
        } finally {
            if (s3Client != null) s3Client.close();
        }
    }

    @Override protected DataSourceInterface getTestDataSource() { return testDataSource; }
}
