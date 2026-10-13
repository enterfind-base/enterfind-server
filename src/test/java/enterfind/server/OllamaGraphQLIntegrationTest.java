package enterfind.server;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.test.annotation.DirtiesContext;

import tools.jackson.databind.JsonNode;

import enterfind.server.dao.DBAccess;
import enterfind.server.model.AiSettingName;

/**
 * Integration tests for Ollama AI Helper GraphQL API.
 * Tests the testOllamaConnection, interpretCronExpression, explainCronExpression,
 * interpretSkipPattern, and explainSkipPattern mutations.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class OllamaGraphQLIntegrationTest extends BaseDatabaseTest {

    private static final Logger logger = LoggerFactory.getLogger(OllamaGraphQLIntegrationTest.class);

    @LocalServerPort
    private int port;

   

    @Autowired
    private DBAccess dbAccess;

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

        // Verify default AI provider is NONE
        String queryProvider = "query { aiProvider }";
        JsonNode providerData = graphQLClient.executeAndGetData(queryProvider);
        String defaultProvider = providerData.path("aiProvider").asString();
        assertThat(defaultProvider).isEqualTo("NONE");
        logger.info("Verified default AI provider is NONE");

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
    public void testOllamaConnection_Success() {
        // Given: Ollama settings are configured (done in @BeforeAll)

        // When: Testing connection
        String mutation = "mutation { testAiConnection }";
        JsonNode data = graphQLClient.executeAndGetData(mutation);

        // Then: Connection should be successful
        boolean result = data.path("testAiConnection").asBoolean();
        assertThat(result).isTrue();
    }

    @Test
    public void testOllamaConnection_NoUrlConfigured() {
        // Given: No Ollama URL configured (set via GraphQL)
        String clearUrl = "mutation { setAiSetting(settingName: \"OllamaApiUrl\", value: \"\") { settingName value } }";
        graphQLClient.executeAndGetData(clearUrl);

        try {
            // When: Testing connection
            String mutation = "mutation { testAiConnection }";
            JsonNode data = graphQLClient.executeAndGetData(mutation);

            // Then: Connection should fail
            boolean result = data.path("testAiConnection").asBoolean();
            assertThat(result).isFalse();
        } finally {
            // Restore settings for other tests via GraphQL
            String restoreUrl = String.format(
                "mutation { setAiSetting(settingName: \"OllamaApiUrl\", value: \"%s\") { settingName value } }",
                fakeOllamaServer.getBaseUrl()
            );
            graphQLClient.executeAndGetData(restoreUrl);
        }
    }

    @Test
    public void testOllamaConnection_ServerDown() {
        // Given: Fake server configured to fail
        fakeOllamaServer.setShouldFail(true);

        try {
            // When: Testing connection
            String mutation = "mutation { testAiConnection }";
            JsonNode data = graphQLClient.executeAndGetData(mutation);

            // Then: Connection should fail
            boolean result = data.path("testAiConnection").asBoolean();
            assertThat(result).isFalse();
        } finally {
            // Restore server state
            fakeOllamaServer.setShouldFail(false);
        }
    }

    @Test
    public void testInterpretCronExpression_Every2Hours() {
        // Given: Natural language input
        String naturalLanguage = "every 2 hours";

        // When: Interpreting cron expression
        String mutation = String.format(
            "mutation { interpretCronExpression(naturalLanguageInput: \"%s\") { cronExpression naturalLanguageInput } }",
            naturalLanguage
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);

        // Then: Should return correct cron expression
        JsonNode result = data.path("interpretCronExpression");
        assertThat(result.path("cronExpression").asString()).isEqualTo("0 */2 * * *");
        assertThat(result.path("naturalLanguageInput").asString()).isEqualTo(naturalLanguage);
    }

    @Test
    public void testInterpretCronExpression_DailyAt3AM() {
        // Given: Natural language input
        String naturalLanguage = "every day at 3am";

        // When: Interpreting cron expression
        String mutation = String.format(
            "mutation { interpretCronExpression(naturalLanguageInput: \"%s\") { cronExpression naturalLanguageInput } }",
            naturalLanguage
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);

        // Then: Should return correct cron expression
        JsonNode result = data.path("interpretCronExpression");
        assertThat(result.path("cronExpression").asString()).isEqualTo("0 3 * * *");
        assertThat(result.path("naturalLanguageInput").asString()).isEqualTo(naturalLanguage);
    }

    @Test
    public void testInterpretCronExpression_MondayAt9AM() {
        // Given: Natural language input
        String naturalLanguage = "every Monday at 9am";

        // When: Interpreting cron expression
        String mutation = String.format(
            "mutation { interpretCronExpression(naturalLanguageInput: \"%s\") { cronExpression naturalLanguageInput } }",
            naturalLanguage
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);

        // Then: Should return correct cron expression
        JsonNode result = data.path("interpretCronExpression");
        assertThat(result.path("cronExpression").asString()).isEqualTo("0 9 * * 1");
        assertThat(result.path("naturalLanguageInput").asString()).isEqualTo(naturalLanguage);
    }

    @Test
    public void testInterpretCronExpression_Every15Minutes() {
        // Given: Natural language input
        String naturalLanguage = "every 15 minutes";

        // When: Interpreting cron expression
        String mutation = String.format(
            "mutation { interpretCronExpression(naturalLanguageInput: \"%s\") { cronExpression naturalLanguageInput } }",
            naturalLanguage
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);

        // Then: Should return correct cron expression
        JsonNode result = data.path("interpretCronExpression");
        assertThat(result.path("cronExpression").asString()).isEqualTo("*/15 * * * *");
        assertThat(result.path("naturalLanguageInput").asString()).isEqualTo(naturalLanguage);
    }

    @Test
    public void testExplainCronExpression_Every2Hours() {
        // Given: Cron expression
        String cronExpression = "0 */2 * * *";

        // When: Explaining cron expression
        String mutation = String.format(
            "mutation { explainCronExpression(cronExpression: \"%s\") { cronExpression naturalLanguageExplanation } }",
            cronExpression
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);

        // Then: Should return natural language explanation
        JsonNode result = data.path("explainCronExpression");
        assertThat(result.path("cronExpression").asString()).isEqualTo(cronExpression);
        assertThat(result.path("naturalLanguageExplanation").asString()).isEqualTo("Every 2 hours");
    }

    @Test
    public void testExplainCronExpression_DailyAt3AM() {
        // Given: Cron expression
        String cronExpression = "0 3 * * *";

        // When: Explaining cron expression
        String mutation = String.format(
            "mutation { explainCronExpression(cronExpression: \"%s\") { cronExpression naturalLanguageExplanation } }",
            cronExpression
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);

        // Then: Should return natural language explanation
        JsonNode result = data.path("explainCronExpression");
        assertThat(result.path("cronExpression").asString()).isEqualTo(cronExpression);
        assertThat(result.path("naturalLanguageExplanation").asString()).isEqualTo("Every day at 3:00 AM");
    }

    @Test
    public void testExplainCronExpression_MondayAt9AM() {
        // Given: Cron expression
        String cronExpression = "0 9 * * 1";

        // When: Explaining cron expression
        String mutation = String.format(
            "mutation { explainCronExpression(cronExpression: \"%s\") { cronExpression naturalLanguageExplanation } }",
            cronExpression
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);

        // Then: Should return natural language explanation
        JsonNode result = data.path("explainCronExpression");
        assertThat(result.path("cronExpression").asString()).isEqualTo(cronExpression);
        assertThat(result.path("naturalLanguageExplanation").asString()).isEqualTo("Every Monday at 9:00 AM");
    }

    @Test
    public void testExplainCronExpression_Every15Minutes() {
        // Given: Cron expression
        String cronExpression = "*/15 * * * *";

        // When: Explaining cron expression
        String mutation = String.format(
            "mutation { explainCronExpression(cronExpression: \"%s\") { cronExpression naturalLanguageExplanation } }",
            cronExpression
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);

        // Then: Should return natural language explanation
        JsonNode result = data.path("explainCronExpression");
        assertThat(result.path("cronExpression").asString()).isEqualTo(cronExpression);
        assertThat(result.path("naturalLanguageExplanation").asString()).isEqualTo("Every 15 minutes");
    }

    @Test
    public void testRoundTrip_InterpretThenExplain() {
        // Given: Natural language input
        String naturalLanguage = "every 2 hours";

        // When: First interpreting to cron
        String interpretMutation = String.format(
            "mutation { interpretCronExpression(naturalLanguageInput: \"%s\") { cronExpression } }",
            naturalLanguage
        );
        JsonNode interpretData = graphQLClient.executeAndGetData(interpretMutation);
        String cronExpression = interpretData.path("interpretCronExpression").path("cronExpression").asString();

        // Then: Cron should be correct
        assertThat(cronExpression).isEqualTo("0 */2 * * *");

        // When: Explaining the cron back to natural language
        String explainMutation = String.format(
            "mutation { explainCronExpression(cronExpression: \"%s\") { naturalLanguageExplanation } }",
            cronExpression
        );
        JsonNode explainData = graphQLClient.executeAndGetData(explainMutation);
        String explanation = explainData.path("explainCronExpression").path("naturalLanguageExplanation").asString();

        // Then: Explanation should make sense
        assertThat(explanation).isEqualTo("Every 2 hours");
    }

    @Test
    public void testInterpretCronExpression_CustomResponse() {
        // Given: Register custom response provider
        fakeOllamaServer.registerResponseProvider(
            "custom schedule",
            prompt -> "0 0 12 * * ?" // Custom cron for testing
        );

        try {
            // When: Interpreting custom schedule
            String mutation = "mutation { interpretCronExpression(naturalLanguageInput: \"custom schedule\") { cronExpression } }";
            JsonNode data = graphQLClient.executeAndGetData(mutation);

            // Then: Should return custom cron
            String cronExpression = data.path("interpretCronExpression").path("cronExpression").asString();
            assertThat(cronExpression).isEqualTo("0 0 12 * * ?");
        } finally {
            // Clean up custom response provider
            fakeOllamaServer.registerResponseProvider("custom schedule", null);
        }
    }

    @Test
    public void testAiSettingsIntegration() {
        // Given: Query AI settings
        String query = "{ aiSettings { settingName value } }";
        JsonNode data = graphQLClient.executeAndGetData(query);

        // Then: Should include Ollama settings
        JsonNode settings = data.path("aiSettings");
        assertThat(settings.isArray()).isTrue();

        boolean foundOllamaUrl = false;
        boolean foundOllamaModel = false;

        for (JsonNode setting : settings) {
            String name = setting.path("settingName").asString();
            if (name.equals("OllamaApiUrl")) {
                foundOllamaUrl = true;
                assertThat(setting.path("value").asString()).isEqualTo(fakeOllamaServer.getBaseUrl());
            } else if (name.equals("OllamaModelName")) {
                foundOllamaModel = true;
                assertThat(setting.path("value").asString()).isEqualTo("llama2");
            }
        }

        assertThat(foundOllamaUrl).isTrue();
        assertThat(foundOllamaModel).isTrue();
    }

    @Test
    public void testAiSettings_redactsSensitiveKeys() {
        // Given: a sensitive key is configured
        String setKey = "mutation { setAiSetting(settingName: \"OpenAiApiKey\", value: \"sk-secret-value\") { settingName value } }";
        graphQLClient.executeAndGetData(setKey);

        // When: the settings are read back over GraphQL (the browser-facing path)
        JsonNode settings = graphQLClient.executeAndGetData("{ aiSettings { settingName value } }").path("aiSettings");

        // Then: the secret is redacted, while non-sensitive settings still carry their real value
        boolean sawKey = false;
        boolean sawModel = false;
        for (JsonNode setting : settings) {
            String name = setting.path("settingName").asString();
            if (name.equals("OpenAiApiKey")) {
                sawKey = true;
                assertThat(setting.path("value").asString()).isEmpty();
            } else if (name.equals("OllamaModelName")) {
                sawModel = true;
                assertThat(setting.path("value").asString()).isEqualTo("llama2");
            }
        }
        assertThat(sawKey).isTrue();
        assertThat(sawModel).isTrue();

        // Cleanup: clear the key we set
        dbAccess.update(
            "INSERT INTO SYSTEM_SETTINGS (setting_name, value) VALUES (?, ?) ON CONFLICT (setting_name) DO UPDATE SET value = EXCLUDED.value",
            AiSettingName.OPENAI_API_KEY.name(), "");
    }

    @Test
    public void testSetOllamaSettings() {
        // Given: New Ollama settings
        String newUrl = "http://localhost:9999";
        String newModel = "llama3";

        // When: Setting Ollama URL
        String setUrlMutation = String.format(
            "mutation { setAiSetting(settingName: \"OllamaApiUrl\", value: \"%s\") { settingName value } }",
            newUrl
        );
        JsonNode urlData = graphQLClient.executeAndGetData(setUrlMutation);

        // Then: Setting should be updated
        assertThat(urlData.path("setAiSetting").path("settingName").asString()).isEqualTo("OllamaApiUrl");
        assertThat(urlData.path("setAiSetting").path("value").asString()).isEqualTo(newUrl);

        // When: Setting Ollama model
        String setModelMutation = String.format(
            "mutation { setAiSetting(settingName: \"OllamaModelName\", value: \"%s\") { settingName value } }",
            newModel
        );
        JsonNode modelData = graphQLClient.executeAndGetData(setModelMutation);

        // Then: Setting should be updated
        assertThat(modelData.path("setAiSetting").path("settingName").asString()).isEqualTo("OllamaModelName");
        assertThat(modelData.path("setAiSetting").path("value").asString()).isEqualTo(newModel);

        // Cleanup: Restore original settings
        String insertSql = """
            INSERT INTO SYSTEM_SETTINGS (setting_name, value)
            VALUES (?, ?)
            ON CONFLICT (setting_name) DO UPDATE SET value = EXCLUDED.value, date_modified = CURRENT_TIMESTAMP
            """;
        dbAccess.update(insertSql, AiSettingName.OLLAMA_API_URL.name(), fakeOllamaServer.getBaseUrl());
        dbAccess.update(insertSql, AiSettingName.OLLAMA_MODEL_NAME.name(), "llama2");
    }

    @Test
    public void testInterpretSkipPattern_JpgAndPng() {
        // Given: Natural language input
        String naturalLanguage = "all jpg and png files";

        // When: Interpreting skip pattern
        String mutation = String.format(
            "mutation { interpretSkipPattern(naturalLanguageInput: \"%s\") { skipPattern naturalLanguageInput } }",
            naturalLanguage
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);

        // Then: Should return correct skip pattern
        JsonNode result = data.path("interpretSkipPattern");
        assertThat(result.path("skipPattern").asString()).isEqualTo(".*\\.jpg|.*\\.png");
        assertThat(result.path("naturalLanguageInput").asString()).isEqualTo(naturalLanguage);
    }

    @Test
    public void testInterpretSkipPattern_AllImageFiles() {
        // Given: Natural language input
        String naturalLanguage = "all image files";

        // When: Interpreting skip pattern
        String mutation = String.format(
            "mutation { interpretSkipPattern(naturalLanguageInput: \"%s\") { skipPattern naturalLanguageInput } }",
            naturalLanguage
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);

        // Then: Should return correct skip pattern
        JsonNode result = data.path("interpretSkipPattern");
        assertThat(result.path("skipPattern").asString()).isEqualTo(".*\\.(jpg|jpeg|png|gif|bmp|svg)");
        assertThat(result.path("naturalLanguageInput").asString()).isEqualTo(naturalLanguage);
    }

    @Test
    public void testInterpretSkipPattern_TemporaryFiles() {
        // Given: Natural language input
        String naturalLanguage = "temporary files";

        // When: Interpreting skip pattern
        String mutation = String.format(
            "mutation { interpretSkipPattern(naturalLanguageInput: \"%s\") { skipPattern naturalLanguageInput } }",
            naturalLanguage
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);

        // Then: Should return correct skip pattern
        JsonNode result = data.path("interpretSkipPattern");
        assertThat(result.path("skipPattern").asString()).isEqualTo(".*\\.tmp|.*\\.temp|.*~");
        assertThat(result.path("naturalLanguageInput").asString()).isEqualTo(naturalLanguage);
    }

    @Test
    public void testInterpretSkipPattern_FilesStartingWithTest() {
        // Given: Natural language input
        String naturalLanguage = "files starting with test";

        // When: Interpreting skip pattern
        String mutation = String.format(
            "mutation { interpretSkipPattern(naturalLanguageInput: \"%s\") { skipPattern naturalLanguageInput } }",
            naturalLanguage
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);

        // Then: Should return correct skip pattern
        JsonNode result = data.path("interpretSkipPattern");
        assertThat(result.path("skipPattern").asString()).isEqualTo("test.*");
        assertThat(result.path("naturalLanguageInput").asString()).isEqualTo(naturalLanguage);
    }

    @Test
    public void testExplainSkipPattern_JpgAndPng() {
        // Given: Skip pattern
        String skipPattern = ".*\\.jpg|.*\\.png";

        // When: Explaining skip pattern (escape backslashes for GraphQL)
        String mutation = String.format(
            "mutation { explainSkipPattern(skipPattern: \"%s\") { skipPattern naturalLanguageExplanation } }",
            skipPattern.replace("\\", "\\\\")
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);

        // Then: Should return natural language explanation
        JsonNode result = data.path("explainSkipPattern");
        assertThat(result.path("skipPattern").asString()).isEqualTo(skipPattern);
        assertThat(result.path("naturalLanguageExplanation").asString()).isEqualTo("All JPG and PNG files");
    }

    @Test
    public void testExplainSkipPattern_AllImageFiles() {
        // Given: Skip pattern
        String skipPattern = ".*\\.(jpg|jpeg|png|gif|bmp|svg)";

        // When: Explaining skip pattern (escape backslashes for GraphQL)
        String mutation = String.format(
            "mutation { explainSkipPattern(skipPattern: \"%s\") { skipPattern naturalLanguageExplanation } }",
            skipPattern.replace("\\", "\\\\")
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);

        // Then: Should return natural language explanation
        JsonNode result = data.path("explainSkipPattern");
        assertThat(result.path("skipPattern").asString()).isEqualTo(skipPattern);
        assertThat(result.path("naturalLanguageExplanation").asString()).isEqualTo("All image files (JPG, JPEG, PNG, GIF, BMP, SVG)");
    }

    @Test
    public void testExplainSkipPattern_TemporaryFiles() {
        // Given: Skip pattern
        String skipPattern = ".*\\.tmp|.*\\.temp|.*~";

        // When: Explaining skip pattern (escape backslashes for GraphQL)
        String mutation = String.format(
            "mutation { explainSkipPattern(skipPattern: \"%s\") { skipPattern naturalLanguageExplanation } }",
            skipPattern.replace("\\", "\\\\")
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);

        // Then: Should return natural language explanation
        JsonNode result = data.path("explainSkipPattern");
        assertThat(result.path("skipPattern").asString()).isEqualTo(skipPattern);
        assertThat(result.path("naturalLanguageExplanation").asString()).isEqualTo("All temporary files");
    }

    @Test
    public void testExplainSkipPattern_FilesStartingWithTest() {
        // Given: Skip pattern
        String skipPattern = "test.*";

        // When: Explaining skip pattern
        String mutation = String.format(
            "mutation { explainSkipPattern(skipPattern: \"%s\") { skipPattern naturalLanguageExplanation } }",
            skipPattern
        );
        JsonNode data = graphQLClient.executeAndGetData(mutation);

        // Then: Should return natural language explanation
        JsonNode result = data.path("explainSkipPattern");
        assertThat(result.path("skipPattern").asString()).isEqualTo(skipPattern);
        assertThat(result.path("naturalLanguageExplanation").asString()).isEqualTo("Files starting with 'test'");
    }

    @Test
    public void testRoundTrip_SkipPattern_InterpretThenExplain() {
        // Given: Natural language input
        String naturalLanguage = "all jpg and png files";

        // When: First interpreting to skip pattern
        String interpretMutation = String.format(
            "mutation { interpretSkipPattern(naturalLanguageInput: \"%s\") { skipPattern } }",
            naturalLanguage
        );
        JsonNode interpretData = graphQLClient.executeAndGetData(interpretMutation);
        String skipPattern = interpretData.path("interpretSkipPattern").path("skipPattern").asString();

        // Then: Skip pattern should be correct
        assertThat(skipPattern).isEqualTo(".*\\.jpg|.*\\.png");

        // When: Explaining the skip pattern back to natural language
        String explainMutation = String.format(
            "mutation { explainSkipPattern(skipPattern: \"%s\") { naturalLanguageExplanation } }",
            skipPattern.replace("\\", "\\\\")
        );
        JsonNode explainData = graphQLClient.executeAndGetData(explainMutation);
        String explanation = explainData.path("explainSkipPattern").path("naturalLanguageExplanation").asString();

        // Then: Explanation should make sense
        assertThat(explanation).isEqualTo("All JPG and PNG files");
    }
}
