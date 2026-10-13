package enterfind.server.scan;

import java.time.LocalDateTime;

public abstract class AbstractItem {
    
    private String id;
    private String datasourceId;
    private String name;
    /*
     * path is slash-separated full path to the item's folder or null if in root
     */
    private String path;
    private LocalDateTime timeCreated;
    private LocalDateTime timeModified;
    private LocalDateTime dateScanned;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getDatasourceId() {
        return datasourceId;
    }

    public void setDatasourceId(String datasourceId) {
        this.datasourceId = datasourceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getFullPath() {
        if (path == null || path.isEmpty()) {
            return name;
        }
        return path + "/" + (name != null ? name : "");
    }

    public LocalDateTime getTimeCreated() {
        return timeCreated;
    }

    public void setTimeCreated(LocalDateTime timeCreated) {
        this.timeCreated = timeCreated;
    }

    public LocalDateTime getTimeModified() {
        return timeModified;
    }

    public void setTimeModified(LocalDateTime timeModified) {
        this.timeModified = timeModified;
    }

    public LocalDateTime getDateScanned() {
        return dateScanned;
    }

    public void setDateScanned(LocalDateTime dateScanned) {
        this.dateScanned = dateScanned;
    }
}
