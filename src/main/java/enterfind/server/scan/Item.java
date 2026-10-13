package enterfind.server.scan;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class Item extends AbstractItem {
    

    private Long sizeBytes;
    private String contentHash;
    private String mimeType;
    
    // For GraphQL - populated by SearchDAO
    private String dataSourceName;
    private String dataSourceType;

    // For GraphQL search result compatibility
    public String getFolder() {
        return getPath();   // already slash-separated
    }
    public String getDateCreated() {
        return getTimeCreated() != null ? getTimeCreated().toString() : null;
    }
    public String getDateModified() {
        return getTimeModified() != null ? getTimeModified().toString() : null;
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

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getDownloadLink() {
        return buildLink("/content/");
    }

    public String getPreviewLink() {
        return buildLink("/preview/");
    }

    private String buildLink(String prefix) {
        if (dataSourceName == null || dataSourceType == null || getName() == null) {
            return null;
        }
        try {
            String encodedDataSourceName = URLEncoder.encode(dataSourceName, StandardCharsets.UTF_8);
            String folder = getFolder();
            String path = (folder != null && !folder.isEmpty()) ? folder + "/" + getName() : getName();
            // The controller does urlDecode(urlDecode(relativePath)), so we need to encode twice
            String encodedPath = URLEncoder.encode(path, StandardCharsets.UTF_8);
            encodedPath = URLEncoder.encode(encodedPath, StandardCharsets.UTF_8);
            return prefix + dataSourceType + "/" + encodedDataSourceName + "/" + encodedPath;
        } catch (Exception e) {
            // If encoding fails, return null
            return null;
        }
    }
}
