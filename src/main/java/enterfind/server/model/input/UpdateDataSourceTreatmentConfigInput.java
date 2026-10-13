package enterfind.server.model.input;

import enterfind.server.scan.Treatments;
import enterfind.server.model.DataSourceType;

/**
 * Input type for updating treatment configuration
 */
public class UpdateDataSourceTreatmentConfigInput {
    
    private String dataSourceName;
    private DataSourceType dataSourceType;
    private Treatments treatment;
    private String config;
    
    public UpdateDataSourceTreatmentConfigInput() {
    }
    
    public UpdateDataSourceTreatmentConfigInput(String dataSourceName, DataSourceType dataSourceType, Treatments treatment, String config) {
        this.dataSourceName = dataSourceName;
        this.dataSourceType = dataSourceType;
        this.treatment = treatment;
        this.config = config;
    }
    
    public String getDataSourceName() {
        return dataSourceName;
    }
    
    public void setDataSourceName(String dataSourceName) {
        this.dataSourceName = dataSourceName;
    }
    
    public DataSourceType getDataSourceType() {
        return dataSourceType;
    }
    
    public void setDataSourceType(DataSourceType dataSourceType) {
        this.dataSourceType = dataSourceType;
    }
    
    public Treatments getTreatment() {
        return treatment;
    }
    
    public void setTreatment(Treatments treatment) {
        this.treatment = treatment;
    }
    
    public String getConfig() {
        return config;
    }
    
    public void setConfig(String config) {
        this.config = config;
    }
}