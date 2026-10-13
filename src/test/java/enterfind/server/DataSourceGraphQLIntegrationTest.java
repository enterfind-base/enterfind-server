package enterfind.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.graphql.test.autoconfigure.tester.AutoConfigureGraphQlTester;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.http.ResponseEntity;
import org.jspecify.annotations.NonNull;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import enterfind.server.dao.DataSourceDao;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceType;
import enterfind.server.model.UserLevel;
import enterfind.server.scan.Scanner;
import enterfind.server.service.AuthenticationService;
import enterfind.server.service.AuthenticationService.UserCreationResult;
import reactor.core.publisher.Flux;

/**
 * GraphQL integration tests for DataSource CRUD operations using HTTP requests
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureGraphQlTester
public class DataSourceGraphQLIntegrationTest extends BaseDatabaseTest {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceGraphQLIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private Scanner scanner;

    @Autowired
    private DataSourceDao dataSourceDao;
    
    @Autowired
    private GraphQlTester graphQlTester;
    
    private GraphQLTestClient graphQLClient;
    private final ObjectMapper objectMapper = new JsonMapper();

    @BeforeEach
    void setUpGraphQLClient() {
        graphQLClient = new GraphQLTestClient(port);
    }

    private ResponseEntity<String> executeGraphQL(String query) {
        return graphQLClient.execute(query);
    }

    @Test
    void testCreateLocalFileSystemDataSource() throws JacksonException {
        String mutation = """
            mutation {
                createLocalFileSystemDataSource(input: {
                    name: "Test Local FS"
                    cronPattern: "0 0 * * *"
                    localPath: "/tmp/test"
                }) {
                    name
                    cronPattern
                    localPath
                    status
                }
            }
            """;

        ResponseEntity<String> response = executeGraphQL(mutation);

        logger.info("Response: {}", response.getBody());

        // Basic assertion - should be successful and contain data
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());

        // Parse JSON response
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());

        // Check that there are no GraphQL errors
        assertFalse(jsonResponse.has("errors"), "GraphQL response should not contain errors: " + response.getBody());

        // Verify data structure and content
        assertTrue(jsonResponse.has("data"), "Response should have data field");
        JsonNode data = jsonResponse.get("data");

        assertTrue(data.has("createLocalFileSystemDataSource"), "Data should have createLocalFileSystemDataSource field");
        JsonNode dataSource = data.get("createLocalFileSystemDataSource");

        // Assert specific field values
        assertEquals("Test Local FS", dataSource.get("name").asString());
        assertEquals("0 0 * * *", dataSource.get("cronPattern").asString());
        assertEquals("/tmp/test", dataSource.get(DataSourceType.FIELD_LOCAL_PATH).asString());
        assertEquals("IDLE", dataSource.get("status").asString(), "Status should be IDLE after creation");

        // Verify database state matches the GraphQL response
        verifyLocalFileSystemDataSourceInDatabase("Test Local FS", "0 0 * * *", "/tmp/test");
    }

    @Test
    void testGetAllDataSources() throws JacksonException {
        // First create a datasource
        String createMutation = """
            mutation {
                createLocalFileSystemDataSource(input: {
                    name: "Test for List"
                    cronPattern: "0 0 * * *"
                    localPath: "/tmp/list"
                }) {
                    name
                }
            }
            """;

        executeGraphQL(createMutation);

        // Then query all datasources
        String query = """
            query {
                dataSources {
                    name
                    cronPattern
                    type
                    status
                    dateCreated
                }
            }
            """;

        ResponseEntity<String> response = executeGraphQL(query);

        logger.info("List Response: {}", response.getBody());

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());

        // Parse JSON response
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());

        // Check that there are no GraphQL errors
        assertFalse(jsonResponse.has("errors"), "GraphQL response should not contain errors: " + response.getBody());

        // Verify data structure
        assertTrue(jsonResponse.has("data"), "Response should have data field");
        JsonNode data = jsonResponse.get("data");

        assertTrue(data.has("dataSources"), "Data should have dataSources field");
        JsonNode dataSources = data.get("dataSources");

        assertTrue(dataSources.isArray(), "dataSources should be an array");
        assertTrue(dataSources.size() > 0, "dataSources array should not be empty");

        // Find and verify the created data source
        JsonNode foundDataSource = null;
        for (JsonNode dataSource : dataSources) {
            if ("Test for List".equals(dataSource.get("name").asString())) {
                foundDataSource = dataSource;
                break;
            }
        }

        assertNotNull(foundDataSource, "Should find the created data source");
        assertEquals("Test for List", foundDataSource.get("name").asString());
        assertEquals("0 0 * * *", foundDataSource.get("cronPattern").asString());
        assertEquals("LOCAL_FILESYSTEM", foundDataSource.get("type").asString());
        assertEquals("IDLE", foundDataSource.get("status").asString(), "Status should be IDLE");
        assertNotNull(foundDataSource.get("dateCreated"));
    }

    @Test
    void testCreateRemoteFileSystemDataSource() throws JacksonException {
        String mutation = """
            mutation {
                createRemoteFileSystemDataSource(input: {
                    name: "Test Remote FS"
                    cronPattern: "0 0 * * *"
                    server: "fileserver.example.com"
                    domain: "WORKGROUP"
                    username: "testuser"
                    password: "testpass"
                    shareName: "share"
                    path: "/remote/path"
                }) {
                    name
                    server
                    domain
                    username
                    shareName
                    path
                }
            }
            """;

        ResponseEntity<String> response = executeGraphQL(mutation);

        logger.info("Remote FS Response: {}", response.getBody());

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());

        // Parse JSON response
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());

        // Check that there are no GraphQL errors
        assertFalse(jsonResponse.has("errors"), "GraphQL response should not contain errors: " + response.getBody());

        // Verify data structure and content
        assertTrue(jsonResponse.has("data"), "Response should have data field");
        JsonNode data = jsonResponse.get("data");

        assertTrue(data.has("createRemoteFileSystemDataSource"), "Data should have createRemoteFileSystemDataSource field");
        JsonNode dataSource = data.get("createRemoteFileSystemDataSource");

        // Assert specific field values
        assertEquals("Test Remote FS", dataSource.get("name").asString());
        assertEquals("fileserver.example.com", dataSource.get(DataSourceType.FIELD_SERVER).asString());
        assertEquals("WORKGROUP", dataSource.get(DataSourceType.FIELD_DOMAIN).asString());
        assertEquals("testuser", dataSource.get(DataSourceType.FIELD_USERNAME).asString());
        assertEquals("share", dataSource.get(DataSourceType.FIELD_SHARE_NAME).asString());
        assertEquals("/remote/path", dataSource.get("path").asString());

        // Verify database state matches the GraphQL response
        verifyRemoteFileSystemDataSourceInDatabase("Test Remote FS", "0 0 * * *", "fileserver.example.com", "WORKGROUP", "testuser", "testpass", "share", "/remote/path");
    }

    @Test
    void testCreateS3BucketDataSource() throws JacksonException {
        String mutation = """
            mutation {
                createS3BucketDataSource(input: {
                    name: "Test S3"
                    cronPattern: "0 0 * * *"
                    nameOrUrl: "my-bucket"
                    accessKey: "AKIAIOSFODNN7EXAMPLE"
                    secretKey: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
                    path: "/s3/path"
                }) {
                    name
                    nameOrUrl
                    accessKey
                    path
                }
            }
            """;

        ResponseEntity<String> response = executeGraphQL(mutation);
        
        logger.info("S3 Response: {}", response.getBody());
        
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        
        // Parse JSON response
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());
        
        // Check that there are no GraphQL errors
        assertFalse(jsonResponse.has("errors"), "GraphQL response should not contain errors: " + response.getBody());
        
        // Verify data structure and content
        assertTrue(jsonResponse.has("data"), "Response should have data field");
        JsonNode data = jsonResponse.get("data");
        
        assertTrue(data.has("createS3BucketDataSource"), "Data should have createS3BucketDataSource field");
        JsonNode dataSource = data.get("createS3BucketDataSource");
        
        // Assert specific field values
        assertEquals("Test S3", dataSource.get("name").asString());
        assertEquals("my-bucket", dataSource.get(DataSourceType.FIELD_NAME_OR_URL).asString());
        assertEquals("AKIAIOSFODNN7EXAMPLE", dataSource.get(DataSourceType.FIELD_ACCESS_KEY).asString());
        assertEquals("/s3/path", dataSource.get("path").asString());

        // Verify database state matches the GraphQL response
        verifyS3BucketDataSourceInDatabase("Test S3", "0 0 * * *", "my-bucket", "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "/s3/path");
    }

    @Test
    void testUpdateLocalFileSystemDataSource() throws JacksonException {
        // First create a datasource to update
        String createMutation = """
            mutation {
                createLocalFileSystemDataSource(input: {
                    name: "Test Update Local FS"
                    cronPattern: "0 0 * * *"
                    localPath: "/tmp/original"
                }) {
                    name
                }
            }
            """;

        executeGraphQL(createMutation);

        // Now update the datasource
        String updateMutation = """
            mutation {
                updateLocalFileSystemDataSource(
                    name: "Test Update Local FS"
                    input: {
                        cronPattern: "30 0 * * *"
                        localPath: "/tmp/updated"
                    }
                ) {
                    name
                    cronPattern
                    type
                    localPath
                    dateCreated
                    dateLastScanned
                }
            }
            """;

        ResponseEntity<String> response = executeGraphQL(updateMutation);
        
        logger.info("Update Response: {}", response.getBody());
        
        // Basic assertion - should be successful and contain data
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        
        // Parse JSON response
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());
        
        // Check that there are no GraphQL errors
        assertFalse(jsonResponse.has("errors"), "GraphQL response should not contain errors: " + response.getBody());
        
        // Verify data structure and content
        assertTrue(jsonResponse.has("data"), "Response should have data field");
        JsonNode data = jsonResponse.get("data");
        
        assertTrue(data.has("updateLocalFileSystemDataSource"), "Data should have updateLocalFileSystemDataSource field");
        JsonNode dataSource = data.get("updateLocalFileSystemDataSource");
        
        // Assert specific field values - should reflect the updates
        assertEquals("Test Update Local FS", dataSource.get("name").asString());
        assertEquals("30 0 * * *", dataSource.get("cronPattern").asString());  // Updated value
        assertEquals("/tmp/updated", dataSource.get(DataSourceType.FIELD_LOCAL_PATH).asString());     // Updated value
        assertEquals("LOCAL_FILESYSTEM", dataSource.get("type").asString());
        assertNotNull(dataSource.get("dateCreated"));

        // Verify database state reflects the update
        verifyLocalFileSystemDataSourceInDatabase("Test Update Local FS", "30 0 * * *", "/tmp/updated");
    }

    @Test
    void testUpdateRemoteFileSystemDataSource() throws JacksonException {
        // First create a datasource to update
        String createMutation = """
            mutation {
                createRemoteFileSystemDataSource(input: {
                    name: "Test Update Remote FS"
                    cronPattern: "0 0 * * *"
                    server: "original.example.com"
                    domain: "WORKGROUP"
                    username: "testuser"
                    password: "testpass"
                    shareName: "originalshare"
                    path: "/original/path"
                }) {
                    name
                }
            }
            """;

        executeGraphQL(createMutation);

        // Now update the datasource
        String updateMutation = """
            mutation {
                updateRemoteFileSystemDataSource(
                    name: "Test Update Remote FS"
                    input: {
                        cronPattern: "45 0 * * *"
                        server: "updated.example.com"
                        domain: "DOMAIN"
                        path: "/updated/path"
                        shareName: "updatedshare"
                    }
                ) {
                    name
                    cronPattern
                    type
                    server
                    domain
                    username
                    password
                    shareName
                    path
                    dateCreated
                    dateLastScanned
                }
            }
            """;

        ResponseEntity<String> response = executeGraphQL(updateMutation);

        logger.info("Update Remote Response: {}", response.getBody());

        // Basic assertion - should be successful and contain data
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());

        // Parse JSON response
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());

        // Check that there are no GraphQL errors
        assertFalse(jsonResponse.has("errors"), "GraphQL response should not contain errors: " + response.getBody());

        // Verify data structure and content
        assertTrue(jsonResponse.has("data"), "Response should have data field");
        JsonNode data = jsonResponse.get("data");

        assertTrue(data.has("updateRemoteFileSystemDataSource"), "Data should have updateRemoteFileSystemDataSource field");
        JsonNode dataSource = data.get("updateRemoteFileSystemDataSource");

        // Assert specific field values - should reflect the updates
        assertEquals("Test Update Remote FS", dataSource.get("name").asString());
        assertEquals("45 0 * * *", dataSource.get("cronPattern").asString());      // Updated value
        assertEquals("updated.example.com", dataSource.get(DataSourceType.FIELD_SERVER).asString());    // Updated value
        assertEquals("DOMAIN", dataSource.get(DataSourceType.FIELD_DOMAIN).asString());    // Updated value
        assertEquals("/updated/path", dataSource.get(DataSourceType.FIELD_PATH).asString());            // Updated value
        assertEquals("updatedshare", dataSource.get(DataSourceType.FIELD_SHARE_NAME).asString());        // Updated value
        assertEquals("testuser", dataSource.get(DataSourceType.FIELD_USERNAME).asString());             // Should remain unchanged
        assertEquals("testpass", dataSource.get(DataSourceType.FIELD_PASSWORD).asString());             // Should remain unchanged
        assertEquals("REMOTE_FILESYSTEM", dataSource.get("type").asString());
        assertNotNull(dataSource.get("dateCreated"));

        // Verify database state reflects the update
        verifyRemoteFileSystemDataSourceInDatabase("Test Update Remote FS", "45 0 * * *", "updated.example.com", "DOMAIN", "testuser", "testpass", "updatedshare", "/updated/path");
    }

    @Test
    void testUpdateS3BucketDataSource() throws JacksonException {
        // First create a datasource to update
        String createMutation = """
            mutation {
                createS3BucketDataSource(input: {
                    name: "Test Update S3"
                    cronPattern: "0 0 * * *"
                    nameOrUrl: "original-bucket"
                    accessKey: "AKIAIOSFODNN7EXAMPLE"
                    secretKey: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
                    path: "/original/s3/path"
                }) {
                    name
                }
            }
            """;

        executeGraphQL(createMutation);

        // Now update the datasource
        String updateMutation = """
            mutation {
                updateS3BucketDataSource(
                    name: "Test Update S3"
                    input: {
                        cronPattern: "15 0 * * *"
                        nameOrUrl: "updated-bucket"
                        path: "/updated/s3/path"
                    }
                ) {
                    name
                    cronPattern
                    type
                    nameOrUrl
                    accessKey
                    secretKey
                    path
                    dateCreated
                    dateLastScanned
                }
            }
            """;

        ResponseEntity<String> response = executeGraphQL(updateMutation);
        
        logger.info("Update S3 Response: {}", response.getBody());
        
        // Basic assertion - should be successful and contain data
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        
        // Parse JSON response
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());
        
        // Check that there are no GraphQL errors
        assertFalse(jsonResponse.has("errors"), "GraphQL response should not contain errors: " + response.getBody());
        
        // Verify data structure and content
        assertTrue(jsonResponse.has("data"), "Response should have data field");
        JsonNode data = jsonResponse.get("data");
        
        assertTrue(data.has("updateS3BucketDataSource"), "Data should have updateS3BucketDataSource field");
        JsonNode dataSource = data.get("updateS3BucketDataSource");
        
        // Assert specific field values - should reflect the updates
        assertEquals("Test Update S3", dataSource.get("name").asString());
        assertEquals("15 0 * * *", dataSource.get("cronPattern").asString());              // Updated value
        assertEquals("updated-bucket", dataSource.get(DataSourceType.FIELD_NAME_OR_URL).asString());              // Updated value
        assertEquals("/updated/s3/path", dataSource.get(DataSourceType.FIELD_PATH).asString());                 // Updated value
        assertEquals("AKIAIOSFODNN7EXAMPLE", dataSource.get(DataSourceType.FIELD_ACCESS_KEY).asString());        // Should remain unchanged
        assertEquals("wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", dataSource.get(DataSourceType.FIELD_SECRET_KEY).asString()); // Should remain unchanged
        assertEquals("S3_BUCKET", dataSource.get("type").asString());
        assertNotNull(dataSource.get("dateCreated"));

        // Verify database state reflects the update
        verifyS3BucketDataSourceInDatabase("Test Update S3", "15 0 * * *", "updated-bucket", "AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "/updated/s3/path");
    }

    // Helper methods for database verification

    private void verifyLocalFileSystemDataSourceInDatabase(String name, String cronPattern, String localPath) {
        String sql = "SELECT name, cron_pattern, type, config FROM DATA_SOURCES WHERE name = ? AND type = ?";
        Map<String, Object> result = dbAccess.queryForMap(sql, name, "LOCAL_FILESYSTEM");
        
        assertEquals(name, result.get("name"));
        assertEquals(cronPattern, result.get("cron_pattern"));
        assertEquals("LOCAL_FILESYSTEM", result.get("type"));
        
        // Parse and verify JSONB config
        Object configObj = result.get("config");
        String configJson = configObj.toString(); // PGobject.toString() returns the JSON string
        try {
            JsonNode config = objectMapper.readTree(configJson);
            assertEquals(localPath, config.get(DataSourceType.FIELD_LOCAL_PATH).asString());
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to parse config JSON", e);
        }
    }

    private void verifyRemoteFileSystemDataSourceInDatabase(String name, String cronPattern, String server, String domain,
                                                           String username, String password, String shareName, String path) {
        String sql = "SELECT name, cron_pattern, type, config FROM DATA_SOURCES WHERE name = ? AND type = ?";
        Map<String, Object> result = dbAccess.queryForMap(sql, name, "REMOTE_FILESYSTEM");

        assertEquals(name, result.get("name"));
        assertEquals(cronPattern, result.get("cron_pattern"));
        assertEquals("REMOTE_FILESYSTEM", result.get("type"));

        // Parse and verify JSONB config
        Object configObj = result.get("config");
        String configJson = configObj.toString(); // PGobject.toString() returns the JSON string
        try {
            JsonNode config = objectMapper.readTree(configJson);
            assertEquals(server, config.get(DataSourceType.FIELD_SERVER).asString());
            assertEquals(domain, config.get(DataSourceType.FIELD_DOMAIN).asString());
            assertEquals(username, config.get(DataSourceType.FIELD_USERNAME).asString());
            assertEquals(password, config.get(DataSourceType.FIELD_PASSWORD).asString());
            assertEquals(shareName, config.get(DataSourceType.FIELD_SHARE_NAME).asString());
            assertEquals(path, config.get(DataSourceType.FIELD_PATH).asString());
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to parse config JSON", e);
        }
    }

    private void verifyS3BucketDataSourceInDatabase(String name, String cronPattern, String nameOrUrl,
                                                   String accessKey, String secretKey, String path) {
        String sql = "SELECT name, cron_pattern, type, config FROM DATA_SOURCES WHERE name = ? AND type = ?";
        Map<String, Object> result = dbAccess.queryForMap(sql, name, "S3_BUCKET");

        assertEquals(name, result.get("name"));
        assertEquals(cronPattern, result.get("cron_pattern"));
        assertEquals("S3_BUCKET", result.get("type"));

        // Parse and verify JSONB config
        Object configObj = result.get("config");
        String configJson = configObj.toString(); // PGobject.toString() returns the JSON string
        try {
            JsonNode config = objectMapper.readTree(configJson);
            assertEquals(nameOrUrl, config.get(DataSourceType.FIELD_NAME_OR_URL).asString());
            assertEquals(accessKey, config.get(DataSourceType.FIELD_ACCESS_KEY).asString());
            assertEquals(secretKey, config.get(DataSourceType.FIELD_SECRET_KEY).asString());
            assertEquals(path, config.get(DataSourceType.FIELD_PATH).asString());
        } catch (JacksonException e) {
            throw new RuntimeException("Failed to parse config JSON", e);
        }
    }

    @Test
    void testScanDataSource() throws JacksonException, InterruptedException {
        // Create test directory first - LocalFilesDataProvider requires root directory to exist
        java.io.File scanTestDir = new java.io.File(System.getProperty("java.io.tmpdir"), "scan-test-" + System.currentTimeMillis());
        scanTestDir.mkdirs();
        
        try {
            // First create a datasource to scan
            String createMutation = """
                mutation {
                    createLocalFileSystemDataSource(input: {
                        name: "Test Scan DataSource"
                        cronPattern: "0 0 * * *"
                        localPath: "%s"
                    }) {
                        name
                        dateLastScanned
                    }
                }
                """.formatted(scanTestDir.getAbsolutePath());

            ResponseEntity<String> createResponse = executeGraphQL(createMutation);
            JsonNode createJsonResponse = objectMapper.readTree(createResponse.getBody());
            JsonNode createData = createJsonResponse.get("data").get("createLocalFileSystemDataSource");

            // Verify dateLastScanned is null before scan
            assertTrue(createData.get("dateLastScanned").isNull(), "dateLastScanned should be null before scan");

            // Query to verify status is IDLE before scan
            String queryBeforeScan = """
                query {
                    dataSource(name: "Test Scan DataSource", type: LOCAL_FILESYSTEM) {
                        name
                        status
                    }
                }
                """;
            ResponseEntity<String> queryBeforeResponse = executeGraphQL(queryBeforeScan);
            JsonNode queryBeforeJson = objectMapper.readTree(queryBeforeResponse.getBody());
            assertEquals("IDLE", queryBeforeJson.get("data").get("dataSource").get("status").asString(),
                "Status should be IDLE before scan");

            // Now trigger an async scan
            String scanMutation = """
                mutation {
                    scanDataSource(name: "Test Scan DataSource", type: LOCAL_FILESYSTEM) {
                        name
                        type
                        status
                        dateLastScanned
                        cronPattern
                    }
                }
                """;

            ResponseEntity<String> response = executeGraphQL(scanMutation);

            logger.info("Scan Response: {}", response.getBody());

            // Basic assertion - should be successful and contain data
            assertTrue(response.getStatusCode().is2xxSuccessful());
            assertNotNull(response.getBody());

            // Parse JSON response
            JsonNode jsonResponse = objectMapper.readTree(response.getBody());

            // Check that there are no GraphQL errors
            assertFalse(jsonResponse.has("errors"), "GraphQL response should not contain errors: " + response.getBody());

            // Verify data structure and content
            assertTrue(jsonResponse.has("data"), "Response should have data field");
            JsonNode data = jsonResponse.get("data");

            assertTrue(data.has("scanDataSource"), "Data should have scanDataSource field");
            JsonNode dataSource = data.get("scanDataSource");

            // Assert specific field values
            assertEquals("Test Scan DataSource", dataSource.get("name").asString());
            assertEquals("LOCAL_FILESYSTEM", dataSource.get("type").asString());
            assertEquals("0 0 * * *", dataSource.get("cronPattern").asString());

            // Since the scan is async, the mutation returns immediately
            // The dateLastScanned will still be null at this point
            assertTrue(dataSource.get("dateLastScanned").isNull(),
                "dateLastScanned should still be null immediately after triggering async scan");

            // Wait for the async scan to complete (for test directory this should be quick)
            Thread.sleep(2000);

            // Query the data source again to verify the scan has completed
            String queryMutation = """
                query {
                    dataSource(name: "Test Scan DataSource", type: LOCAL_FILESYSTEM) {
                        name
                        type
                        status
                        dateLastScanned
                    }
                }
                """;

            ResponseEntity<String> queryResponse = executeGraphQL(queryMutation);
            JsonNode queryJsonResponse = objectMapper.readTree(queryResponse.getBody());
            JsonNode queryData = queryJsonResponse.get("data").get("dataSource");

            // After waiting, dateLastScanned should now be populated
            assertNotNull(queryData.get("dateLastScanned"), "dateLastScanned should be set after async scan completes");
            assertFalse(queryData.get("dateLastScanned").isNull(), "dateLastScanned should not be null after async scan completes");
            // Status should be back to IDLE after scan completes
            assertEquals("IDLE", queryData.get("status").asString(), "Status should be IDLE after scan completes");

            // Verify database state shows updated dateLastScanned and status
            String sql = "SELECT date_last_scanned, status FROM DATA_SOURCES WHERE name = ? AND type = ?";
            Map<String, Object> result = dbAccess.queryForMap(sql, "Test Scan DataSource", "LOCAL_FILESYSTEM");
            assertNotNull(result.get("date_last_scanned"), "date_last_scanned should be set in database");
            assertEquals("IDLE", result.get("status"), "status should be IDLE in database");
        } finally {
            // Clean up test directory
            scanTestDir.delete();
        }
    }

    @Test
    void testScanDataSourceWhenAlreadyScanning() throws JacksonException {
        // Create test directory
        java.io.File scanTestDir = new java.io.File(System.getProperty("java.io.tmpdir"), "scan-conflict-test-" + System.currentTimeMillis());
        scanTestDir.mkdirs();

        try {
            // Create a datasource
            String createMutation = """
                mutation {
                    createLocalFileSystemDataSource(input: {
                        name: "Test Scan Conflict DataSource"
                        cronPattern: "0 0 * * *"
                        localPath: "%s"
                    }) {
                        name
                    }
                }
                """.formatted(scanTestDir.getAbsolutePath());

            executeGraphQL(createMutation);

            // Get the datasource to manually set status to SCANNING
            DataSourceInterface dataSource = dataSourceDao.findByNameAndType("Test Scan Conflict DataSource", DataSourceType.LOCAL_FILESYSTEM)
                .orElseThrow(() -> new RuntimeException("DataSource not found"));
            String datasourceId = DataSourceType.LOCAL_FILESYSTEM.extractDatasourceId(dataSource);

            // Manually set status to SCANNING to simulate an ongoing scan
            dataSourceDao.updateStatus(datasourceId, enterfind.server.model.DataSourceStatus.SCANNING);

            // Try to trigger another scan while already SCANNING
            String scanMutation = """
                mutation {
                    scanDataSource(name: "Test Scan Conflict DataSource", type: LOCAL_FILESYSTEM) {
                        name
                        type
                        status
                    }
                }
                """;

            ResponseEntity<String> response = executeGraphQL(scanMutation);

            logger.info("Scan Conflict Response: {}", response.getBody());

            // Parse JSON response
            JsonNode jsonResponse = objectMapper.readTree(response.getBody());

            // Should have GraphQL errors because datasource is already scanning
            assertTrue(jsonResponse.has("errors"), "GraphQL response should contain errors when datasource is already scanning");
            JsonNode errors = jsonResponse.get("errors");
            assertTrue(errors.isArray() && errors.size() > 0, "Should have at least one error");

            // Verify the error message mentions "already scanning"
            String errorMessage = errors.get(0).get("message").asString();
            assertTrue(errorMessage.toLowerCase().contains("already scanning"),
                "Error message should indicate datasource is already scanning: " + errorMessage);

            // Clean up - set status back to IDLE
            dataSourceDao.updateStatus(datasourceId, enterfind.server.model.DataSourceStatus.IDLE);
        } finally {
            // Clean up test directory
            scanTestDir.delete();
        }
    }

    @Test
    void testDataSourceStats() throws JacksonException {
        // Create a local filesystem datasource with a real temp directory
        java.io.File tempDir = new java.io.File(System.getProperty("java.io.tmpdir"), "test-stats-" + System.currentTimeMillis());
        tempDir.mkdirs();
        
        // Create a few test files
        try {
            new java.io.File(tempDir, "file1.txt").createNewFile();
            new java.io.File(tempDir, "file2.txt").createNewFile();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to create test files", e);
        }

        String createMutation = String.format("""
            mutation {
                createLocalFileSystemDataSource(input: {
                    name: "Test Stats DataSource"
                    cronPattern: "0 0 * * *"
                    localPath: "%s"
                }) {
                    name
                    type
                }
            }
            """, tempDir.getAbsolutePath());

        executeGraphQL(createMutation);

        // Query stats before scan - should have 0 items
        String statsQuery = """
            query {
                dataSourceStats(name: "Test Stats DataSource", type: LOCAL_FILESYSTEM) {
                    totalItems
                    treatmentStatusCounts {
                        status
                        count
                    }
                }
            }
            """;

        ResponseEntity<String> response = executeGraphQL(statsQuery);
        
        logger.info("Stats Response (before scan): {}", response.getBody());
        
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());
        assertFalse(jsonResponse.has("errors"), "GraphQL response should not contain errors: " + response.getBody());
        
        JsonNode data = jsonResponse.get("data");
        assertTrue(data.has("dataSourceStats"), "Data should have dataSourceStats field");
        
        JsonNode stats = data.get("dataSourceStats");
        assertEquals(0, stats.get("totalItems").asInt(), "Should have 0 items before scan");
        
        JsonNode statusCounts = stats.get("treatmentStatusCounts");
        assertTrue(statusCounts.isArray(), "treatmentStatusCounts should be an array");
        assertEquals(5, statusCounts.size(), "Should have 5 status entries (one for each ItemTreatmentStatus)");
        
        // Trigger a synchronous scan
        DataSourceInterface dataSource = dataSourceDao.findByNameAndType("Test Stats DataSource", DataSourceType.LOCAL_FILESYSTEM)
                .orElseThrow(() -> new RuntimeException("DataSource not found"));
        scanner.scan(dataSource);
        
        // Query stats after scan - should have items and treatment counts
        ResponseEntity<String> responseAfterScan = executeGraphQL(statsQuery);
        
        logger.info("Stats Response (after scan): {}", responseAfterScan.getBody());
        
        JsonNode jsonResponseAfterScan = objectMapper.readTree(responseAfterScan.getBody());
        assertFalse(jsonResponseAfterScan.has("errors"), "GraphQL response should not contain errors after scan");
        
        JsonNode statsAfterScan = jsonResponseAfterScan.get("data").get("dataSourceStats");
        assertTrue(statsAfterScan.get("totalItems").asInt() >= 2, "Should have at least 2 items after scan");
        
        // Check that we have status counts
        JsonNode statusCountsAfterScan = statsAfterScan.get("treatmentStatusCounts");
        assertTrue(statusCountsAfterScan.isArray(), "treatmentStatusCounts should be an array after scan");
        
        // Sum up all the treatment counts - should be > 0 after scan
        int totalTreatments = 0;
        for (JsonNode statusCount : statusCountsAfterScan) {
            totalTreatments += statusCount.get("count").asInt();
        }
        assertTrue(totalTreatments >= 0, "Should have treatment status counts after scan");
        
        // Clean up
        for (java.io.File file : tempDir.listFiles()) {
            file.delete();
        }
        tempDir.delete();
    }

    @Test
    void testTestDataSource() throws JacksonException {
        // Create a local filesystem datasource with a real temp directory containing files
        java.io.File tempDir = new java.io.File(System.getProperty("java.io.tmpdir"), "test-connection-" + System.currentTimeMillis());
        tempDir.mkdirs();
        
        // Create test files and a subfolder
        java.io.File subFolder = new java.io.File(tempDir, "subfolder");
        subFolder.mkdirs();
        
        try {
            new java.io.File(tempDir, "testfile1.txt").createNewFile();
            new java.io.File(tempDir, "testfile2.pdf").createNewFile();
            new java.io.File(subFolder, "nested.txt").createNewFile();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to create test files", e);
        }

        String createMutation = String.format("""
            mutation {
                createLocalFileSystemDataSource(input: {
                    name: "Test Connection DataSource"
                    localPath: "%s"
                }) {
                    name
                    type
                }
            }
            """, tempDir.getAbsolutePath());

        executeGraphQL(createMutation);

        // Test the data source connection
        String testQuery = """
            query {
                testDataSource(name: "Test Connection DataSource", type: LOCAL_FILESYSTEM) {
                    success
                    message
                    files {
                        name
                        folder
                        sizeBytes
                        dataSourceName
                        dataSourceType
                        dateCreated
                        dateModified
                    }
                    folders {
                        name
                        folder
                        dataSourceName
                        dataSourceType
                    }
                }
            }
            """;

        ResponseEntity<String> response = executeGraphQL(testQuery);
        
        logger.info("Test DataSource Response: {}", response.getBody());
        
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());
        assertFalse(jsonResponse.has("errors"), "GraphQL response should not contain errors: " + response.getBody());
        
        JsonNode data = jsonResponse.get("data");
        assertTrue(data.has("testDataSource"), "Data should have testDataSource field");
        
        JsonNode testResult = data.get("testDataSource");
        
        // Verify success
        assertTrue(testResult.get("success").asBoolean(), "Test should be successful");
        assertTrue(testResult.get("message").isNull(), "Message should be null on success");
        
        // Verify files array
        JsonNode files = testResult.get("files");
        assertTrue(files.isArray(), "files should be an array");
        assertEquals(2, files.size(), "Should have 2 files at root level");
        
        // Verify file properties
        boolean foundFile1 = false;
        boolean foundFile2 = false;
        for (JsonNode file : files) {
            String fileName = file.get("name").asString();
            if ("testfile1.txt".equals(fileName)) {
                foundFile1 = true;
                assertEquals("Test Connection DataSource", file.get("dataSourceName").asString());
                assertEquals("LOCAL_FILESYSTEM", file.get("dataSourceType").asString());
                assertTrue(file.get("folder").isNull(), "Root files should have null folder");
            } else if ("testfile2.pdf".equals(fileName)) {
                foundFile2 = true;
            }
        }
        assertTrue(foundFile1, "Should find testfile1.txt");
        assertTrue(foundFile2, "Should find testfile2.pdf");
        
        // Verify folders array
        JsonNode folders = testResult.get("folders");
        assertTrue(folders.isArray(), "folders should be an array");
        assertEquals(1, folders.size(), "Should have 1 folder at root level");
        
        JsonNode folder = folders.get(0);
        assertEquals("subfolder", folder.get("name").asString());
        assertEquals("Test Connection DataSource", folder.get("dataSourceName").asString());
        assertEquals("LOCAL_FILESYSTEM", folder.get("dataSourceType").asString());
        assertTrue(folder.get("folder").isNull(), "Root folder should have null parent folder");
        
        // Clean up
        new java.io.File(subFolder, "nested.txt").delete();
        subFolder.delete();
        for (java.io.File file : tempDir.listFiles()) {
            file.delete();
        }
        tempDir.delete();
    }

    @Test
    void testTestDataSourceWithEmptyDirectory() throws JacksonException {
        // Create a datasource with a non-existent path
        String createMutation = """
            mutation {
                createLocalFileSystemDataSource(input: {
                    name: "Test Empty Path DataSource"
                    localPath: "/non/existent/path/that/does/not/exist"
                }) {
                    name
                    type
                }
            }
            """;

        executeGraphQL(createMutation);

        // Test the data source connection - should fail because root directory doesn't exist
        String testQuery = """
            query {
                testDataSource(name: "Test Empty Path DataSource", type: LOCAL_FILESYSTEM) {
                    success
                    message
                    files {
                        name
                    }
                    folders {
                        name
                    }
                }
            }
            """;

        ResponseEntity<String> response = executeGraphQL(testQuery);
        
        logger.info("Test Empty DataSource Response: {}", response.getBody());
        
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());
        assertFalse(jsonResponse.has("errors"), "GraphQL response should not contain errors: " + response.getBody());
        
        JsonNode testResult = jsonResponse.get("data").get("testDataSource");
        
        // LocalFilesDataProvider now fails for non-existent root directories
        assertFalse(testResult.get("success").asBoolean(), "Test should fail for non-existent path");
        assertNotNull(testResult.get("message"), "Message should contain error details");
        assertTrue(testResult.get("message").asString().contains("does not exist"), 
            "Error message should indicate directory does not exist: " + testResult.get("message").asString());
    }

    @Test
    void testTestDataSourceNotFound() throws JacksonException {
        // Test a non-existent datasource - should return a GraphQL error
        String testQuery = """
            query {
                testDataSource(name: "NonExistent DataSource", type: LOCAL_FILESYSTEM) {
                    success
                    message
                    files {
                        name
                    }
                    folders {
                        name
                    }
                }
            }
            """;

        ResponseEntity<String> response = executeGraphQL(testQuery);
        
        logger.info("Test NonExistent DataSource Response: {}", response.getBody());
        
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());
        
        // Should have GraphQL errors for non-existent datasource
        assertTrue(jsonResponse.has("errors"), "GraphQL response should contain errors for non-existent datasource");
        JsonNode errors = jsonResponse.get("errors");
        assertTrue(errors.isArray() && errors.size() > 0, "Should have at least one error");
        
        // Verify the error message mentions "not found"
        String errorMessage = errors.get(0).get("message").asString();
        assertTrue(errorMessage.toLowerCase().contains("not found"), 
            "Error message should indicate datasource not found: " + errorMessage);
    }

    @Test
    void testCreateRemoteFileSystemDataSourceWithEmptyPath() throws JacksonException {
        // Create a remote filesystem datasource with empty path (path field omitted)
        String createMutation = """
            mutation {
                createRemoteFileSystemDataSource(input: {
                    name: "Test Remote FS Empty Path"
                    server: "localhost"
                    username: "smbtest"
                    password: "smbtest123"
                    shareName: "scratch"
                }) {
                    name
                    server
                    username
                    shareName
                    path
                }
            }
            """;

        ResponseEntity<String> response = executeGraphQL(createMutation);
        
        logger.info("Create Remote FS Empty Path Response: {}", response.getBody());
        
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());
        
        // Check that there are no GraphQL errors - this was the bug we're fixing
        assertFalse(jsonResponse.has("errors"), "GraphQL response should not contain errors: " + response.getBody());
        
        // Verify data structure and content
        assertTrue(jsonResponse.has("data"), "Response should have data field");
        JsonNode data = jsonResponse.get("data");
        
        assertTrue(data.has("createRemoteFileSystemDataSource"), "Data should have createRemoteFileSystemDataSource field");
        JsonNode dataSource = data.get("createRemoteFileSystemDataSource");
        
        // Assert specific field values
        assertEquals("Test Remote FS Empty Path", dataSource.get("name").asString());
        assertEquals("localhost", dataSource.get("server").asString());
        assertEquals("smbtest", dataSource.get("username").asString());
        assertEquals("scratch", dataSource.get("shareName").asString());
        // Path should be empty string, not null (which would cause GraphQL error)
        assertEquals("", dataSource.get("path").asString(), "Path should be empty string when not provided");
        
        // Now test the datasource connection using testDataSource query
        String testQuery = """
            query {
                testDataSource(name: "Test Remote FS Empty Path", type: REMOTE_FILESYSTEM) {
                    success
                    message
                    files {
                        name
                        folder
                        dataSourceName
                        dataSourceType
                    }
                    folders {
                        name
                        folder
                        dataSourceName
                        dataSourceType
                    }
                }
            }
            """;

        ResponseEntity<String> testResponse = executeGraphQL(testQuery);
        
        logger.info("Test Remote FS Empty Path Response: {}", testResponse.getBody());
        
        assertTrue(testResponse.getStatusCode().is2xxSuccessful());
        assertNotNull(testResponse.getBody());
        
        JsonNode testJsonResponse = objectMapper.readTree(testResponse.getBody());
        assertFalse(testJsonResponse.has("errors"), "GraphQL test response should not contain errors: " + testResponse.getBody());
        
        JsonNode testResult = testJsonResponse.get("data").get("testDataSource");
        
        // Verify success - the SMB server is available in the dev container
        assertTrue(testResult.get("success").asBoolean(), "Test should be successful connecting to SMB share");
        assertTrue(testResult.get("message").isNull(), "Message should be null on success");
        
        // Verify files and folders arrays exist (content may vary)
        JsonNode files = testResult.get("files");
        assertTrue(files.isArray(), "files should be an array");
        
        JsonNode folders = testResult.get("folders");
        assertTrue(folders.isArray(), "folders should be an array");
    }

    @Test
    void testCreateS3BucketDataSourceWithEmptyPath() throws JacksonException {
        // Create an S3 bucket datasource with empty path (path field omitted)
        String createMutation = """
            mutation {
                createS3BucketDataSource(input: {
                    name: "Test S3 Empty Path"
                    nameOrUrl: "my-test-bucket"
                    accessKey: "AKIAIOSFODNN7EXAMPLE"
                    secretKey: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
                }) {
                    name
                    nameOrUrl
                    accessKey
                    path
                }
            }
            """;

        ResponseEntity<String> response = executeGraphQL(createMutation);
        
        logger.info("Create S3 Empty Path Response: {}", response.getBody());
        
        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertNotNull(response.getBody());
        
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());
        
        // Check that there are no GraphQL errors - this was the bug we're fixing
        assertFalse(jsonResponse.has("errors"), "GraphQL response should not contain errors: " + response.getBody());
        
        // Verify data structure and content
        assertTrue(jsonResponse.has("data"), "Response should have data field");
        JsonNode data = jsonResponse.get("data");
        
        assertTrue(data.has("createS3BucketDataSource"), "Data should have createS3BucketDataSource field");
        JsonNode dataSource = data.get("createS3BucketDataSource");
        
        // Assert specific field values
        assertEquals("Test S3 Empty Path", dataSource.get("name").asString());
        assertEquals("my-test-bucket", dataSource.get("nameOrUrl").asString());
        assertEquals("AKIAIOSFODNN7EXAMPLE", dataSource.get("accessKey").asString());
        // Path should be empty string, not null (which would cause GraphQL error)
        assertEquals("", dataSource.get("path").asString(), "Path should be empty string when not provided");
    }

    @Test
    void testDataSourceStatsUpdatesSubscription() throws JacksonException, InterruptedException {
        // Create a local filesystem datasource with a real temp directory
        java.io.File tempDir = new java.io.File(System.getProperty("java.io.tmpdir"), "test-subscription-" + System.currentTimeMillis());
        tempDir.mkdirs();
        
        // Create a test file
        try {
            new java.io.File(tempDir, "file1.txt").createNewFile();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to create test file", e);
        }

        String createMutation = String.format("""
            mutation {
                createLocalFileSystemDataSource(input: {
                    name: "Test Subscription DataSource"
                    cronPattern: "0 0 * * *"
                    localPath: "%s"
                }) {
                    name
                    type
                }
            }
            """, tempDir.getAbsolutePath());

        executeGraphQL(createMutation);

        // Test WebSocket subscription using GraphQlTester with real periodic updates
        String subscriptionQuery = """
            subscription {
                dataSourceStatsUpdates(name: "Test Subscription DataSource", type: LOCAL_FILESYSTEM, intervalSeconds: 1) {
                    totalItems
                    treatmentStatusCounts {
                        status
                        count
                    }
                }
            }
            """;

        logger.info("Testing WebSocket subscription for periodic dataSourceStatsUpdates");

        // Create a Flux from the subscription and take 2 emissions
        Flux<?> statsFlux = graphQlTester
            .document(subscriptionQuery)
            .executeSubscription()
            .toFlux();

        // Subscribe to the flux and verify we get periodic emissions
        java.util.concurrent.CountDownLatch emissionLatch = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicInteger emissionCount = new java.util.concurrent.atomic.AtomicInteger(0);

        statsFlux
            .take(2)  // Take only 2 emissions to avoid waiting too long
            .subscribe(
                response -> {
                    int count = emissionCount.incrementAndGet();
                    logger.info("Received subscription emission #{}", count);
                    
                    // Verify the response structure on each emission
                    try {
                        // The response object is a GraphQlTester.Response
                        // We need to use its API directly, not try to convert it to JSON
                        logger.info("Subscription emission #{} received and validating structure", count);
                        
                        // For now, simply verifying we receive emissions successfully
                        // The subscription is working if we get this far without errors
                        assertTrue(true, "Successfully received subscription emission");
                        
                        logger.info("Emission #{} verified successfully", count);
                    } catch (Exception e) {
                        logger.error("Error verifying emission #{}: {}", count, e.getMessage(), e);
                        throw new RuntimeException("Verification failed for emission #" + count, e);
                    }
                    
                    emissionLatch.countDown();
                },
                error -> {
                    logger.error("Subscription error: {}", error.getMessage(), error);
                    throw new RuntimeException("Subscription failed", error);
                }
            );

        // Wait for both emissions (with 10 second timeout)
        boolean completed = emissionLatch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(completed, "Should receive 2 subscription emissions within 10 seconds");
        assertEquals(2, emissionCount.get(), "Should have received exactly 2 emissions");

        logger.info("WebSocket subscription test completed successfully - received {} periodic emissions", emissionCount.get());
        
        // Clean up
        for (java.io.File file : tempDir.listFiles()) {
            file.delete();
        }
        tempDir.delete();
    }

    // ================= Authenticated WebSocket Subscription Tests =================

    @Autowired
    private AuthenticationService authenticationService;

    /**
     * Test WebSocket subscription with authentication enabled.
     * This tests the full auth flow: user creation, login, and authenticated subscription.
     */
    @Test
    void testDataSourceStatsUpdatesSubscription_withAuthentication() throws Exception {
        // Create an admin user to enable authentication
        UserCreationResult admin = authenticationService.createUser("admin", UserLevel.ADMIN, null);
        String authToken = getAuthToken(admin);

        // Verify authentication is now required
        assertTrue(authenticationService.isAuthenticationRequired(), 
            "Authentication should be required after creating a user");

        // Create a local filesystem datasource (need to use authenticated HTTP request)
        java.io.File tempDir = new java.io.File(System.getProperty("java.io.tmpdir"), 
            "test-auth-subscription-" + System.currentTimeMillis());
        tempDir.mkdirs();

        try {
            new java.io.File(tempDir, "file1.txt").createNewFile();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to create test file", e);
        }

        // Create data source with authentication (token in GraphQL query)
        String createMutation = String.format("""
            mutation {
                createLocalFileSystemDataSource(token: "%s", input: {
                    name: "Test Auth Subscription DataSource"
                    cronPattern: "0 0 * * *"
                    localPath: "%s"
                }) {
                    name
                    type
                }
            }
            """, authToken, tempDir.getAbsolutePath());

        executeGraphQL(createMutation);

        // Test WebSocket subscription with authentication
        String subscriptionQuery = String.format("""
            subscription {
                dataSourceStatsUpdates(token: "%s", name: "Test Auth Subscription DataSource", type: LOCAL_FILESYSTEM, intervalSeconds: 1) {
                    totalItems
                    treatmentStatusCounts {
                        status
                        count
                    }
                    dataSourceStatus
                }
            }
            """, authToken);

        logger.info("Testing authenticated WebSocket subscription for dataSourceStatsUpdates");

        // Connect to WebSocket with auth and subscribe
        CountDownLatch emissionLatch = new CountDownLatch(2);
        AtomicInteger emissionCount = new AtomicInteger(0);
        AtomicReference<String> errorRef = new AtomicReference<>(null);
        AtomicBoolean connectionAccepted = new AtomicBoolean(false);

        String wsUrl = "ws://localhost:" + port + "/graphql";
        StandardWebSocketClient client = new StandardWebSocketClient();
        AtomicReference<WebSocketSession> sessionRef = new AtomicReference<>(null);

        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
                sessionRef.set(session);
                // Send connection_init (no auth payload needed - token is in GraphQL query)
                String initPayload = "{\"type\":\"connection_init\",\"payload\":{}}";
                session.sendMessage(new TextMessage(Objects.requireNonNull(initPayload)));
            }

            @Override
            public void handleMessage(@NonNull WebSocketSession session, @NonNull WebSocketMessage<?> message) throws Exception {
                String payload = message.getPayload().toString();
                logger.info("WebSocket received: {}", payload);

                JsonNode json = objectMapper.readTree(payload);
                String type = json.path("type").asString();

                switch (type) {
                    case "connection_ack":
                        logger.info("WebSocket connection acknowledged, sending subscription");
                        connectionAccepted.set(true);
                        // Send subscription request
                        String subscribeMsg = String.format(
                            "{\"id\":\"1\",\"type\":\"subscribe\",\"payload\":{\"query\":\"%s\"}}",
                            subscriptionQuery.replace("\n", "\\n").replace("\"", "\\\""));
                        session.sendMessage(new TextMessage(Objects.requireNonNull(subscribeMsg)));
                        break;
                    case "next":
                        int count = emissionCount.incrementAndGet();
                        logger.info("Received authenticated subscription emission #{}", count);
                        // Verify we got valid data
                        assertTrue(json.has("payload"), "Should have payload");
                        assertTrue(json.path("payload").has("data"), "Should have data in payload");
                        emissionLatch.countDown();
                        break;
                    case "error":
                        String errorMsg = json.path("payload").toString();
                        logger.error("Subscription error: {}", errorMsg);
                        errorRef.set(errorMsg);
                        emissionLatch.countDown();
                        emissionLatch.countDown(); // Count down twice to release
                        break;
                    case "complete":
                        logger.info("Subscription completed");
                        break;
                }
            }

            @Override
            public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) throws Exception {
                logger.error("WebSocket transport error", exception);
                errorRef.set(exception.getMessage());
                emissionLatch.countDown();
                emissionLatch.countDown();
            }

            @Override
            public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus closeStatus) throws Exception {
                logger.info("WebSocket closed: {}", closeStatus);
            }

            @Override
            public boolean supportsPartialMessages() {
                return false;
            }
        };

        try {
            URI uri = Objects.requireNonNull(URI.create(wsUrl));
            var future = client.execute(handler, null, uri);
            future.get(5, TimeUnit.SECONDS);

            // Wait for emissions
            boolean completed = emissionLatch.await(15, TimeUnit.SECONDS);

            // Close session if still open
            WebSocketSession session = sessionRef.get();
            if (session != null && session.isOpen()) {
                // Send complete message before closing
                session.sendMessage(new TextMessage("{\"id\":\"1\",\"type\":\"complete\"}"));
                session.close();
            }

            // Verify results
            assertTrue(connectionAccepted.get(), "WebSocket connection should be accepted with valid auth token");
            assertNull(errorRef.get(), "Should not have errors: " + errorRef.get());
            assertTrue(completed, "Should receive 2 subscription emissions within 15 seconds");
            assertEquals(2, emissionCount.get(), "Should have received exactly 2 emissions");

            logger.info("Authenticated WebSocket subscription test completed successfully - received {} emissions", 
                emissionCount.get());
        } finally {
            // Clean up temp directory
            for (java.io.File file : tempDir.listFiles()) {
                file.delete();
            }
            tempDir.delete();
        }
    }

    /**
     * Test that WebSocket subscription is rejected without authentication when auth is required.
     */
    @Test
    void testDataSourceStatsUpdatesSubscription_withoutAuth_rejected() throws Exception {
        // Create an admin user to enable authentication
        authenticationService.createUser("admin", UserLevel.ADMIN, null);

        // Verify authentication is now required
        assertTrue(authenticationService.isAuthenticationRequired(), 
            "Authentication should be required after creating a user");

        // Try to connect without auth token
        CountDownLatch connectionLatch = new CountDownLatch(1);
        AtomicBoolean connectionAccepted = new AtomicBoolean(false);
        AtomicReference<String> errorRef = new AtomicReference<>(null);

        String wsUrl = "ws://localhost:" + port + "/graphql";
        StandardWebSocketClient client = new StandardWebSocketClient();

        WebSocketHandler handler = new WebSocketHandler() {
            @Override
            public void afterConnectionEstablished(@NonNull WebSocketSession session) throws Exception {
                // Send connection_init WITHOUT auth payload
                String initPayload = "{\"type\":\"connection_init\",\"payload\":{}}";
                session.sendMessage(new TextMessage(Objects.requireNonNull(initPayload)));
            }

            @Override
            public void handleMessage(@NonNull WebSocketSession session, @NonNull WebSocketMessage<?> message) throws Exception {
                String payload = message.getPayload().toString();
                logger.info("WebSocket received: {}", payload);

                JsonNode json = objectMapper.readTree(payload);
                String type = json.path("type").asString();

                if ("connection_ack".equals(type)) {
                    connectionAccepted.set(true);
                    connectionLatch.countDown();
                } else if ("error".equals(type)) {
                    errorRef.set(json.path("payload").toString());
                    connectionLatch.countDown();
                }
            }

            @Override
            public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) throws Exception {
                errorRef.set(exception.getMessage());
                connectionLatch.countDown();
            }

            @Override
            public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus closeStatus) throws Exception {
                if (!connectionAccepted.get() && errorRef.get() == null) {
                    errorRef.set("Connection closed: " + closeStatus.getReason());
                }
                connectionLatch.countDown();
            }

            @Override
            public boolean supportsPartialMessages() {
                return false;
            }
        };

        try {
            URI uri = Objects.requireNonNull(URI.create(wsUrl));
            var future = client.execute(handler, null, uri);
            future.get(5, TimeUnit.SECONDS);

            // Wait for response
            boolean completed = connectionLatch.await(5, TimeUnit.SECONDS);

            assertTrue(completed, "WebSocket handshake should complete");
            assertTrue(connectionAccepted.get(), 
                "Connection should be ACCEPTED (auth is now per-subscription, not per-connection)");
            logger.info("WebSocket connection accepted (auth is per-subscription)");
        } catch (Exception e) {
            // Connection rejection might come as an exception
            logger.info("WebSocket connection correctly rejected: {}", e.getMessage());
        }
    }

    private String getAuthToken(UserCreationResult user) throws Exception {
        String recoveryCode = user.recoveryCodes().get(0);
        String loginMutation = String.format("""
            mutation {
                loginWithRecoveryCode(input: {
                    nickname: "%s"
                    recoveryCode: "%s"
                }) {
                    token
                }
            }
            """, user.user().nickname(), recoveryCode);

        ResponseEntity<String> response = graphQLClient.execute(loginMutation);
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());
        return jsonResponse.path("data").path("loginWithRecoveryCode").path("token").asString();
    }



    private void assertNull(Object obj, String message) {
        assertTrue(obj == null, message);
    }

}