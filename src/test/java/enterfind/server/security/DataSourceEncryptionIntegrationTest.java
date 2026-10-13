package enterfind.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.Schema;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.RemoteFileSystemDataSource;
import enterfind.server.model.S3BucketDataSource;

/**
 * Integration tests for datasource configuration encryption.
 * Tests the full flow of encrypting/decrypting datasource configs in the database.
 */
@TestPropertySource(properties = {
    "ENTERFIND_ENCRYPTION_KEY=PykQmzYw2SbTShBb8g5gukpLjtFQRvMJ07UTmPTr2m8=" // 32-byte test key
})
class DataSourceEncryptionIntegrationTest extends BaseDatabaseTest {

    @Autowired
    private DataSourceDao dataSourceDao;

    @Autowired
    private ConfigEncryptor configEncryptor;

    @Autowired
    private Schema schema;

    @BeforeEach
    void setUpTest() {
        super.setUp();
    }

    @Test
    void testEncryptionIsEnabled() {
        assertThat(configEncryptor.isEncryptionEnabled()).isTrue();
    }

    @Test
    void testRemoteFileSystemDataSourceConfigIsEncrypted() {
        // Given - create a remote filesystem datasource with credentials
        RemoteFileSystemDataSource dataSource = new RemoteFileSystemDataSource(
                null, // id assigned by DAO
                "test-smb-source",
                "0 0 * * *",
                null, // dateCreated
                null, // dateLastScanned
                DataSourceStatus.IDLE,
                "192.168.1.100",
                "DOMAIN",
                "admin",
                "secretPassword123",
                "SharedDocs",
                "/documents",
                null // skipFiles
        );

        // When - save to database
        DataSourceInterface saved = dataSourceDao.create(dataSource);
        String savedId = ((RemoteFileSystemDataSource) saved).id();

        // Then - verify config is encrypted in database
        String rawConfig = getRawConfigFromDatabase(savedId);
        assertThat(rawConfig).contains("encryptedValue");
        assertThat(rawConfig).contains("ENC:");
        assertThat(rawConfig).doesNotContain("secretPassword123");
        assertThat(rawConfig).doesNotContain("admin");

        // And - verify we can read it back correctly
        DataSourceInterface loaded = dataSourceDao.findById(savedId).orElseThrow();
        assertThat(loaded).isInstanceOf(RemoteFileSystemDataSource.class);
        RemoteFileSystemDataSource loadedRfs = (RemoteFileSystemDataSource) loaded;
        assertThat(loadedRfs.password()).isEqualTo("secretPassword123");
        assertThat(loadedRfs.username()).isEqualTo("admin");
        assertThat(loadedRfs.server()).isEqualTo("192.168.1.100");
        assertThat(loadedRfs.domain()).isEqualTo("DOMAIN");
    }

    @Test
    void testS3BucketDataSourceConfigIsEncrypted() {
        // Given - create an S3 datasource with credentials
        S3BucketDataSource dataSource = new S3BucketDataSource(
                null, // id
                "test-s3-bucket",
                null, // cronPattern
                null, // dateCreated
                null, // dateLastScanned
                DataSourceStatus.IDLE,
                "my-bucket",
                "AKIAIOSFODNN7EXAMPLE",
                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                "/data",
                null, // region
                null // skipFiles
        );

        // When - save to database
        DataSourceInterface saved = dataSourceDao.create(dataSource);
        String savedId = ((S3BucketDataSource) saved).id();

        // Then - verify config is encrypted in database
        String rawConfig = getRawConfigFromDatabase(savedId);
        assertThat(rawConfig).contains("encryptedValue");
        assertThat(rawConfig).contains("ENC:");
        assertThat(rawConfig).doesNotContain("AKIAIOSFODNN7EXAMPLE");
        assertThat(rawConfig).doesNotContain("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");

        // And - verify we can read it back correctly
        DataSourceInterface loaded = dataSourceDao.findById(savedId).orElseThrow();
        assertThat(loaded).isInstanceOf(S3BucketDataSource.class);
        S3BucketDataSource loadedS3 = (S3BucketDataSource) loaded;
        assertThat(loadedS3.accessKey()).isEqualTo("AKIAIOSFODNN7EXAMPLE");
        assertThat(loadedS3.secretKey()).isEqualTo("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
        assertThat(loadedS3.nameOrUrl()).isEqualTo("my-bucket");
    }

    @Test
    void testUpdateDataSourceReencrypts() {
        // Given - create and save a datasource
        RemoteFileSystemDataSource original = new RemoteFileSystemDataSource(
                null, // id
                "test-update-source", // name
                null, // cronPattern
                null, // dateCreated
                null, // dateLastScanned
                DataSourceStatus.IDLE, // status
                "server1", // server
                null, // domain
                "user1", // username
                "pass1", // password
                "share1", // shareName
                "/path1", // path
                null // skipFiles
        );
        DataSourceInterface saved = dataSourceDao.create(original);
        String savedId = ((RemoteFileSystemDataSource) saved).id();
        String originalEncrypted = getRawConfigFromDatabase(savedId);

        // When - update with new password
        RemoteFileSystemDataSource updated = new RemoteFileSystemDataSource(
                savedId, // id
                "test-update-source", // name
                null, // cronPattern
                ((RemoteFileSystemDataSource) saved).dateCreated(), // dateCreated
                null, // dateLastScanned
                DataSourceStatus.IDLE, // status
                "server1", // server
                null, // domain
                "user1", // username
                "newPassword123", // password
                "share1", // shareName
                "/path1", // path
                null // skipFiles
        );
        dataSourceDao.update(savedId, updated);

        // Then - config should be re-encrypted (different ciphertext due to new IV)
        String newEncrypted = getRawConfigFromDatabase(savedId);
        assertThat(newEncrypted).contains("encryptedValue");
        assertThat(newEncrypted).contains("ENC:");
        assertThat(newEncrypted).isNotEqualTo(originalEncrypted);

        // And - verify new password is stored correctly
        DataSourceInterface reloaded = dataSourceDao.findById(savedId).orElseThrow();
        assertThat(((RemoteFileSystemDataSource) reloaded).password()).isEqualTo("newPassword123");
    }

    @Test
    void testFindAllReturnsDecryptedDataSources() {
        // Given - create multiple datasources
        RemoteFileSystemDataSource ds1 = new RemoteFileSystemDataSource(
                null, // id
                "source1", // name
                null, // cronPattern
                null, // dateCreated
                null, // dateLastScanned
                DataSourceStatus.IDLE, // status
                "server1", // server
                null, // domain
                "user1", // username
                "pass1", // password
                "share1", // shareName
                "/path1", // path
                null // skipFiles
        );
        S3BucketDataSource ds2 = new S3BucketDataSource(
                null, // id
                "source2", // name
                null, // cronPattern
                null, // dateCreated
                null, // dateLastScanned
                DataSourceStatus.IDLE, // status
                "bucket1", // nameOrUrl
                "key1", // accessKey
                "secret1", // secretKey
                "/data", // path
                null, // region
                null // skipFiles
        );

        dataSourceDao.create(ds1);
        dataSourceDao.create(ds2);

        // When
        var allDataSources = dataSourceDao.findAll();

        // Then - all should be decrypted correctly
        assertThat(allDataSources).hasSize(2);

        var rfs = allDataSources.stream()
                .filter(ds -> ds instanceof RemoteFileSystemDataSource)
                .map(ds -> (RemoteFileSystemDataSource) ds)
                .findFirst()
                .orElseThrow();
        assertThat(rfs.password()).isEqualTo("pass1");

        var s3 = allDataSources.stream()
                .filter(ds -> ds instanceof S3BucketDataSource)
                .map(ds -> (S3BucketDataSource) ds)
                .findFirst()
                .orElseThrow();
        assertThat(s3.secretKey()).isEqualTo("secret1");
    }

    @Test
    void testMigrationEncryptsPlaintextConfig() {
        // Given - insert a datasource with plaintext config directly into database
        String plaintextConfig = "{\"server\":\"testserver\",\"domain\":null,\"username\":\"testuser\"," +
                "\"password\":\"plaintextpass\",\"shareName\":\"share\",\"path\":\"/test\",\"skipFiles\":null}";
        String id = insertPlaintextDataSource("migration-test", "REMOTE_FILESYSTEM", plaintextConfig);

        // Verify it's stored as plaintext
        String beforeMigration = getRawConfigFromDatabase(id);
        assertThat(beforeMigration).doesNotContain("encryptedValue");
        assertThat(beforeMigration).contains("plaintextpass");

        // When - read and re-save (simulating migration)
        DataSourceInterface loaded = dataSourceDao.findById(id).orElseThrow();
        dataSourceDao.update(id, loaded);

        // Then - config should now be encrypted (wrapped in JSON)
        String afterMigration = getRawConfigFromDatabase(id);
        assertThat(afterMigration).contains("encryptedValue");
        assertThat(afterMigration).contains("ENC:");
        assertThat(afterMigration).doesNotContain("plaintextpass");

        // And - data should still be readable
        DataSourceInterface reloaded = dataSourceDao.findById(id).orElseThrow();
        assertThat(((RemoteFileSystemDataSource) reloaded).password()).isEqualTo("plaintextpass");
    }

    @Test
    void testSchemaMigrationEncryptsPlaintextDataSourcesWhenEncryptionEnabled() {
        // Given - insert multiple unencrypted datasources directly into database
        // (simulating datasources created before encryption was enabled)
        String plaintextConfigRfs = "{\"server\":\"server1\",\"domain\":null,\"username\":\"user1\"" +
                ",\"password\":\"pass1\",\"shareName\":\"share1\",\"path\":\"/path1\",\"skipFiles\":null}";
        String plaintextConfigS3 = "{\"nameOrUrl\":\"bucket1\",\"accessKey\":\"key1\"" +
                ",\"secretKey\":\"secret1\",\"path\":\"/data\",\"skipFiles\":null}";
        
        String rfsId = insertPlaintextDataSource("rfs-test", "REMOTE_FILESYSTEM", plaintextConfigRfs);
        String s3Id = insertPlaintextDataSource("s3-test", "S3_BUCKET", plaintextConfigS3);
        
        // Verify they are stored as plaintext JSON (no encryptedValue wrapper)
        String rfsBeforeMigration = getRawConfigFromDatabase(rfsId);
        String s3BeforeMigration = getRawConfigFromDatabase(s3Id);
        assertThat(rfsBeforeMigration).doesNotContain("encryptedValue");
        assertThat(rfsBeforeMigration).contains("\"server\":");
        assertThat(s3BeforeMigration).doesNotContain("encryptedValue");
        assertThat(s3BeforeMigration).contains("\"nameOrUrl\":");
        
        // When - invoke the actual schema migration logic that runs at startup
        // This will detect the plaintext datasources and encrypt them
        schema.migrateUnencryptedDataSources();
        
        // Then - verify the datasources can be read and decrypted correctly
        DataSourceInterface reloadedRfs = dataSourceDao.findById(rfsId).orElseThrow();
        assertThat(reloadedRfs).isInstanceOf(RemoteFileSystemDataSource.class);
        RemoteFileSystemDataSource rfs = (RemoteFileSystemDataSource) reloadedRfs;
        assertThat(rfs.password()).isEqualTo("pass1");
        assertThat(rfs.username()).isEqualTo("user1");
        assertThat(rfs.server()).isEqualTo("server1");
        
        DataSourceInterface reloadedS3 = dataSourceDao.findById(s3Id).orElseThrow();
        assertThat(reloadedS3).isInstanceOf(S3BucketDataSource.class);
        S3BucketDataSource s3 = (S3BucketDataSource) reloadedS3;
        assertThat(s3.accessKey()).isEqualTo("key1");
        assertThat(s3.secretKey()).isEqualTo("secret1");
        assertThat(s3.nameOrUrl()).isEqualTo("bucket1");
        
        // And - verify they are now encrypted in database (wrapped in encryptedValue)
        String rfsAfterMigration = getRawConfigFromDatabase(rfsId);
        String s3AfterMigration = getRawConfigFromDatabase(s3Id);
        assertThat(rfsAfterMigration).contains("encryptedValue");
        assertThat(rfsAfterMigration).contains("ENC:");
        assertThat(rfsAfterMigration).doesNotContain("pass1");
        assertThat(s3AfterMigration).contains("encryptedValue");
        assertThat(s3AfterMigration).contains("ENC:");
        assertThat(s3AfterMigration).doesNotContain("secret1");
    }

    /**
     * Get raw config value from database without decryption.
     */
    private String getRawConfigFromDatabase(String id) {
        return dbAccess.queryForObject(
                "SELECT config::text FROM DATA_SOURCES WHERE id = ?",
                String.class,
                id
        );
    }

    /**
     * Insert a datasource with plaintext config for migration testing.
     */
    private String insertPlaintextDataSource(String name, String type, String config) {
        String id = enterfind.server.util.Utilities.generateHexId();
        dbAccess.update(
                "INSERT INTO DATA_SOURCES (id, name, type, config, status, date_created) " +
                        "VALUES (?, ?, ?, ?::jsonb, 'IDLE', NOW())",
                id, name, type, config
        );
        return id;
    }
}
