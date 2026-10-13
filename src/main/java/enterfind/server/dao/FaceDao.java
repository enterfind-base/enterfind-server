package enterfind.server.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import enterfind.server.model.FaceDetection;
import enterfind.server.model.FaceEmbedding;
import enterfind.server.model.ItemFace;
import enterfind.server.scan.treatment.FaceLandmarkUtils;
import enterfind.server.util.Utilities;

/**
 * Storage for the two per-face tables a treatment produces: FACE_DETECTIONS (geometry, owned by
 * FACE_DETECTION) and FACE_EMBEDDINGS (vectors and group assignment, owned by FACE_RECOGNITION).
 *
 * <p><b>Why these two share a DAO and FACE_GROUPS does not.</b> The line is lifecycle, not table
 * count. Both tables here are one item treatment's <em>output</em>: anchored on
 * {@code (item_treatment_id, face_index)}, delete-and-reinserted whole on every run, cascading away
 * with the treatment that produced them, and written only from
 * {@link TreatmentDao#saveItemTreatmentResult}. {@link FaceGroupDao} holds instance-global identity
 * with its own lifecycle — cleared on a model change, relabeled by rename and carryover, read by
 * every grouping decision. They also join: {@link #getFacesByItemId} spans both tables here plus
 * FACE_GROUPS, which is the query the item view captions its crops from.
 *
 * <p><b>Transactions.</b> Nothing here declares {@code @Transactional} and nothing here should.
 * These methods are called from inside {@code TreatmentDao.saveItemTreatmentResult}'s transaction,
 * and {@link DBAccess#update} and {@link DBAccess#query} go through the shared transaction-aware
 * {@code JdbcTemplate}, so they join the caller's transaction rather than opening their own —
 * geometry, embeddings, group upserts, and the {@code facecount} and {@code detectedface} results
 * that address them all become visible in one commit. Two ways to break that, both worth naming:
 * annotating a method here {@code REQUIRES_NEW}, which would commit face rows the enclosing
 * transaction might still roll back; and reaching for {@link DBAccess#executeAdmin}, which takes its
 * own pooled connection and would deadlock against the caller's uncommitted writes.
 *
 * <p>Living in a separate class from {@code TreatmentDao} costs none of that — Spring propagation is
 * per-thread, not per-bean, which {@code saveItemTreatmentResult} already relies on when it calls
 * {@code FaceGroupDao.upsertGroup} inside the same transaction.
 *
 * <p>The write methods stay package-private so the only path in remains
 * {@code saveItemTreatmentResult}. That is not ceremony: a second, unsynchronised write path is
 * exactly how the detection rows and {@code facecount} would come to disagree.
 */
@Repository
public class FaceDao {

    private final DBAccess dbAccess;

    public FaceDao(DBAccess dbAccess) {
        this.dbAccess = dbAccess;
    }

    // ==================== FACE_DETECTIONS ====================

    /**
     * Get one face detection by item and its index within that item. This is the lookup behind the
     * {@code /detected-face/...} crop endpoint.
     *
     * <p>Reached through ITEM_TREATMENTS because that is the only link the row carries. No filter on
     * treatment type is needed: FACE_DETECTION is the sole producer of these rows, so an item's
     * other treatments join to nothing here.
     */
    public Optional<FaceDetection> getFaceDetectionByItemAndIndex(String itemId, int faceIndex) {
        String sql = """
                SELECT fd.id, fd.face_index, fd.confidence,
                    fd.bbox_x, fd.bbox_y, fd.bbox_w, fd.bbox_h, fd.landmarks, fd.date_created
                FROM FACE_DETECTIONS fd
                JOIN ITEM_TREATMENTS it ON it.id = fd.item_treatment_id
                WHERE it.item_id = ? AND fd.face_index = ?
                """;

        try {
            return Optional.of(dbAccess.queryForObject(sql, new FaceDetectionRowMapper(), itemId, faceIndex));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Get all face detections for an item, in face-index order. Public for the FACE_RECOGNITION
     * handler, which re-aligns each face from its persisted landmarks; the crop endpoint addresses
     * one face at a time via {@link #getFaceDetectionByItemAndIndex}.
     */
    public List<FaceDetection> getFaceDetectionsByItemId(String itemId) {
        String sql = """
                SELECT fd.id, fd.face_index, fd.confidence,
                    fd.bbox_x, fd.bbox_y, fd.bbox_w, fd.bbox_h, fd.landmarks, fd.date_created
                FROM FACE_DETECTIONS fd
                JOIN ITEM_TREATMENTS it ON it.id = fd.item_treatment_id
                WHERE it.item_id = ?
                ORDER BY fd.face_index
                """;

        return dbAccess.query(sql, new FaceDetectionRowMapper(), itemId);
    }

    /**
     * Replace one item treatment's face detections with the given set, which may be empty.
     *
     * <p>Deliberately delete-then-insert rather than an upsert: face indices are positional, so a
     * run that finds fewer faces than the last one must not leave the tail behind.
     *
     * <p>Scoped by item treatment, not by item. That is what makes the call safe to issue for any
     * treatment: OCR saving results for an image owns no rows here, so its delete matches nothing,
     * while FACE_DETECTION's matches exactly the rows it wrote last time.
     */
    void replaceFaceDetections(String itemTreatmentId, List<FaceDetection> detections) {
        deleteFaceDetectionsByItemTreatmentId(itemTreatmentId);
        for (FaceDetection detection : detections) {
            createFaceDetection(itemTreatmentId, detection.getFaceIndex(), detection.getConfidence(),
                    detection.getBboxX(), detection.getBboxY(), detection.getBboxW(), detection.getBboxH(),
                    detection.getLandmarks());
        }
    }

    /**
     * Insert one detected face. Package-private: callers outside this package go through
     * {@code TreatmentDao.saveItemTreatmentResult}, so that the insert lands in the same transaction
     * as the facecount that makes it reachable.
     *
     * @param faceIndex stable 0-based position of this face within the item; the UNIQUE
     *                  (item_treatment_id, face_index) constraint turns a gap or duplicate into a
     *                  database error rather than a silently wrong face crop
     * @param landmarks [5][2] pixel coordinates, or null
     */
    FaceDetection createFaceDetection(String itemTreatmentId, int faceIndex, float confidence,
                                      float bboxX, float bboxY, float bboxW, float bboxH,
                                      float[][] landmarks) {
        String id = Utilities.generateHexId();
        LocalDateTime dateCreated = LocalDateTime.now();

        String sql = """
                INSERT INTO FACE_DETECTIONS (id, item_treatment_id, face_index, confidence,
                    bbox_x, bbox_y, bbox_w, bbox_h, landmarks, date_created)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        dbAccess.update(sql, id, itemTreatmentId, faceIndex, confidence,
                bboxX, bboxY, bboxW, bboxH, FaceLandmarkUtils.toBytes(landmarks),
                Timestamp.valueOf(dateCreated));

        return new FaceDetection(id, faceIndex, confidence,
                bboxX, bboxY, bboxW, bboxH, landmarks, dateCreated);
    }

    /**
     * Delete the face detections written by one item treatment. Reprocessing goes through
     * {@link #replaceFaceDetections}, which is what makes it idempotent.
     */
    int deleteFaceDetectionsByItemTreatmentId(String itemTreatmentId) {
        String sql = "DELETE FROM FACE_DETECTIONS WHERE item_treatment_id = ?";
        return dbAccess.update(sql, itemTreatmentId);
    }

    // ==================== FACE_EMBEDDINGS ====================
    //
    // Anchored on (item_treatment_id, face_index) of the *recognition* treatment, mirroring
    // detection — never on a detection-row id, which is re-minted every detection run.

    /**
     * Every detected face of an item with its identity label, in face-index order — one row per
     * FACE_DETECTIONS row, {@code label} null when the face has no name yet.
     *
     * <p>This is the pairing the {@code detectedface} treatment results cannot express. Those are a
     * key/value multiset with no face_index column, and {@code SearchDAO.getItemTreatmentResults}
     * returns them {@code ORDER BY key, value} — alphabetical by name, not face order — so they say
     * which names are in a photo but never which face carries which. The crop URL addresses a face
     * by index, so the label has to be joined to that same index server-side — here — rather than
     * zipped client-side against a differently-sorted list that also has holes (recognition skips a
     * face with no landmarks or a degenerate embedding).
     *
     * <p>Driven from FACE_DETECTIONS, not from the embeddings: a face detection is the thing the UI
     * renders, and it exists whether or not recognition ran. Detection-only items therefore come
     * back fully populated with null labels. The LEFT JOIN reaches embeddings through the
     * <em>recognition</em> item treatment on the same item, which is why it goes via a derived
     * table — the two treatments are different ITEM_TREATMENTS rows.
     */
    public List<ItemFace> getFacesByItemId(String itemId) {
        String sql = """
                SELECT fd.face_index, fg.label
                FROM FACE_DETECTIONS fd
                JOIN ITEM_TREATMENTS dit ON dit.id = fd.item_treatment_id
                LEFT JOIN (
                    SELECT rit.item_id, fe.face_index, fe.face_group_id
                    FROM FACE_EMBEDDINGS fe
                    JOIN ITEM_TREATMENTS rit ON rit.id = fe.item_treatment_id
                ) e ON e.item_id = dit.item_id AND e.face_index = fd.face_index
                LEFT JOIN FACE_GROUPS fg ON fg.id = e.face_group_id
                WHERE dit.item_id = ?
                ORDER BY fd.face_index
                """;

        return dbAccess.query(sql,
                (rs, rowNum) -> new ItemFace(rs.getInt("face_index"), rs.getString("label")),
                itemId);
    }

    /**
     * Replace one recognition item treatment's embeddings with the given set, which may be empty.
     * Delete-then-insert, like {@link #replaceFaceDetections}: every re-embed re-assigns from
     * scratch, so a run producing fewer faces than the last must not leave the tail behind.
     */
    void replaceFaceEmbeddings(String itemTreatmentId, List<FaceEmbedding> embeddings) {
        deleteFaceEmbeddingsByItemTreatmentId(itemTreatmentId);
        for (FaceEmbedding embedding : embeddings) {
            createFaceEmbedding(itemTreatmentId, embedding.getFaceIndex(), embedding.getFaceGroupId(),
                    embedding.getEmbedding(), embedding.getModelHash());
        }
    }

    /**
     * Insert one face embedding. Package-private: callers go through
     * {@code TreatmentDao.saveItemTreatmentResult} so the insert lands in the same transaction as
     * the group assignment and detectedface results.
     */
    FaceEmbedding createFaceEmbedding(String itemTreatmentId, int faceIndex, String faceGroupId,
            byte[] embedding, String modelHash) {
        String id = Utilities.generateHexId();
        LocalDateTime dateCreated = LocalDateTime.now();
        String sql = """
                INSERT INTO FACE_EMBEDDINGS (id, item_treatment_id, face_index, face_group_id,
                    embedding, model_hash, date_created)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        dbAccess.update(sql, id, itemTreatmentId, faceIndex, faceGroupId, embedding, modelHash,
                Timestamp.valueOf(dateCreated));
        return new FaceEmbedding(id, faceIndex, faceGroupId, embedding, modelHash, dateCreated);
    }

    int deleteFaceEmbeddingsByItemTreatmentId(String itemTreatmentId) {
        return dbAccess.update("DELETE FROM FACE_EMBEDDINGS WHERE item_treatment_id = ?", itemTreatmentId);
    }

    /**
     * Every recognition item treatment holding embeddings in the given model's space, in a stable
     * order. Drives the threshold recluster, which walks them one at a time so its memory stays
     * bounded by the largest single image rather than by the whole corpus of vectors.
     */
    public List<String> getItemTreatmentIdsWithEmbeddings(String modelHash) {
        String sql = """
                SELECT DISTINCT item_treatment_id
                FROM FACE_EMBEDDINGS
                WHERE model_hash = ?
                ORDER BY item_treatment_id
                """;
        return dbAccess.query(sql, (rs, rowNum) -> rs.getString("item_treatment_id"), modelHash);
    }

    /**
     * Point one face at a group, leaving its vector untouched.
     *
     * <p>Public where the other writes here are package-private, and for a reason worth stating: those
     * are barred so callers cannot bypass {@code TreatmentDao.saveItemTreatmentResult} and write
     * embeddings outside the save transaction. A recluster writes no embeddings — it only re-decides
     * which group each existing vector belongs to, inside its own transaction under the grouping lock
     * — so there is no save to bypass.
     */
    public int assignFaceGroup(String itemTreatmentId, int faceIndex, String faceGroupId) {
        return dbAccess.update("""
                UPDATE FACE_EMBEDDINGS
                SET face_group_id = ?
                WHERE item_treatment_id = ? AND face_index = ?
                """, faceGroupId, itemTreatmentId, faceIndex);
    }

    /**
     * Embeddings written by one recognition item treatment, in face-index order. Public unlike the
     * writes above, which stay package-private so callers cannot bypass the save transaction: a read
     * has nothing to bypass, and the vectors are what a grouping test inspects to check the
     * embedding space itself rather than only the labels the grouping produced from it.
     */
    public List<FaceEmbedding> getFaceEmbeddingsByItemTreatmentId(String itemTreatmentId) {
        String sql = """
                SELECT id, face_index, face_group_id, embedding, model_hash, date_created
                FROM FACE_EMBEDDINGS
                WHERE item_treatment_id = ?
                ORDER BY face_index
                """;
        return dbAccess.query(sql, new FaceEmbeddingRowMapper(), itemTreatmentId);
    }

    // ==================== Row mappers ====================

    private static class FaceDetectionRowMapper implements RowMapper<FaceDetection> {
        @Override
        public FaceDetection mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            FaceDetection detection = new FaceDetection();
            detection.setId(rs.getString("id"));
            detection.setFaceIndex(rs.getInt("face_index"));
            detection.setConfidence(rs.getFloat("confidence"));
            detection.setBboxX(rs.getFloat("bbox_x"));
            detection.setBboxY(rs.getFloat("bbox_y"));
            detection.setBboxW(rs.getFloat("bbox_w"));
            detection.setBboxH(rs.getFloat("bbox_h"));
            detection.setLandmarks(FaceLandmarkUtils.fromBytes(rs.getBytes("landmarks")));

            Timestamp dateCreated = rs.getTimestamp("date_created");
            if (dateCreated != null) {
                detection.setDateCreated(dateCreated.toLocalDateTime());
            }

            return detection;
        }
    }

    private static class FaceEmbeddingRowMapper implements RowMapper<FaceEmbedding> {
        @Override
        public FaceEmbedding mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            FaceEmbedding embedding = new FaceEmbedding();
            embedding.setId(rs.getString("id"));
            embedding.setFaceIndex(rs.getInt("face_index"));
            embedding.setFaceGroupId(rs.getString("face_group_id"));
            embedding.setEmbedding(rs.getBytes("embedding"));
            embedding.setModelHash(rs.getString("model_hash"));
            Timestamp dateCreated = rs.getTimestamp("date_created");
            if (dateCreated != null) {
                embedding.setDateCreated(dateCreated.toLocalDateTime());
            }
            return embedding;
        }
    }
}
