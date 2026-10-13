package enterfind.server.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * MCP Server information
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ServerInfo {

    private String name;
    private String version;
    private Capabilities capabilities;

    public ServerInfo() {
    }

    public ServerInfo(String name, String version) {
        this.name = name;
        this.version = version;
        this.capabilities = new Capabilities();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Capabilities getCapabilities() {
        return capabilities;
    }

    public void setCapabilities(Capabilities capabilities) {
        this.capabilities = capabilities;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Capabilities {
        private ToolsCapability tools;

        public Capabilities() {
            this.tools = new ToolsCapability();
        }

        public ToolsCapability getTools() {
            return tools;
        }

        public void setTools(ToolsCapability tools) {
            this.tools = tools;
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class ToolsCapability {
        // Empty object indicating tools are supported
        public ToolsCapability() {
        }
    }
}
