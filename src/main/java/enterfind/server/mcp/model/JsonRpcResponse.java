package enterfind.server.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON-RPC 2.0 response
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JsonRpcResponse {

    @JsonProperty("jsonrpc")
    private String jsonrpc = "2.0";

    private Object result;
    private JsonRpcError error;
    private Object id;

    public JsonRpcResponse() {
    }

    public JsonRpcResponse(Object result, Object id) {
        this.result = result;
        this.id = id;
    }

    public JsonRpcResponse(JsonRpcError error, Object id) {
        this.error = error;
        this.id = id;
    }

    public static JsonRpcResponse success(Object result, Object id) {
        return new JsonRpcResponse(result, id);
    }

    public static JsonRpcResponse error(int code, String message, Object id) {
        return new JsonRpcResponse(new JsonRpcError(code, message), id);
    }

    public String getJsonrpc() {
        return jsonrpc;
    }

    public void setJsonrpc(String jsonrpc) {
        this.jsonrpc = jsonrpc;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public JsonRpcError getError() {
        return error;
    }

    public void setError(JsonRpcError error) {
        this.error = error;
    }

    public Object getId() {
        return id;
    }

    public void setId(Object id) {
        this.id = id;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JsonRpcError {
        private int code;
        private String message;
        private Object data;

        public JsonRpcError() {
        }

        public JsonRpcError(int code, String message) {
            this.code = code;
            this.message = message;
        }

        public JsonRpcError(int code, String message, Object data) {
            this.code = code;
            this.message = message;
            this.data = data;
        }

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public Object getData() {
            return data;
        }

        public void setData(Object data) {
            this.data = data;
        }
    }
}
