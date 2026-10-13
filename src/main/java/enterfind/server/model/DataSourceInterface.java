package enterfind.server.model;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Base DataSource model representing common fields for all datasource types
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = LocalFileSystemDataSource.class, name = "LOCAL_FILESYSTEM"),
    @JsonSubTypes.Type(value = RemoteFileSystemDataSource.class, name = "REMOTE_FILESYSTEM"),
    @JsonSubTypes.Type(value = S3BucketDataSource.class, name = "S3_BUCKET")
})
public sealed interface DataSourceInterface
    permits LocalFileSystemDataSource, RemoteFileSystemDataSource, S3BucketDataSource {

    String getName();
    String getCronPattern();
    LocalDateTime getDateCreated();
    LocalDateTime getDateLastScanned();
    DataSourceType getType();
    DataSourceStatus getStatus();
    String getSkipFiles();
}