package enterfind.server.model;

import java.time.LocalDateTime;

/**
 * S3-like bucket datasource implementation
 */
public record S3BucketDataSource(
    String id,
    String name,
    String cronPattern,
    LocalDateTime dateCreated,
    LocalDateTime dateLastScanned,
    DataSourceStatus status,
    String nameOrUrl,
    String accessKey,
    String secretKey,
    String path,
    String region,
    String skipFiles
) implements DataSourceInterface {

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getCronPattern() {
        return cronPattern;
    }

    @Override
    public LocalDateTime getDateCreated() {
        return dateCreated;
    }

    @Override
    public LocalDateTime getDateLastScanned() {
        return dateLastScanned;
    }

    @Override
    public DataSourceStatus getStatus() {
        return status;
    }

    @Override
    public DataSourceType getType() {
        return DataSourceType.S3_BUCKET;
    }

    @Override
    public String getSkipFiles() {
        return skipFiles;
    }
}