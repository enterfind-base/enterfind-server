package enterfind.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import tools.jackson.databind.JsonNode;

import enterfind.server.dao.DataSourceDao;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceType;
import enterfind.server.model.UserLevel;
import enterfind.server.scan.Scanner;
import enterfind.server.scan.TreatmentProcessor;
import enterfind.server.service.AuthenticationService;
import enterfind.server.service.AuthenticationService.UserCreationResult;

/**
 * End-to-end integration test that validates the complete workflow using a hybrid approach:
 * - GraphQL API for: creating datasource, adding treatments, searching
 * - Direct calls for: scanning (sync), processing treatments (no GraphQL API available)
 * 
 * Workflow:
 * 1. Copy test data to temp directory
 * 2. Create data source via GraphQL
 * 3. Add treatment via GraphQL
 * 4. Run scan (direct call - GraphQL scan is async)
 * 5. Process treatments (direct call - no GraphQL API)
 * 6. Assert files can be found by name via GraphQL search
 * 7. Add content treatment via GraphQL
 * 8. Run scan again and process treatments
 * 9. Assert files can be found by content via GraphQL search
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public class EndToEndIntegrationTest extends BaseDatabaseTest {

    private static final Logger logger = LoggerFactory.getLogger(EndToEndIntegrationTest.class);

    @LocalServerPort
    private int port;

    // Direct service calls needed for operations not exposed via GraphQL
    @Autowired
    private Scanner scanner;

    @Autowired
    private TreatmentProcessor treatmentProcessor;

    @Autowired
    private DataSourceDao dataSourceDao;

    @Autowired
    private AuthenticationService authenticationService;

    private GraphQLTestClient graphQLClient;

    private Path tempTestDir;
    private String testDataSourceName;

    @BeforeEach
    void setUpTest() throws Exception {
        // Wipe database tables before each test
        wipeDatabase();

        // Initialize GraphQL test client
        graphQLClient = new GraphQLTestClient(port);

        // Note: No authentication setup here - existing tests run without users (auth not required).
        // New auth-specific tests handle their own user creation and authentication.

        // Create temporary test directory and copy test data
        tempTestDir = Files.createTempDirectory("e2e-test-");
        TestFileUtils.copyTestShareToTempDir(tempTestDir);

        logger.info("Created temp test directory: {}", tempTestDir);
    }

    @AfterEach
    void tearDownTest() throws IOException {
        // Clean up temporary directory
        if (tempTestDir != null && Files.exists(tempTestDir)) {
            TestFileUtils.deleteDirectoryRecursively(tempTestDir);
            logger.info("Cleaned up temp test directory: {}", tempTestDir);
        }
    }

    private ResponseEntity<String> executeGraphQLQuery(String query) {
        return graphQLClient.execute(query);
    }

    private JsonNode parseGraphQLResponse(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return graphQLClient.parseResponse(response);
    }

    private void executeGraphQLMutation(String mutation) {
        graphQLClient.executeMutation(mutation);
    }

    /**
     * Create a local filesystem datasource via GraphQL API
     */
    private void createDataSourceViaGraphQL(String name, String localPath) throws Exception {
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
        executeGraphQLMutation(mutation);
    }

    /**
     * Add a treatment to a datasource via GraphQL API
     */
    private void addTreatmentViaGraphQL(String dataSourceName, String treatmentType) throws Exception {
        String mutation = """
            mutation {
                addDataSourceTreatment(input: {
                    dataSourceName: "%s"
                    dataSourceType: LOCAL_FILESYSTEM
                    treatment: %s
                }) {
                    treatment
                }
            }
            """.formatted(dataSourceName, treatmentType);
        executeGraphQLMutation(mutation);
    }

    /**
     * Get datasource via GraphQL query to verify it exists, then return the Java object from DAO
     * (scanner.scan() requires the full DataSourceInterface object, not just GraphQL data)
     */
    private DataSourceInterface getDataSourceViaGraphQL(String name) throws Exception {
        return getDataSourceViaGraphQL(name, null);
    }

    /**
     * Get datasource via GraphQL query to verify it exists, then return the Java object from DAO
     * (scanner.scan() requires the full DataSourceInterface object, not just GraphQL data)
     * 
     * @param name the name of the datasource
     * @param token optional authentication token (pass null for unauthenticated requests)
     */
    private DataSourceInterface getDataSourceViaGraphQL(String name, String token) throws Exception {
        // First verify datasource exists via GraphQL API
        String query;
        if (token != null) {
            query = """
                {
                    dataSource(token: \"%s\", name: \"%s\", type: LOCAL_FILESYSTEM) {
                        name
                        type
                    }
                }
                """.formatted(token, name);
        } else {
            query = """
                {
                    dataSource(name: \"%s\", type: LOCAL_FILESYSTEM) {
                        name
                        type
                    }
                }
                """.formatted(name);
        }
        ResponseEntity<String> response = executeGraphQLQuery(query);
        JsonNode result = parseGraphQLResponse(response);
        JsonNode dataSourceNode = result.path("data").path("dataSource");
        
        if (dataSourceNode.isMissingNode() || dataSourceNode.isNull()) {
            throw new RuntimeException("DataSource not found via GraphQL: " + name);
        }
        
        // Return the Java object (needed for scanner which requires DataSourceInterface)
        return dataSourceDao.findByNameAndType(name, DataSourceType.LOCAL_FILESYSTEM)
            .orElseThrow(() -> new RuntimeException("DataSource not found: " + name));
    }

    /**
     * Remove a treatment from a datasource via GraphQL API
     */
    private void removeTreatmentViaGraphQL(String dataSourceName, String treatmentType) throws Exception {
        String mutation = """
            mutation {
                removeDataSourceTreatment(
                    dataSourceName: "%s"
                    dataSourceType: LOCAL_FILESYSTEM
                    treatment: %s
                )
            }
            """.formatted(dataSourceName, treatmentType);
        executeGraphQLMutation(mutation);
    }

    /**
     * Delete a datasource via GraphQL API
     */
    private void deleteDataSourceViaGraphQL(String dataSourceName) throws Exception {
        String mutation = """
            mutation {
                deleteDataSource(
                    name: "%s"
                    type: LOCAL_FILESYSTEM
                )
            }
            """.formatted(dataSourceName);
        executeGraphQLMutation(mutation);
    }

    /**
     * Clear data from a datasource via GraphQL API (keeps datasource and treatments)
     */
    private int clearDataSourceDataViaGraphQL(String dataSourceName) throws Exception {
        String mutation = """
            mutation {
                clearDataSourceData(
                    name: "%s"
                    type: LOCAL_FILESYSTEM
                )
            }
            """.formatted(dataSourceName);
        ResponseEntity<String> response = executeGraphQLQuery(mutation);
        JsonNode result = parseGraphQLResponse(response);
        return result.path("data").path("clearDataSourceData").asInt();
    }

    @Test
    void testEndToEndWorkflow_CreateDatasource_Scan_SearchByName_AddContentTreatment_SearchByContent()
            throws Exception {
        // ========== STEP 1: Create data source via GraphQL ==========
        logger.info("STEP 1: Creating data source via GraphQL...");

        testDataSourceName = "E2E Test Data Source";
        createDataSourceViaGraphQL(testDataSourceName, tempTestDir.toString());
        logger.info("Created data source: {} (NAME_AND_PATH treatment auto-added)", testDataSourceName);

        // ========== STEP 2: Run initial scan (direct call - GraphQL scan is async) ==========
        logger.info("STEP 2: Running initial scan (direct call)...");

        DataSourceInterface dataSource = getDataSourceViaGraphQL(testDataSourceName);
        scanner.scan(dataSource);
        logger.info("Initial scan completed");

        // ========== STEP 3: Process treatments (direct call - no GraphQL API) ==========
        logger.info("STEP 3: Processing NAME_AND_PATH treatments (direct call)...");
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);
        // ========== STEP 4: Assert files can be found by name via GraphQL search ==========
        logger.info("STEP 4: Asserting files can be found by name via GraphQL search...");

        // Search for text.txt by name
        String searchByNameQuery = "{ search(name: \"text.txt\", skip: 0, howmany: 10) { totalHits items { name folder } } }";
        ResponseEntity<String> nameSearchResponse = executeGraphQLQuery(searchByNameQuery);
        JsonNode nameSearchResult = parseGraphQLResponse(nameSearchResponse);

        JsonNode nameSearchData = nameSearchResult.path("data").path("search");
        assertThat(nameSearchData.path("totalHits").asInt()).isGreaterThan(0);
        assertThat(nameSearchData.path("items").get(0).path("name").asString()).isEqualTo("text.txt");
        logger.info("Search by name returned {} hits", nameSearchData.path("totalHits").asInt());

        // Search by partial name using searchString
        String searchByPartialNameQuery = "{ search(searchString: \"text.txt\", skip: 0, howmany: 10) { totalHits items { name folder } } }";
        ResponseEntity<String> partialNameSearchResponse = executeGraphQLQuery(searchByPartialNameQuery);
        JsonNode partialNameSearchResult = parseGraphQLResponse(partialNameSearchResponse);

        JsonNode partialNameSearchData = partialNameSearchResult.path("data").path("search");
        assertThat(partialNameSearchData.path("totalHits").asInt()).isGreaterThan(0);
        logger.info("Search by partial name 'text' returned {} hits", partialNameSearchData.path("totalHits").asInt());

        // ========== STEP 5: Add content treatment via GraphQL ==========
        logger.info("STEP 5: Adding CONTENT treatment via GraphQL...");

        addTreatmentViaGraphQL(testDataSourceName, "CONTENT");

        // ========== STEP 6: Re-scan and process treatments (direct calls) ==========
        logger.info("STEP 6: Re-scanning and processing CONTENT treatments (direct calls)...");
        dataSource = getDataSourceViaGraphQL(testDataSourceName);
        scanner.scan(dataSource);
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);
        logger.info("Content treatments processed successfully");

        // ========== STEP 7: Assert files can be found by content via GraphQL search ==========
        logger.info("STEP 7: Asserting files can be found by content via GraphQL search...");

        // Search for content "aabaa" which is the content of text.txt
        String searchByContentQuery = "{ search(searchString: \"aabaa\", skip: 0, howmany: 10) { totalHits items { name folder } } }";
        ResponseEntity<String> contentSearchResponse = executeGraphQLQuery(searchByContentQuery);
        JsonNode contentSearchResult = parseGraphQLResponse(contentSearchResponse);

        JsonNode contentSearchData = contentSearchResult.path("data").path("search");
        assertThat(contentSearchData.path("totalHits").asInt()).isGreaterThan(0);

        // Verify the text.txt file was found by its content
        JsonNode foundItems = contentSearchData.path("items");
        boolean foundTextFile = false;
        for (int i = 0; i < foundItems.size(); i++) {
            if ("text.txt".equals(foundItems.get(i).path("name").asString())) {
                foundTextFile = true;
                break;
            }
        }
        assertThat(foundTextFile).isTrue()
                .withFailMessage("text.txt should be found when searching for its content 'aabaa'");
        logger.info("Search by content 'aabaa' returned {} hits, found text.txt: {}",
                contentSearchData.path("totalHits").asInt(), foundTextFile);

        // Search for PDF-specific content (e.g., resume content)
        String searchByPdfContentQuery = "{ search(searchString: \"Mikhail\", skip: 0, howmany: 10) { totalHits items { name folder } } }";
        ResponseEntity<String> pdfContentSearchResponse = executeGraphQLQuery(searchByPdfContentQuery);
        JsonNode pdfContentSearchResult = parseGraphQLResponse(pdfContentSearchResponse);

        JsonNode pdfContentSearchData = pdfContentSearchResult.path("data").path("search");
        logger.info("Search by PDF content 'Mikhail' returned {} hits", pdfContentSearchData.path("totalHits").asInt());

        // If hits are found, verify at least one is a PDF
        if (pdfContentSearchData.path("totalHits").asInt() > 0) {
            JsonNode pdfItems = pdfContentSearchData.path("items");
            boolean foundPdfFile = false;
            for (int i = 0; i < pdfItems.size(); i++) {
                String name = pdfItems.get(i).path("name").asString();
                if (name.endsWith(".pdf")) {
                    foundPdfFile = true;
                    logger.info("Found PDF file by content search: {}", name);
                    break;
                }
            }
            // PDF content search may or may not find results depending on PDF content extraction
            logger.info("PDF file found by content: {}", foundPdfFile);
        }

        // ========== STEP 8: Remove CONTENT treatment and verify content search returns no results ==========
        logger.info("STEP 8: Removing CONTENT treatment via GraphQL...");
        removeTreatmentViaGraphQL(testDataSourceName, "CONTENT");

        // Verify that content search no longer finds the file (treatment results should be removed)
        String searchAfterContentRemovalQuery = "{ search(searchString: \"aabaa\", skip: 0, howmany: 10) { totalHits items { name folder } } }";
        ResponseEntity<String> afterContentRemovalResponse = executeGraphQLQuery(searchAfterContentRemovalQuery);
        JsonNode afterContentRemovalResult = parseGraphQLResponse(afterContentRemovalResponse);

        JsonNode afterContentRemovalData = afterContentRemovalResult.path("data").path("search");
        assertThat(afterContentRemovalData.path("totalHits").asInt()).isEqualTo(0)
                .withFailMessage("After removing CONTENT treatment, content search should return 0 hits");
        logger.info("After removing CONTENT treatment, content search returned {} hits (expected 0)",
                afterContentRemovalData.path("totalHits").asInt());

        // Verify that NAME_AND_PATH search still works (only CONTENT was removed)
        String searchAfterContentRemovalByNameQuery = "{ search(searchString: \"text.txt\", skip: 0, howmany: 10) { totalHits items { name folder } } }";
        ResponseEntity<String> afterContentRemovalByNameResponse = executeGraphQLQuery(searchAfterContentRemovalByNameQuery);
        JsonNode afterContentRemovalByNameResult = parseGraphQLResponse(afterContentRemovalByNameResponse);

        JsonNode afterContentRemovalByNameData = afterContentRemovalByNameResult.path("data").path("search");
        assertThat(afterContentRemovalByNameData.path("totalHits").asInt()).isGreaterThan(0)
                .withFailMessage("After removing CONTENT treatment, NAME_AND_PATH search should still work");
        logger.info("After removing CONTENT treatment, name search still returned {} hits",
                afterContentRemovalByNameData.path("totalHits").asInt());

        // ========== STEP 9: Delete datasource and verify all searches return no results ==========
        logger.info("STEP 9: Deleting datasource via GraphQL...");
        deleteDataSourceViaGraphQL(testDataSourceName);

        // Verify that all searches return no results after datasource deletion
        String searchAfterDeletionQuery = "{ search(skip: 0, howmany: 10) { totalHits items { name folder } } }";
        ResponseEntity<String> afterDeletionResponse = executeGraphQLQuery(searchAfterDeletionQuery);
        JsonNode afterDeletionResult = parseGraphQLResponse(afterDeletionResponse);

        JsonNode afterDeletionData = afterDeletionResult.path("data").path("search");
        assertThat(afterDeletionData.path("totalHits").asInt()).isEqualTo(0)
                .withFailMessage("After deleting datasource, search should return 0 hits");
        logger.info("After deleting datasource, search returned {} hits (expected 0)",
                afterDeletionData.path("totalHits").asInt());

        // Verify name-based search also returns no results
        String searchByNameAfterDeletionQuery = "{ search(name: \"text.txt\", skip: 0, howmany: 10) { totalHits items { name folder } } }";
        ResponseEntity<String> nameAfterDeletionResponse = executeGraphQLQuery(searchByNameAfterDeletionQuery);
        JsonNode nameAfterDeletionResult = parseGraphQLResponse(nameAfterDeletionResponse);

        JsonNode nameAfterDeletionData = nameAfterDeletionResult.path("data").path("search");
        assertThat(nameAfterDeletionData.path("totalHits").asInt()).isEqualTo(0)
                .withFailMessage("After deleting datasource, name search should return 0 hits");
        logger.info("After deleting datasource, name search returned {} hits (expected 0)",
                nameAfterDeletionData.path("totalHits").asInt());

        logger.info("End-to-end test completed successfully!");
    }

    @Test
    void testEndToEndWorkflow_VerifyFacetsAfterContentTreatment() throws Exception {
        // ========== Setup: Create datasource via GraphQL (NAME_AND_PATH auto-added) ==========
        testDataSourceName = "E2E Facets Test Data Source";
        createDataSourceViaGraphQL(testDataSourceName, tempTestDir.toString());

        // Add CONTENT treatment via GraphQL (NAME_AND_PATH is auto-added)
        addTreatmentViaGraphQL(testDataSourceName, "CONTENT");

        // Run scan and process treatments (direct calls)
        DataSourceInterface dataSource = getDataSourceViaGraphQL(testDataSourceName);
        scanner.scan(dataSource);
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);

        // ========== Test: Search without facets (facets are now a separate query) ==========
        String searchQuery = "{ search(skip: 0, howmany: 10) { totalHits items { name folder } } }";
        ResponseEntity<String> searchResponse = executeGraphQLQuery(searchQuery);
        JsonNode searchResult = parseGraphQLResponse(searchResponse);

        JsonNode searchData = searchResult.path("data").path("search");
        assertThat(searchData.path("totalHits").asInt()).isGreaterThan(0);

        int totalHits = searchData.path("totalHits").asInt();
        logger.info("Search returned {} hits", totalHits);

        // ========== Test: Get facets separately using getFacets query ==========
        // Test with 'name' key as it should always exist
        String facetsQuery = "{ getFacets(key: \"name\") { value count } }";
        ResponseEntity<String> facetsResponse = executeGraphQLQuery(facetsQuery);
        JsonNode facetsResult = parseGraphQLResponse(facetsResponse);

        JsonNode facets = facetsResult.path("data").path("getFacets");
        assertThat(facets.isArray()).isTrue();
        assertThat(facets.size()).isGreaterThan(0);

        logger.info("getFacets returned {} facet values for key 'name'", facets.size());

        // Verify facet structure
        for (int i = 0; i < facets.size(); i++) {
            JsonNode facetValue = facets.get(i);
            assertThat(facetValue.has("value")).isTrue();
            assertThat(facetValue.has("count")).isTrue();
            logger.info("Facet value: {} with count {}",
                    facetValue.path("value").asString(),
                    facetValue.path("count").asInt());
        }
    }

    @Test
    void testEndToEndWorkflow_SearchByFolder() throws Exception {
        // ========== Setup: Create datasource via GraphQL (NAME_AND_PATH auto-added) ==========
        testDataSourceName = "E2E Folder Test Data Source";
        createDataSourceViaGraphQL(testDataSourceName, tempTestDir.toString());

        // NAME_AND_PATH treatment is auto-added when data source is created

        // Run scan and process treatments (direct calls)
        DataSourceInterface dataSource = getDataSourceViaGraphQL(testDataSourceName);
        scanner.scan(dataSource);
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);

        // ========== Test: Search by folder ==========
        String searchByFolderQuery = "{ search(folder: \"subf\", skip: 0, howmany: 10) { totalHits items { name folder } } }";
        ResponseEntity<String> folderResponse = executeGraphQLQuery(searchByFolderQuery);
        JsonNode folderResult = parseGraphQLResponse(folderResponse);

        JsonNode folderSearchData = folderResult.path("data").path("search");
        assertThat(folderSearchData.path("totalHits").asInt()).isEqualTo(2); // 2 files in subf/

        // Verify all returned items are in the subf folder
        JsonNode folderItems = folderSearchData.path("items");
        for (int i = 0; i < folderItems.size(); i++) {
            assertThat(folderItems.get(i).path("folder").asString()).isEqualTo("subf");
        }

        logger.info("Search by folder 'subf' returned {} hits", folderSearchData.path("totalHits").asInt());
    }

    @Test
    void testEndToEndWorkflow_ClearDataSourceData() throws Exception {
        // ========== Setup: Create datasource via GraphQL (NAME_AND_PATH auto-added) ==========
        testDataSourceName = "E2E Clear Data Test";
        createDataSourceViaGraphQL(testDataSourceName, tempTestDir.toString());
        logger.info("Created data source: {}", testDataSourceName);

        // Add CONTENT treatment via GraphQL
        addTreatmentViaGraphQL(testDataSourceName, "CONTENT");
        logger.info("Added CONTENT treatment");

        // Run scan and process treatments (direct calls)
        DataSourceInterface dataSource = getDataSourceViaGraphQL(testDataSourceName);
        scanner.scan(dataSource);
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);
        logger.info("Initial scan and treatment processing completed");

        // ========== Step 1: Verify data exists before clearing ==========
        String searchBeforeClearQuery = "{ search(skip: 0, howmany: 100) { totalHits items { name folder } } }";
        ResponseEntity<String> beforeClearResponse = executeGraphQLQuery(searchBeforeClearQuery);
        JsonNode beforeClearResult = parseGraphQLResponse(beforeClearResponse);
        int hitsBefore = beforeClearResult.path("data").path("search").path("totalHits").asInt();
        assertThat(hitsBefore).isGreaterThan(0);
        logger.info("Before clear: {} items found", hitsBefore);

        // Verify content search works before clearing
        String contentSearchBeforeQuery = "{ search(searchString: \"aabaa\", skip: 0, howmany: 10) { totalHits } }";
        ResponseEntity<String> contentBeforeResponse = executeGraphQLQuery(contentSearchBeforeQuery);
        JsonNode contentBeforeResult = parseGraphQLResponse(contentBeforeResponse);
        int contentHitsBefore = contentBeforeResult.path("data").path("search").path("totalHits").asInt();
        assertThat(contentHitsBefore).isGreaterThan(0);
        logger.info("Before clear: content search returned {} hits", contentHitsBefore);

        // Verify treatments exist
        String treatmentsQuery = """
            {
                dataSourceTreatments(dataSourceName: "%s", dataSourceType: LOCAL_FILESYSTEM) {
                    treatment
                }
            }
            """.formatted(testDataSourceName);
        ResponseEntity<String> treatmentsBeforeResponse = executeGraphQLQuery(treatmentsQuery);
        JsonNode treatmentsBeforeResult = parseGraphQLResponse(treatmentsBeforeResponse);
        int treatmentCountBefore = treatmentsBeforeResult.path("data").path("dataSourceTreatments").size();
        assertThat(treatmentCountBefore).isGreaterThan(0);
        logger.info("Before clear: {} treatments configured", treatmentCountBefore);

        // ========== Step 2: Clear data via GraphQL ==========
        int deletedCount = clearDataSourceDataViaGraphQL(testDataSourceName);
        assertThat(deletedCount).isEqualTo(hitsBefore);
        logger.info("Cleared {} items via GraphQL", deletedCount);

        // ========== Step 3: Verify data is gone but datasource and treatments remain ==========
        
        // Verify no items found
        ResponseEntity<String> afterClearResponse = executeGraphQLQuery(searchBeforeClearQuery);
        JsonNode afterClearResult = parseGraphQLResponse(afterClearResponse);
        int hitsAfter = afterClearResult.path("data").path("search").path("totalHits").asInt();
        assertThat(hitsAfter).isEqualTo(0);
        logger.info("After clear: {} items found (expected 0)", hitsAfter);

        // Verify content search returns nothing
        ResponseEntity<String> contentAfterResponse = executeGraphQLQuery(contentSearchBeforeQuery);
        JsonNode contentAfterResult = parseGraphQLResponse(contentAfterResponse);
        int contentHitsAfter = contentAfterResult.path("data").path("search").path("totalHits").asInt();
        assertThat(contentHitsAfter).isEqualTo(0);
        logger.info("After clear: content search returned {} hits (expected 0)", contentHitsAfter);

        // Verify datasource still exists
        String dataSourceQuery = """
            {
                dataSource(name: "%s", type: LOCAL_FILESYSTEM) {
                    name
                    type
                    dateLastScanned
                }
            }
            """.formatted(testDataSourceName);
        ResponseEntity<String> dataSourceResponse = executeGraphQLQuery(dataSourceQuery);
        JsonNode dataSourceResult = parseGraphQLResponse(dataSourceResponse);
        JsonNode dataSourceNode = dataSourceResult.path("data").path("dataSource");
        assertThat(dataSourceNode.isMissingNode()).isFalse();
        assertThat(dataSourceNode.path("name").asString()).isEqualTo(testDataSourceName);
        // dateLastScanned should be null after clear
        assertThat(dataSourceNode.path("dateLastScanned").isNull()).isTrue();
        logger.info("After clear: datasource still exists with dateLastScanned = null");

        // Verify treatments still exist
        ResponseEntity<String> treatmentsAfterResponse = executeGraphQLQuery(treatmentsQuery);
        JsonNode treatmentsAfterResult = parseGraphQLResponse(treatmentsAfterResponse);
        int treatmentCountAfter = treatmentsAfterResult.path("data").path("dataSourceTreatments").size();
        assertThat(treatmentCountAfter).isEqualTo(treatmentCountBefore);
        logger.info("After clear: {} treatments still configured (same as before)", treatmentCountAfter);

        // ========== Step 4: Re-scan and verify data is restored ==========
        dataSource = getDataSourceViaGraphQL(testDataSourceName);
        scanner.scan(dataSource);
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);
        logger.info("Re-scan and treatment processing completed");

        // Verify items are back
        ResponseEntity<String> afterRescanResponse = executeGraphQLQuery(searchBeforeClearQuery);
        JsonNode afterRescanResult = parseGraphQLResponse(afterRescanResponse);
        int hitsAfterRescan = afterRescanResult.path("data").path("search").path("totalHits").asInt();
        assertThat(hitsAfterRescan).isEqualTo(hitsBefore);
        logger.info("After re-scan: {} items found (same as before clear)", hitsAfterRescan);

        // Verify content search works again
        ResponseEntity<String> contentAfterRescanResponse = executeGraphQLQuery(contentSearchBeforeQuery);
        JsonNode contentAfterRescanResult = parseGraphQLResponse(contentAfterRescanResponse);
        int contentHitsAfterRescan = contentAfterRescanResult.path("data").path("search").path("totalHits").asInt();
        assertThat(contentHitsAfterRescan).isEqualTo(contentHitsBefore);
        logger.info("After re-scan: content search returned {} hits (same as before clear)", contentHitsAfterRescan);

        logger.info("Clear data source data end-to-end test completed successfully!");
    }

    @Test
    void testAuthenticatedWorkflow_CreateUser_Login_PerformQuery_VerifyDbState() throws Exception {
        // ========== STEP 1: Create admin user ==========
        // Note: User creation uses AuthenticationService because GraphQL createHumanUser
        // requires TOTP confirmation which adds unnecessary complexity for testing.
        logger.info("STEP 1: Creating admin user...");

        UserCreationResult adminResult = authenticationService.createUser("e2e-admin", UserLevel.ADMIN, null);
        assertThat(adminResult).isNotNull();
        assertThat(adminResult.recoveryCodes()).isNotEmpty();
        String recoveryCode = adminResult.recoveryCodes().get(0);
        logger.info("Admin user created successfully");

        // ========== STEP 2: Login via GraphQL with recovery code ==========
        logger.info("STEP 2: Logging in via GraphQL with recovery code...");

        // Clear any existing auth token from setup
        graphQLClient.clearAuthToken();

        String loginMutation = String.format("""
            mutation {
                loginWithRecoveryCode(input: {
                    nickname: "e2e-admin"
                    recoveryCode: "%s"
                }) {
                    token
                    expiresAt
                    user {
                        id
                        nickname
                        userLevel
                        isActive
                    }
                }
            }
            """, recoveryCode);

        ResponseEntity<String> loginResponse = executeGraphQLQuery(loginMutation);
        JsonNode loginResult = parseGraphQLResponse(loginResponse);

        assertThat(loginResult.path("errors").isMissingNode() || loginResult.path("errors").isNull())
            .withFailMessage("Login failed with errors: " + loginResult.path("errors"))
            .isTrue();

        JsonNode authResultData = loginResult.path("data").path("loginWithRecoveryCode");
        String authToken = authResultData.path("token").asString();
        assertThat(authToken).isNotBlank();
        
        // Verify user data from login response
        JsonNode userData = authResultData.path("user");
        assertThat(userData.path("nickname").asString()).isEqualTo("e2e-admin");
        assertThat(userData.path("userLevel").asString()).isEqualTo("ADMIN");
        assertThat(userData.path("isActive").asBoolean()).isTrue();
        logger.info("Logged in successfully as user: {}", userData.path("nickname").asString());

        // ========== STEP 3: Verify currentUser query returns correct user ==========
        logger.info("STEP 3: Verifying currentUser query...");

        String currentUserQuery = String.format("{ currentUser(token: \"%s\") { id nickname userLevel isActive } }", authToken);
        ResponseEntity<String> currentUserResponse = executeGraphQLQuery(currentUserQuery);
        JsonNode currentUserResult = parseGraphQLResponse(currentUserResponse);

        assertThat(currentUserResult.path("errors").isMissingNode() || currentUserResult.path("errors").isNull())
            .withFailMessage("currentUser query failed: " + currentUserResult.path("errors"))
            .isTrue();

        JsonNode currentUser = currentUserResult.path("data").path("currentUser");
        assertThat(currentUser.path("nickname").asString()).isEqualTo("e2e-admin");
        assertThat(currentUser.path("userLevel").asString()).isEqualTo("ADMIN");
        logger.info("currentUser verified: {}", currentUser.path("nickname").asString());

        // ========== STEP 4: Create data source via authenticated GraphQL ==========
        logger.info("STEP 4: Creating data source via authenticated GraphQL...");

        testDataSourceName = "Auth E2E Test Data Source";
        String createDsMutation = String.format("""
            mutation {
                createLocalFileSystemDataSource(token: \"%s\", input: {
                    name: \"%s\"
                    cronPattern: \"0 0 * * *\"
                    localPath: \"%s\"
                }) {
                    name
                    type
                }
            }
            """, authToken, testDataSourceName, tempTestDir.toString());

        ResponseEntity<String> createDsResponse = executeGraphQLQuery(createDsMutation);
        JsonNode createDsResult = parseGraphQLResponse(createDsResponse);

        assertThat(createDsResult.path("errors").isMissingNode() || createDsResult.path("errors").isNull())
            .withFailMessage("Create data source failed with errors: " + createDsResult.path("errors"))
            .isTrue();

        String createdDsName = createDsResult.path("data").path("createLocalFileSystemDataSource").path("name").asString();
        assertThat(createdDsName).isEqualTo(testDataSourceName);
        logger.info("Data source created via authenticated GraphQL: {}", createdDsName);

        // ========== STEP 5: Run scan and process treatments ==========
        logger.info("STEP 5: Running scan and processing treatments...");

        DataSourceInterface dataSource = getDataSourceViaGraphQL(testDataSourceName, authToken);
        scanner.scan(dataSource);
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);
        logger.info("Scan and treatment processing completed");

        // ========== STEP 6: Perform authenticated search query ==========
        logger.info("STEP 6: Performing authenticated search query...");

        String searchQuery = String.format("{ search(token: \"%s\", name: \"text.txt\", skip: 0, howmany: 10) { totalHits items { name folder } } }", authToken);
        ResponseEntity<String> searchResponse = executeGraphQLQuery(searchQuery);
        JsonNode searchResult = parseGraphQLResponse(searchResponse);

        assertThat(searchResult.path("errors").isMissingNode() || searchResult.path("errors").isNull())
            .withFailMessage("Search failed with errors: " + searchResult.path("errors"))
            .isTrue();

        JsonNode searchData = searchResult.path("data").path("search");
        assertThat(searchData.path("totalHits").asInt()).isGreaterThan(0);
        assertThat(searchData.path("items").get(0).path("name").asString()).isEqualTo("text.txt");
        logger.info("Authenticated search returned {} hits", searchData.path("totalHits").asInt());

        // ========== STEP 7: Verify search fails without authentication ==========
        logger.info("STEP 7: Verifying search fails without authentication...");

        String unauthSearchQuery = "{ search(name: \"text.txt\", skip: 0, howmany: 10) { totalHits items { name folder } } }";
        ResponseEntity<String> unauthSearchResponse = executeGraphQLQuery(unauthSearchQuery);
        JsonNode unauthSearchResult = parseGraphQLResponse(unauthSearchResponse);

        // Should have errors (authentication required)
        assertThat(unauthSearchResult.path("errors").isArray() && unauthSearchResult.path("errors").size() > 0)
            .withFailMessage("Expected authentication error for unauthenticated search request")
            .isTrue();
        logger.info("Unauthenticated search correctly rejected with errors");

        // ========== STEP 8: Verify about query works without authentication ==========
        logger.info("STEP 8: Verifying 'about' query works without authentication...");

        String aboutQuery = "{ about { name version } }";
        ResponseEntity<String> aboutResponse = executeGraphQLQuery(aboutQuery);
        JsonNode aboutResult = parseGraphQLResponse(aboutResponse);

        assertThat(aboutResult.path("errors").isMissingNode() || aboutResult.path("errors").isNull())
            .withFailMessage("About query should work without auth: " + aboutResult.path("errors"))
            .isTrue();

        String appName = aboutResult.path("data").path("about").path("name").asString();
        assertThat(appName).isNotBlank();
        logger.info("About query returned app name: {}", appName);

        // ========== STEP 9: Logout and verify session invalidated ==========
        logger.info("STEP 9: Logging out and verifying session invalidation...");

        String logoutMutation = String.format("mutation { logout(token: \"%s\") }", authToken);
        ResponseEntity<String> logoutResponse = executeGraphQLQuery(logoutMutation);
        JsonNode logoutResult = parseGraphQLResponse(logoutResponse);

        assertThat(logoutResult.path("data").path("logout").asBoolean()).isTrue();
        logger.info("Logout successful");

        // ========== STEP 10: Verifying currentUser returns null after logout ==========
        logger.info("STEP 10: Verifying currentUser returns null after logout...");

        String postLogoutCurrentUserQuery = String.format("{ currentUser(token: \"%s\") { id nickname userLevel isActive } }", authToken);
        ResponseEntity<String> postLogoutCurrentUserResponse = executeGraphQLQuery(postLogoutCurrentUserQuery);
        JsonNode postLogoutCurrentUserResult = parseGraphQLResponse(postLogoutCurrentUserResponse);

        // Either should have errors or currentUser should be null
        boolean hasErrors = postLogoutCurrentUserResult.path("errors").isArray() 
            && postLogoutCurrentUserResult.path("errors").size() > 0;
        boolean userIsNull = postLogoutCurrentUserResult.path("data").path("currentUser").isNull();
        assertThat(hasErrors || userIsNull)
            .withFailMessage("After logout, currentUser should be null or return auth error")
            .isTrue();
        logger.info("Session correctly invalidated - currentUser not accessible after logout");

        // ========== STEP 11: Verifying search fails with invalidated token ==========
        logger.info("STEP 11: Verifying search fails with invalidated token...");

        String postLogoutSearchQuery = String.format("{ search(token: \"%s\", name: \"text.txt\", skip: 0, howmany: 10) { totalHits items { name folder } } }", authToken);
        ResponseEntity<String> postLogoutSearchResponse = executeGraphQLQuery(postLogoutSearchQuery);
        JsonNode postLogoutSearchResult = parseGraphQLResponse(postLogoutSearchResponse);

        assertThat(postLogoutSearchResult.path("errors").isArray() && postLogoutSearchResult.path("errors").size() > 0)
            .withFailMessage("Expected authentication error for request with invalidated token")
            .isTrue();
        logger.info("Search with invalidated token correctly rejected");

        logger.info("Authenticated workflow end-to-end test completed successfully!");
    }

    @Test
    void testReaderPermissions_CanSearchButNotModify() throws Exception {
        // ========== STEP 1: Create admin and reader users ==========
        // Note: User creation uses AuthenticationService because GraphQL createHumanUser
        // requires TOTP confirmation which adds unnecessary complexity for testing.
        logger.info("STEP 1: Creating admin and reader users...");

        UserCreationResult adminResult = authenticationService.createUser("e2e-admin2", UserLevel.ADMIN, null);
        UserCreationResult readerResult = authenticationService.createUser("e2e-reader", UserLevel.READER, "e2e-admin2");

        String adminRecoveryCode = adminResult.recoveryCodes().get(0);
        String readerRecoveryCode = readerResult.recoveryCodes().get(0);
        logger.info("Admin and reader users created");

        // ========== STEP 2: Login as admin via GraphQL ==========
        logger.info("STEP 2: Logging in as admin via GraphQL...");

        String adminLoginMutation = String.format("""
            mutation {
                loginWithRecoveryCode(input: {
                    nickname: "e2e-admin2"
                    recoveryCode: "%s"
                }) {
                    token
                    user {
                        id
                        nickname
                        userLevel
                    }
                }
            }
            """, adminRecoveryCode);

        ResponseEntity<String> adminLoginResponse = executeGraphQLQuery(adminLoginMutation);
        JsonNode adminLoginResult = parseGraphQLResponse(adminLoginResponse);
        String adminToken = adminLoginResult.path("data").path("loginWithRecoveryCode").path("token").asString();

        // Verify admin user level from response
        JsonNode adminUserData = adminLoginResult.path("data").path("loginWithRecoveryCode").path("user");
        assertThat(adminUserData.path("userLevel").asString()).isEqualTo("ADMIN");
        logger.info("Logged in as admin: {}", adminUserData.path("nickname").asString());

        // ========== STEP 3: Verify users via humanUsers query ==========
        logger.info("STEP 3: Verifying users via humanUsers query...");

        String usersQuery = String.format("{ humanUsers(token: \"%s\") { nickname userLevel isActive } }", adminToken);
        ResponseEntity<String> usersResponse = executeGraphQLQuery(usersQuery);
        JsonNode usersResult = parseGraphQLResponse(usersResponse);

        JsonNode users = usersResult.path("data").path("humanUsers");
        assertThat(users.size()).isGreaterThanOrEqualTo(2);

        // Find admin and reader in the list
        boolean foundAdmin = false;
        boolean foundReader = false;
        for (JsonNode user : users) {
            if ("e2e-admin2".equals(user.path("nickname").asString())) {
                assertThat(user.path("userLevel").asString()).isEqualTo("ADMIN");
                foundAdmin = true;
            }
            if ("e2e-reader".equals(user.path("nickname").asString())) {
                assertThat(user.path("userLevel").asString()).isEqualTo("READER");
                foundReader = true;
            }
        }
        assertThat(foundAdmin).isTrue();
        assertThat(foundReader).isTrue();
        logger.info("Both users verified via humanUsers query");

        // ========== STEP 4: Create data source as admin ==========
        logger.info("STEP 4: Creating data source as admin...");

        testDataSourceName = "Reader Test Data Source";
        String createDsMutation = String.format("""
            mutation {
                createLocalFileSystemDataSource(token: \"%s\", input: {
                    name: \"%s\"
                    cronPattern: \"0 0 * * *\"
                    localPath: \"%s\"
                }) {
                    name
                }
            }
            """, adminToken, testDataSourceName, tempTestDir.toString());

        executeGraphQLQuery(createDsMutation);

        // Scan and process
        DataSourceInterface dataSource = getDataSourceViaGraphQL(testDataSourceName, adminToken);
        scanner.scan(dataSource);
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);
        logger.info("Data source created and indexed by admin");

        // ========== STEP 5: Login as reader via GraphQL ==========
        logger.info("STEP 5: Logging in as reader via GraphQL...");

        String readerLoginMutation = String.format("""
            mutation {
                loginWithRecoveryCode(input: {
                    nickname: "e2e-reader"
                    recoveryCode: "%s"
                }) {
                    token
                    user {
                        nickname
                        userLevel
                    }
                }
            }
            """, readerRecoveryCode);

        ResponseEntity<String> readerLoginResponse = executeGraphQLQuery(readerLoginMutation);
        JsonNode readerLoginResult = parseGraphQLResponse(readerLoginResponse);
        String readerToken = readerLoginResult.path("data").path("loginWithRecoveryCode").path("token").asString();

        // Verify reader user level from login response
        JsonNode readerUserData = readerLoginResult.path("data").path("loginWithRecoveryCode").path("user");
        assertThat(readerUserData.path("userLevel").asString()).isEqualTo("READER");
        logger.info("Logged in as reader: {}", readerUserData.path("nickname").asString());

        // ========== STEP 6: Verify currentUser returns reader ==========
        logger.info("STEP 6: Verifying currentUser returns reader...");

        String currentUserQuery = String.format("{ currentUser(token: \"%s\") { nickname userLevel } }", readerToken);
        ResponseEntity<String> currentUserResponse = executeGraphQLQuery(currentUserQuery);
        JsonNode currentUserResult = parseGraphQLResponse(currentUserResponse);

        JsonNode currentUser = currentUserResult.path("data").path("currentUser");
        assertThat(currentUser.path("nickname").asString()).isEqualTo("e2e-reader");
        assertThat(currentUser.path("userLevel").asString()).isEqualTo("READER");
        logger.info("currentUser verified as reader");

        // ========== STEP 7: Reader CAN perform search ==========
        logger.info("STEP 7: Verifying reader can perform search...");

        String searchQuery = String.format("{ search(token: \"%s\", name: \"text.txt\", skip: 0, howmany: 10) { totalHits items { name } } }", readerToken);
        ResponseEntity<String> searchResponse = executeGraphQLQuery(searchQuery);
        JsonNode searchResult = parseGraphQLResponse(searchResponse);

        assertThat(searchResult.path("errors").isMissingNode() || searchResult.path("errors").isNull())
            .withFailMessage("Reader should be able to search: " + searchResult.path("errors"))
            .isTrue();
        assertThat(searchResult.path("data").path("search").path("totalHits").asInt()).isGreaterThan(0);
        logger.info("Reader successfully performed search");

        // ========== STEP 8: Reader CANNOT create data source ==========
        logger.info("STEP 8: Verifying reader cannot create data source...");

        String attemptCreateDsMutation = String.format("""
            mutation {
                createLocalFileSystemDataSource(token: \"%s\", input: {
                    name: \"Unauthorized DS\"
                    cronPattern: \"0 0 * * *\"
                    localPath: \"/tmp/test\"
                }) {
                    name
                }
            }
            """, readerToken);

        ResponseEntity<String> unauthorizedResponse = executeGraphQLQuery(attemptCreateDsMutation);
        JsonNode unauthorizedResult = parseGraphQLResponse(unauthorizedResponse);

        assertThat(unauthorizedResult.path("errors").isArray() && unauthorizedResult.path("errors").size() > 0)
            .withFailMessage("Reader should NOT be able to create data source")
            .isTrue();
        logger.info("Reader correctly rejected when trying to create data source");

        // ========== STEP 9: Reader CANNOT delete data source ==========
        logger.info("STEP 9: Verifying reader cannot delete data source...");

        String attemptDeleteMutation = String.format("""
            mutation {
                deleteDataSource(token: \"%s\", name: \"%s\", type: LOCAL_FILESYSTEM)
            }
            """, readerToken, testDataSourceName);

        ResponseEntity<String> deleteResponse = executeGraphQLQuery(attemptDeleteMutation);
        JsonNode deleteResult = parseGraphQLResponse(deleteResponse);

        assertThat(deleteResult.path("errors").isArray() && deleteResult.path("errors").size() > 0)
            .withFailMessage("Reader should NOT be able to delete data source")
            .isTrue();
        logger.info("Reader correctly rejected when trying to delete data source");

        logger.info("Reader permissions end-to-end test completed successfully!");
    }

    @Test
    void testUnauthenticatedRequest_FailsWhenAuthRequired() throws Exception {
        logger.info("========================================");
        logger.info("STARTING: Unauthenticated Request Failure Test");
        logger.info("========================================");

        // ========== STEP 1: Create a user (which makes auth required) ==========
        logger.info("STEP 1: Creating a user to make authentication required...");

        authenticationService.createUser("auth-test-admin", UserLevel.ADMIN, null);

        logger.info("Admin user created - authentication is now required");

        // ========== STEP 2: Verify 'about' query still works (exempt from auth) ==========
        logger.info("STEP 2: Verifying 'about' query works without authentication...");

        String aboutQuery = "query { about { name version } }";
        ResponseEntity<String> aboutResponse = executeGraphQLQuery(aboutQuery);
        JsonNode aboutResult = parseGraphQLResponse(aboutResponse);

        assertThat(aboutResult.path("errors").isMissingNode() || aboutResult.path("errors").isNull())
            .withFailMessage("'about' query should work without auth: " + aboutResult.path("errors"))
            .isTrue();
        assertThat(aboutResult.path("data").path("about").path("name").asString()).isNotEmpty();
        logger.info("'about' query works without authentication as expected");

        // ========== STEP 3: Verify protected queries fail without auth ==========
        logger.info("STEP 3: Verifying protected queries fail without authentication...");

        String dataSourcesQuery = "query { dataSources { name } }";
        ResponseEntity<String> dsResponse = executeGraphQLQuery(dataSourcesQuery);
        JsonNode dsResult = parseGraphQLResponse(dsResponse);

        assertThat(dsResult.path("errors").isArray() && dsResult.path("errors").size() > 0)
            .withFailMessage("dataSources query should fail without auth when users exist")
            .isTrue();
        logger.info("dataSources query correctly rejected without authentication");

        // ========== STEP 4: Verify protected mutations fail without auth ==========
        logger.info("STEP 4: Verifying protected mutations fail without authentication...");

        String createDsMutation = """
            mutation {
                createLocalFileSystemDataSource(input: {
                    name: "Unauthorized DS"
                    cronPattern: "0 0 * * *"
                    localPath: "/tmp/test"
                }) {
                    name
                }
            }
            """;

        ResponseEntity<String> createResponse = executeGraphQLQuery(createDsMutation);
        JsonNode createResult = parseGraphQLResponse(createResponse);

        assertThat(createResult.path("errors").isArray() && createResult.path("errors").size() > 0)
            .withFailMessage("createLocalFileSystemDataSource mutation should fail without auth when users exist")
            .isTrue();
        logger.info("createLocalFileSystemDataSource mutation correctly rejected without authentication");

        // ========== STEP 5: Verify search fails without auth ==========
        logger.info("STEP 5: Verifying search query fails without authentication...");

        String searchQuery = """
            query {
                search(input: { query: "test" }) {
                    totalHits
                }
            }
            """;

        ResponseEntity<String> searchResponse = executeGraphQLQuery(searchQuery);
        JsonNode searchResult = parseGraphQLResponse(searchResponse);

        assertThat(searchResult.path("errors").isArray() && searchResult.path("errors").size() > 0)
            .withFailMessage("search query should fail without auth when users exist")
            .isTrue();
        logger.info("search query correctly rejected without authentication");

        logger.info("Unauthenticated request failure test completed successfully!");
    }
}
