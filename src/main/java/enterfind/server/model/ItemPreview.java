package enterfind.server.model;

import java.time.LocalDateTime;

/**
 * Model representing a preview for an item (thumbnail for images/PDFs)
 */
public class ItemPreview {
    
    private String itemId;
    private ItemPreviewStatus status;
    private byte[] previewData;
    private String contentType;
    private LocalDateTime dateCreated;
    private LocalDateTime dateModified;
    private int version;
    
    /**
     * Default constructor
     */
    public ItemPreview() {
    }
    
    /**
     * Constructor with all fields
     */
    public ItemPreview(String itemId, ItemPreviewStatus status, 
                      byte[] previewData, String contentType,
                      LocalDateTime dateCreated, LocalDateTime dateModified, int version) {
        this.itemId = itemId;
        this.status = status;
        this.previewData = previewData;
        this.contentType = contentType;
        this.dateCreated = dateCreated;
        this.dateModified = dateModified;
        this.version = version;
    }
    
    public String getItemId() {
        return itemId;
    }
    
    public void setItemId(String itemId) {
        this.itemId = itemId;
    }
    
    public ItemPreviewStatus getStatus() {
        return status;
    }
    
    public void setStatus(ItemPreviewStatus status) {
        this.status = status;
    }
    
    public byte[] getPreviewData() {
        return previewData;
    }
    
    public void setPreviewData(byte[] previewData) {
        this.previewData = previewData;
    }
    
    public String getContentType() {
        return contentType;
    }
    
    public void setContentType(String contentType) {
        this.contentType = contentType;
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
}
