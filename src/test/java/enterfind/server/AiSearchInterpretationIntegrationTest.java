package enterfind.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;

import tools.jackson.databind.JsonNode;

/**
 * Integration tests for AI Search Interpretation GraphQL API.
 * Tests the interpretSearchQuery mutation.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class AiSearchInterpretationIntegrationTest extends BaseDatabaseTest {

    private static final Logger logger = LoggerFactory.getLogger(AiSearchInterpretationIntegrationTest.class);

    @LocalServerPort
    private int port;


    private GraphQLTestClient graphQLClient;
    private FakeOllamaServer fakeOllamaServer;

    @Override
    protected void setUp() {
        // Override to prevent database wipe before each test
        // since we're using @BeforeAll for one-time setup
    }

    @BeforeAll
    void setUpTestData() throws Exception {
        wipeDatabase();  // Clean once before all tests
        graphQLClient = new GraphQLTestClient(port);

        // Start fake Ollama server
        fakeOllamaServer = new FakeOllamaServer();
        fakeOllamaServer.start();
        logger.info("Fake Ollama server started on {}", fakeOllamaServer.getBaseUrl());

        // Configure Ollama settings via GraphQL
        String setOllamaUrl = String.format(
            "mutation { setAiSetting(settingName: \"OllamaApiUrl\", value: \"%s\") { settingName value } }",
            fakeOllamaServer.getBaseUrl()
        );
        graphQLClient.executeAndGetData(setOllamaUrl);

        String setOllamaModel = "mutation { setAiSetting(settingName: \"OllamaModelName\", value: \"llama2\") { settingName value } }";
        graphQLClient.executeAndGetData(setOllamaModel);

        // Set AI provider to OLLAMA via GraphQL
        String setProvider = "mutation { setAiSetting(settingName: \"AiProvider\", value: \"OLLAMA\") { settingName value } }";
        graphQLClient.executeAndGetData(setProvider);
        
        logger.info("Configured Ollama via GraphQL");
    }

    @AfterAll
    void tearDown() {
        if (fakeOllamaServer != null) {
            fakeOllamaServer.stop();
        }
    }

    @Test
    public void testInterpretSearchQuery_PdfFiles() {
        // Given: A natural language query for PDF files
        String query = "find all pdf files";

        // When: Interpreting the search query
        String mutation = String.format(
            """
            mutation {
                interpretSearchQuery(input: { naturalLanguageQuery: "%s" }) {
                    naturalLanguageQuery
                    success
                    searchConditions { name operation value }
                    searchString
                    explanation
                    confidence
                }
            }
            """,
            query
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);
        JsonNode result = data.path("interpretSearchQuery");

        // Then: Should return successful interpretation with content-type filter
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("naturalLanguageQuery").asString()).isEqualTo(query);
        assertThat(result.path("searchConditions").isArray()).isTrue();
        assertThat(result.path("searchConditions").size()).isEqualTo(1);
        
        JsonNode condition = result.path("searchConditions").get(0);
        assertThat(condition.path("name").asString()).isEqualTo("content-type");
        assertThat(condition.path("operation").asString()).isEqualTo("LIKE");
        assertThat(condition.path("value").asString()).contains("pdf");
        
        assertThat(result.path("explanation").asString()).isNotEmpty();
        assertThat(result.path("confidence").asDouble()).isGreaterThan(0.0);
    }

    @Test
    public void testInterpretSearchQuery_FilesInFolder() {
        // Given: A natural language query for files in a folder
        String query = "files in folder documents";

        // When: Interpreting the search query
        String mutation = String.format(
            """
            mutation {
                interpretSearchQuery(input: { naturalLanguageQuery: "%s" }) {
                    success
                    searchConditions { name operation value }
                    explanation
                }
            }
            """,
            query
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);
        JsonNode result = data.path("interpretSearchQuery");

        // Then: Should return path filter with LIKE operation
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("searchConditions").size()).isEqualTo(1);
        
        JsonNode condition = result.path("searchConditions").get(0);
        assertThat(condition.path("name").asString()).isEqualTo("path");
        assertThat(condition.path("operation").asString()).isEqualTo("LIKE");
        assertThat(condition.path("value").asString()).contains("documents");
    }

    @Test
    public void testInterpretSearchQuery_FilesNotInFolder() {
        // Given: A natural language query excluding a folder
        String query = "files not in folder archive";

        // When: Interpreting the search query
        String mutation = String.format(
            """
            mutation {
                interpretSearchQuery(input: { naturalLanguageQuery: "%s" }) {
                    success
                    searchConditions { name operation value }
                    explanation
                }
            }
            """,
            query
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);
        JsonNode result = data.path("interpretSearchQuery");

        // Then: Should return path filter with NOT_LIKE operation
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("searchConditions").size()).isEqualTo(1);
        
        JsonNode condition = result.path("searchConditions").get(0);
        assertThat(condition.path("name").asString()).isEqualTo("path");
        assertThat(condition.path("operation").asString()).isEqualTo("NOT_LIKE");
        assertThat(condition.path("value").asString()).contains("archive");
    }

    @Test
    public void testInterpretSearchQuery_ImageFiles() {
        // Given: A natural language query for images
        String query = "show me all images";

        // When: Interpreting the search query
        String mutation = String.format(
            """
            mutation {
                interpretSearchQuery(input: { naturalLanguageQuery: "%s" }) {
                    success
                    searchConditions { name operation value }
                    confidence
                }
            }
            """,
            query
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);
        JsonNode result = data.path("interpretSearchQuery");

        // Then: Should return content-type filter for images
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("searchConditions").size()).isEqualTo(1);
        
        JsonNode condition = result.path("searchConditions").get(0);
        assertThat(condition.path("name").asString()).isEqualTo("content-type");
        assertThat(condition.path("operation").asString()).isEqualTo("LIKE");
        assertThat(condition.path("value").asString()).contains("image");
    }

    @Test
    public void testInterpretSearchQuery_ContentSearch() {
        // Given: A natural language query for content search
        String query = "documents containing budget";

        // When: Interpreting the search query
        String mutation = String.format(
            """
            mutation {
                interpretSearchQuery(input: { naturalLanguageQuery: "%s" }) {
                    success
                    searchConditions { name operation value }
                    searchString
                    explanation
                }
            }
            """,
            query
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);
        JsonNode result = data.path("interpretSearchQuery");

        // Then: Should return searchString for free text search
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("searchString").asString()).contains("budget");
        assertThat(result.path("searchConditions").size()).isEqualTo(0);
    }

    @Test
    public void testInterpretSearchQuery_FilesByName() {
        // Given: A natural language query for files by name
        String query = "files named report";

        // When: Interpreting the search query
        String mutation = String.format(
            """
            mutation {
                interpretSearchQuery(input: { naturalLanguageQuery: "%s" }) {
                    success
                    searchConditions { name operation value }
                }
            }
            """,
            query
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);
        JsonNode result = data.path("interpretSearchQuery");

        // Then: Should return name filter
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("searchConditions").size()).isEqualTo(1);
        
        JsonNode condition = result.path("searchConditions").get(0);
        assertThat(condition.path("name").asString()).isEqualTo("name");
        assertThat(condition.path("operation").asString()).isEqualTo("LIKE");
        assertThat(condition.path("value").asString()).contains("report");
    }

    @Test
    public void testInterpretSearchQuery_WithAvailableKeys() {
        // Given: A query with available keys provided
        String query = "find pdf files";

        // When: Interpreting with available keys
        String mutation = String.format(
            """
            mutation {
                interpretSearchQuery(input: { 
                    naturalLanguageQuery: "%s",
                    availableKeys: ["content-type", "path", "name", "author", "created-date"]
                }) {
                    success
                    searchConditions { name operation value }
                    confidence
                }
            }
            """,
            query
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);
        JsonNode result = data.path("interpretSearchQuery");

        // Then: Should still interpret correctly
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("searchConditions").size()).isGreaterThan(0);
    }

    @Test
    public void testInterpretSearchQuery_EmptyQuery() {
        // Given: An empty query

        // When: Interpreting empty query
        String mutation = """
            mutation {
                interpretSearchQuery(input: { naturalLanguageQuery: "" }) {
                    success
                    explanation
                }
            }
            """;
        JsonNode data = graphQLClient.executeAndGetData(mutation);
        JsonNode result = data.path("interpretSearchQuery");

        // Then: Should return error result
        assertThat(result.path("success").asBoolean()).isFalse();
        assertThat(result.path("explanation").asString()).isNotEmpty();
    }

    @Test
    public void testInterpretSearchQuery_AiNotConfigured() {
        // Given: AI provider set to NONE
        String clearProvider = "mutation { setAiSetting(settingName: \"AiProvider\", value: \"NONE\") { settingName value } }";
        graphQLClient.executeAndGetData(clearProvider);

        try {
            // When: Interpreting when AI is not configured
            String mutation = """
                mutation {
                    interpretSearchQuery(input: { naturalLanguageQuery: "find pdf files" }) {
                        success
                        explanation
                    }
                }
                """;
            JsonNode data = graphQLClient.executeAndGetData(mutation);
            JsonNode result = data.path("interpretSearchQuery");

            // Then: Should return error result
            assertThat(result.path("success").asBoolean()).isFalse();
            assertThat(result.path("explanation").asString()).containsIgnoringCase("not configured");
        } finally {
            // Restore AI provider
            String restoreProvider = "mutation { setAiSetting(settingName: \"AiProvider\", value: \"OLLAMA\") { settingName value } }";
            graphQLClient.executeAndGetData(restoreProvider);
        }
    }

    @Test
    public void testInterpretSearchQuery_ServerError() {
        // Given: Fake server configured to fail
        fakeOllamaServer.setShouldFail(true);

        try {
            // When: Interpreting when server fails
            String mutation = """
                mutation {
                    interpretSearchQuery(input: { naturalLanguageQuery: "find pdf files" }) {
                        success
                        explanation
                    }
                }
                """;
            JsonNode data = graphQLClient.executeAndGetData(mutation);
            JsonNode result = data.path("interpretSearchQuery");

            // Then: Should return error result (not throw exception)
            assertThat(result.path("success").asBoolean()).isFalse();
            assertThat(result.path("explanation").asString()).isNotEmpty();
        } finally {
            // Restore server state
            fakeOllamaServer.setShouldFail(false);
        }
    }

    @Test
    public void testInterpretSearchQuery_CustomResponseProvider() {
        // Given: Register custom response provider for testing
        fakeOllamaServer.registerResponseProvider(
            "top secret documents",
            prompt -> """
                {
                    "searchString": null,
                    "searchConditions": [
                        {"name": "classification", "operation": "EQUAL", "value": "TOP_SECRET"}
                    ],
                    "explanation": "Finding top secret classified documents",
                    "confidence": 0.99
                }"""
        );

        try {
            // When: Interpreting with custom provider
            String mutation = """
                mutation {
                    interpretSearchQuery(input: { naturalLanguageQuery: "find top secret documents" }) {
                        success
                        searchConditions { name operation value }
                        confidence
                    }
                }
                """;
            JsonNode data = graphQLClient.executeAndGetData(mutation);
            JsonNode result = data.path("interpretSearchQuery");

            // Then: Should return custom interpretation
            assertThat(result.path("success").asBoolean()).isTrue();
            assertThat(result.path("searchConditions").size()).isEqualTo(1);
            
            JsonNode condition = result.path("searchConditions").get(0);
            assertThat(condition.path("name").asString()).isEqualTo("classification");
            assertThat(condition.path("operation").asString()).isEqualTo("EQUAL");
            assertThat(condition.path("value").asString()).isEqualTo("TOP_SECRET");
            assertThat(result.path("confidence").asDouble()).isEqualTo(0.99);
        } finally {
            // Clean up custom response provider
            fakeOllamaServer.registerResponseProvider("top secret documents", null);
        }
    }

    @Test
    public void testInterpretSearchQuery_SpreadsheetFiles() {
        // Given: Query for spreadsheet files
        String query = "find all excel spreadsheet files";

        // When: Interpreting the search query
        String mutation = String.format(
            """
            mutation {
                interpretSearchQuery(input: { naturalLanguageQuery: "%s" }) {
                    success
                    searchConditions { name operation value }
                }
            }
            """,
            query
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);
        JsonNode result = data.path("interpretSearchQuery");

        // Then: Should return spreadsheet content type filter
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("searchConditions").size()).isEqualTo(1);
        
        JsonNode condition = result.path("searchConditions").get(0);
        assertThat(condition.path("name").asString()).isEqualTo("content-type");
        assertThat(condition.path("operation").asString()).isEqualTo("LIKE");
        assertThat(condition.path("value").asString()).contains("spreadsheet");
    }

    @Test
    public void testInterpretSearchQuery_FreeTextFallback() {
        // Given: An ambiguous query that should fall back to free text
        String query = "quarterly sales data";

        // When: Interpreting the search query
        String mutation = String.format(
            """
            mutation {
                interpretSearchQuery(input: { naturalLanguageQuery: "%s" }) {
                    success
                    searchConditions { name operation value }
                    searchString
                    confidence
                }
            }
            """,
            query
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);
        JsonNode result = data.path("interpretSearchQuery");

        // Then: Should return as free text search with lower confidence
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("searchString").asString()).isNotEmpty();
        assertThat(result.path("confidence").asDouble()).isLessThan(0.8);
    }
}
