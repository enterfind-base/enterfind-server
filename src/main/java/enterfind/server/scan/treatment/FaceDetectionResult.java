package enterfind.server.scan.treatment;

/**
 * Result from SCRFD face detection: bounding box, confidence, and 5 facial landmarks.
 * Coordinates are in original image pixel space.
 * Landmarks order: left eye, right eye, nose, left mouth corner, right mouth corner.
 */
public record FaceDetectionResult(
        float x,
        float y,
        float width,
        float height,
        float confidence,
        float[][] landmarks  // [5][2] - each landmark is [x, y]
) {}
