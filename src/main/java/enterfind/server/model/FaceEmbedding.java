package enterfind.server.model;

import java.time.LocalDateTime;

/**
 * One face's embedding vector, produced by the FACE_RECOGNITION treatment.
 *
 * <p>A rebuildable cache row, owned by the recognition item treatment, and anchored on
 * {@code (item_treatment_id, face_index)} of that <em>recognition</em> treatment — never on
 * {@code FACE_DETECTIONS.id}, which is delete-and-reinserted with a fresh id on every detection run
 * and so would cascade every embedding away on an ordinary rescan. The reference to the detection
 * layer is logical: the same {@code face_index} the crop URL uses, resolved through the item, so a
 * re-detection cannot reach into identity and the handler reads a face's landmarks from the
 * detection row at the matching index.
 *
 * <p>{@code faceGroupId} is re-decided from scratch on every (re)embed — new item, content change or
 * model change alike. It is never carried forward: {@code face_index} is meaningful only within one
 * image's pixels, so stapling an old group id onto a changed image's face would attach one person's
 * identity to another's face.
 *
 * <p>{@code modelHash} tags the embedding space the vector lives in, so grouping only ever compares
 * within the current model during a regroup drain.
 */
public class FaceEmbedding {

    private String id;
    private int faceIndex;
    private String faceGroupId;
    /** Big-endian {@code float[]} serialized by {@code FaceEmbeddingUtils.toBytes}. */
    private byte[] embedding;
    private String modelHash;
    private LocalDateTime dateCreated;

    public FaceEmbedding() {
    }

    public FaceEmbedding(String id, int faceIndex, String faceGroupId, byte[] embedding,
                         String modelHash, LocalDateTime dateCreated) {
        this.id = id;
        this.faceIndex = faceIndex;
        this.faceGroupId = faceGroupId;
        this.embedding = embedding;
        this.modelHash = modelHash;
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

    public String getFaceGroupId() {
        return faceGroupId;
    }

    public void setFaceGroupId(String faceGroupId) {
        this.faceGroupId = faceGroupId;
    }

    public byte[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(byte[] embedding) {
        this.embedding = embedding;
    }

    public String getModelHash() {
        return modelHash;
    }

    public void setModelHash(String modelHash) {
        this.modelHash = modelHash;
    }

    public LocalDateTime getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(LocalDateTime dateCreated) {
        this.dateCreated = dateCreated;
    }

    @Override
    public String toString() {
        return "FaceEmbedding{id='" + id + "', faceIndex=" + faceIndex
                + ", faceGroupId='" + faceGroupId + "'}";
    }
}
