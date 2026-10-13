package enterfind.server.scan.treatment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Holds the built-in model itself to the separation bar, and pins the bytes it is supposed to be.
 *
 * <p><b>Why this exists as its own test.</b> The BUILTIN path deliberately runs <em>no</em> probe —
 * it hardcodes {@code BUILTIN_PREPROC} and {@code BUILTIN_OUTPUT_DIM} because EdgeFace-S's convention
 * is one we know. That means nothing in production ever checks those two constants, and nothing
 * notices if the URL starts serving different weights. Without this test, a changed or replaced
 * built-in surfaces as a mystifying grouping failure in an integration test three layers up — the
 * embedding space quietly degrades and the symptom is "two groups became three", with the model the
 * last place anyone would look.
 *
 * <p>So: fetch what the built-in URL actually serves, assert the bytes are the ones we verified, and
 * re-derive by measurement the two values the built-in path takes on faith. A failure here names the
 * cause outright.
 *
 * <p>{@code FaceModelProbeTest} covers the verdict logic against fabricated vectors and needs no
 * network. This one is the opposite: trivial assertions, real weights.
 */
class FaceBuiltinModelProbeTest {

    /**
     * SHA-256 of EdgeFace-S as verified on 2026-07-27. A mismatch means the URL is serving different
     * bytes — re-run the probe by hand, satisfy yourself the replacement is sound, then update this
     * pin. Do not "fix" it by pasting the new hash without looking.
     */
    private static final String EXPECTED_SHA256 =
            "b850767cf791bda585600b5c4c7d7432b2f998ccd862caae34ef1afa967d2e54";

    @Test
    void theBuiltinModelIsTheBytesWeVerifiedAndStillSeparatesOurFixtures() throws Exception {
        TreatmentUtils.FetchedModel fetched =
                TreatmentUtils.fetchModelByContentHash(FaceEmbeddingModelService.BUILTIN_URL);

        assertThat(fetched.contentHash())
                .as("the built-in URL is serving different weights than the ones we verified")
                .isEqualTo(EXPECTED_SHA256);

        FaceModelProbe.ProbeResult result = new FaceModelProbe().probe(fetched.modelDir());

        assertThat(result.passed())
                .as("built-in failed its own probe: %s", result.message())
                .isTrue();

        // The two constants the BUILTIN path hardcodes instead of probing. This is the only place
        // either is checked against the model itself.
        assertThat(result.preproc())
                .as("BUILTIN_PREPROC no longer matches what the model actually wants")
                .isEqualTo(FaceEmbeddingModelService.BUILTIN_PREPROC);
        assertThat(result.outputDim())
                .as("BUILTIN_OUTPUT_DIM no longer matches the model's real embedding size")
                .isEqualTo(FaceEmbeddingModelService.BUILTIN_OUTPUT_DIM);
        assertThat(FaceEmbeddingModelService.deriveThreshold(result.withinSimilarity()))
                .as("BUILTIN_THRESHOLD is no longer what this model's own measurements imply")
                .isEqualTo(FaceEmbeddingModelService.BUILTIN_THRESHOLD);

        // Margin, not just a pass: a built-in sitting just above the bar would group unreliably on
        // anything harder than our two fixtures, and that is worth knowing before it ships.
        assertThat(result.withinSimilarity()).isGreaterThan(FaceModelProbe.MIN_WITHIN_SIMILARITY);
        assertThat(result.crossSimilarity()).isLessThan(FaceModelProbe.MAX_CROSS_SIMILARITY);
    }
}
