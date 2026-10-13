package enterfind.server.service.tunnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public class TunnelMessageTest {

    private final ObjectMapper objectMapper = new JsonMapper();

    private JsonNode toJson(TunnelMessage msg) throws JacksonException {
        return objectMapper.readTree(objectMapper.writeValueAsString(msg));
    }

    @Test
    public void register_hasRequiredFields() throws Exception {
        TunnelMessage msg = TunnelMessage.register("1.0.0");
        JsonNode json = toJson(msg);

        assertEquals("REGISTER", json.get("type").asString());
        assertFalse(json.has("serverId"), "serverId should be absent from REGISTER");
        assertEquals("1.0.0", json.get("version").asString());
        // NON_NULL: no null fields should appear
        assertFalse(json.has("requestId"), "requestId should be absent");
        assertFalse(json.has("body"), "body should be absent");
        assertFalse(json.has("wsSessionId"), "wsSessionId should be absent");
    }

    @Test
    public void httpResponse_hasRequiredFields() throws Exception {
        Map<String, String> headers = Map.of("content-type", "application/json");
        TunnelMessage msg = TunnelMessage.httpResponse("req-1", "router-conn-1", 200, headers, "{\"data\":true}");
        JsonNode json = toJson(msg);

        assertEquals("HTTP_RESPONSE", json.get("type").asString());
        assertEquals("req-1", json.get("requestId").asString());
        assertEquals("router-conn-1", json.get("routerConnectionId").asString());
        assertEquals(200, json.get("status").asInt());
        assertEquals("{\"data\":true}", json.get("body").asString());
        assertNotNull(json.get("responseHeaders"));
    }

    @Test
    public void httpResponseS3_hasNoBodyField() throws Exception {
        Map<String, String> headers = Map.of("content-type", "image/jpeg");
        TunnelMessage msg = TunnelMessage.httpResponseS3("req-2", "router-conn-2", 200, headers);
        JsonNode json = toJson(msg);

        assertEquals("HTTP_RESPONSE_S3", json.get("type").asString());
        assertFalse(json.has("body"), "body should be absent from HTTP_RESPONSE_S3");
        assertEquals("req-2", json.get("requestId").asString());
        assertEquals(200, json.get("status").asInt());
    }

    @Test
    public void wsServerMsg_hasSessionIdAndData() throws Exception {
        TunnelMessage msg = TunnelMessage.wsServerMsg("session-123", "{\"type\":\"connection_ack\"}");
        JsonNode json = toJson(msg);

        assertEquals("WS_SERVER_MSG", json.get("type").asString());
        assertEquals("session-123", json.get("wsSessionId").asString());
        assertEquals("{\"type\":\"connection_ack\"}", json.get("data").asString());
        assertFalse(json.has("requestId"), "requestId should be absent");
    }

    @Test
    public void pong_hasTypeOnly() throws Exception {
        TunnelMessage msg = TunnelMessage.pong();
        JsonNode json = toJson(msg);

        assertEquals("PONG", json.get("type").asString());
        assertFalse(json.has("serverId"), "serverId should be absent from PONG");
        // Only type should be present (all others are null → excluded)
        assertFalse(json.has("version"), "version should be absent from PONG");
        assertFalse(json.has("requestId"), "requestId should be absent from PONG");
        assertFalse(json.has("body"), "body should be absent from PONG");
        assertFalse(json.has("wsSessionId"), "wsSessionId should be absent from PONG");
        assertTrue(json.size() == 1, "PONG should have exactly 1 field: type");
    }
}
