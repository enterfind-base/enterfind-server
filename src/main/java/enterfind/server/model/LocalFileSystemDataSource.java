package enterfind.server.model;

import java.time.LocalDateTime;

/**
 * Local file system datasource implementation
 */
public record LocalFileSystemDataSource(
    String id,
    String name,
    String cronPattern,
    LocalDateTime dateCreated,
    LocalDateTime dateLastScanned,
    DataSourceStatus status,
    String localPath,
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
        return DataSourceType.LOCAL_FILESYSTEM;
    }

    @Override
    public String getSkipFiles() {
        return skipFiles;
    }
}