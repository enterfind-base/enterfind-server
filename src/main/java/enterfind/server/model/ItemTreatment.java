package enterfind.server.model;

import java.time.LocalDateTime;

/**
 * Model representing an item treatment - the intersection between datasource treatments and items
 * Contains the status of a specific treatment for a specific item
 */
public class ItemTreatment {
    
    private String id;
    private String datasourceTreatmentId;
    private String itemId;
    private ItemTreatmentStatus status;
    private LocalDateTime dateCreated;
    private LocalDateTime dateModified;
    private int version;
    
    /**
     * Default constructor
     */
    public ItemTreatment() {
    }
    
    /**
     * Constructor with all fields
     */
    public ItemTreatment(String id, String datasourceTreatmentId, String itemId, 
                        ItemTreatmentStatus status, LocalDateTime dateCreated, 
                        LocalDateTime dateModified, int version) {
        this.id = id;
        this.datasourceTreatmentId = datasourceTreatmentId;
        this.itemId = itemId;
        this.status = status;
        this.dateCreated = dateCreated;
        this.dateModified = dateModified;
        this.version = version;
    }
    
    // Getters and setters
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getDatasourceTreatmentId() {
        return datasourceTreatmentId;
    }
    
    public void setDatasourceTreatmentId(String datasourceTreatmentId) {
        this.datasourceTreatmentId = datasourceTreatmentId;
    }
    
    public String getItemId() {
        return itemId;
    }
    
    public void setItemId(String itemId) {
        this.itemId = itemId;
    }
    
    public ItemTreatmentStatus getStatus() {
        return status;
    }
    
    public void setStatus(ItemTreatmentStatus status) {
        this.status = status;
    }
    
    public LocalDateTime getDateCreated() {
        return dateCreated;
    }
    
    public void setDateCreated(LocalDateTime dateCreated) {
        this.dateCreated = dateCreated;
    }
    
    public LocalDateTime getDateModified() {
        return dateModified;
    }
    
    public void setDateModified(LocalDateTime dateModified) {
        this.dateModified = dateModified;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }
    
    @Override
    public String toString() {
        return "ItemTreatment{" +
            "id='" + id + '\'' +
            ", datasourceTreatmentId='" + datasourceTreatmentId + '\'' +
            ", itemId='" + itemId + '\'' +
            ", status=" + status +
            ", dateCreated=" + dateCreated +
            ", dateModified=" + dateModified +
            ", version=" + version +
            '}';
    }
}