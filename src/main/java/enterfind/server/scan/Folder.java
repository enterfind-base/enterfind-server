package enterfind.server.scan;

public class Folder extends AbstractItem {
    
    // For GraphQL - populated by controller
    private String dataSourceName;
    private String dataSourceType;
    
    // For GraphQL
    public String getFolder() {
        return getPath();   // already slash-separated
    }
    
    public String getDataSourceName() {
        return dataSourceName;
    }
    
    public void setDataSourceName(String dataSourceName) {
        this.dataSourceName = dataSourceName;
    }
    
    public String getDataSourceType() {
        return dataSourceType;
    }
    
    public void setDataSourceType(String dataSourceType) {
        this.dataSourceType = dataSourceType;
    }
}
