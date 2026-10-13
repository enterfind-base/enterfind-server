package enterfind.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import tools.jackson.databind.ObjectMapper;

import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.TreatmentDao;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.LocalFileSystemDataSource;
import enterfind.server.scan.Scanner;
import enterfind.server.scan.TreatmentProcessor;
import enterfind.server.scan.Treatments;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class GraphQLIntegrationTest extends BaseDatabaseTest {

    private static final Logger logger = LoggerFactory.getLogger(GraphQLIntegrationTest.class);

    @LocalServerPort
    private int port;

    @Autowired
    private Scanner scanner;
    @Autowired
    private TreatmentProcessor treatmentProcessor;
    @Autowired
    private DataSourceDao dataSourceDao;
    @Autowired
    private TreatmentDao treatmentDao;
    @Autowired
    private ObjectMapper objectMapper;

    private Path tempTestDir;
    private LocalFileSystemDataSource testDataSource;
    private GraphQLTestClient graphQLClient;

    @Override
    protected void setUp() {
        // Override to prevent database wipe before each test
        // since we're using @BeforeAll for one-time setup
    }

    @BeforeAll
    void setUpTestData() throws Exception {
        wipeDatabase();  // Clean once before all tests
        graphQLClient = new GraphQLTestClient(port);
        tempTestDir = Files.createTempDirectory("graphql-integration-test-");
        TestFileUtils.copyTestShareToTempDir(tempTestDir);
        testDataSource = new LocalFileSystemDataSource(
                null,
                "Test Data Source",
                "0 0 * * *",
                LocalDateTime.now(),
                null,
                DataSourceStatus.IDLE,
                tempTestDir.toString(),
                null);
        testDataSource = (LocalFileSystemDataSource) dataSourceDao.create(testDataSource);
        // NAME_AND_PATH is auto-added on datasource creation, add CONTENT treatment for search tests
        treatmentDao.addTreatment(testDataSource.id(), Treatments.CONTENT, objectMapper.createObjectNode());
        scanner.scan(testDataSource);
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);
    }

    @AfterAll
    void tearDown() throws IOException {
        if (tempTestDir != null && Files.exists(tempTestDir)) {
            TestFileUtils.deleteDirectoryRecursively(tempTestDir);
        }
    }

    @Test
    public void testAboutQuery() {
        String query = "{ about { name version description environment } }";
        ResponseEntity<String> response = graphQLClient.execute(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("enterfind-server");
        assertThat(response.getBody()).contains("alpha");
        logger.info("GraphQL Response: {}", response.getBody());
    }

    @Test
    public void testSearchQueryWithMetadata() {
        String query = "{ search(skip: 0, howmany: 10) { offset howmany totalHits items { name folder mimeType dateCreated dateModified downloadLink } } }";
        ResponseEntity<String> response = graphQLClient.execute(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("offset");
        assertThat(response.getBody()).contains("howmany");
        assertThat(response.getBody()).contains("totalHits");
        assertThat(response.getBody()).contains("items");
        assertThat(response.getBody()).contains("mimeType");
        assertThat(response.getBody()).contains("downloadLink");
        assertThat(response.getBody()).contains("/content/");
        logger.info("Search GraphQL Response: {}", response.getBody());
    }
}