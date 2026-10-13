package enterfind.server.scan;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Shared S3/MinIO helpers for integration tests.
 * Encapsulates connection details, bucket creation, object upload, and prefix deletion.
 */
class S3TestHelper {

    static final String ENDPOINT = "http://localhost:9000";
    static final String ACCESS_KEY = "minioadmin";
    static final String SECRET_KEY = "minioadmin";
    static final String BUCKET = "enterfind-test";
    private static final String TEST_PATH_PREFIX = "enterfind-server-tests/";

    /** Returns a pre-configured S3Client pointing at the local MinIO instance. */
    static S3Client getS3Client() {
        return S3Client.builder()
                .region(Region.US_EAST_1)
                .endpointOverride(URI.create(ENDPOINT))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .build();
    }

    /** Ensures the test bucket exists, creating it if absent. */
    static void createBucketIfAbsent(S3Client client) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(BUCKET).build());
        } catch (NoSuchBucketException e) {
            client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    /** Returns a unique key prefix for a test run, e.g. {@code enterfind-server-tests/<uuid>}. */
    static String newTestPath() {
        return TEST_PATH_PREFIX + UUID.randomUUID();
    }

    /** Uploads a UTF-8 text string as an S3 object. */
    static void putObject(S3Client client, String key, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key(key).build(),
                RequestBody.fromBytes(bytes));
    }

    /** Uploads bytes from an InputStream as an S3 object. Content-length must be known. */
    static void putObject(S3Client client, String key, InputStream in, long contentLength) throws IOException {
        client.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key(key).build(),
                RequestBody.fromInputStream(in, contentLength));
    }

    /**
     * Deletes all objects whose keys start with the given prefix (inclusive of
     * a trailing "/" added if absent). Does nothing if the prefix has no objects.
     */
    static void deletePrefix(S3Client client, String prefix) {
        String normalizedPrefix = prefix.endsWith("/") ? prefix : prefix + "/";

        String continuationToken = null;
        do {
            ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                    .bucket(BUCKET)
                    .prefix(normalizedPrefix)
                    .continuationToken(continuationToken)
                    .build();

            ListObjectsV2Response response = client.listObjectsV2(listReq);

            if (!response.contents().isEmpty()) {
                Delete delete = Delete.builder()
                        .objects(response.contents().stream()
                                .map(obj -> ObjectIdentifier.builder().key(obj.key()).build())
                                .toList())
                        .build();
                client.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(BUCKET)
                        .delete(delete)
                        .build());
            }

            continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
        } while (continuationToken != null);
    }

    private S3TestHelper() {}
}
