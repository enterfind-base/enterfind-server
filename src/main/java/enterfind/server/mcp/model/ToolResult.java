package enterfind.server.mcp.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Result of executing an MCP tool
 */
public class ToolResult {

    private List<ContentItem> content;

    private boolean isError;

    public ToolResult() {
    }

    public ToolResult(List<ContentItem> content, boolean isError) {
        this.content = content;
        this.isError = isError;
    }

    public static ToolResult success(String text) {
        return new ToolResult(List.of(new ContentItem("text", text)), false);
    }

    public static ToolResult error(String errorMessage) {
        return new ToolResult(List.of(new ContentItem("text", errorMessage)), true);
    }

    public List<ContentItem> getContent() {
        return content;
    }

    public void setContent(List<ContentItem> content) {
        this.content = content;
    }

    @JsonProperty("isError")
    public boolean isError() {
        return isError;
    }

    public void setIsError(boolean isError) {
        this.isError = isError;
    }

    public static class ContentItem {
        private String type;
        private String text;

        public ContentItem() {
        }

        public ContentItem(String type, String text) {
            this.type = type;
            this.text = text;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}
