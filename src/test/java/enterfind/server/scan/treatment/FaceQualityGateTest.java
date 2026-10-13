package enterfind.server.scan.treatment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import enterfind.server.model.FaceDetection;
import enterfind.server.model.FaceGroup;
import enterfind.server.model.FaceGroupMutation;

/**
 * The confidence floor that decides which detected faces are worth embedding, tested at its exact
 * boundary against fabricated detections — no model, no Spring, no database.
 *
 * <p>The gate exists because a compact embedding model maps faces too degraded to carry identity
 * onto a single "null response" direction. Those vectors are mutually similar, so greedy
 * nearest-centroid grouping accretes them into one enormous bogus person — observed on a 40k-photo
 * corpus as a group of 943 faces drawn from 557 photos, i.e. containing hundreds of pairs of faces
 * that appear together in the same picture and therefore cannot be the same person.
 *
 * <p>{@link #degradedFacesFromDifferentPhotosDoNotAccreteIntoOneGroupOnceGated()} is the regression
 * proper: it drives the real {@link FaceGrouping} with the shape of embeddings that caused the
 * incident, in the cross-photograph form that {@code FaceGrouping}'s own one-group-per-photo rule
 * cannot address.
 */
class FaceQualityGateTest {

    private static FaceDetection detection(int faceIndex, float confidence) {
        return new FaceDetection(null, faceIndex, confidence, 0.1f, 0.1f, 0.05f, 0.05f, null, null);
    }

    @Test
    void keepsFacesAtOrAboveTheFloorAndDropsThoseBelow() {
        List<FaceDetection> detections = List.of(
                detection(0, 0.69f), detection(1, 0.70f), detection(2, 0.799f),
                detection(3, 0.80f), detection(4, 0.95f));

        List<FaceDetection> kept = FaceRecognitionTreatmentHandler.filterByConfidence(detections, 0.80f);

        // The floor is inclusive: 0.80 survives a floor of 0.80, 0.799 does not.
        assertThat(kept).extracting(FaceDetection::getFaceIndex).containsExactly(3, 4);
    }

    @Test
    void preservesFaceIndexSoCropsAndEmbeddingsStayPaired() {
        // Face index is the stable key crops are served under and embeddings are anchored on, so
        // gating must never renumber: a gated face leaves a hole, it does not shift its neighbours.
        List<FaceDetection> detections = List.of(
                detection(0, 0.95f), detection(1, 0.60f), detection(2, 0.90f));

        List<FaceDetection> kept = FaceRecognitionTreatmentHandler.filterByConfidence(detections, 0.80f);

        assertThat(kept).extracting(FaceDetection::getFaceIndex).containsExactly(0, 2);
    }

    @Test
    void aFloorOfZeroKeepsEverythingSoTheGateCanBeTurnedOff() {
        List<FaceDetection> detections = List.of(detection(0, 0.05f), detection(1, 0.99f));

        assertThat(FaceRecognitionTreatmentHandler.filterByConfidence(detections, 0.0f)).hasSize(2);
    }

    @Test
    void emptyInputYieldsEmptyOutputRatherThanThrowing() {
        assertThat(FaceRecognitionTreatmentHandler.filterByConfidence(List.of(), 0.80f)).isEmpty();
    }

    @Test
    void defaultFloorSitsAboveTheDetectionFloorSoDetectionStaysGenerous() {
        // Detect generously, identify conservatively. If these ever cross, every detected face is
        // embedded and the gate silently stops existing.
        assertThat(FaceRecognitionTreatmentHandler.DEFAULT_MIN_CONFIDENCE)
                .isGreaterThan(YuNetFaceDetectionTranslator.DEFAULT_CONFIDENCE_THRESHOLD);
    }

    /**
     * The incident, reproduced and then prevented — and specifically the half of it that the
     * one-group-per-photo rule cannot reach.
     *
     * <p>That rule stops degraded faces in a <em>single</em> frame from merging. It says nothing
     * about degraded faces arriving from <em>different</em> photographs, which is how the observed
     * group reached 943 faces across 557 photos. Only refusing to embed them addresses that, which is
     * why the gate and the constraint are complements rather than alternatives.
     */
    @Test
    void degradedFacesFromDifferentPhotosDoNotAccreteIntoOneGroupOnceGated() {
        // One low-confidence face per photograph, each embedding to almost the same direction — the
        // null response. Cosine between any pair is ~0.999, far above any usable threshold.
        List<FaceDetection> oneDegradedFacePerPhoto = List.of(
                detection(0, 0.71f), detection(0, 0.73f), detection(0, 0.75f),
                detection(0, 0.77f), detection(0, 0.79f));

        // Ungated, walking photo by photo exactly as the drain and the recluster both do.
        List<FaceGroup> groups = new ArrayList<>();
        List<String> landedIn = new ArrayList<>();
        for (int photo = 0; photo < oneDegradedFacePerPhoto.size(); photo++) {
            FaceGrouping.Result result = FaceGrouping.assign(groups,
                    List.of(new FaceGrouping.Candidate(0, nullResponse(photo))), 0.6, "model-hash");
            landedIn.add(result.assignments().get(0).groupId());
            for (FaceGroupMutation m : result.mutations()) {
                if (m.isNew()) {
                    groups.add(new FaceGroup(m.id(), m.label(), m.centroid(), m.modelHash(), 1,
                            LocalDateTime.now(), null));
                }
            }
        }

        assertThat(landedIn).as("degraded faces across photographs collapse into a single identity")
                .hasSize(5).containsOnly(landedIn.get(0));

        // Gated at the default floor, none of them is ever embedded, so none reaches grouping.
        List<FaceDetection> kept = FaceRecognitionTreatmentHandler.filterByConfidence(
                oneDegradedFacePerPhoto, FaceRecognitionTreatmentHandler.DEFAULT_MIN_CONFIDENCE);

        assertThat(kept).as("every degraded face is gated out before embedding").isEmpty();
    }

    /**
     * A vector barely distinguishable from its siblings: the same dominant direction with a tiny
     * per-face perturbation, which is what a compact model emits for an unidentifiable crop.
     */
    private static float[] nullResponse(int faceIndex) {
        float[] v = new float[512];
        v[0] = 1.0f;
        v[1 + faceIndex] = 0.02f;
        return v;
    }
}
