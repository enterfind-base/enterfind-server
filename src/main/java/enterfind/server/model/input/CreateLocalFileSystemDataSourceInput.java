package enterfind.server.model.input;

/**
 * Input type for creating a local file system datasource
 */
public record CreateLocalFileSystemDataSourceInput(
    String name,
    String cronPattern,
    String localPath,
    String skipFiles
) {
}