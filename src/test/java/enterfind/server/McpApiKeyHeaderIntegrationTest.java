package enterfind.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import enterfind.server.dao.ApiSessionDao;
import enterfind.server.dao.ApiUserDao;
import enterfind.server.dao.DataSourceDao;
import enterfind.server.mcp.model.JsonRpcRequest;
import enterfind.server.mcp.model.JsonRpcResponse;
import enterfind.server.model.ApiUser;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.LocalFileSystemDataSource;
import enterfind.server.model.UserLevel;
import enterfind.server.scan.Scanner;
import enterfind.server.scan.TreatmentProcessor;
import enterfind.server.service.AuthenticationService;
import enterfind.server.service.RemoteAccessService;
import enterfind.server.util.ProfileHelper;

/**
 * Flow 1 of AI connector authentication end to end: an API key in the Authorization header of
 * {@code POST /mcp} is exchanged for a session on the server, so a query needs no {@code token}
 * anywhere and the returned links fetch the document by themselves.
 *
 * <p>One indexed file and one admin are set up once; each test creates the API users it needs, so
 * tests that deactivate or delete one leave the others alone.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class McpApiKeyHeaderIntegrationTest extends BaseDatabaseTest {

    private static final String SOURCE_NAME = "Header Test Source";
    // Lower-case: item paths are stored lower-cased, and the provider opens the file by that path
    private static final String FOLDER = "notes dir";
    private static final String FILE_NAME = "connector-note.txt";
    private static final String FILE_CONTENT = "Opened through a connector header.";
    private static final String PREVIEW_CONTENT = "preview bytes";

    // Written out rather than built with the code under test: the source name is form-encoded once
    // and the folder/name path twice, which is what DownloadController decodes.
    private static final String ITEM_PATH = "LOCAL_FILESYSTEM/Header+Test+Source/notes%2Bdir%252Fconnector-note.txt";
    private static final String CONTENT_LINK = "/content/" + ITEM_PATH;
    private static final String PREVIEW_LINK = "/preview/" + ITEM_PATH;

    private static final String SEARCH =
        "{ search(searchString: \"connector-note\", skip: 0, howmany: 10) { items { name downloadLink previewLink } } }";

    @LocalServerPort
    private int port;

    @Autowired
    private Scanner scanner;
    @Autowired
    private TreatmentProcessor treatmentProcessor;
    @Autowired
    private DataSourceDao dataSourceDao;
    @Autowired
    private AuthenticationService authenticationService;
    @Autowired
    private ApiUserDao apiUserDao;
    @Autowired
    private ApiSessionDao apiSessionDao;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RemoteAccessService remoteAccessService;
    @Autowired
    private ProfileHelper profileHelper;

    private final TestRestTemplate restTemplate = new TestRestTemplate();
    private Path tempDir;
    private String adminId;
    private McpTestClient mcp;
    private int apiUserCounter;

    @Override
    protected void setUp() {
        // One-time setup in @BeforeAll; no wipe between tests
    }

    @BeforeAll
    void setUpOnce() throws Exception {
        wipeDatabase();
        tempDir = Files.createTempDirectory("mcp-header-test-");
        Path folder = Files.createDirectory(tempDir.resolve(FOLDER));
        Files.writeString(folder.resolve(FILE_NAME), FILE_CONTENT);

        LocalFileSystemDataSource dataSource = (LocalFileSystemDataSource) dataSourceDao.create(
            new LocalFileSystemDataSource(null, SOURCE_NAME, "0 0 * * *", LocalDateTime.now(), null,
                DataSourceStatus.IDLE, tempDir.toString(), null));
        scanner.scan(dataSource);
        while (treatmentProcessor.processTreatments() > 0) {
            // drain
        }

        // Previews never generate on the alpha profile; finish the scan's pending one by hand so
        // previewLink can be fetched
        String itemId = dbAccess.queryForObject("SELECT id FROM ITEMS WHERE name = ?", String.class, FILE_NAME);
        dbAccess.update("""
            INSERT INTO ITEM_PREVIEWS (item_id, status, preview_data, content_type)
            VALUES (?, 'DONE', ?, 'image/png')
            ON CONFLICT (item_id) DO UPDATE
            SET status = 'DONE', preview_data = EXCLUDED.preview_data, content_type = EXCLUDED.content_type
            """, itemId, PREVIEW_CONTENT.getBytes(StandardCharsets.UTF_8));

        adminId = authenticationService.createUser("admin", UserLevel.ADMIN, null).user().id();
    }

    @BeforeEach
    void newClient() {
        mcp = new McpTestClient(port);
    }

    @AfterAll
    void tearDown() throws IOException {
        if (tempDir != null) {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    // ========== Without a header ==========

    @Test
    void withoutHeader_authErrorPointsALocalClientAtTheLocalSetupPage() {
        ToolCall call = callTool(SEARCH);

        assertThat(call.isError()).isTrue();
        assertThat(call.text()).contains("Authentication required")
            .contains("see " + localBase() + "/mcp-setup.html#cli-assistants.");
    }

    @Test
    void withoutHeader_authErrorPointsARelayedClientAtThePublicSetupPage() {
        mcp.setHeader("X-Enterfind-Tunnel-Proxy", "1");

        ToolCall call = callTool(SEARCH);

        assertThat(call.isError()).isTrue();
        assertThat(call.text()).contains("Authentication required")
            .contains("see " + remoteBase() + "/public-ai-setup.html.");
    }

    @Test
    void withoutHeader_publicQueriesStillWork() {
        assertThat(callTool("{ about { name } }").isError()).isFalse();
    }

    @Test
    void withoutHeader_loginInChatStillWorksAndLinksAreAbsolute() throws Exception {
        Created key = newApiUser(UserLevel.READER);
        ToolCall login = callTool(String.format(
            "mutation { loginWithApiKey(input: { nickname: \"%s\", keyValue: \"%s\" }) { token } }",
            key.apiUser().nickname(), key.keyValue()));
        String token = objectMapper.readTree(login.text()).path("loginWithApiKey").path("token").asString();

        ToolCall search = callTool(
            "query($token: String) { search(token: $token, searchString: \"connector-note\", skip: 0, howmany: 10) "
                + "{ items { downloadLink previewLink } } }",
            objectMapper.valueToTree(Map.of("token", token)));

        assertThat(search.isError()).as(search.text()).isFalse();
        JsonNode item = firstItem(search);
        // Absolute, and without ?t=: the model appends the token it logged in with
        assertThat(item.path("downloadLink").asString()).isEqualTo(localBase() + CONTENT_LINK);
        assertThat(item.path("previewLink").asString()).isEqualTo(localBase() + PREVIEW_LINK);
        assertFetches(item.path("downloadLink").asString() + "?t=" + token, FILE_CONTENT);
        assertFetches(item.path("previewLink").asString() + "?t=" + token, PREVIEW_CONTENT);
    }

    // ========== With a header ==========

    @Test
    void withHeader_searchNeedsNoTokenAndTheLinksFetchTheDocument() {
        Created key = newApiUser(UserLevel.READER);
        mcp.setAuthorization("Bearer " + key.keyValue());

        ToolCall search = callTool(SEARCH);

        assertThat(search.isError()).as(search.text()).isFalse();
        JsonNode item = firstItem(search);
        String session = onlySessionOf(key);
        assertThat(item.path("downloadLink").asString()).isEqualTo(localBase() + CONTENT_LINK + "?t=" + session);
        assertThat(item.path("previewLink").asString()).isEqualTo(localBase() + PREVIEW_LINK + "?t=" + session);
        assertFetches(item.path("downloadLink").asString(), FILE_CONTENT);
        assertFetches(item.path("previewLink").asString(), PREVIEW_CONTENT);
    }

    @Test
    void withHeader_throughTheTunnelLinksUseThePerServerHost() {
        Created key = newApiUser(UserLevel.READER);
        mcp.setAuthorization("Bearer " + key.keyValue());
        mcp.setHeader("X-Enterfind-Tunnel-Proxy", "1");

        ToolCall search = callTool(SEARCH);

        assertThat(search.isError()).as(search.text()).isFalse();
        JsonNode item = firstItem(search);
        String base = remoteBase();
        String session = onlySessionOf(key);
        assertThat(item.path("downloadLink").asString()).isEqualTo(base + CONTENT_LINK + "?t=" + session);
        assertThat(item.path("previewLink").asString()).isEqualTo(base + PREVIEW_LINK + "?t=" + session);
    }

    @Test
    void withHeader_keyWithoutBearerPrefixWorks() {
        mcp.setAuthorization(newApiUser(UserLevel.READER).keyValue());

        assertThat(callTool(SEARCH).isError()).isFalse();
    }

    @Test
    void withHeader_headerWinsOverAModelSuppliedToken() {
        mcp.setAuthorization("Bearer " + newApiUser(UserLevel.READER).keyValue());

        ToolCall search = callTool(
            "{ search(token: \"made-up-by-the-model\", searchString: \"connector-note\", skip: 0, howmany: 10) { totalHits } }");

        assertThat(search.isError()).as(search.text()).isFalse();
    }

    @Test
    void withHeader_headerWinsOverAModelSuppliedTokenVariable() {
        mcp.setAuthorization("Bearer " + newApiUser(UserLevel.READER).keyValue());

        // The token arrives through a variable rather than a literal, so it is resolved before the
        // instrumentation sees the arguments
        ToolCall search = callTool(
            "query($token: String) { search(token: $token, searchString: \"connector-note\", skip: 0, howmany: 10) "
                + "{ items { name } } }",
            objectMapper.valueToTree(Map.of("token", "made-up-by-the-model")));

        assertThat(search.isError()).as(search.text()).isFalse();
        assertThat(firstItem(search).path("name").asString()).isEqualTo(FILE_NAME);
    }

    @Test
    void withHeader_readerCannotRunAdminMutations() {
        mcp.setAuthorization("Bearer " + newApiUser(UserLevel.READER).keyValue());

        ToolCall call = callTool("mutation { setUserSetting(settingName: \"Banner\", value: \"x\") { value } }");

        assertThat(call.isError()).isTrue();
        assertThat(call.text()).contains("INSUFFICIENT_PERMISSIONS");
    }

    @Test
    void withHeader_callsShareOneSession() {
        Created key = newApiUser(UserLevel.READER);
        mcp.setAuthorization("Bearer " + key.keyValue());

        callTool(SEARCH);
        callTool(SEARCH);

        assertThat(apiSessionDao.countValidSessionsForApiUser(key.apiUser().id())).isEqualTo(1);
    }

    @Test
    void withHeader_introspectionCreatesNoSession() {
        Created key = newApiUser(UserLevel.READER);
        mcp.setAuthorization("Bearer " + key.keyValue());

        mcp.introspectSchema(true);

        assertThat(apiSessionDao.countValidSessionsForApiUser(key.apiUser().id())).isZero();
    }

    @Test
    void withHeader_expiredKeyIsAToolErrorWithItsReason() {
        Created key = newApiUser(UserLevel.READER);
        dbAccess.update("UPDATE API_USERS SET date_expires = ? WHERE id = ?",
            Timestamp.valueOf(LocalDateTime.now().minusDays(1)), key.apiUser().id());
        mcp.setAuthorization("Bearer " + key.keyValue());

        assertToolError(SEARCH, "API key has expired");
    }

    @Test
    void withHeader_deactivatedKeyIsAToolErrorWithItsReason() {
        Created key = newApiUser(UserLevel.READER);
        apiUserDao.deactivate(key.apiUser().id());
        mcp.setAuthorization("Bearer " + key.keyValue());

        assertToolError(SEARCH, "API key is disabled");
    }

    @Test
    void withHeader_unknownAndNonApiKeyValuesAreToolErrors() {
        mcp.setAuthorization("Bearer efk_notakeyofthisinstance");
        assertToolError(SEARCH, "not valid for this Enterfind instance");

        mcp.setAuthorization("Bearer some-session-token");
        assertToolError(SEARCH, "must carry an Enterfind API key");
    }

    @Test
    void withHeader_deletedApiUserIsRefusedOnTheNextCall() {
        Created key = newApiUser(UserLevel.READER);
        mcp.setAuthorization("Bearer " + key.keyValue());
        assertThat(callTool(SEARCH).isError()).isFalse();

        authenticationService.deleteApiUser(key.apiUser().id(), "admin");

        assertToolError(SEARCH, "not valid for this Enterfind instance");
    }

    // ========== Tool descriptions ==========

    @Test
    void toolsList_followsTheHeader() {
        Map<String, String> withoutHeader = toolDescriptions();
        assertThat(withoutHeader.values()).allSatisfy(description -> assertThat(description)
            .contains("isAuthenticationRequired")
            .contains("loginWithApiKey"));

        Created key = newApiUser(UserLevel.READER);
        mcp.setAuthorization("Bearer " + key.keyValue());
        Map<String, String> withHeader = toolDescriptions();

        assertThat(withHeader.values()).allSatisfy(description -> assertThat(description)
            .contains("handled by the connection")
            .doesNotContain("isAuthenticationRequired")
            .doesNotContain("loginWithApiKey")
            .doesNotContain("?t="));
        assertThat(apiSessionDao.countValidSessionsForApiUser(key.apiUser().id())).isZero();
    }

    // ========== /graphql is unchanged ==========

    @Test
    void graphqlEndpoint_ignoresTheHeaderAndKeepsRelativeLinks() throws Exception {
        Created key = newApiUser(UserLevel.READER);

        JsonNode withHeaderOnly = postGraphql(SEARCH, "Bearer " + key.keyValue());
        assertThat(withHeaderOnly.path("errors").toString()).contains("Authentication required");

        String token = authenticationService.loginWithApiKey(key.apiUser().nickname(), key.keyValue()).token();
        JsonNode withToken = postGraphql(String.format(
            "{ search(token: \"%s\", searchString: \"connector-note\", skip: 0, howmany: 10) "
                + "{ items { downloadLink previewLink } } }",
            token), null);
        JsonNode item = withToken.path("data").path("search").path("items").get(0);
        assertThat(item.path("downloadLink").asString()).isEqualTo(CONTENT_LINK);
        assertThat(item.path("previewLink").asString()).isEqualTo(PREVIEW_LINK);
        assertFetches(localBase() + CONTENT_LINK + "?t=" + token, FILE_CONTENT);
        assertFetches(localBase() + PREVIEW_LINK + "?t=" + token, PREVIEW_CONTENT);
    }

    // ========== helpers ==========

    private record Created(ApiUser apiUser, String keyValue) {}

    private record ToolCall(boolean isError, String text) {}

    private Created newApiUser(UserLevel level) {
        var created = authenticationService.createApiUser("connector-" + (++apiUserCounter), level, 365, adminId);
        return new Created(created.apiUser(), created.keyValue());
    }

    private ToolCall callTool(String query) {
        return toToolCall(mcp.executeGraphQLQuery(query));
    }

    private ToolCall callTool(String query, JsonNode variables) {
        return toToolCall(mcp.executeGraphQLQuery(query, variables));
    }

    private ToolCall toToolCall(ResponseEntity<JsonRpcResponse> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode result = objectMapper.valueToTree(response.getBody().getResult());
        return new ToolCall(result.path("isError").asBoolean(), result.path("content").get(0).path("text").asString());
    }

    private void assertToolError(String query, String reason) {
        ToolCall call = callTool(query);
        assertThat(call.isError()).isTrue();
        assertThat(call.text()).contains(reason);
    }

    private JsonNode firstItem(ToolCall call) {
        JsonNode items = objectMapper.readTree(call.text()).path("search").path("items");
        assertThat(items.size()).as(call.text()).isGreaterThan(0);
        return items.get(0);
    }

    private String localBase() {
        return "http://localhost:" + port;
    }

    private String remoteBase() {
        return "https://" + remoteAccessService.getConfig().serverId()
            + ".remote-" + profileHelper.getActiveProfile() + ".enterfind.com";
    }

    /** The one valid session of the key's API user, which a call through the header created. */
    private String onlySessionOf(Created key) {
        var sessions = apiSessionDao.findValidByApiUserId(key.apiUser().id());
        assertThat(sessions).hasSize(1);
        return sessions.getFirst().token();
    }

    private void assertFetches(String absoluteLink, String expectedBody) {
        ResponseEntity<String> response = fetch(absoluteLink);
        assertThat(response.getStatusCode().value()).as(absoluteLink).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(expectedBody);
    }

    private ResponseEntity<String> fetch(String absoluteLink) {
        // URI.create keeps the link's own (double) encoding; a String URL would be re-encoded
        ResponseEntity<byte[]> response = restTemplate.getForEntity(URI.create(absoluteLink), byte[].class);
        String body = response.getBody() == null ? null : new String(response.getBody(), StandardCharsets.UTF_8);
        return ResponseEntity.status(response.getStatusCode()).body(body);
    }

    private Map<String, String> toolDescriptions() {
        JsonRpcRequest request = new JsonRpcRequest("tools/list", Map.of(), 1);
        JsonNode result = objectMapper.valueToTree(mcp.execute(request).getBody().getResult());
        Map<String, String> descriptions = new HashMap<>();
        result.path("tools").forEach(tool -> descriptions.put(tool.path("name").asString(),
            tool.path("description").asString() + " " + tool.path("inputSchema").toString()));
        return descriptions;
    }

    private JsonNode postGraphql(String query, String authorization) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        ResponseEntity<String> response = restTemplate.postForEntity("http://localhost:" + port + "/graphql",
            new HttpEntity<>(Map.of("query", query), headers), String.class);
        return objectMapper.readTree(response.getBody());
    }
}
