package enterfind.server.scan.treatment;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.hash.Hashing;
import com.google.common.io.MoreFiles;

/**
 * Utility methods shared across treatment handlers.
 */
public final class TreatmentUtils {

    private static final Logger logger = LoggerFactory.getLogger(TreatmentUtils.class);

    /**
     * Maximum chunk size for content to ensure full-text search (FTS) indexing.
     * This matches the limit in the database trigger function that populates the FTS column.
     * Content larger than this will be split into multiple chunks.
     */
    public static final int FTS_CHUNK_SIZE = 500000; // 500KB

    // --- Noise-attribute filter thresholds (tunable in one place) ---
    // Maximum number of whitespace-separated integer tokens before a value is considered a numeric blob
    private static final int NOISE_MAX_INTEGER_TOKENS = 16;
    // Minimum ratio of alphabetic characters for a value to be considered real text
    private static final double NOISE_MIN_ALPHABETIC_RATIO = 0.15;
    // Minimum value length before the alphabetic-ratio test is applied
    private static final int NOISE_MIN_LENGTH_FOR_RATIO = 64;

    // Tika's own rendering of an oversized/binary multi-valued tag, e.g. "[22000 values]"
    private static final Pattern TIKA_ARRAY_SUMMARY = Pattern.compile("^\\[\\d+ values\\]$");

    // Bare hex-ID key: optional leading underscores, 0x prefix, hex digits, optional trailing underscores
    // e.g. "_0x004a_", "0x80", "__0xffff__" — never a meaningful search field
    private static final Pattern HEX_ID_KEY = Pattern.compile("^_*0x[0-9a-f]+_*$");

    // Supported image file extensions for treatments that process images
    private static final Set<String> SUPPORTED_IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "bmp", "tiff", "tif"
    );

    /**
     * Local cache directory for ONNX models downloaded at runtime by treatment handlers
     * (face recognition, object detection). Kept out of the DJL zoo cache on purpose: these
     * are hand-fetched from upstream releases, not zoo artifacts.
     */
    public static final Path MODEL_CACHE_DIR = Path.of(System.getProperty("user.home"), ".enterfind", "models");

    private TreatmentUtils() {
        // Prevent instantiation
    }

    /** Fixed file stem inside a content-hash cache directory. */
    public static final String HASH_CACHED_MODEL_NAME = "model";

    /**
     * A model fetched (or revalidated) from a URL and cached by the SHA-256 of its bytes.
     *
     * @param modelDir     directory holding {@code model.onnx}, suitable for {@code optModelPath}
     * @param contentHash  lowercase hex SHA-256 of the bytes — the model's stable identity
     * @param etag         the origin's {@code ETag} for those bytes, or null if it offered none.
     *                     Stored by the caller so a later fetch can revalidate instead of transfer.
     */
    public record FetchedModel(Path modelDir, String contentHash, String etag) {}

    // ==================== Model cache (content-hash keyed) ====================
    //
    // Every model — the two hardcoded detectors and the operator-chosen embedding model — is cached
    // as MODEL_CACHE_DIR/<sha256>/model.onnx. Keying on *content* rather than a fixed name is what
    // makes an operator-supplied URL safe, and it removes a trap the old name-keyed helper carried
    // even for hardcoded URLs: it returned a cached file without consulting the URL, so repointing a
    // model constant at new bytes under the same name would have left every existing install loading
    // the old weights silently and forever.

    /** Cache directory holding the model with this content hash. */
    private static Path cacheDirFor(String contentHash) {
        return MODEL_CACHE_DIR.resolve(contentHash);
    }

    /** The model file inside a content-hash cache directory. */
    private static Path modelFileIn(Path modelDir) {
        return modelDir.resolve(HASH_CACHED_MODEL_NAME + ".onnx");
    }

    /** True when the cache already holds the model with this content hash. */
    private static boolean isCached(String contentHash) {
        return contentHash != null && Files.exists(modelFileIn(cacheDirFor(contentHash)));
    }

    /** A completed transfer: the temp file holding the bytes, plus the origin's ETag for them. */
    private record Transfer(Path file, String etag) {}

    /** What we last learned about a URL: which cached model it produced, and that model's ETag. */
    private record CachedOrigin(String contentHash, String etag) {}

    /** Metadata file inside a hash directory, recording where those exact bytes came from. */
    private static final String ORIGIN_FILE = "origin";

    /**
     * The cache's one naming rule: a directory holds a model <b>iff</b> it is named for the SHA-256
     * of that model's bytes.
     *
     * <p>This is the invariant that makes the cache safely purgeable — anything in
     * {@link #MODEL_CACHE_DIR} whose name is not a content hash is a directory from the older
     * name-keyed layout or a {@code download-*.onnx} left by an interrupted transfer, and can be
     * deleted without losing anything. CI mounts this directory wholesale as its model cache, so
     * keeping the rule absolute is what stops dead weight accumulating there.
     */
    public static boolean isContentHashName(String name) {
        return name.length() == 64
                && name.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'));
    }

    /**
     * Find the model previously fetched from {@code url} by scanning the hash directories for one
     * whose origin record names it.
     *
     * <p>A scan, rather than an index file next to the models, precisely so the invariant above holds
     * with no exceptions. With a handful of models this is a few small reads, and it happens once per
     * set-model — never on the inference path.
     */
    private static CachedOrigin findCachedOrigin(String url) {
        if (!Files.isDirectory(MODEL_CACHE_DIR)) {
            return null;
        }
        try (java.util.stream.Stream<Path> entries = Files.list(MODEL_CACHE_DIR)) {
            for (Path dir : entries.toList()) {
                if (!Files.isDirectory(dir) || !isContentHashName(dir.getFileName().toString())) {
                    continue;
                }
                Path origin = dir.resolve(ORIGIN_FILE);
                if (!Files.exists(origin)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(origin);
                if (!lines.isEmpty() && lines.get(0).trim().equals(url)) {
                    return new CachedOrigin(dir.getFileName().toString(),
                            lines.size() > 1 ? lines.get(1).trim() : "");
                }
            }
        } catch (java.io.IOException e) {
            logger.debug("Could not read model cache origins; will re-fetch", e);
        }
        return null;
    }

    private static void writeOrigin(Path modelDir, String url, String etag) {
        try {
            Files.write(modelDir.resolve(ORIGIN_FILE), List.of(url, etag == null ? "" : etag));
        } catch (java.io.IOException e) {
            // Purely an optimisation: a failed write costs a transfer next time, nothing more.
            logger.debug("Could not record model origin for {}", url, e);
        }
    }

    /**
     * Transfer {@code url} into a temp file, or return null if the origin answered
     * {@code 304 Not Modified} to the conditional request.
     *
     * <p>Non-HTTP URLs ({@code file:}, used by tests and air-gapped installs) simply transfer: there
     * is no ETag to send or read, and no round trip to save.
     */
    private static Transfer transfer(String url, String ifNoneMatch) throws java.io.IOException {
        URLConnection connection = URI.create(url).toURL().openConnection();
        String etag = null;
        if (connection instanceof HttpURLConnection http) {
            if (ifNoneMatch != null && !ifNoneMatch.isBlank()) {
                http.setRequestProperty("If-None-Match", ifNoneMatch);
            }
            if (http.getResponseCode() == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return null;
            }
            etag = http.getHeaderField("ETag");
        }

        Files.createDirectories(MODEL_CACHE_DIR);
        Path temp = Files.createTempFile(MODEL_CACHE_DIR, "download-", ".onnx");
        try (InputStream in = connection.getInputStream()) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        return new Transfer(temp, etag);
    }

    /**
     * Fetch a model, revalidating against what this machine already holds rather than re-transferring
     * it, and cache it by the SHA-256 of its bytes.
     *
     * <p>The point of this function has always been to derive identity <em>from the bytes</em>, so it
     * cannot simply trust a cache keyed on the URL. Revalidation keeps that guarantee while skipping
     * the transfer: {@code If-None-Match} asks the origin whether the representation still matches
     * the ETag we saw at the moment we hashed those bytes, and a {@code 304} is the origin answering
     * that it does. A changed file yields {@code 200} and is hashed afresh exactly as before.
     * Verified to return 304 with a zero-byte body on both {@code raw.githubusercontent.com} and
     * GitHub Releases (through its redirect to blob storage); a {@code file:} URL has no ETag and
     * simply transfers.
     *
     * <p>The conditional is only sent when a {@code 304} could actually be honoured — the index must
     * name a hash the cache still holds. On a fresh box, a cleared cache, or a URL never seen before,
     * the request goes out unconditional and the bytes really are fetched.
     *
     * <p><b>Trust boundary:</b> on a 304 the bytes are not re-hashed; we take the origin's word that
     * they are unchanged. That is the same trust already extended to the origin serving the model at
     * all, and the conditional carries only an ETag we recorded ourselves.
     */
    public static FetchedModel fetchModelByContentHash(String url) throws java.io.IOException {
        // Non-null only when a 304 could actually be honoured: we know an ETag for this URL and still
        // hold the bytes it named. It and the conditional below are one decision, which is why the
        // origin is carried rather than a boolean — the 304 branch needs the hash it points at.
        CachedOrigin known = findCachedOrigin(url);
        CachedOrigin revalidatable =
                known != null && !known.etag().isBlank() && isCached(known.contentHash()) ? known : null;

        Transfer transferred = transfer(url, revalidatable == null ? null : revalidatable.etag());
        if (transferred == null) {
            // transfer() returns null only for a 304, which an origin may only send in reply to the
            // conditional that revalidatable enabled. Stated rather than assumed: a server breaking
            // that rule should name itself here, not surface as a null dereference.
            CachedOrigin reused = Objects.requireNonNull(revalidatable,
                    () -> "Origin returned 304 for " + url + " without a conditional request");
            logger.info("Model at {} unchanged; reusing cached sha256 {} without transfer",
                    url, reused.contentHash());
            return new FetchedModel(cacheDirFor(reused.contentHash()), reused.contentHash(), reused.etag());
        }

        try {
            String hash = sha256Of(transferred.file());
            Path modelDir = cacheDirFor(hash);
            Files.createDirectories(modelDir);
            Files.move(transferred.file(), modelFileIn(modelDir), StandardCopyOption.REPLACE_EXISTING);
            writeOrigin(modelDir, url, transferred.etag());
            logger.info("Cached model {} (sha256 {})", modelFileIn(modelDir), hash);
            return new FetchedModel(modelDir, hash, transferred.etag());
        } finally {
            Files.deleteIfExists(transferred.file());
        }
    }

    /**
     * Resolve the cache directory for a model already known by its content hash, downloading from
     * {@code url} only if the directory is absent (cache cleared, fresh box). A download whose bytes
     * no longer match {@code expectedHash} throws rather than serving a model we did not verify.
     *
     * <p>This is the steady-state load path for every model: the two hardcoded detectors pin their
     * hash as a constant, and the embedding handler passes the hash the picker derived and stored.
     */
    public static Path ensureModelCachedByHash(String url, String expectedHash) throws java.io.IOException {
        if (isCached(expectedHash)) {
            return cacheDirFor(expectedHash);
        }
        FetchedModel fetched = fetchModelByContentHash(url);
        if (!fetched.contentHash().equals(expectedHash)) {
            throw new java.io.IOException("Model at " + url + " changed: expected sha256 " + expectedHash
                    + " but got " + fetched.contentHash()
                    + ". If the upstream change is intended, verify the new model and update its pin.");
        }
        return fetched.modelDir();
    }

    /**
     * Lowercase hex SHA-256 of a file, streamed rather than read into memory — some of these models
     * are hundreds of megabytes. Guava is already a direct dependency (it backs
     * {@code NamedLockRegistry}), so this needs no hand-rolled digest loop.
     */
    private static String sha256Of(Path file) throws java.io.IOException {
        return MoreFiles.asByteSource(file).hash(Hashing.sha256()).toString();
    }

    /**
     * Returns true if a metadata attribute produced by the Tika metadata loop is noise
     * and should be dropped before persisting.
     *
     * <p>Predicate 1 (provenance): drop keys that the extractor itself flagged as
     * uninterpretable — {@code unknown_tag_*} prefix, bare hex-ID keys (e.g.
     * {@code _0x004a_}), or keys that contain no alphabetic characters after
     * normalization (digit-only tags).
     *
     * <p>Predicate 2 (blob shape): drop values that are binary/array data masquerading
     * as metadata — Tika's {@code [N values]} summary form, long runs of bare integers,
     * or values whose proportion of alphabetic characters is below the threshold.
     *
     * <p>This method is intentionally scoped to the metadata loop only. OCR/content
     * fields are emitted on a separate path and are never passed here.
     *
     * @param normalizedKey the key after {@code toLowerCase().replaceAll("[^a-zA-Z0-9_-]", "_")}
     * @param value         the sanitized, non-empty metadata value
     * @return true if the attribute should be dropped
     */
    public static boolean isNoiseAttribute(String normalizedKey, String value) {
        // Predicate 1a: drewnoakes emits "unknown_tag_" for maker-note tags it cannot name
        if (normalizedKey.startsWith("unknown_tag_")) {
            logger.info("Dropping noise attribute [unknown_tag key]: key='{}' value='{}'",
                    normalizedKey, StringUtils.abbreviate(value, 80));
            return true;
        }

        // Predicate 1b: bare hex-ID key — e.g. "_0x004a_"; contains letters a-f but
        // is never a meaningful search field
        if (HEX_ID_KEY.matcher(normalizedKey).matches()) {
            logger.info("Dropping noise attribute [hex-id key]: key='{}' value='{}'",
                    normalizedKey, StringUtils.abbreviate(value, 80));
            return true;
        }

        // Predicate 1c: key with no alphabetic content is never a meaningful search field
        boolean hasLetter = false;
        for (int i = 0; i < normalizedKey.length(); i++) {
            if (Character.isLetter(normalizedKey.charAt(i))) {
                hasLetter = true;
                break;
            }
        }
        if (!hasLetter) {
            logger.info("Dropping noise attribute [no-letter key]: key='{}' value='{}'",
                    normalizedKey, StringUtils.abbreviate(value, 80));
            return true;
        }

        // Predicate 2a: Tika's own "[N values]" summary for oversized/binary multi-valued tags
        if (TIKA_ARRAY_SUMMARY.matcher(value).matches()) {
            logger.info("Dropping noise attribute [array summary value]: key='{}' value='{}'",
                    normalizedKey, value);
            return true;
        }

        // Predicate 2b: long run of whitespace-separated integers (numeric blob)
        String[] tokens = value.trim().split("\\s+");
        if (tokens.length > NOISE_MAX_INTEGER_TOKENS) {
            boolean allIntegers = true;
            for (String token : tokens) {
                if (!token.matches("-?\\d+")) {
                    allIntegers = false;
                    break;
                }
            }
            if (allIntegers) {
                logger.info("Dropping noise attribute [integer blob, {} tokens]: key='{}' value='{}'",
                        tokens.length, normalizedKey, StringUtils.abbreviate(value, 80));
                return true;
            }
        }

        // Predicate 2c: low alphabetic ratio over a minimum length (binary/packed data)
        if (value.length() > NOISE_MIN_LENGTH_FOR_RATIO) {
            long letterCount = 0;
            for (int i = 0; i < value.length(); i++) {
                if (Character.isLetter(value.charAt(i))) {
                    letterCount++;
                }
            }
            double ratio = (double) letterCount / value.length();
            if (ratio < NOISE_MIN_ALPHABETIC_RATIO) {
                logger.info("Dropping noise attribute [low alpha ratio {}%, len={}]: key='{}' value='{}'",
                        String.format("%.1f", ratio * 100), value.length(), normalizedKey, StringUtils.abbreviate(value, 80));
                return true;
            }
        }

        return false;
    }


    /**
     * Sanitize text content for UTF-8 storage by removing null bytes and other problematic characters
     * that cannot be stored in PostgreSQL text fields.
     * 
     * @param input the input string to sanitize
     * @return sanitized string safe for UTF-8 storage
     */
    public static String sanitizeForUtf8Storage(String input) {
        if (input == null) {
            return "";
        }

        // Remove null bytes (0x00) and other control characters that cause UTF-8 storage issues
        // Keep only printable characters, whitespace, and common symbols
        StringBuilder sanitized = new StringBuilder();
        for (char c : input.toCharArray()) {
            // Allow printable ASCII, extended ASCII, and common Unicode characters
            // Exclude null bytes (0x00) and other problematic control characters
            if (c >= 32 || c == '\t' || c == '\n' || c == '\r') {
                sanitized.append(c);
            }
        }

        return sanitized.toString();
    }

    /**
     * Extract the file extension from a filename.
     * 
     * @param fileName the filename to extract extension from
     * @return the file extension (lowercase), or empty string if no extension
     */
    public static String getFileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1).toLowerCase() : "";
    }

    /**
     * Check if the given file extension is supported for image processing treatments.
     *
     * @param extension the file extension to check
     * @return true if the extension is supported for image processing
     */
    public static boolean isSupportedImageExtension(String extension) {
        return extension != null && SUPPORTED_IMAGE_EXTENSIONS.contains(extension.toLowerCase());
    }

    /**
     * Result keys for {@code count} chunks of {@code field}, numbered from {@code startIndex}. A
     * document that is a single chunk keeps the bare key; a document of several chunks is numbered
     * throughout ({@code field-0}, {@code field-1}, ...), including its first chunk.
     *
     * @param field the bare result key, e.g. {@code content}
     * @param startIndex the number of chunks of this document already emitted under numbered keys
     * @param count the number of chunks to name
     */
    public static List<String> chunkKeys(String field, int startIndex, int count) {
        if (startIndex == 0 && count == 1) {
            return List.of(field);
        }
        return IntStream.range(startIndex, startIndex + count).mapToObj(i -> field + "-" + i).toList();
    }

    /**
     * Split text into chunks of approximately maxChunkSize characters, breaking on whitespace boundaries.
     * This ensures each chunk is under the FTS index limit while maintaining word integrity.
     *
     * @param text the text to chunk
     * @param maxChunkSize the maximum size of each chunk in characters
     * @return list of text chunks, each under maxChunkSize
     */
    public static List<String> chunkText(String text, int maxChunkSize) {
        List<String> chunks = new ArrayList<>();

        if (text == null || text.isEmpty()) {
            return chunks;
        }

        if (text.length() <= maxChunkSize) {
            chunks.add(text);
            return chunks;
        }

        int startIndex = 0;
        while (startIndex < text.length()) {
            int endIndex = Math.min(startIndex + maxChunkSize, text.length());

            // If this is not the last chunk, try to break on whitespace
            if (endIndex < text.length()) {
                // Look backwards from endIndex to find the last whitespace
                int lastWhitespace = -1;
                for (int i = endIndex - 1; i > startIndex; i--) {
                    if (Character.isWhitespace(text.charAt(i))) {
                        lastWhitespace = i;
                        break;
                    }
                }

                // If we found whitespace within reasonable distance (at least 80% of chunk size),
                // break there to avoid splitting words
                if (lastWhitespace > startIndex + (maxChunkSize * 4 / 5)) {
                    endIndex = lastWhitespace + 1; // Include the whitespace in this chunk
                }
            }

            chunks.add(text.substring(startIndex, endIndex));
            startIndex = endIndex;
        }

        return chunks;
    }

    /**
     * Safely cleans up temporary files with retry logic and proper error handling.
     * Prevents accumulation of orphaned temp files that can cause disk space issues.
     *
     * @param file the temporary file to delete (can be null)
     */
    public static void cleanupTempFile(File file) {
        if (file == null || !file.exists()) {
            return;
        }

        // Try to delete immediately
        boolean deleted = file.delete();

        if (!deleted) {
            logger.warn("Failed to delete temp file immediately: {}", file.getAbsolutePath());

            // Mark for deletion on JVM exit as fallback
            file.deleteOnExit();

            // Try again asynchronously after a brief delay (file might be locked)
            Thread retryThread = new Thread(() -> {
                try {
                    Thread.sleep(100);
                    boolean retryDeleted = file.delete();
                    if (retryDeleted) {
                        logger.info("Successfully deleted temp file on async retry: {}", file.getName());
                    } else if (file.exists()) {
                        logger.error("CRITICAL: Unable to delete temp file even after async retry: {} (size: {} bytes)",
                                    file.getAbsolutePath(), file.length());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Async temp file cleanup retry was interrupted for: {}", file.getName());
                }
            }, "TempFileCleanup-" + file.getName());
            retryThread.setDaemon(true);
            retryThread.start();
        }
    }

    /**
     * Safely cleans up temporary files with retry logic and proper error handling.
     * Overload that accepts a Path for convenience.
     *
     * @param path the temporary file path to delete (can be null)
     */
    public static void cleanupTempFile(Path path) {
        if (path != null) {
            cleanupTempFile(path.toFile());
        }
    }
}
