package enterfind.server.scan.treatment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Serialization, similarity and centroid math for face embeddings.
 *
 * <p>The grouping is greedy nearest-centroid clustering, so the math here is cosine-to-centroid
 * similarity and a running-mean, L2-normalised centroid update.
 *
 * <p>No embedding dimension is hardcoded: any model is admitted (EdgeFace-S, 128-dim fixtures,
 * bring-your-own) and the dimension is read off the loaded model. Every method here works on whatever length it is handed and only
 * requires the two vectors it compares to agree.
 */
public final class FaceEmbeddingUtils {

    private FaceEmbeddingUtils() {
        // Prevent instantiation
    }

    /**
     * Serialize a {@code float[]} embedding to {@code byte[]} for BYTEA storage, big-endian.
     * A 512-dim embedding becomes 2048 bytes, a 128-dim one 512 bytes — the length is implied by
     * the model that produced it, not fixed here.
     */
    public static byte[] toBytes(float[] embedding) {
        if (embedding == null) {
            throw new IllegalArgumentException("Embedding must not be null");
        }
        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * Float.BYTES);
        buffer.order(ByteOrder.BIG_ENDIAN);
        for (float v : embedding) {
            buffer.putFloat(v);
        }
        return buffer.array();
    }

    /**
     * Deserialize {@code byte[]} from BYTEA storage back to {@code float[]}. The length is derived
     * from the byte count, so a stored embedding round-trips at whatever dimension it was written.
     */
    public static float[] fromBytes(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("Bytes must not be null");
        }
        if (bytes.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("Byte array length must be a multiple of " + Float.BYTES);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.BIG_ENDIAN);
        float[] embedding = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < embedding.length; i++) {
            embedding[i] = buffer.getFloat();
        }
        return embedding;
    }

    /**
     * Cosine similarity between two embeddings, in [-1, 1] where 1 means identical. For unit vectors
     * (which the translator produces) this is a dot product; the full form is kept so a centroid that
     * has drifted slightly off unit length still compares correctly.
     */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("Embeddings must not be null");
        }
        if (a.length != b.length) {
            throw new IllegalArgumentException("Embeddings must have the same length");
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dotProduct += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        if (denominator == 0.0) {
            return 0.0;
        }

        return dotProduct / denominator;
    }

    /**
     * Update a centroid with a new embedding as a running mean, then re-L2-normalise:
     * {@code c' = normalise((c * n + e) / (n + 1))}.
     */
    public static float[] updateCentroid(float[] centroid, float[] newEmbedding, int existingCount) {
        if (centroid == null || newEmbedding == null) {
            throw new IllegalArgumentException("Centroid and new embedding must not be null");
        }
        if (centroid.length != newEmbedding.length) {
            throw new IllegalArgumentException("Centroid and new embedding must have the same length");
        }

        float[] updated = new float[centroid.length];
        int newCount = existingCount + 1;

        for (int i = 0; i < centroid.length; i++) {
            updated[i] = (centroid[i] * existingCount + newEmbedding[i]) / newCount;
        }

        return l2Normalize(updated);
    }

    /**
     * Return an L2-normalised copy of the vector, or the vector's own values when its norm is zero.
     * Used by the translator (no NDArray {@code norm}/{@code div}, which would drag PyTorch back in)
     * and by {@link #updateCentroid}.
     */
    public static float[] l2Normalize(float[] vector) {
        double norm = 0.0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        float[] out = new float[vector.length];
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                out[i] = (float) (vector[i] / norm);
            }
        } else {
            System.arraycopy(vector, 0, out, 0, vector.length);
        }
        return out;
    }

    /**
     * True if an embedding is unusable — null, empty, or containing a NaN/Inf, or all-zeros (zero
     * norm). The handler's runtime backstop against persisting a degenerate vector from a model that
     * passed the set-time probe but chokes on some corpus image.
     */
    public static boolean isDegenerate(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            return true;
        }
        double sumSq = 0.0;
        for (float v : embedding) {
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                return true;
            }
            sumSq += (double) v * v;
        }
        return sumSq == 0.0;
    }
}
