package enterfind.server.scan;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.exception.SystemIssueException;
import enterfind.server.model.S3BucketDataSource;
import enterfind.server.util.MimeTypeDetector;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Data provider for scanning S3-compatible buckets using AWS SDK v2.
 * Supports both AWS S3 and S3-compatible endpoints (e.g. MinIO).
 *
 * <p>The {@code nameOrUrl} field of the datasource is interpreted as follows:
 * <ul>
 *   <li>If it contains "://", it is treated as {@code <endpointUrl>/<bucketName>}, e.g.
 *       {@code http://localhost:9000/mybucket}.</li>
 *   <li>Otherwise it is treated as a plain AWS bucket name, using the default regional endpoint.</li>
 * </ul>
 */
public class S3BucketDataProvider implements DataProviderInterface {

    private static final Logger logger = LoggerFactory.getLogger(S3BucketDataProvider.class);
    private static final String DEFAULT_REGION = "us-east-1";

    private final S3BucketDataSource dataSource;
    private final S3Client s3Client;
    private final String bucketName;
    private final String basePath;

    public S3BucketDataProvider(S3BucketDataSource dataSource) {
        this.dataSource = dataSource;
        this.basePath = dataSource.path() != null ? dataSource.path() : "";

        ParsedNameOrUrl parsed = parseNameOrUrl(dataSource.nameOrUrl());
        this.bucketName = parsed.bucketName;
        this.s3Client = buildS3Client(parsed.endpointUrl, dataSource);
    }

    // -----------------------------------------------------------------------
    // DataProviderInterface
    // -----------------------------------------------------------------------

    @Override
    public List<Item> getItems(String path) {
        String prefix = resolvePrefix(path);
        List<Item> items = new ArrayList<>();

        String continuationToken = null;
        do {
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .delimiter("/")
                    .continuationToken(continuationToken)
                    .build();

            ListObjectsV2Response response;
            try {
                response = s3Client.listObjectsV2(request);
            } catch (Exception e) {
                throw new SystemIssueException("Error listing S3 objects at prefix: " + prefix, e);
            }

            for (S3Object obj : response.contents()) {
                // Skip "directory" placeholder objects (key ends with /)
                if (obj.key().endsWith("/")) {
                    continue;
                }
                Item item = createItemFromS3Object(obj, path);
                if (item != null) {
                    items.add(item);
                }
            }

            continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
        } while (continuationToken != null);

        return items;
    }

    @Override
    public List<Folder> getFolders(String path) {
        String prefix = resolvePrefix(path);
        List<Folder> folders = new ArrayList<>();

        String continuationToken = null;
        do {
            ListObjectsV2Request request = ListObjectsV2Request.builder()
                    .bucket(bucketName)
                    .prefix(prefix)
                    .delimiter("/")
                    .continuationToken(continuationToken)
                    .build();

            ListObjectsV2Response response;
            try {
                response = s3Client.listObjectsV2(request);
            } catch (Exception e) {
                throw new SystemIssueException("Error listing S3 folders at prefix: " + prefix, e);
            }

            for (CommonPrefix cp : response.commonPrefixes()) {
                Folder folder = createFolderFromCommonPrefix(cp, path);
                if (folder != null) {
                    folders.add(folder);
                }
            }

            continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
        } while (continuationToken != null);

        return folders;
    }

    @Override
    public InputStream getInputStream(Item item) {
        String key = resolveKey(item.getFullPath());
        try {
            return s3Client.getObject(GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(key)
                    .build());
        } catch (Exception e) {
            throw new SystemIssueException("Error opening S3 object: " + key, e);
        }
    }

    @Override
    public void writeFile(Item item, InputStream data) {
        if (item.getFullPath() == null || item.getFullPath().isEmpty()) {
            throw new InvalidArgumentException("Item must have a full path to write");
        }
        String key = resolveKey(item.getFullPath());
        try {
            // Stream directly when Content-Length is known; AWS SDK v2 putObject
            // requires a content length for true streaming (no in-memory buffering).
            // Fall back to byte-array only when size is unavailable.
            final RequestBody requestBody;
            if (item.getSizeBytes() != null && item.getSizeBytes() > 0) {
                requestBody = RequestBody.fromInputStream(data, item.getSizeBytes());
            } else {
                logger.warn("Content-Length unknown for S3 upload key: {}; buffering entire stream into memory", key);
                requestBody = RequestBody.fromBytes(data.readAllBytes());
            }
            s3Client.putObject(
                PutObjectRequest.builder().bucket(bucketName).key(key).build(),
                requestBody);
        } catch (java.io.IOException e) {
            throw new SystemIssueException("Error reading upload data for S3 key: " + key, e);
        } catch (Exception e) {
            throw new SystemIssueException("Error writing S3 object: " + key, e);
        }
    }

    @Override
    public void deleteFile(Item item) {
        if (item.getFullPath() == null || item.getFullPath().isEmpty()) {
            throw new InvalidArgumentException("Item must have a full path to delete");
        }
        String key = resolveKey(item.getFullPath());
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(key).build());
        } catch (Exception e) {
            throw new SystemIssueException("Error deleting S3 object: " + key, e);
        }
    }

    @Override
    public void shutdown() {
        try {
            s3Client.close();
        } catch (Exception e) {
            logger.warn("Error closing S3 client for datasource {}: {}", dataSource.id(), e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Path helpers
    // -----------------------------------------------------------------------

    /**
     * Converts a logical path (relative to basePath) into an S3 key prefix.
     * The prefix always ends with "/" unless it is empty.
     */
    private String resolvePrefix(String path) {
        String keyPath = resolveKey(path);
        // For listing, the prefix must end with "/" when non-empty so that we
        // get only direct children rather than the key itself.
        if (keyPath.isEmpty()) {
            return keyPath;
        }
        return keyPath.endsWith("/") ? keyPath : keyPath + "/";
    }

    /**
     * Converts a logical path (relative to basePath) into an S3 key string.
     * Applies the same security checks used by other data providers.
     */
    private String resolveKey(String path) {
        if (path == null || path.isEmpty()) {
            return basePath.isEmpty() ? "" : (basePath.endsWith("/") ? basePath : basePath + "/");
        }

        // Security: reject null bytes
        if (path.contains("\0")) {
            throw new InvalidArgumentException("Access denied: Path contains null bytes");
        }

        // Security: reject absolute paths
        if (Paths.get(path).isAbsolute()) {
            throw new InvalidArgumentException("Access denied: Absolute paths are not allowed");
        }

        // Security: normalize and check for traversal
        String normalized = Paths.get(path).normalize().toString().replace("\\", "/");
        if (normalized.startsWith("..") || normalized.contains("/..")) {
            throw new InvalidArgumentException("Access denied: Path traversal not allowed");
        }

        if (basePath.isEmpty()) {
            return normalized;
        }
        String base = basePath.endsWith("/") ? basePath : basePath + "/";
        return base + normalized;
    }

    // -----------------------------------------------------------------------
    // Object/folder model construction
    // -----------------------------------------------------------------------

    private Item createItemFromS3Object(S3Object obj, String parentPath) {
        try {
            // The key relative to the prefix is the file name; strip trailing "/"
            String key = obj.key();
            String name = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
            if (name.isEmpty()) {
                return null;
            }

            Item item = new Item();
            item.setName(name);
            item.setPath(parentPath);
            item.setSizeBytes(obj.size());
            item.setTimeModified(convertToLocalDateTime(obj.lastModified()));
            item.setTimeCreated(convertToLocalDateTime(obj.lastModified()));
            item.setMimeType(MimeTypeDetector.detectMimeType(name));
            return item;
        } catch (Exception e) {
            logger.error("Error creating item from S3 object {}: {}", obj.key(), e.getMessage(), e);
            return null;
        }
    }

    private Folder createFolderFromCommonPrefix(CommonPrefix cp, String parentPath) {
        try {
            String prefix = cp.prefix();
            // Strip trailing "/"
            if (prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            String name = prefix.contains("/") ? prefix.substring(prefix.lastIndexOf('/') + 1) : prefix;
            if (name.isEmpty()) {
                return null;
            }

            Folder folder = new Folder();
            folder.setName(name);
            folder.setPath(parentPath);
            return folder;
        } catch (Exception e) {
            logger.error("Error creating folder from S3 prefix {}: {}", cp.prefix(), e.getMessage(), e);
            return null;
        }
    }

    private LocalDateTime convertToLocalDateTime(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    // -----------------------------------------------------------------------
    // Client construction
    // -----------------------------------------------------------------------

    private S3Client buildS3Client(String endpointUrl, S3BucketDataSource ds) {
        String regionStr = (ds.region() != null && !ds.region().isBlank()) ? ds.region() : DEFAULT_REGION;

        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(regionStr))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ds.accessKey(), ds.secretKey())));

        if (endpointUrl != null) {
            builder.endpointOverride(URI.create(endpointUrl))
                   .forcePathStyle(true);
        }

        return builder.build();
    }

    /**
     * Parses {@code nameOrUrl} into an optional endpoint URL and bucket name.
     * <ul>
     *   <li>{@code http://localhost:9000/mybucket} → endpoint={@code http://localhost:9000}, bucket={@code mybucket}</li>
     *   <li>{@code mybucket} → endpoint=null (use AWS default), bucket={@code mybucket}</li>
     * </ul>
     */
    private static ParsedNameOrUrl parseNameOrUrl(String nameOrUrl) {
        if (nameOrUrl == null || nameOrUrl.isBlank()) {
            throw new InvalidArgumentException("S3 nameOrUrl must not be blank");
        }
        if (nameOrUrl.contains("://")) {
            // e.g. "http://localhost:9000/mybucket"
            URI uri = URI.create(nameOrUrl);
            String path = uri.getPath();
            if (path == null || path.equals("/") || path.isEmpty()) {
                throw new InvalidArgumentException(
                        "S3 nameOrUrl with endpoint must include bucket name, e.g. http://localhost:9000/mybucket");
            }
            // Strip leading "/"
            String bucket = path.startsWith("/") ? path.substring(1) : path;
            // Remove any further path segments — only the first segment is the bucket
            if (bucket.contains("/")) {
                bucket = bucket.substring(0, bucket.indexOf('/'));
            }
            String endpoint = uri.getScheme() + "://" + uri.getAuthority();
            return new ParsedNameOrUrl(endpoint, bucket);
        }
        // Plain bucket name
        return new ParsedNameOrUrl(null, nameOrUrl.trim());
    }

    private record ParsedNameOrUrl(String endpointUrl, String bucketName) {}
}
