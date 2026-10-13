package enterfind.server.model;

import java.time.LocalDateTime;

import tools.jackson.databind.JsonNode;

import enterfind.server.scan.Treatments;

/**
 * Model representing a treatment applied to a data source.
 * Each treatment defines how the data source content is processed and indexed.
 */
public class DataSourceTreatment {
    
    private String id;
    private String dataSourceId;
    private Treatments treatment;
    private JsonNode config;
    private LocalDateTime dateCreated;
    private LocalDateTime dateModified;
    
    /**
     * Default constructor
     */
    public DataSourceTreatment() {
    }
    
    /**
     * Constructor with all fields
     */
    public DataSourceTreatment(String id, String dataSourceId, Treatments treatment, 
                              JsonNode config, LocalDateTime dateCreated, 
                              LocalDateTime dateModified) {
        this.id = id;
        this.dataSourceId = dataSourceId;
        this.treatment = treatment;
        this.config = config;
        this.dateCreated = dateCreated;
        this.dateModified = dateModified;
    }
    
    // Getters and setters
    public String getId() {
        return id;
    }
    
    public void setId(String id) {
        this.id = id;
    }
    
    public String getDataSourceId() {
        return dataSourceId;
    }
    
    public void setDataSourceId(String dataSourceId) {
        this.dataSourceId = dataSourceId;
    }
    
    public Treatments getTreatment() {
        return treatment;
    }
    
    public void setTreatment(Treatments treatment) {
        this.treatment = treatment;
    }
    
    public JsonNode getConfig() {
        return config;
    }
    
    public void setConfig(JsonNode config) {
        this.config = config;
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
    

    
    @Override
    public String toString() {
        return "DataSourceTreatment{" +
                "id='" + id + '\'' +
                ", dataSourceId='" + dataSourceId + '\'' +
                ", treatment=" + treatment +
                ", config=" + config +
                ", dateCreated=" + dateCreated +
                ", dateModified=" + dateModified +
                '}';
    }
}