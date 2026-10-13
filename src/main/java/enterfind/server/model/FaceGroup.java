package enterfind.server.model;

import java.time.LocalDateTime;

/**
 * One instance-global identity cluster produced by the FACE_RECOGNITION treatment.
 *
 * <p>Its fields split cleanly into two lifetimes, and model changes depend on the split:
 *
 * <ul>
 *   <li><b>Durable</b> — {@code label}. A human-typed name ("Alice") or an auto-generated
 *       "Person N". This is the only thing carried across a model change, and only best-effort by
 *       membership overlap; see {@code FACE_LABEL_SNAPSHOT} and the label-carryover pass.</li>
 *   <li><b>Rebuildable</b> — {@code centroid} and {@code modelHash}. The centroid is a running-mean
 *       L2-normalised vector in one model's embedding space, tagged with that model's hash. On a
 *       model change every group is cleared and re-clustered from scratch, so these are never
 *       migrated.</li>
 * </ul>
 *
 * <p>{@code modelHash} is what keeps grouping from comparing a fresh embedding against a centroid
 * from a different embedding space during the drain window of a total regroup. It is a plain
 * content-derived string, deliberately not a foreign key: the only question it answers is "is this
 * centroid in the current model's space?", a string comparison against the current model hash.
 */
public class FaceGroup {

    private String id;
    private String label;
    private byte[] centroid;
    private String modelHash;
    private int faceCount;
    private LocalDateTime dateCreated;
    private LocalDateTime dateModified;

    public FaceGroup() {
    }

    public FaceGroup(String id, String label, byte[] centroid, String modelHash, int faceCount,
                     LocalDateTime dateCreated, LocalDateTime dateModified) {
        this.id = id;
        this.label = label;
        this.centroid = centroid;
        this.modelHash = modelHash;
        this.faceCount = faceCount;
        this.dateCreated = dateCreated;
        this.dateModified = dateModified;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public byte[] getCentroid() {
        return centroid;
    }

    public void setCentroid(byte[] centroid) {
        this.centroid = centroid;
    }

    public String getModelHash() {
        return modelHash;
    }

    public void setModelHash(String modelHash) {
        this.modelHash = modelHash;
    }

    public int getFaceCount() {
        return faceCount;
    }

    public void setFaceCount(int faceCount) {
        this.faceCount = faceCount;
    }

    public LocalDateTime getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(LocalDateTime dateCreated) {
        this.dateCreated = dateCreated;
    }

    public LocalDateTime getDateModified() {
        return dateModified;
    }

    public void setDateModified(LocalDateTime dateModified) {
        this.dateModified = dateModified;
    }

    @Override
    public String toString() {
        return "FaceGroup{id='" + id + "', label='" + label + "', faceCount=" + faceCount + "}";
    }
}
