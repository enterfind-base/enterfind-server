package enterfind.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import enterfind.server.dao.DataSourceDao;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceType;
import enterfind.server.model.UserLevel;
import enterfind.server.scan.PreviewGenerator;
import enterfind.server.scan.Scanner;
import enterfind.server.scan.TreatmentProcessor;
import enterfind.server.service.AuthenticationService;

/**
 * Integration test for the DownloadController endpoint.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class DownloadControllerIntegrationTest extends BaseDatabaseTest {

    private static final Logger logger = LoggerFactory.getLogger(DownloadControllerIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private Scanner scanner;

    @Autowired
    private TreatmentProcessor treatmentProcessor;

    @Autowired
    private DataSourceDao dataSourceDao;

    @Autowired
    private PreviewGenerator previewGenerator;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private AuthenticationService authenticationService;

    private Path tempTestDir;
    private String testDataSourceName;

    @BeforeEach
    protected void initTest() throws Exception {
        tempTestDir = Files.createTempDirectory("download-test-");
        TestFileUtils.copyTestShareToTempDir(tempTestDir);
        logger.info("Created temp test directory: {}", tempTestDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempTestDir != null && Files.exists(tempTestDir)) {
            TestFileUtils.deleteDirectoryRecursively(tempTestDir);
            logger.info("Cleaned up temp test directory: {}", tempTestDir);
        }
    }

    private String createDataSourceViaGraphQL(String name, String localPath) throws Exception {
        String mutation = """
            mutation {
                createLocalFileSystemDataSource(input: {
                    name: "%s"
                    cronPattern: "0 0 * * *"
                    localPath: "%s"
                }) {
                    name
                    type
                }
            }
            """.formatted(name, localPath);
        GraphQLTestClient client = new GraphQLTestClient(port);
        client.executeMutation(mutation);
        return name;
    }

    @Test
    void downloadShouldReturnStoredContentTypeAndBytes() throws Exception {
        testDataSourceName = "Download Test Source";
        createDataSourceViaGraphQL(testDataSourceName, tempTestDir.toString());

        DataSourceInterface ds = dataSourceDao.findByNameAndType(testDataSourceName, DataSourceType.LOCAL_FILESYSTEM)
            .orElseThrow(() -> new RuntimeException("Datasource not found"));

        // Scan and process NAME_AND_PATH so mime_type is captured in DB
        scanner.scan(ds);
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);

        // File to download: subf/text.txt in test share
        Path original = tempTestDir.resolve("subf").resolve("text.txt");
        byte[] expected = Files.readAllBytes(original);

        String encodedName = URLEncoder.encode(testDataSourceName, StandardCharsets.UTF_8).replace("+", "%20");
        String url = "http://localhost:" + port + "/content/local-filesystem/" + encodedName + "/subf/text.txt";

        ResponseEntity<byte[]> resp = restTemplate.getForEntity(url, byte[].class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        HttpHeaders headers = resp.getHeaders();
        String contentType = headers.getFirst(HttpHeaders.CONTENT_TYPE);
        assertThat(contentType).isNotNull();
        // Expect the downloaded file to be served as text/plain
        assertThat(contentType).startsWith("text/plain");

        // Content-Length header should match the file size
        long contentLength = headers.getContentLength();
        assertThat(contentLength).isEqualTo(expected.length);

        // Body bytes should match the original file
        assertThat(resp.getBody()).isEqualTo(expected);
    }

    @Test
    void downloadMissingFileShouldReturn404() throws Exception {
        testDataSourceName = "Download Test Source";
        createDataSourceViaGraphQL(testDataSourceName, tempTestDir.toString());

        DataSourceInterface ds = dataSourceDao.findByNameAndType(testDataSourceName, DataSourceType.LOCAL_FILESYSTEM)
            .orElseThrow(() -> new RuntimeException("Datasource not found"));

        // Scan so folders/items are registered; we don't create the missing file
        scanner.scan(ds);
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);

        String encodedName = URLEncoder.encode(testDataSourceName, StandardCharsets.UTF_8).replace("+", "%20");
        String url = "http://localhost:" + port + "/content/local-filesystem/" + encodedName + "/subf/this-file-does-not-exist.txt";

        ResponseEntity<byte[]> resp = restTemplate.getForEntity(url, byte[].class);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void downloadShouldWorkWithSpacesInFileName() throws Exception {
        testDataSourceName = "Download Test Source";
        createDataSourceViaGraphQL(testDataSourceName, tempTestDir.toString());

        // Create a file with spaces in the name
        Path fileWithSpaces = tempTestDir.resolve("file with spaces.txt");
        String content = "This is a test file with spaces in the name.";
        Files.writeString(fileWithSpaces, content);

        DataSourceInterface ds = dataSourceDao.findByNameAndType(testDataSourceName, DataSourceType.LOCAL_FILESYSTEM)
            .orElseThrow(() -> new RuntimeException("Datasource not found"));

        // Scan and process NAME_AND_PATH so mime_type is captured in DB
        scanner.scan(ds);
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);

        byte[] expected = content.getBytes(StandardCharsets.UTF_8);

        String encodedName = URLEncoder.encode(testDataSourceName, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedFileName = URLEncoder.encode("file with spaces.txt", StandardCharsets.UTF_8).replace("+", "%20");
        String url = "http://localhost:" + port + "/content/local-filesystem/" + encodedName + "/" + encodedFileName;

        ResponseEntity<byte[]> resp = restTemplate.getForEntity(url, byte[].class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        HttpHeaders headers = resp.getHeaders();
        String contentType = headers.getFirst(HttpHeaders.CONTENT_TYPE);
        assertThat(contentType).isNotNull();
        // Expect the downloaded file to be served as text/plain
        assertThat(contentType).startsWith("text/plain");

        // Content-Length header should match the file size
        long contentLength = headers.getContentLength();
        assertThat(contentLength).isEqualTo(expected.length);

        // Body bytes should match the original file
        assertThat(resp.getBody()).isEqualTo(expected);
    }

    @Test
    void previewShouldReturnGeneratedThumbnail() throws Exception {
        testDataSourceName = "Preview Test Source";
        createDataSourceViaGraphQL(testDataSourceName, tempTestDir.toString());

        DataSourceInterface ds = dataSourceDao.findByNameAndType(testDataSourceName, DataSourceType.LOCAL_FILESYSTEM)
            .orElseThrow(() -> new RuntimeException("Datasource not found"));

        // Scan and process NAME_AND_PATH so mime_type is captured in DB
        scanner.scan(ds);
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);

        // Generate previews
        previewGenerator.generatePreviews();

        // File to preview: subf/beam.jpg in test share
        String encodedName = URLEncoder.encode(testDataSourceName, StandardCharsets.UTF_8).replace("+", "%20");
        String url = "http://localhost:" + port + "/preview/local-filesystem/" + encodedName + "/subf/beam.jpg";

        ResponseEntity<byte[]> resp = restTemplate.getForEntity(url, byte[].class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        HttpHeaders headers = resp.getHeaders();
        String contentType = headers.getFirst(HttpHeaders.CONTENT_TYPE);
        assertThat(contentType).isNotNull();
        // Expect the preview to be served as image/jpeg (thumbnail format)
        assertThat(contentType).startsWith("image/jpeg");

        // Body should be present (preview bytes)
        byte[] previewBytes = resp.getBody();
        assertThat(previewBytes).isNotNull();
        assertThat(previewBytes.length).isGreaterThan(0);
        
        logger.info("Preview generated with {} bytes", previewBytes.length);
    }

    @Test
    void previewMissingFileShouldReturn404() throws Exception {
        testDataSourceName = "Preview Test Source";
        createDataSourceViaGraphQL(testDataSourceName, tempTestDir.toString());

        DataSourceInterface ds = dataSourceDao.findByNameAndType(testDataSourceName, DataSourceType.LOCAL_FILESYSTEM)
            .orElseThrow(() -> new RuntimeException("Datasource not found"));

        // Scan so folders/items are registered; we don't create the missing file
        scanner.scan(ds);
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);

        String encodedName = URLEncoder.encode(testDataSourceName, StandardCharsets.UTF_8).replace("+", "%20");
        String url = "http://localhost:" + port + "/preview/local-filesystem/" + encodedName + "/subf/this-file-does-not-exist.jpg";

        ResponseEntity<byte[]> resp = restTemplate.getForEntity(url, byte[].class);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    private String createUserAndLogin() throws Exception {
        AuthenticationService.UserCreationResult result =
            authenticationService.createUser("testuser", UserLevel.READER, null);
        String recoveryCode = result.recoveryCodes().get(0);
        String loginMutation = """
            mutation {
                loginWithRecoveryCode(input: {
                    nickname: "testuser"
                    recoveryCode: "%s"
                }) { token }
            }
            """.formatted(recoveryCode);
        GraphQLTestClient client = new GraphQLTestClient(port);
        tools.jackson.databind.JsonNode response =
            client.executeAndParse(loginMutation);
        return response.path("data").path("loginWithRecoveryCode").path("token").asString();
    }

    private String createApiUserAndLogin() throws Exception {
        // Need an admin human user first
        AuthenticationService.UserCreationResult admin =
            authenticationService.createUser("admin", UserLevel.ADMIN, null);
        String recoveryCode = admin.recoveryCodes().get(0);

        GraphQLTestClient client = new GraphQLTestClient(port);

        String loginMutation = """
            mutation {
                loginWithRecoveryCode(input: {
                    nickname: "admin"
                    recoveryCode: "%s"
                }) { token }
            }
            """.formatted(recoveryCode);
        String adminToken = client.executeAndParse(loginMutation)
            .path("data").path("loginWithRecoveryCode").path("token").asString();

        String createApiUserMutation = """
            mutation {
                createApiUser(token: "%s", input: {
                    nickname: "api-reader"
                    userLevel: READER
                    expirationDays: 30
                }) { keyValue }
            }
            """.formatted(adminToken);
        String keyValue = client.executeAndParse(createApiUserMutation)
            .path("data").path("createApiUser").path("keyValue").asString();

        String apiLoginMutation = """
            mutation {
                loginWithApiKey(input: {
                    nickname: "api-reader"
                    keyValue: "%s"
                }) { token }
            }
            """.formatted(keyValue);
        return client.executeAndParse(apiLoginMutation)
            .path("data").path("loginWithApiKey").path("token").asString();
    }

    @Test
    void downloadRequiresTokenWhenAuthEnabled() throws Exception {
        testDataSourceName = "Auth Test Source";
        createDataSourceViaGraphQL(testDataSourceName, tempTestDir.toString());

        // Create a user so authentication is required
        authenticationService.createUser("admin", UserLevel.ADMIN, null);

        String encodedName = URLEncoder.encode(testDataSourceName, StandardCharsets.UTF_8).replace("+", "%20");
        String url = "http://localhost:" + port + "/content/local-filesystem/" + encodedName + "/subf/text.txt";

        ResponseEntity<byte[]> resp = restTemplate.getForEntity(url, byte[].class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void downloadWithInvalidTokenReturns401() throws Exception {
        testDataSourceName = "Auth Test Source";
        createDataSourceViaGraphQL(testDataSourceName, tempTestDir.toString());

        authenticationService.createUser("admin", UserLevel.ADMIN, null);

        String encodedName = URLEncoder.encode(testDataSourceName, StandardCharsets.UTF_8).replace("+", "%20");
        String url = "http://localhost:" + port + "/content/local-filesystem/" + encodedName + "/subf/text.txt?t=invalid-token";

        ResponseEntity<byte[]> resp = restTemplate.getForEntity(url, byte[].class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void downloadWithValidTokenSucceeds() throws Exception {
        testDataSourceName = "Auth Token Source";
        createDataSourceViaGraphQL(testDataSourceName, tempTestDir.toString());

        DataSourceInterface ds = dataSourceDao.findByNameAndType(testDataSourceName, DataSourceType.LOCAL_FILESYSTEM)
            .orElseThrow();
        scanner.scan(ds);
        int processed;
        do { processed = treatmentProcessor.processTreatments(); } while (processed > 0);

        String token = createUserAndLogin();

        String encodedName = URLEncoder.encode(testDataSourceName, StandardCharsets.UTF_8).replace("+", "%20");
        String url = "http://localhost:" + port + "/content/local-filesystem/" + encodedName + "/subf/text.txt?t=" + token;

        ResponseEntity<byte[]> resp = restTemplate.getForEntity(url, byte[].class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void previewRequiresTokenWhenAuthEnabled() throws Exception {
        testDataSourceName = "Auth Preview Source";
        createDataSourceViaGraphQL(testDataSourceName, tempTestDir.toString());

        authenticationService.createUser("admin", UserLevel.ADMIN, null);

        String encodedName = URLEncoder.encode(testDataSourceName, StandardCharsets.UTF_8).replace("+", "%20");
        String url = "http://localhost:" + port + "/preview/local-filesystem/" + encodedName + "/subf/beam.jpg";

        ResponseEntity<byte[]> resp = restTemplate.getForEntity(url, byte[].class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void previewWithValidTokenSucceeds() throws Exception {
        testDataSourceName = "Auth Preview Token Source";
        createDataSourceViaGraphQL(testDataSourceName, tempTestDir.toString());

        DataSourceInterface ds = dataSourceDao.findByNameAndType(testDataSourceName, DataSourceType.LOCAL_FILESYSTEM)
            .orElseThrow();
        scanner.scan(ds);
        int processed;
        do { processed = treatmentProcessor.processTreatments(); } while (processed > 0);
        previewGenerator.generatePreviews();

        String token = createUserAndLogin();

        String encodedName = URLEncoder.encode(testDataSourceName, StandardCharsets.UTF_8).replace("+", "%20");
        String url = "http://localhost:" + port + "/preview/local-filesystem/" + encodedName + "/subf/beam.jpg?t=" + token;

        ResponseEntity<byte[]> resp = restTemplate.getForEntity(url, byte[].class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().length).isGreaterThan(0);
    }

    @Test
    void downloadWithApiSessionTokenSucceeds() throws Exception {
        testDataSourceName = "Api Token Download Source";
        createDataSourceViaGraphQL(testDataSourceName, tempTestDir.toString());

        DataSourceInterface ds = dataSourceDao.findByNameAndType(testDataSourceName, DataSourceType.LOCAL_FILESYSTEM)
            .orElseThrow();
        scanner.scan(ds);
        int processed;
        do { processed = treatmentProcessor.processTreatments(); } while (processed > 0);

        String apiSessionToken = createApiUserAndLogin();

        String encodedName = URLEncoder.encode(testDataSourceName, StandardCharsets.UTF_8).replace("+", "%20");
        String url = "http://localhost:" + port + "/content/local-filesystem/" + encodedName + "/subf/text.txt?t=" + apiSessionToken;

        ResponseEntity<byte[]> resp = restTemplate.getForEntity(url, byte[].class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isEqualTo(Files.readAllBytes(tempTestDir.resolve("subf").resolve("text.txt")));
    }

    @Test
    void downloadShouldWorkWithCommaInFolderName() throws Exception {
        testDataSourceName = "Comma Folder Test Source";
        createDataSourceViaGraphQL(testDataSourceName, tempTestDir.toString());

        // Create a folder whose name contains a comma
        Path commaFolder = tempTestDir.resolve("Rust Servers, Services, and Apps");
        Files.createDirectories(commaFolder);
        String fileContent = "chapter content for comma test";
        Files.writeString(commaFolder.resolve("chapter1.pdf"), fileContent);

        DataSourceInterface ds = dataSourceDao.findByNameAndType(testDataSourceName, DataSourceType.LOCAL_FILESYSTEM)
            .orElseThrow(() -> new RuntimeException("Datasource not found"));

        scanner.scan(ds);
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);

        byte[] expected = fileContent.getBytes(StandardCharsets.UTF_8);
        String encodedName = URLEncoder.encode(testDataSourceName, StandardCharsets.UTF_8).replace("+", "%20");

        // Single-encode the folder name — the controller double-decodes the relative path,
        // and TestRestTemplate does not re-encode an already-encoded URL string.
        String folderName = "Rust Servers, Services, and Apps";
        String encodedFolder = URLEncoder.encode(folderName, StandardCharsets.UTF_8).replace("+", "%20");
        String encodedFileName = URLEncoder.encode("chapter1.pdf", StandardCharsets.UTF_8).replace("+", "%20");

        String url = "http://localhost:" + port + "/content/local-filesystem/" + encodedName
            + "/" + encodedFolder + "/" + encodedFileName;

        ResponseEntity<byte[]> resp = restTemplate.getForEntity(url, byte[].class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isEqualTo(expected);
    }
}
