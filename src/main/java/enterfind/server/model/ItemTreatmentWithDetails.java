package enterfind.server.model;

import enterfind.server.scan.Item;
import enterfind.server.scan.Treatments;

/**
 * Model representing an item treatment with its details including the full item and treatment name.
 * Used for fetching item treatments ready for processing along with their associated items.
 */
public class ItemTreatmentWithDetails {
    
    private String itemTreatmentId;
    private Treatments treatmentName;
    private DataSourceType dataSourceType;
    private String dataSourceId;
    private Item item;
    private ItemTreatmentStatus status;
    private int version;

    /**
     * Default constructor
     */
    public ItemTreatmentWithDetails() {
    }

    /**
     * Constructor with all fields
     */
    public ItemTreatmentWithDetails(String itemTreatmentId, Treatments treatmentName, DataSourceType dataSourceType, String dataSourceId, Item item, ItemTreatmentStatus status) {
        this.itemTreatmentId = itemTreatmentId;
        this.treatmentName = treatmentName;
        this.dataSourceType = dataSourceType;
        this.dataSourceId = dataSourceId;
        this.item = item;
        this.status = status;
        this.version = 1;
    }

    // Getters and setters
    public String getItemTreatmentId() {
        return itemTreatmentId;
    }

    public void setItemTreatmentId(String itemTreatmentId) {
        this.itemTreatmentId = itemTreatmentId;
    }

    public Treatments getTreatmentName() {
        return treatmentName;
    }

    public void setTreatmentName(Treatments treatmentName) {
        this.treatmentName = treatmentName;
    }

    public DataSourceType getDataSourceType() {
        return dataSourceType;
    }

    public void setDataSourceType(DataSourceType dataSourceType) {
        this.dataSourceType = dataSourceType;
    }

    public String getDataSourceId() {
        return dataSourceId;
    }

    public void setDataSourceId(String dataSourceId) {
        this.dataSourceId = dataSourceId;
    }

    public Item getItem() {
        return item;
    }

    public void setItem(Item item) {
        this.item = item;
    }

    public ItemTreatmentStatus getStatus() {
        return status;
    }

    public void setStatus(ItemTreatmentStatus status) {
        this.status = status;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    @Override
    public String toString() {
        return "ItemTreatmentWithDetails{" +
            "itemTreatmentId='" + itemTreatmentId + '\'' +
            ", treatmentName=" + treatmentName +
            ", dataSourceType=" + dataSourceType +
            ", dataSourceId='" + dataSourceId + '\'' +
            ", item=" + item +
            ", status=" + status +
            ", version=" + version +
            '}';
    }
}