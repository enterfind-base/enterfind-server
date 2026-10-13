package enterfind.server.mcp.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import enterfind.server.service.RemoteAccessService;
import enterfind.server.util.ProfileHelper;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Exposes an OpenAPI 3.1 document describing the Enterfind GraphQL endpoint.
 * The server URL is derived dynamically from the incoming request so that the
 * document is correct regardless of how the instance is accessed (localhost,
 * LAN address, HTTPS domain, etc.).
 *
 * When a request arrives via the remote-access tunnel, the
 * {@code X-Enterfind-Tunnel-Proxy} header is present (injected by
 * {@link enterfind.server.service.tunnel.TunnelRequestHandler}). In that case
 * the server URL is built as
 * {@code https://remote-<stage>.enterfind.com/<serverId>} using live values
 * from {@link RemoteAccessService} and {@link ProfileHelper}.
 */
@RestController
public class OpenApiController {

    private final RemoteAccessService remoteAccessService;
    private final ProfileHelper profileHelper;

    public OpenApiController(RemoteAccessService remoteAccessService, ProfileHelper profileHelper) {
        this.remoteAccessService = remoteAccessService;
        this.profileHelper = profileHelper;
    }

    @GetMapping(value = "/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> openApiSpec(HttpServletRequest request) {
        String serverUrl = buildServerUrl(request);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("openapi", "3.1.0");

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", "Enterfind");
        info.put("description", "Search and retrieve documents from a self-hosted Enterfind instance.");
        info.put("version", "1.0.0");
        spec.put("info", info);

        spec.put("servers", List.of(Map.of("url", serverUrl)));

        spec.put("paths", buildPaths());

        return ResponseEntity.ok(spec);
    }

    private String buildServerUrl(HttpServletRequest request) {
        if ("1".equals(request.getHeader("X-Enterfind-Tunnel-Proxy"))) {
            String stage = profileHelper.getActiveProfile();
            String serverId = remoteAccessService.getConfig().serverId();
            return "https://remote-" + stage + ".enterfind.com/" + serverId;
        }
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        boolean isDefaultPort = ("http".equals(scheme) && port == 80)
                || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (isDefaultPort ? "" : ":" + port);
    }

    private Map<String, Object> buildPaths() {
        Map<String, Object> paths = new LinkedHashMap<>();
        paths.put("/graphql", Map.of("post", buildGraphQLOperation()));
        return paths;
    }

    private Map<String, Object> buildGraphQLOperation() {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("operationId", "executeGraphQL");
        op.put("summary", "Execute a GraphQL query or mutation against Enterfind");
        op.put("description", """
                Single endpoint for all Enterfind operations.

                Workflow: call with the standard __schema introspection query FIRST to \
                discover available operations and types. The schema is the source of \
                truth — do not guess query shapes.

                Auth model: tokens are GraphQL arguments, not HTTP headers. Check \
                isAuthenticationRequired; if true, call loginWithApiKey(nickname, keyValue) \
                or login(nickname, totpCode), then pass the returned token as a "token" \
                variable on subsequent authenticated operations.

                Search tips: searchString searches every attribute at once and is the \
                right default for "find documents about X" — each of its terms may be \
                satisfied from a file name or from document text independently. Use \
                searchConditions to filter one named attribute, where every term must \
                share a single value of that key; folder and name are shortcuts for \
                filtering on the item's own path and file name. Quote a phrase in either \
                to require the words adjacent and in order — that is the only way to ask \
                for adjacency, and on document text it is the slow option. Use \
                availableItemAttributeKeys + getFacets to discover faceting dimensions \
                before filtering by them. Use itemAttributes for full metadata on a \
                specific document. The schema documents the rest, including the limits \
                on short terms and on large documents — read the descriptions.""");

        op.put("requestBody", buildRequestBody());
        op.put("responses", buildResponses());
        return op;
    }

    private Map<String, Object> buildRequestBody() {
        Map<String, Object> queryProp = new LinkedHashMap<>();
        queryProp.put("type", "string");
        queryProp.put("description", "GraphQL query or mutation string.");

        Map<String, Object> variablesProp = new LinkedHashMap<>();
        variablesProp.put("type", "object");
        variablesProp.put("description", "Variables for the query. Include \"token\" for authenticated operations.");
        variablesProp.put("additionalProperties", true);

        Map<String, Object> opNameProp = new LinkedHashMap<>();
        opNameProp.put("type", "string");
        opNameProp.put("description", "Operation name when the query contains multiple operations.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", queryProp);
        properties.put("variables", variablesProp);
        properties.put("operationName", opNameProp);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("query"));
        schema.put("properties", properties);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("application/json", Map.of("schema", schema));

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("required", true);
        requestBody.put("content", content);
        return requestBody;
    }

    private Map<String, Object> buildResponses() {
        Map<String, Object> dataProp = new LinkedHashMap<>();
        dataProp.put("type", "object");
        dataProp.put("additionalProperties", true);

        Map<String, Object> errorItem = new LinkedHashMap<>();
        errorItem.put("type", "object");
        errorItem.put("additionalProperties", true);

        Map<String, Object> errorsProp = new LinkedHashMap<>();
        errorsProp.put("type", "array");
        errorsProp.put("items", errorItem);

        Map<String, Object> responseProps = new LinkedHashMap<>();
        responseProps.put("data", dataProp);
        responseProps.put("errors", errorsProp);

        Map<String, Object> responseSchema = new LinkedHashMap<>();
        responseSchema.put("type", "object");
        responseSchema.put("properties", responseProps);

        Map<String, Object> responseContent = new LinkedHashMap<>();
        responseContent.put("application/json", Map.of("schema", responseSchema));

        Map<String, Object> response200 = new LinkedHashMap<>();
        response200.put("description",
                "GraphQL response. HTTP 200 even when GraphQL errors are present — check the \"errors\" field.");
        response200.put("content", responseContent);

        Map<String, Object> responses = new LinkedHashMap<>();
        responses.put("200", response200);
        return responses;
    }
}
