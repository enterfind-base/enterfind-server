package enterfind.server.scan.treatment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import enterfind.server.scan.treatment.FaceModelProbe.ProbeResult;

/**
 * The probe's verdict, unit-tested against fabricated vectors — no ONNX, no network, no model.
 *
 * <p>These replace the synthetic ONNX fixtures the plan once called for. Those existed only to reach
 * these branches through the model-loading path, and the branch that matters most — a model that
 * <em>loads perfectly and produces an incoherent space</em> — is precisely the one no genuine model
 * exhibits, so it had to be manufactured. Fabricating the vectors instead reaches every branch at
 * exact boundary values, and leaves the model estate entirely real (EdgeFace-S, buffalo_l, SFace).
 *
 * <p>Vectors are built on an orthogonal basis so the cosine values are the arithmetic the assertions
 * name, not an accident of random numbers.
 */
class FaceModelProbeTest {

    /** A unit vector at {@code angle} from the x-axis in the x/y plane; cos between two is exact. */
    private static float[] vector(double angleRadians, int dim) {
        float[] v = new float[dim];
        v[0] = (float) Math.cos(angleRadians);
        v[1] = (float) Math.sin(angleRadians);
        return v;
    }

    /**
     * One preprocessing cell. Person A's two vectors sit {@code withinAngle} apart; person B's pair
     * is rotated away by {@code crossAngle}, so within-similarity is cos(withinAngle) and
     * cross-similarity is bounded by cos(crossAngle - withinAngle).
     */
    private static Map<String, List<float[]>> cell(double withinAngle, double crossAngle, int dim) {
        Map<String, List<float[]>> people = new LinkedHashMap<>();
        people.put("A", List.of(vector(0, dim), vector(withinAngle, dim)));
        people.put("B", List.of(vector(crossAngle, dim), vector(crossAngle + withinAngle, dim)));
        return people;
    }

    private static Map<FaceEmbeddingPreproc, Map<String, List<float[]>>> oneCell(
            FaceEmbeddingPreproc preproc, Map<String, List<float[]>> people) {
        Map<FaceEmbeddingPreproc, Map<String, List<float[]>>> byCell = new LinkedHashMap<>();
        byCell.put(preproc, people);
        return byCell;
    }

    @Test
    void aCoherentSpacePasses() {
        // Same person nearly identical (cos ~0.995), different people near-orthogonal (cos ~0.1).
        ProbeResult result = FaceModelProbe.evaluate(
                oneCell(FaceEmbeddingPreproc.RGB_CENTERED, cell(0.1, Math.PI / 2 - 0.05, 512)));

        assertThat(result.passed()).isTrue();
        assertThat(result.withinSimilarity()).isGreaterThan(FaceModelProbe.MIN_WITHIN_SIMILARITY);
        assertThat(result.crossSimilarity()).isLessThan(FaceModelProbe.MAX_CROSS_SIMILARITY);
        assertThat(result.preproc()).isEqualTo(FaceEmbeddingPreproc.RGB_CENTERED);
    }

    /**
     * The branch no real model provides: it loads, it returns well-formed finite vectors, and they
     * carry no identity — every face lands in roughly the same place. This is what a mis-exported
     * checkpoint or a non-face feature extractor looks like, and the probe exists to catch it.
     */
    @Test
    void aSpaceWhereEveryoneLooksAlikeFailsOnCrossSimilarity() {
        // All four vectors within 0.05 rad: within is high, but so is cross.
        ProbeResult result = FaceModelProbe.evaluate(
                oneCell(FaceEmbeddingPreproc.RGB_CENTERED, cell(0.02, 0.03, 512)));

        assertThat(result.passed()).isFalse();
        assertThat(result.crossSimilarity()).isGreaterThan(FaceModelProbe.MAX_CROSS_SIMILARITY);
        assertThat(result.message())
                .as("the operator gets the numbers, not just a rejection")
                .contains("cross-person similarity");
    }

    @Test
    void aSpaceThatSplitsOnePersonFailsOnWithinSimilarity() {
        // Person A's own two faces land near-orthogonal: nothing would ever group them.
        Map<String, List<float[]>> people = new LinkedHashMap<>();
        people.put("A", List.of(vector(0, 512), vector(Math.PI / 2, 512)));
        people.put("B", List.of(vector(Math.PI, 512), vector(Math.PI, 512)));

        ProbeResult result = FaceModelProbe.evaluate(
                oneCell(FaceEmbeddingPreproc.RGB_CENTERED, people));

        assertThat(result.passed()).isFalse();
        assertThat(result.withinSimilarity()).isLessThan(FaceModelProbe.MIN_WITHIN_SIMILARITY);
        assertThat(result.message()).contains("same-person similarity");
    }

    @Test
    void allCellsDegenerateIsReportedAsSuchRatherThanAsPoorSeparation() {
        Map<FaceEmbeddingPreproc, Map<String, List<float[]>>> byCell = new LinkedHashMap<>();
        for (FaceEmbeddingPreproc preproc : FaceEmbeddingPreproc.values()) {
            Map<String, List<float[]>> people = new LinkedHashMap<>();
            people.put("A", List.of(new float[512], new float[512]));   // all zeros
            people.put("B", List.of(new float[512], new float[512]));
            byCell.put(preproc, people);
        }

        ProbeResult result = FaceModelProbe.evaluate(byCell);

        assertThat(result.passed()).isFalse();
        assertThat(result.preproc()).isNull();
        assertThat(result.outputDim()).isZero();
        assertThat(result.message()).contains("degenerate");
    }

    @Test
    void aDegenerateCellIsSkippedWhenAnotherCellWorks() {
        Map<FaceEmbeddingPreproc, Map<String, List<float[]>>> byCell = new LinkedHashMap<>();
        Map<String, List<float[]>> broken = new LinkedHashMap<>();
        broken.put("A", List.of(new float[512], new float[512]));
        broken.put("B", List.of(new float[512], new float[512]));
        byCell.put(FaceEmbeddingPreproc.BGR_RAW, broken);
        byCell.put(FaceEmbeddingPreproc.RGB_CENTERED, cell(0.1, Math.PI / 2 - 0.05, 512));

        ProbeResult result = FaceModelProbe.evaluate(byCell);

        assertThat(result.passed()).isTrue();
        assertThat(result.preproc())
                .as("a model whose wrong channel order yields garbage is still usable in its right one")
                .isEqualTo(FaceEmbeddingPreproc.RGB_CENTERED);
    }

    @Test
    void theBestSeparatingCellWinsEvenWhenSeveralClearTheBar() {
        Map<FaceEmbeddingPreproc, Map<String, List<float[]>>> byCell = new LinkedHashMap<>();
        byCell.put(FaceEmbeddingPreproc.RGB_RAW, cell(0.6, Math.PI / 2 - 0.05, 512));     // passes, weaker
        byCell.put(FaceEmbeddingPreproc.BGR_CENTERED, cell(0.05, Math.PI / 2 - 0.05, 512)); // passes, tighter

        ProbeResult result = FaceModelProbe.evaluate(byCell);

        assertThat(result.passed()).isTrue();
        assertThat(result.preproc()).isEqualTo(FaceEmbeddingPreproc.BGR_CENTERED);
    }

    /**
     * The reason a 128-dim model is admissible at all: the dimension is read off what the model
     * returned. This is what the synthetic 128-dim fixture was meant to prove and never could — the
     * picker rejects anything that fails separation, so a synthetic model could be measured but
     * never stored. SFace covers it end to end; this covers the reading.
     */
    @Test
    void outputDimensionIsReadFromTheVectorsNotAssumed() {
        assertThat(FaceModelProbe.evaluate(
                oneCell(FaceEmbeddingPreproc.RGB_CENTERED, cell(0.1, Math.PI / 2 - 0.05, 128)))
                .outputDim()).isEqualTo(128);

        assertThat(FaceModelProbe.evaluate(
                oneCell(FaceEmbeddingPreproc.RGB_CENTERED, cell(0.1, Math.PI / 2 - 0.05, 512)))
                .outputDim()).isEqualTo(512);
    }

    /**
     * A person contributing one crop has no same-person pair, so within-similarity would be vacuous.
     * Guarded rather than silently scored, since a manifest edit is the likely cause.
     */
    @Test
    void aCellWithoutTwoCropsPerPersonIsNotScored() {
        Map<String, List<float[]>> people = new LinkedHashMap<>();
        people.put("A", List.of(vector(0, 512)));
        people.put("B", List.of(vector(Math.PI / 2, 512)));

        ProbeResult result = FaceModelProbe.evaluate(
                oneCell(FaceEmbeddingPreproc.RGB_CENTERED, people));

        assertThat(result.passed()).isFalse();
    }
}
