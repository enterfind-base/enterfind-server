package enterfind.server.model.input;

/**
 * Input type for creating a remote file system datasource
 */
public record CreateRemoteFileSystemDataSourceInput(
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