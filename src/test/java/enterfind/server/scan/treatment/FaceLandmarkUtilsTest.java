package enterfind.server.scan.treatment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The landmark BYTEA codec, unit-tested with no Spring context and no database — it was living in
 * the DAO, so these two assertions used to start Postgres to exercise a {@code ByteBuffer}.
 */
class FaceLandmarkUtilsTest {

    /** Landmarks in the fixed order: left eye, right eye, nose, left mouth, right mouth. */
    private static float[][] landmarks(float offset) {
        return new float[][] {
            {38.25f + offset, 51.5f},
            {73.5f + offset, 51.25f},
            {56.0f + offset, 71.75f},
            {41.5f + offset, 92.25f},
            {70.75f + offset, 92.0f},
        };
    }

    @Test
    void roundTripsExactly() {
        float[][] original = landmarks(3.5f);

        byte[] bytes = FaceLandmarkUtils.toBytes(original);

        assertThat(bytes).hasSize(FaceLandmarkUtils.LANDMARK_BYTES);
        // FACE_RECOGNITION re-aligns faces from these rather than re-detecting, so any loss here degrades
        // embeddings later with no signal.
        assertThat(FaceLandmarkUtils.fromBytes(bytes)).isDeepEqualTo(original);
    }

    @Test
    void nullAndMalformedInputYieldNull() {
        assertThat(FaceLandmarkUtils.toBytes(null)).isNull();
        assertThat(FaceLandmarkUtils.fromBytes(null)).isNull();
        // A short/legacy blob decodes to null rather than throwing: unreadable landmarks should
        // degrade to "no landmarks", not break reading the detection row around them.
        assertThat(FaceLandmarkUtils.fromBytes(new byte[]{1, 2, 3})).isNull();
    }

    /**
     * A wrong count is a programming error, not stored-data rot, so it throws where a bad blob
     * degrades. The DAO relies on this: {@code createFaceDetection} would otherwise silently write
     * a truncated landmark set.
     */
    @Test
    void wrongLandmarkCountThrows() {
        assertThatThrownBy(() -> FaceLandmarkUtils.toBytes(new float[][] {{1, 2}, {3, 4}}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 landmarks");

        assertThatThrownBy(() -> FaceLandmarkUtils.toBytes(new float[][] {
                {1, 2, 3}, {3, 4}, {5, 6}, {7, 8}, {9, 10}}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[x, y] pair");
    }
}
