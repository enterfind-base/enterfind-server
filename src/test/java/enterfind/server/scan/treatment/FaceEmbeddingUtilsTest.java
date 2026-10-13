package enterfind.server.scan.treatment;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the embedding math — no database, no model. Covers the serialization,
 * similarity and centroid operations grouping relies on, plus the degeneracy backstop and the
 * "dimension is read, not hardcoded" property that lets 128- and 512-dim models coexist.
 */
class FaceEmbeddingUtilsTest {

    @Test
    void bytesRoundTripAtAnyDimension() {
        float[] dim512 = new float[512];
        for (int i = 0; i < dim512.length; i++) {
            dim512[i] = (i % 7) * 0.013f - 0.4f;
        }
        assertArrayEquals(dim512, FaceEmbeddingUtils.fromBytes(FaceEmbeddingUtils.toBytes(dim512)), 0f);

        float[] dim128 = {0.5f, -0.25f, 1.0f, -1.0f, 0f};
        assertArrayEquals(dim128, FaceEmbeddingUtils.fromBytes(FaceEmbeddingUtils.toBytes(dim128)), 0f);
        // 5 floats => 20 bytes; the length is implied by the content, not fixed at 512.
        assertEquals(20, FaceEmbeddingUtils.toBytes(dim128).length);
    }

    @Test
    void cosineSimilarityIdenticalIsOneOrthogonalIsZero() {
        float[] a = {1f, 0f, 0f};
        float[] b = {0f, 1f, 0f};
        assertEquals(1.0, FaceEmbeddingUtils.cosineSimilarity(a, a), 1e-6);
        assertEquals(0.0, FaceEmbeddingUtils.cosineSimilarity(a, b), 1e-6);
        assertEquals(-1.0, FaceEmbeddingUtils.cosineSimilarity(a, new float[]{-1f, 0f, 0f}), 1e-6);
    }

    @Test
    void l2NormalizeProducesUnitVectorAndToleratesZero() {
        float[] normalized = FaceEmbeddingUtils.l2Normalize(new float[]{3f, 4f});
        assertEquals(0.6f, normalized[0], 1e-6);
        assertEquals(0.8f, normalized[1], 1e-6);

        // Zero vector stays zero rather than producing NaN.
        assertArrayEquals(new float[]{0f, 0f}, FaceEmbeddingUtils.l2Normalize(new float[]{0f, 0f}), 0f);
    }

    @Test
    void updateCentroidIsRunningMeanReNormalized() {
        // Two opposite-ish unit vectors: the running mean of the first with a second, re-normalized.
        float[] centroid = {1f, 0f};
        float[] updated = FaceEmbeddingUtils.updateCentroid(centroid, new float[]{0f, 1f}, 1);
        // (1,0)*1 + (0,1) = (1,1), normalized => (0.707, 0.707)
        assertEquals(0.70710677f, updated[0], 1e-5);
        assertEquals(0.70710677f, updated[1], 1e-5);
        assertEquals(1.0, Math.hypot(updated[0], updated[1]), 1e-5);
    }

    @Test
    void isDegenerateCatchesNanInfZeroAndEmpty() {
        assertTrue(FaceEmbeddingUtils.isDegenerate(null));
        assertTrue(FaceEmbeddingUtils.isDegenerate(new float[0]));
        assertTrue(FaceEmbeddingUtils.isDegenerate(new float[]{0f, 0f, 0f}));
        assertTrue(FaceEmbeddingUtils.isDegenerate(new float[]{Float.NaN, 1f}));
        assertTrue(FaceEmbeddingUtils.isDegenerate(new float[]{Float.POSITIVE_INFINITY, 1f}));
        assertFalse(FaceEmbeddingUtils.isDegenerate(new float[]{0.001f, 0f}));
    }
}
