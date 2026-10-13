package enterfind.server.model;

/**
 * One detected face of an item, paired with its identity label — the join the UI needs to caption a
 * face crop.
 *
 * <p>{@code faceIndex} is the face's stable 0-based position within its item, the same key the
 * {@code /detected-face} crop URL addresses, so a caption built from this record can never belong
 * to a different face than the thumbnail above it.
 *
 * <p>{@code label} is null when the face has no name: recognition is not enabled, has not drained
 * yet, skipped this face, or its group was cleared by a model change. A null label is not an error
 * — it is the normal state of a detection-only instance, and the UI renders such a face as a plain
 * crop with no caption and no rename affordance.
 */
public record ItemFace(int faceIndex, String label) {
}
