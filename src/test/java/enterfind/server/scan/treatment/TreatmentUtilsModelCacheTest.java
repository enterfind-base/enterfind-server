package enterfind.server.scan.treatment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The model cache's revalidation path: a re-fetch of an unchanged model must cost a round trip, not
 * a transfer.
 *
 * <p><b>How the saving is observed.</b> "No bytes moved" is not directly visible from the return
 * value — both paths hand back a cache directory. So these tests point the on-disk URL index at a
 * <em>deliberately wrong</em> content hash. If the origin answers {@code 304} the function
 * short-circuits and returns that wrong hash; if it transfers, it hashes the real bytes and returns
 * the real hash. The two paths are then trivially distinguishable, with no timing and no flakiness.
 *
 * <p>That trick also states the trust boundary plainly: on a {@code 304} we take the origin's word
 * that the bytes are unchanged and never re-hash them. That is the same trust already extended to the
 * origin serving the model at all, and it is why the conditional is only ever sent with an ETag we
 * recorded ourselves at the moment we hashed those bytes.
 */
class TreatmentUtilsModelCacheTest {

    /** Not the hash of anything; it only has to be a well-formed, unused cache directory name. */
    private static final String FAKE_HASH =
            "0000000000000000000000000000000000000000000000000000000000000001";

    private static final String URL = FaceEmbeddingModelService.BUILTIN_URL;

    /**
     * Make the cache believe {@code URL} was last fetched into the FAKE_HASH directory with
     * {@code etag} — the origin record a real fetch would have written there.
     */
    private static void writeOrigin(String etag) throws Exception {
        Path dir = TreatmentUtils.MODEL_CACHE_DIR.resolve(FAKE_HASH);
        Files.createDirectories(dir);
        Files.write(dir.resolve("origin"), java.util.List.of(URL, etag));
    }

    /** Drop the real model's origin record so it cannot answer for URL during these tests. */
    private static void hideRealOrigin(Path realModelDir) throws Exception {
        Files.deleteIfExists(realModelDir.resolve("origin"));
    }

    @AfterEach
    void removeFakeCacheEntry() throws Exception {
        Path dir = TreatmentUtils.MODEL_CACHE_DIR.resolve(FAKE_HASH);
        if (Files.exists(dir)) {
            try (var paths = Files.walk(dir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (Exception ignored) {
                        // best effort; a leftover fake dir affects nothing
                    }
                });
            }
        }
    }

    @Test
    void anUnchangedModelIsRevalidatedRatherThanTransferred() throws Exception {
        TreatmentUtils.FetchedModel first = TreatmentUtils.fetchModelByContentHash(URL);
        assertThat(first.etag())
                .as("the origin must offer an ETag for revalidation to be possible at all")
                .isNotBlank();

        seedCacheUnderFakeHash(first.modelDir());
        hideRealOrigin(first.modelDir());
        writeOrigin(first.etag());

        TreatmentUtils.FetchedModel revalidated = TreatmentUtils.fetchModelByContentHash(URL);

        assertThat(revalidated.contentHash())
                .as("a 304 short-circuits: the bytes were never pulled, so they were never re-hashed")
                .isEqualTo(FAKE_HASH);
        assertThat(revalidated.modelDir()).isEqualTo(TreatmentUtils.MODEL_CACHE_DIR.resolve(FAKE_HASH));
    }

    @Test
    void aStaleEtagFallsBackToATransferAndRehashes() throws Exception {
        TreatmentUtils.FetchedModel first = TreatmentUtils.fetchModelByContentHash(URL);
        seedCacheUnderFakeHash(first.modelDir());
        hideRealOrigin(first.modelDir());
        writeOrigin("\"stale-etag-from-a-previous-file\"");

        TreatmentUtils.FetchedModel refetched = TreatmentUtils.fetchModelByContentHash(URL);

        assertThat(refetched.contentHash())
                .as("no 304, so the bytes were pulled and hashed — identity still comes from content")
                .isEqualTo(first.contentHash());
    }

    @Test
    void aMissingCacheEntryForcesATransferEvenWithAValidEtag() throws Exception {
        TreatmentUtils.FetchedModel first = TreatmentUtils.fetchModelByContentHash(URL);

        // The origin record names FAKE_HASH, but its model file is deleted below: a 304 could not be
        // honoured, because there would be nothing on disk to serve. Fresh box / cleared cache.
        hideRealOrigin(first.modelDir());
        writeOrigin(first.etag());
        Files.deleteIfExists(TreatmentUtils.MODEL_CACHE_DIR.resolve(FAKE_HASH)
                .resolve(TreatmentUtils.HASH_CACHED_MODEL_NAME + ".onnx"));

        TreatmentUtils.FetchedModel refetched = TreatmentUtils.fetchModelByContentHash(URL);

        assertThat(refetched.contentHash())
                .as("the conditional must not be sent when we could not serve the cached answer")
                .isEqualTo(first.contentHash());
        assertThat(refetched.modelDir()).isNotEqualTo(TreatmentUtils.MODEL_CACHE_DIR.resolve(FAKE_HASH));
    }

    @Test
    void everyRealCacheEntryIsNamedForItsContentHash() throws Exception {
        TreatmentUtils.FetchedModel fetched = TreatmentUtils.fetchModelByContentHash(URL);

        assertThat(TreatmentUtils.isContentHashName(fetched.modelDir().getFileName().toString()))
                .as("a model directory is named for its content hash, which is what makes anything "
                        + "else in the cache safe to delete")
                .isTrue();
        assertThat(TreatmentUtils.isContentHashName("yolox_tiny")).isFalse();
        assertThat(TreatmentUtils.isContentHashName("download-123.onnx")).isFalse();
        assertThat(TreatmentUtils.isContentHashName(FAKE_HASH)).isTrue();
    }

    /** Make the cache look as though FAKE_HASH is a model we already hold. */
    private static void seedCacheUnderFakeHash(Path realModelDir) throws Exception {
        Path fakeDir = TreatmentUtils.MODEL_CACHE_DIR.resolve(FAKE_HASH);
        Files.createDirectories(fakeDir);
        Files.copy(realModelDir.resolve(TreatmentUtils.HASH_CACHED_MODEL_NAME + ".onnx"),
                fakeDir.resolve(TreatmentUtils.HASH_CACHED_MODEL_NAME + ".onnx"),
                StandardCopyOption.REPLACE_EXISTING);
    }
}
