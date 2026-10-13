package enterfind.server.model.input;

/**
 * Input type for updating an S3 bucket datasource
 */
public record UpdateS3BucketDataSourceInput(
    String name,
    String cronPattern,
    String nameOrUrl,
    String accessKey,
    String secretKey,
    String path,
    String region,
    String skipFiles
) {
}