package enterfind.server.model;

import java.time.LocalDateTime;

/**
 * One face found in one item by the FACE_DETECTION treatment.
 *
 * <p>Holds only model-independent geometry — bounding box, 5-point landmarks and the detector's
 * confidence. Embeddings and group membership are deliberately absent: they belong to the
 * FACE_RECOGNITION treatment and to its own tables, so that changing the embedding model never
 * invalidates a detection.
 *
 * <p>{@code faceIndex} is the stable {@code 0..N-1} position of this face within its item, assigned
 * by the handler in descending confidence order and unique per item. It — not {@code id} — is what
 * the {@code /detected-face/...} crop URL addresses, because detections are deleted and re-inserted
 * on reprocessing and ids therefore do not survive a rescan.
 *
 * <p>The owning item and treatment are absent by design. A detection is reached through the item
 * treatment that produced it, which is where the row is anchored in the database; carrying the item
 * id here as well would be a second copy of that link, free to disagree with it.
 */
public class FaceDetection {

    private String id;
    private int faceIndex;
    private float confidence;
    private float bboxX;
    private float bboxY;
    private float bboxW;
    private float bboxH;
    /** [5][2] landmark coordinates, normalised to the image, in the order fixed by
     *  {@code FaceDetectionResult}: left eye, right eye, nose, left mouth, right mouth. */
    private float[][] landmarks;
    private LocalDateTime dateCreated;

    public FaceDetection() {
    }

    public FaceDetection(String id, int faceIndex, float confidence,
                         float bboxX, float bboxY, float bboxW, float bboxH,
                         float[][] landmarks, LocalDateTime dateCreated) {
        this.id = id;
        this.faceIndex = faceIndex;
        this.confidence = confidence;
        this.bboxX = bboxX;
        this.bboxY = bboxY;
        this.bboxW = bboxW;
        this.bboxH = bboxH;
        this.landmarks = landmarks;
        this.dateCreated = dateCreated;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getFaceIndex() {
        return faceIndex;
    }

    public void setFaceIndex(int faceIndex) {
        this.faceIndex = faceIndex;
    }

    public float getConfidence() {
        return confidence;
    }

    public void setConfidence(float confidence) {
        this.confidence = confidence;
    }

    public float getBboxX() {
        return bboxX;
    }

    public void setBboxX(float bboxX) {
        this.bboxX = bboxX;
    }

    public float getBboxY() {
        return bboxY;
    }

    public void setBboxY(float bboxY) {
        this.bboxY = bboxY;
    }

    public float getBboxW() {
        return bboxW;
    }

    public void setBboxW(float bboxW) {
        this.bboxW = bboxW;
    }

    public float getBboxH() {
        return bboxH;
    }

    public void setBboxH(float bboxH) {
        this.bboxH = bboxH;
    }

    public float[][] getLandmarks() {
        return landmarks;
    }

    public void setLandmarks(float[][] landmarks) {
        this.landmarks = landmarks;
    }

    public LocalDateTime getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(LocalDateTime dateCreated) {
        this.dateCreated = dateCreated;
    }

    @Override
    public String toString() {
        return "FaceDetection{id='" + id + "', faceIndex=" + faceIndex +
                ", confidence=" + confidence + "}";
    }
}
