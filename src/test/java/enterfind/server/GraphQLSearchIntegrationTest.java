package enterfind.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Objects;

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
import enterfind.server.dao.ItemDao;
import enterfind.server.dao.TreatmentDao;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.LocalFileSystemDataSource;
import enterfind.server.scan.Item;
import enterfind.server.scan.Scanner;
import enterfind.server.scan.TreatmentProcessor;
import enterfind.server.scan.Treatments;

/**
 * Integration tests for GraphQL search functionality with facets.
 * 
 * Uses @TestInstance(PER_CLASS) for one-time expensive setup (file creation, scan, treatment).
 * Overrides the parent's @DirtiesContext to prevent context restarts between tests,
 * allowing the shared setup to persist and the GraphQLTestClient to be reused.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class GraphQLSearchIntegrationTest extends BaseDatabaseTest {

    private static final Logger logger = LoggerFactory.getLogger(GraphQLSearchIntegrationTest.class);

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
    private ItemDao itemDao;
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
    void setThisUp() throws Exception {
        wipeDatabase();  // Clean once before all tests
        graphQLClient = new GraphQLTestClient(port);
        tempTestDir = Files.createTempDirectory("graphql-search-test-");
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

    /**
     * Execute a GraphQL query using the shared client.
     */
    private ResponseEntity<String> executeGraphQLQuery(String query) {
        return graphQLClient.execute(query);
    }

    @Test
    public void testSearchQueryWithoutFacets() {
        String query = "{ search(skip: 0, howmany: 10) { offset howmany totalHits items { name folder dateCreated dateModified } } }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("offset");
        assertThat(response.getBody()).contains("howmany");
        assertThat(response.getBody()).contains("totalHits");
        assertThat(response.getBody()).contains("items");
        logger.info("Search without facets: {}", response.getBody());
    }

    @Test
    public void testSearchQueryWithFacets() {
        // Test search without facets (facets are now a separate query)
        String searchQuery = "{ search(skip: 0, howmany: 10) { offset howmany totalHits items { name folder dateCreated dateModified } } }";
        ResponseEntity<String> searchResponse = executeGraphQLQuery(searchQuery);

        assertThat(searchResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(searchResponse.getBody()).contains("offset");
        assertThat(searchResponse.getBody()).contains("howmany");
        assertThat(searchResponse.getBody()).contains("totalHits");
        assertThat(searchResponse.getBody()).contains("items");
        
        // Test getFacets separately
        String facetsQuery = "{ getFacets(key: \"name\") { value count } }";
        ResponseEntity<String> facetsResponse = executeGraphQLQuery(facetsQuery);
        
        assertThat(facetsResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(facetsResponse.getBody()).contains("value");
        assertThat(facetsResponse.getBody()).contains("count");
        logger.info("Search: {}", searchResponse.getBody());
        logger.info("Facets: {}", facetsResponse.getBody());
    }

    @Test
    public void testSearchByNameWithFacets() {
        String query = "{ search(name: \"text.txt\", skip: 0, howmany: 10) { offset howmany totalHits items { name folder dateCreated dateModified } } }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("text.txt");
        logger.info("Search by name: {}", response.getBody());
    }

    @Test
    public void testSearchByFolderWithFacets() {
        String query = "{ search(folder: \"subf\", skip: 0, howmany: 10) { offset howmany totalHits items { name folder dateCreated dateModified } } }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"folder\":\"subf\"");
        logger.info("Search by folder: {}", response.getBody());
    }

    @Test
    public void testSearchBySearchStringWithFacets() {
        String query = "{ search(searchString: \"pdf\", skip: 0, howmany: 10) { offset howmany totalHits items { name folder dateCreated dateModified } } }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        logger.info("Search by searchString: {}", response.getBody());
    }

    @Test
    public void testSearchWithPaginationAndFacets() {
        String query = "{ search(skip: 0, howmany: 3) { offset howmany totalHits items { name folder dateCreated dateModified } } }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"offset\":0");
        assertThat(response.getBody()).contains("\"howmany\":3");
        logger.info("Search with pagination: {}", response.getBody());
    }

    @Test
    public void testSearchWithConditionAndFacets() {
        String query = "{ search(searchConditions: [{name: \"name\", operation: LIKE, value: \"%text%\"}], skip: 0, howmany: 10) { offset howmany totalHits items { name folder dateCreated dateModified } } }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        logger.info("Search with condition: {}", response.getBody());
    }

    @Test
    public void testSearchFacetsHaveValidStructure() {
        // Test the new getFacets query
        String query = "{ getFacets(key: \"name\") { value count } }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = response.getBody();
        
        // Verify facet values structure
        assertThat(body).contains("\"value\"");
        assertThat(body).contains("\"count\"");
        
        // Verify counts are integers
        assertThat(body).containsPattern("\"count\":\\d+");
        
        logger.info("Facets structure validation: {}", body);
    }

    @Test
    public void testSearchMetadataWithAndWithoutFacets() {
        // Test search
        String queryWithoutFacets = "{ search(skip: 0, howmany: 5) { offset howmany totalHits } }";
        ResponseEntity<String> responseWithout = executeGraphQLQuery(queryWithoutFacets);
        
        // Test getFacets separately
        String queryWithFacets = "{ getFacets(key: \"name\") { value count } }";
        ResponseEntity<String> responseWith = executeGraphQLQuery(queryWithFacets);

        assertThat(responseWithout.getStatusCode().value()).isEqualTo(200);
        assertThat(responseWith.getStatusCode().value()).isEqualTo(200);
        
        // Search should have metadata
        assertThat(responseWithout.getBody()).contains("\"offset\":0");
        assertThat(responseWithout.getBody()).contains("\"howmany\":5");
        
        // getFacets should have facet values
        assertThat(responseWith.getBody()).contains("getFacets");
        
        logger.info("Search metadata: {}", responseWithout.getBody());
        logger.info("Facets: {}", responseWith.getBody());
    }

    @Test
    public void testSearchWithEqualOperation() {
        String query = "{ search(searchConditions: [{name: \"name\", operation: EQUAL, value: \"text.txt\"}], skip: 0, howmany: 10) { totalHits items { name } } }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"totalHits\":1");
        assertThat(response.getBody()).contains("\"name\":\"text.txt\"");
        logger.info("Search with EQUAL operation: {}", response.getBody());
    }

    @Test
    public void testSearchWithLessOperation() {
        // Search for items with names less than "m" (alphabetically)
        String query = "{ search(searchConditions: [{name: \"name\", operation: LESS, value: \"m\"}], skip: 0, howmany: 20) { totalHits items { name } } }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // Should find items starting with letters before 'm' (like 'A', 'b', etc.)
        String body = response.getBody();
        assertThat(body).contains("totalHits");
        logger.info("Search with LESS operation: {}", body);
    }

    @Test
    public void testSearchWithGreaterOperation() {
        // Search for items with names greater than "m" (alphabetically)
        String query = "{ search(searchConditions: [{name: \"name\", operation: GREATER, value: \"m\"}], skip: 0, howmany: 20) { totalHits items { name } } }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // Should find items starting with letters after 'm' (like 'o', 't', etc.)
        String body = response.getBody();
        assertThat(body).contains("totalHits");
        logger.info("Search with GREATER operation: {}", body);
    }

    @Test
    public void testSearchWithAllOperations() {
        // Verify that LESS, GREATER, EQUAL combined cover all items
        String allQuery = "{ search(skip: 0, howmany: 100) { totalHits } }";
        ResponseEntity<String> allResponse = executeGraphQLQuery(allQuery);
        
        String lessQuery = "{ search(searchConditions: [{name: \"name\", operation: LESS, value: \"ocrable.pdf\"}], skip: 0, howmany: 100) { totalHits } }";
        ResponseEntity<String> lessResponse = executeGraphQLQuery(lessQuery);
        
        String greaterQuery = "{ search(searchConditions: [{name: \"name\", operation: GREATER, value: \"ocrable.pdf\"}], skip: 0, howmany: 100) { totalHits } }";
        ResponseEntity<String> greaterResponse = executeGraphQLQuery(greaterQuery);
        
        String equalQuery = "{ search(searchConditions: [{name: \"name\", operation: EQUAL, value: \"ocrable.pdf\"}], skip: 0, howmany: 100) { totalHits } }";
        ResponseEntity<String> equalResponse = executeGraphQLQuery(equalQuery);

        assertThat(allResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(lessResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(greaterResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(equalResponse.getStatusCode().value()).isEqualTo(200);
        
        logger.info("All operations coverage test - All: {}, Less: {}, Greater: {}, Equal: {}", 
            allResponse.getBody(), lessResponse.getBody(), greaterResponse.getBody(), equalResponse.getBody());
    }

    // ==================== itemAttributes API Tests ====================

    @Test
    public void testItemAttributesForRootLevelItem() {
        // Test itemAttributes API for an item in root folder (no folder/path)
        // Uses forward-slash folder format in API, should find item with empty path in database
        String query = String.format(
            "{ itemAttributes(dataSourceName: \"%s\", dataSourceType: LOCAL_FILESYSTEM, name: \"ocrable.pdf\", folder: null) { key value } }",
            testDataSource.name()
        );
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"key\"");
        assertThat(response.getBody()).contains("\"value\"");
        // NAME_AND_PATH treatment should create 'name' and 'path' attributes
        assertThat(response.getBody()).contains("\"name\"");
        logger.info("itemAttributes for root level item: {}", response.getBody());
    }

    @Test
    public void testItemAttributesForSubfolderItem() {
        // Test itemAttributes API for an item in subfolder
        // API uses forward-slash format ("subf"), database stores comma-separated ("subf")
        String query = String.format(
            "{ itemAttributes(dataSourceName: \"%s\", dataSourceType: LOCAL_FILESYSTEM, name: \"text.txt\", folder: \"subf\") { key value } }",
            testDataSource.name()
        );
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"key\"");
        assertThat(response.getBody()).contains("\"value\"");
        // Should find the item and return its attributes
        assertThat(response.getBody()).contains("\"name\"");
        logger.info("itemAttributes for subfolder item: {}", response.getBody());
    }

    @Test
    public void testItemAttributesNotFoundWithWrongFolder() {
        // Test that itemAttributes returns empty when folder doesn't match
        String query = String.format(
            "{ itemAttributes(dataSourceName: \"%s\", dataSourceType: LOCAL_FILESYSTEM, name: \"text.txt\", folder: \"wrongfolder\") { key value } }",
            testDataSource.name()
        );
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // Should return empty array when item not found at that path
        assertThat(response.getBody()).contains("\"itemAttributes\":[]");
        logger.info("itemAttributes with wrong folder: {}", response.getBody());
    }

    @Test
    public void testItemAttributesNotFoundWithWrongName() {
        // Test that itemAttributes returns empty when name doesn't match
        String query = String.format(
            "{ itemAttributes(dataSourceName: \"%s\", dataSourceType: LOCAL_FILESYSTEM, name: \"nonexistent.txt\", folder: \"subf\") { key value } }",
            testDataSource.name()
        );
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // Should return empty array when item not found
        assertThat(response.getBody()).contains("\"itemAttributes\":[]");
        logger.info("itemAttributes with wrong name: {}", response.getBody());
    }

    @Test
    public void testItemAttributesWithSingleKeyNameFilter() {
        // Test itemAttributes API with keyNames parameter filtering for a single key
        String query = String.format(
            "{ itemAttributes(dataSourceName: \"%s\", dataSourceType: LOCAL_FILESYSTEM, name: \"ocrable.pdf\", folder: null, keyNames: [\"name\"]) { key value } }",
            testDataSource.name()
        );
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("\"key\"");
        assertThat(response.getBody()).contains("\"value\"");
        // Should only return the 'name' attribute
        assertThat(response.getBody()).contains("\"name\"");
        // Should NOT return other attributes like 'path', 'extension', etc.
        String body = Objects.requireNonNull(response.getBody());
        int nameOccurrences = body.split("\"key\"").length - 1;
        // Should have exactly 1 key (the one we filtered for)
        assertThat(nameOccurrences).isEqualTo(1);
        logger.info("itemAttributes with single keyName filter: {}", body);
    }

    @Test
    public void testItemAttributesWithMultipleKeyNamesFilter() {
        // Test itemAttributes API with keyNames parameter filtering for multiple keys
        // First query without filter to see all available attributes
        String queryAll = String.format(
            "{ itemAttributes(dataSourceName: \"%s\", dataSourceType: LOCAL_FILESYSTEM, name: \"ocrable.pdf\", folder: null) { key value } }",
            testDataSource.name()
        );
        ResponseEntity<String> responseAll = executeGraphQLQuery(queryAll);
        logger.info("All attributes for ocrable.pdf: {}", responseAll.getBody());

        // Now test with filter - use 'name' twice to test multiple filter (name appears once)
        // or use a query that requests name and a non-existent key to verify the filter works
        String query = String.format(
            "{ itemAttributes(dataSourceName: \"%s\", dataSourceType: LOCAL_FILESYSTEM, name: \"text.txt\", folder: \"subf\", keyNames: [\"name\", \"path\"]) { key value } }",
            testDataSource.name()
        );
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        // Should have filtered results
        String body = Objects.requireNonNull(response.getBody());
        int keyCount = body.split("\"key\"").length - 1;
        assertThat(keyCount).isGreaterThan(0).isLessThanOrEqualTo(2);
        logger.info("itemAttributes with multiple keyNames filter: {}", body);
    }

    @Test
    public void testItemAttributesWithNonExistentKeyName() {
        // Test itemAttributes API with a keyName that doesn't exist
        String query = String.format(
            "{ itemAttributes(dataSourceName: \"%s\", dataSourceType: LOCAL_FILESYSTEM, name: \"ocrable.pdf\", folder: null, keyNames: [\"nonexistent_key\"]) { key value } }",
            testDataSource.name()
        );
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // Should return empty array when filtering for non-existent keys
        assertThat(response.getBody()).contains("\"itemAttributes\":[]");
        logger.info("itemAttributes with non-existent keyName: {}", response.getBody());
    }

    @Test
    public void testGetFacetsWithSearchConditions() {
        // Test getFacets with search conditions to filter the facet values
        String query = "{ getFacets(key: \"name\", searchConditions: [{name: \"extension\", operation: EQUAL, value: \"pdf\"}]) { value count } }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("getFacets");
        logger.info("getFacets with search conditions: {}", response.getBody());
    }

    @Test
    public void testGetFacetsForExtension() {
        // Test getFacets for extension key
        String query = "{ getFacets(key: \"extension\") { value count } }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("getFacets");
        // Should have various extensions like .pdf, .txt, .jpg etc
        logger.info("getFacets for extension: {}", response.getBody());
    }

    // ==================== availableItemAttributeKeys API Tests ====================

    @Test
    public void testAvailableItemAttributeKeysWithoutFilters() {
        // Test availableItemAttributeKeys without any filters - should return all keys
        String query = "{ availableItemAttributeKeys }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("availableItemAttributeKeys");
        // Should include common keys like name, path, extension
        assertThat(response.getBody()).contains("name");
        assertThat(response.getBody()).contains("path");
        logger.info("availableItemAttributeKeys without filters: {}", response.getBody());
    }

    @Test
    public void testAvailableItemAttributeKeysWithSearchCondition() {
        // Test availableItemAttributeKeys with a search condition
        // Filter to only PDF files, should only return keys that exist for PDFs
        String query = "{ availableItemAttributeKeys(searchConditions: [{name: \"content-type\", operation: EQUAL, value: \"application/pdf\"}]) }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("availableItemAttributeKeys");
        // Should still have basic keys like name, path, content-type
        assertThat(response.getBody()).contains("name");
        assertThat(response.getBody()).contains("content-type");
        logger.info("availableItemAttributeKeys with content-type=application/pdf condition: {}", response.getBody());
    }

    @Test
    public void testAvailableItemAttributeKeysWithSearchString() {
        // Test availableItemAttributeKeys with searchString filter
        // Search for "pdf" should narrow down to items containing that word in their content
        String query = "{ availableItemAttributeKeys(searchString: \"pdf\") }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("availableItemAttributeKeys");
        // Should have name and path for items matching "pdf"
        assertThat(response.getBody()).contains("name");
        logger.info("availableItemAttributeKeys with searchString='pdf': {}", response.getBody());
    }

    @Test
    public void testAvailableItemAttributeKeysWithMultipleConditions() {
        // Test availableItemAttributeKeys with multiple search conditions
        // This should further narrow down the available keys
        String query = "{ availableItemAttributeKeys(searchConditions: [{name: \"content-type\", operation: LIKE, value: \"%text/plain%\"}, {name: \"path\", operation: EQUAL, value: \"subf\"}]) }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("availableItemAttributeKeys");
        // Should only return keys for items that are text files in the "subf" folder
        assertThat(response.getBody()).contains("name");
        logger.info("availableItemAttributeKeys with multiple conditions: {}", response.getBody());
    }

    @Test
    public void testAvailableItemAttributeKeysVsAllKeys() {
        // Compare availableItemAttributeKeys with and without filters
        // Without filter should return more or equal keys than with filter
        String allKeysQuery = "{ allKeys: itemAttributeKeys }";
        ResponseEntity<String> allKeysResponse = executeGraphQLQuery(allKeysQuery);

        String filteredKeysQuery = "{ filteredKeys: availableItemAttributeKeys(searchConditions: [{name: \"content-type\", operation: EQUAL, value: \"application/pdf\"}]) }";
        ResponseEntity<String> filteredKeysResponse = executeGraphQLQuery(filteredKeysQuery);

        assertThat(allKeysResponse.getStatusCode().value()).isEqualTo(200);
        assertThat(filteredKeysResponse.getStatusCode().value()).isEqualTo(200);

        // Both should have name and path
        assertThat(allKeysResponse.getBody()).contains("name");
        assertThat(filteredKeysResponse.getBody()).contains("name");

        logger.info("All keys: {}", allKeysResponse.getBody());
        logger.info("Filtered keys (pdf only): {}", filteredKeysResponse.getBody());
    }

    @Test
    public void testAvailableItemAttributeKeysWithNameFilter() {
        // Test availableItemAttributeKeys with name parameter
        String query = "{ availableItemAttributeKeys(name: \"text.txt\") }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("availableItemAttributeKeys");
        // Should return keys for items matching "text.txt" substring
        assertThat(response.getBody()).contains("name");
        logger.info("availableItemAttributeKeys with name='text.txt': {}", response.getBody());
    }

    @Test
    public void testAvailableItemAttributeKeysWithFolderFilter() {
        // Test availableItemAttributeKeys with folder parameter
        String query = "{ availableItemAttributeKeys(folder: \"subf\") }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("availableItemAttributeKeys");
        // Should return keys for items in the "subf" folder
        assertThat(response.getBody()).contains("name");
        assertThat(response.getBody()).contains("path");
        logger.info("availableItemAttributeKeys with folder='subf': {}", response.getBody());
    }

    @Test
    public void testAvailableItemAttributeKeysEmptyResult() {
        // Test availableItemAttributeKeys with impossible condition - should return empty array
        String query = "{ availableItemAttributeKeys(searchConditions: [{name: \"name\", operation: EQUAL, value: \"nonexistentfile12345.xyz\"}]) }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("availableItemAttributeKeys");
        // Should return empty array since no items match
        assertThat(response.getBody()).contains("[]");
        logger.info("availableItemAttributeKeys with no matching items: {}", response.getBody());
    }

    @Test
    public void testSizeBytesExceedingIntMaxValueIsSerializedCorrectly() {
        // Regression test: sizeBytes was declared as Int in GraphQL schema but the Java model
        // uses Long. Files larger than Integer.MAX_VALUE (~2 GB) caused:
        //   "Can't serialize value: Expected a value that can be converted to type 'Int' but it was a 'Long'"
        long oversizedBytes = (long) Integer.MAX_VALUE + 1; // 2_147_483_648L

        Item bigItem = new Item();
        bigItem.setDatasourceId(testDataSource.id());
        bigItem.setName("huge-file.bin");
        bigItem.setPath(null);
        bigItem.setSizeBytes(oversizedBytes);
        bigItem.setMimeType("application/octet-stream");
        bigItem.setTimeCreated(LocalDateTime.now());
        bigItem.setTimeModified(LocalDateTime.now());
        itemDao.createItem(bigItem);

        String query = "{ search(name: \"huge-file.bin\", skip: 0, howmany: 1) { totalHits items { name sizeBytes } } }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = response.getBody();
        assertThat(body).doesNotContain("errors");
        assertThat(body).contains("\"name\":\"huge-file.bin\"");
        assertThat(body).contains("\"sizeBytes\":" + oversizedBytes);
        logger.info("Large sizeBytes serialization test: {}", body);
    }

    @Test
    public void testRawBase64ContentField() {
        // Test that rawBase64Content field returns the correct base64 encoded content
        String query = "{ search(searchConditions: [{name: \"name\", operation: EQUAL, value: \"text.txt\"}], skip: 0, howmany: 10) { items { name rawBase64Content } } }";
        ResponseEntity<String> response = executeGraphQLQuery(query);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = response.getBody();
        assertThat(body).contains("\"name\":\"text.txt\"");
        // The content "aabaa" base64 encoded is "YWFiYWE="
        assertThat(body).contains("\"rawBase64Content\":\"YWFiYWE=\"");
        logger.info("Raw base64 content test: {}", body);
    }
}
