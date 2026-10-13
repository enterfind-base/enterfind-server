package enterfind.server.service.tunnel;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TunnelMessage(
    String type,
    String serverId,
    String version,
    String token,
    String reason,
    String requestId,
    String routerConnectionId,
    String method,
    String path,
    Map<String, String> headers,
    String body,
    Map<String, String> queryStringParameters,
    String s3UploadUrl,
    Integer status,
    Map<String, String> responseHeaders,
    String wsSessionId,
    String wsPath,
    String data
) {

    public static TunnelMessage register(String version) {
        return new TunnelMessage("REGISTER", null, version,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static TunnelMessage pong() {
        return new TunnelMessage("PONG", null, null,
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static TunnelMessage httpResponse(String requestId, String routerConnectionId,
            int status, Map<String, String> responseHeaders, String body) {
        return new TunnelMessage("HTTP_RESPONSE", null, null,
            null, null, requestId, routerConnectionId, null, null, null, body, null, null, status, responseHeaders,
            null, null, null);
    }

    public static TunnelMessage httpResponseS3(String requestId, String routerConnectionId,
            int status, Map<String, String> responseHeaders) {
        return new TunnelMessage("HTTP_RESPONSE_S3", null, null,
            null, null, requestId, routerConnectionId, null, null, null, null, null, null, status, responseHeaders,
            null, null, null);
    }

    public static TunnelMessage wsServerMsg(String wsSessionId, String data) {
        return new TunnelMessage("WS_SERVER_MSG", null, null,
            null, null, null, null, null, null, null, null, null, null, null, null,
            wsSessionId, null, data);
    }

    public static TunnelMessage wsServerClose(String wsSessionId) {
        return new TunnelMessage("WS_SERVER_CLOSE", null, null,
            null, null, null, null, null, null, null, null, null, null, null, null,
            wsSessionId, null, null);
    }

    public static TunnelMessage wsSessionOpen(String wsSessionId) {
        return new TunnelMessage("WS_SESSION_OPEN", null, null,
            null, null, null, null, null, null, null, null, null, null, null, null,
            wsSessionId, null, null);
    }
}
