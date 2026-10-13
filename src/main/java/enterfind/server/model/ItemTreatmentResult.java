package enterfind.server.model;

import java.time.LocalDateTime;

/**
 * Model representing a treatment result for an item treatment.
 * Stores key-value pairs from TreatmentResult objects linked to specific item treatments.
 */
public class ItemTreatmentResult {
    
    private String id;
    private String itemTreatmentId;
    private String key;
    private String value;
    private LocalDateTime dateCreated;
    
    /**
     * Default constructor
     */
    public ItemTreatmentResult() {
    }
    
    /**
     * Constructor with all fields
     */
    public ItemTreatmentResult(String id, String itemTreatmentId, String key, String value, LocalDateTime dateCreated) {
        this.id = id;
        this.itemTreatmentId = itemTreatmentId;
        this.key = key;
        this.value = value;
        this.dateCreated = dateCreated;
    }
    
    /**
     * Constructor without id and dateCreated (for new records)
     */
    public ItemTreatmentResult(String itemTreatmentId, String key, String value) {
        this.itemTreatmentId = itemTreatmentId;
        this.key = key;
        this.value = value;
    }
    
    // Getters and setters
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getItemTreatmentId() {
        return itemTreatmentId;
    }
    
    public void setItemTreatmentId(String itemTreatmentId) {
        this.itemTreatmentId = itemTreatmentId;
    }
    
    public String getKey() {
        return key;
    }
    
    public void setKey(String key) {
        this.key = key;
    }
    
    public String getValue() {
        return value;
    }
    
    public void setValue(String value) {
        this.value = value;
    }
    
    public LocalDateTime getDateCreated() {
        return dateCreated;
    }
    
    public void setDateCreated(LocalDateTime dateCreated) {
        this.dateCreated = dateCreated;
    }
    
    @Override
    public String toString() {
        return "ItemTreatmentResult{" +
                "id='" + id + '\'' +
                ", itemTreatmentId='" + itemTreatmentId + '\'' +
                ", key='" + key + '\'' +
                ", value='" + value + '\'' +
                ", dateCreated=" + dateCreated +
                '}';
    }
}