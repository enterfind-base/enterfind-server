package enterfind.server.model.input;

/**
 * Input type for updating a remote file system datasource
 */
public record UpdateRemoteFileSystemDataSourceInput(
    String name,
    String cronPattern,
    String server,
    String domain,
    String username,
    String password,
    String shareName,
    String path,
    String skipFiles
) {
}