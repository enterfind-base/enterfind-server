package enterfind.server.scan.treatment;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Serialization for the 5-point facial landmarks a detection carries.
 *
 * <p>Sits beside {@link FaceEmbeddingUtils} on purpose: that class packs a {@code float[]} embedding
 * into the BYTEA column that stores it, and this packs a {@code float[5][2]} landmark set into
 * FACE_DETECTIONS' BYTEA column the same big-endian way. Keeping one in a utility class and the
 * other inside the DAO made two identical concerns look like different ones.
 *
 * <p>The shape is fixed where the embedding's is not. An embedding round-trips at whatever dimension
 * the model produced; landmarks are always the five points {@link FaceAlignmentUtil} maps to the
 * ArcFace template — left eye, right eye, nose, left mouth corner, right mouth corner — so a wrong
 * count is a programming error and throws, while an unreadable stored blob degrades to "no
 * landmarks" rather than breaking the read of the detection row around it.
 */
public final class FaceLandmarkUtils {

    /** Left eye, right eye, nose, left mouth corner, right mouth corner. */
    public static final int NUM_LANDMARKS = 5;

    /** Five (x, y) float pairs. */
    public static final int LANDMARK_BYTES = NUM_LANDMARKS * 2 * Float.BYTES;

    private FaceLandmarkUtils() {
        // Prevent instantiation
    }

    /** Pack [5][2] landmarks into 40 bytes for BYTEA storage. Null-tolerant. */
    public static byte[] toBytes(float[][] landmarks) {
        if (landmarks == null) {
            return null;
        }
        if (landmarks.length != NUM_LANDMARKS) {
            throw new IllegalArgumentException("Expected " + NUM_LANDMARKS + " landmarks, got " + landmarks.length);
        }
        ByteBuffer buffer = ByteBuffer.allocate(LANDMARK_BYTES).order(ByteOrder.BIG_ENDIAN);
        for (float[] point : landmarks) {
            if (point.length != 2) {
                throw new IllegalArgumentException("Each landmark must be a [x, y] pair");
            }
            buffer.putFloat(point[0]);
            buffer.putFloat(point[1]);
        }
        return buffer.array();
    }

    /**
     * Unpack landmarks written by {@link #toBytes}. Returns null for null or wrong-length input:
     * a detection whose landmarks cannot be read is still a usable detection — it just cannot be
     * re-aligned — so this degrades rather than throwing.
     */
    public static float[][] fromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != LANDMARK_BYTES) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        float[][] landmarks = new float[NUM_LANDMARKS][2];
        for (int i = 0; i < NUM_LANDMARKS; i++) {
            landmarks[i][0] = buffer.getFloat();
            landmarks[i][1] = buffer.getFloat();
        }
        return landmarks;
    }
}
