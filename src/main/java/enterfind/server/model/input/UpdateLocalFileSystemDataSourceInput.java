package enterfind.server.model.input;

/**
 * Input type for updating a local file system datasource
 */
public record UpdateLocalFileSystemDataSourceInput(
    String name,
    String cronPattern,
    String localPath,
    String skipFiles
) {
}