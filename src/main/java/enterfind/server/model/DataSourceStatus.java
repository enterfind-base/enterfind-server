package enterfind.server.model;

/**
 * Status of a data source
 */
public enum DataSourceStatus {
    /**
     * Data source is idle and ready for scanning
     */
    IDLE,

    /**
     * Data source is currently being scanned
     */
    SCANNING
}
