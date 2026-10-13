package enterfind.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class TreatmentGraphQLIntegrationTest extends BaseDatabaseTest {

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    private GraphQLTestClient graphQLClient;

    private String testDataSourceName = "test-datasource";
    private String testDataSourceType = "LOCAL_FILESYSTEM";

    @BeforeEach
    void setUpTestData() {
        super.setUp();
        graphQLClient = new GraphQLTestClient(port);
        // Create test datasource for all tests
        createTestDataSource();
    }

    private void createTestDataSource() {
        // Create a test datasource using GraphQL mutation
        String graphqlQuery = String.format("""
            mutation {
                createLocalFileSystemDataSource(input: {
                    name: "%s",
                    cronPattern: "0 0 * * *",
                    localPath: "/tmp/test"
                }) {
                    name
                }
            }
            """, testDataSourceName);

        ResponseEntity<String> response = executeGraphQL(graphqlQuery);
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Failed to create test datasource: " + response.getBody());
        }
    }

    private ResponseEntity<String> executeGraphQL(String query) {
        return graphQLClient.execute(query);
    }

    @Test
    void testAddDataSourceTreatment() throws Exception {

        // When
        String graphqlQuery = String.format("""
            mutation {
                addDataSourceTreatment(input: {
                    dataSourceName: "%s",
                    dataSourceType: %s,
                    treatment: CONTENT,
                    config: \"{\\\"testKey\\\": \\\"testValue\\\"}\"
                }) {
                    treatment
                    config
                    dateCreated
                }
            }
            """, testDataSourceName, testDataSourceType);

        ResponseEntity<String> response = executeGraphQL(graphqlQuery);

        // Then
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());
        JsonNode treatment = jsonResponse.path("data").path("addDataSourceTreatment");
        
        assertThat(treatment.path("treatment").asString()).isEqualTo("CONTENT");
        
        // Database assertion: Verify treatment was actually persisted
        Integer treatmentCount = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM DATASOURCE_TREATMENTS dt " +
            "JOIN DATA_SOURCES ds ON dt.datasource_id = ds.id " +
            "WHERE ds.name = ? AND ds.type = ? AND dt.treatment = 'CONTENT'", 
            Integer.class, testDataSourceName, testDataSourceType);
        assertThat(treatmentCount).isEqualTo(1);
        
        // Verify the config was stored correctly (PostgreSQL formats JSON with spaces)
        String storedConfig = dbAccess.queryForObject(
            "SELECT dt.config FROM DATASOURCE_TREATMENTS dt " +
            "JOIN DATA_SOURCES ds ON dt.datasource_id = ds.id " +
            "WHERE ds.name = ? AND ds.type = ? AND dt.treatment = 'CONTENT'", 
            String.class, testDataSourceName, testDataSourceType);
        assertThat(storedConfig).contains("testKey").contains("testValue");
    }

    @Test
    void testQueryDataSourceTreatments() throws Exception {
        // Given - add some treatments first
        String addContentTreatment = String.format("""
            mutation {
                addDataSourceTreatment(input: {
                    dataSourceName: "%s",
                    dataSourceType: %s,
                    treatment: CONTENT,
                    config: null
                }) {
                    treatment
                }
            }
            """, testDataSourceName, testDataSourceType);

        String addOcrTreatment = String.format("""
            mutation {
                addDataSourceTreatment(input: {
                    dataSourceName: "%s",
                    dataSourceType: %s,
                    treatment: OCR,
                    config: \"{\\\"lang\\\": \\\"en\\\"}\"
                }) {
                    treatment
                }
            }
            """, testDataSourceName, testDataSourceType);

        executeGraphQL(addContentTreatment);
        executeGraphQL(addOcrTreatment);

        // When - Query treatments
        String query = String.format("""
            query {
                dataSourceTreatments(dataSourceName: "%s", dataSourceType: %s) {
                    treatment
                    config
                }
            }
            """, testDataSourceName, testDataSourceType);

        ResponseEntity<String> response = executeGraphQL(query);

        // Then
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        
        JsonNode jsonResponse = objectMapper.readTree(response.getBody());
        JsonNode treatments = jsonResponse.path("data").path("dataSourceTreatments");
        
        assertThat(treatments.isArray()).isTrue();
        assertThat(treatments).hasSize(3);  // NAME_AND_PATH (auto-added) + CONTENT + OCR
        
        // Find and verify CONTENT treatment
        JsonNode contentTreatment = null;
        JsonNode ocrTreatment = null;
        
        for (JsonNode treatment : treatments) {
            if ("CONTENT".equals(treatment.path("treatment").asString())) {
                contentTreatment = treatment;
            } else if ("OCR".equals(treatment.path("treatment").asString())) {
                ocrTreatment = treatment;
            }
        }
        
        assertThat(contentTreatment).isNotNull();
        if (contentTreatment != null) {
            assertThat(contentTreatment.path("config").isNull()).isTrue();
        }

        assertThat(ocrTreatment).isNotNull();
        if (ocrTreatment != null) {
            assertThat(ocrTreatment.path("config").asString()).isEqualTo("{\"lang\":\"en\"}");
        }
        
                // Database assertion: Verify all treatments are persisted (NAME_AND_PATH auto-added + CONTENT + OCR)\n        Integer totalTreatmentsCount = jdbcTemplate.queryForObject(\n            \"SELECT COUNT(*) FROM DATASOURCE_TREATMENTS dt \" +\n            \"JOIN DATA_SOURCES ds ON dt.datasource_id = ds.id \" +\n            \"WHERE ds.name = ? AND ds.type = ?\", \n            Integer.class, testDataSourceName, testDataSourceType);\n        assertThat(totalTreatmentsCount).isEqualTo(3);  // NAME_AND_PATH + CONTENT + OCR
        
        // Verify specific treatments exist in database  
        Integer contentTreatmentCount = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM DATASOURCE_TREATMENTS dt " +
            "JOIN DATA_SOURCES ds ON dt.datasource_id = ds.id " +
            "WHERE ds.name = ? AND ds.type = ? AND dt.treatment = 'CONTENT'", 
            Integer.class, testDataSourceName, testDataSourceType);
        assertThat(contentTreatmentCount).isEqualTo(1);
        
        Integer ocrTreatmentCount = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM DATASOURCE_TREATMENTS dt " +
            "JOIN DATA_SOURCES ds ON dt.datasource_id = ds.id " +
            "WHERE ds.name = ? AND ds.type = ? AND dt.treatment = 'OCR' AND dt.config::text LIKE '%lang%'", 
            Integer.class, testDataSourceName, testDataSourceType);
        assertThat(ocrTreatmentCount).isEqualTo(1);
    }

    @Test
    void testRemoveDataSourceTreatment() throws Exception {
        // Given - add a treatment first
        String addTreatment = String.format("""
            mutation {
                addDataSourceTreatment(input: {
                    dataSourceName: "%s",
                    dataSourceType: %s,
                    treatment: CONTENT,
                    config: null
                }) {
                    treatment
                }
            }
            """, testDataSourceName, testDataSourceType);

        ResponseEntity<String> addResponse = executeGraphQL(addTreatment);
        assertThat(addResponse.getStatusCode().is2xxSuccessful()).isTrue();

        // When - Remove the treatment
        String removeTreatment = String.format("""
            mutation {
                removeDataSourceTreatment(
                    dataSourceName: "%s",
                    dataSourceType: %s,
                    treatment: CONTENT
                )
            }
            """, testDataSourceName, testDataSourceType);

        ResponseEntity<String> removeResponse = executeGraphQL(removeTreatment);

        // Then
        assertThat(removeResponse.getStatusCode().is2xxSuccessful()).isTrue();
        
        JsonNode jsonResponse = objectMapper.readTree(removeResponse.getBody());
        JsonNode result = jsonResponse.path("data").path("removeDataSourceTreatment");
        
        assertThat(result.asBoolean()).isTrue();

        // Verify it's actually removed by querying treatments
        String queryTreatments = String.format("""
            query {
                dataSourceTreatments(dataSourceName: "%s", dataSourceType: %s) {
                    treatment
                }
            }
            """, testDataSourceName, testDataSourceType);

        ResponseEntity<String> queryResponse = executeGraphQL(queryTreatments);
        JsonNode queryJsonResponse = objectMapper.readTree(queryResponse.getBody());
        JsonNode treatments = queryJsonResponse.path("data").path("dataSourceTreatments");
        
        // Should have only NAME_AND_PATH remaining (auto-added on datasource creation)
        assertThat(treatments.isArray()).isTrue();
        assertThat(treatments).hasSize(1);
        assertThat(treatments.get(0).path("treatment").asString()).isEqualTo("NAME_AND_PATH");
        
        // Database assertion: Verify treatment was actually removed from database
        Integer remainingTreatmentsCount = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM DATASOURCE_TREATMENTS dt " +
            "JOIN DATA_SOURCES ds ON dt.datasource_id = ds.id " +
            "WHERE ds.name = ? AND ds.type = ? AND dt.treatment = 'CONTENT'", 
            Integer.class, testDataSourceName, testDataSourceType);
        assertThat(remainingTreatmentsCount).isEqualTo(0);
        
        // Verify the datasource still exists (only the treatment was removed)
        Integer datasourceCount = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM DATA_SOURCES WHERE name = ? AND type = ?", 
            Integer.class, testDataSourceName, testDataSourceType);
        assertThat(datasourceCount).isEqualTo(1);
    }

    @Test
    void testCannotRemoveRequiredNameAndPathTreatment() throws Exception {
        String removeTreatment = String.format("""
            mutation {
                removeDataSourceTreatment(
                    dataSourceName: "%s",
                    dataSourceType: %s,
                    treatment: NAME_AND_PATH
                )
            }
            """, testDataSourceName, testDataSourceType);

        ResponseEntity<String> response = executeGraphQL(removeTreatment);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

        JsonNode jsonResponse = objectMapper.readTree(response.getBody());
        assertThat(jsonResponse.has("errors")).isTrue();
        JsonNode errors = jsonResponse.get("errors");
        assertThat(errors.isArray()).isTrue();
        assertThat(errors).hasSize(1);

        JsonNode error = errors.get(0);
        assertThat(error.path("message").asString()).contains("NAME_AND_PATH treatment is required");
        assertThat(error.path("extensions").path("errorType").asString()).isEqualTo("INVALID_ARGUMENT");
        assertThat(error.path("extensions").path("errorCode").asString()).isEqualTo("BAD_REQUEST");

        Integer nameAndPathTreatmentCount = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM DATASOURCE_TREATMENTS dt " +
            "JOIN DATA_SOURCES ds ON dt.datasource_id = ds.id " +
            "WHERE ds.name = ? AND ds.type = ? AND dt.treatment = 'NAME_AND_PATH'",
            Integer.class, testDataSourceName, testDataSourceType);
        assertThat(nameAndPathTreatmentCount).isEqualTo(1);
    }

    @Test
    void testUpdateDataSourceTreatmentConfig() throws Exception {
        // Given - add a treatment first
        String addTreatment = String.format("""
            mutation {
                addDataSourceTreatment(input: {
                    dataSourceName: "%s",
                    dataSourceType: %s,
                    treatment: CONTENT,
                    config: \"{\\\"old\\\": true}\"
                }) {
                    treatment
                }
            }
            """, testDataSourceName, testDataSourceType);

        ResponseEntity<String> addResponse = executeGraphQL(addTreatment);
        assertThat(addResponse.getStatusCode().is2xxSuccessful()).isTrue();

        // When - Update the treatment config
        String updateTreatment = String.format("""
            mutation {
                updateDataSourceTreatmentConfig(input: {
                    dataSourceName: "%s",
                    dataSourceType: %s,
                    treatment: CONTENT,
                    config: \"{\\\"updated\\\": true}\"
                }) {
                    treatment
                    config
                }
            }
            """, testDataSourceName, testDataSourceType);

        ResponseEntity<String> updateResponse = executeGraphQL(updateTreatment);

        // Then
        assertThat(updateResponse.getStatusCode().is2xxSuccessful()).isTrue();
        
        JsonNode jsonResponse = objectMapper.readTree(updateResponse.getBody());
        JsonNode treatment = jsonResponse.path("data").path("updateDataSourceTreatmentConfig");
        
        assertThat(treatment.path("treatment").asString()).isEqualTo("CONTENT");
        assertThat(treatment.path("config").asString()).isEqualTo("{\"updated\":true}");
        
        // Database assertion: Verify config was actually updated in database
        // Verify the updated config was stored correctly
        String updatedConfig = dbAccess.queryForObject(
            "SELECT dt.config FROM DATASOURCE_TREATMENTS dt " +
            "JOIN DATA_SOURCES ds ON dt.datasource_id = ds.id " +
            "WHERE ds.name = ? AND ds.type = ? AND dt.treatment = 'CONTENT'", 
            String.class, testDataSourceName, testDataSourceType);
        assertThat(updatedConfig).contains("updated").contains("true");
        
        // Verify the date_modified field was updated (should not be null)
        String dateModified = dbAccess.queryForObject(
            "SELECT date_modified::text FROM DATASOURCE_TREATMENTS dt " +
            "JOIN DATA_SOURCES ds ON dt.datasource_id = ds.id " +
            "WHERE ds.name = ? AND ds.type = ? AND dt.treatment = 'CONTENT'", 
            String.class, testDataSourceName, testDataSourceType);
        assertThat(dateModified).isNotNull();
        assertThat(dateModified).isNotEmpty();
        
        // Verify only one treatment exists (the updated one)
        Integer treatmentCount = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM DATASOURCE_TREATMENTS dt " +
            "JOIN DATA_SOURCES ds ON dt.datasource_id = ds.id " +
            "WHERE ds.name = ? AND ds.type = ? AND dt.treatment = 'CONTENT'", 
            Integer.class, testDataSourceName, testDataSourceType);
        assertThat(treatmentCount).isEqualTo(1);
    }


}