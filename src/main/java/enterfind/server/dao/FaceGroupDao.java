package enterfind.server.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import enterfind.server.exception.NotFoundException;
import enterfind.server.model.FaceGroup;
import enterfind.server.model.FaceGroupMutation;
import enterfind.server.model.FieldNameConstants;
import enterfind.server.util.Utilities;

/**
 * DAO for FACE_GROUPS — the instance-global identity store.
 *
 * <p>FACE_GROUPS is global state, not one item treatment's output, which is why it gets its own DAO
 * rather than living in {@link TreatmentDao} beside the per-item detection and embedding rows.
 *
 * <p>Persistence only: the grouping <em>decision</em> (cosine, threshold, centroid math, labels)
 * lives in the handler ({@code FaceGrouping}), which owns the domain logic and serializes concurrent
 * runs with a {@code NamedLockRegistry} lock. This DAO just reads current-model groups and applies
 * the upserts the handler decided ({@link #upsertGroup}), so it carries no grouping business logic
 * and no lock.
 */
@Repository
public class FaceGroupDao {

    private static final Logger logger = LoggerFactory.getLogger(FaceGroupDao.class);

    private final DBAccess dbAccess;

    public FaceGroupDao(DBAccess dbAccess) {
        this.dbAccess = dbAccess;
    }

    /**
     * Apply one group upsert the handler decided: insert a new group, or update an existing group's
     * centroid, face count and model hash. A dumb applier — the {@code isNew} flag and the final
     * state are the handler's decision, made under the grouping lock.
     */
    public void upsertGroup(FaceGroupMutation mutation) {
        if (mutation.isNew()) {
            String sql = """
                    INSERT INTO FACE_GROUPS (id, label, centroid, model_hash, date_created)
                    VALUES (?, ?, ?, ?, ?)
                    """;
            dbAccess.update(sql, mutation.id(), mutation.label(), mutation.centroid(),
                    mutation.modelHash(), Timestamp.valueOf(LocalDateTime.now()));
        } else {
            updateFaceGroupCentroid(mutation.id(), mutation.centroid(), mutation.modelHash());
        }
    }

    /**
     * Every read of a group counts its members rather than trusting {@code FACE_GROUPS.face_count}.
     *
     * <p><b>The count is derived, not stored.</b> A stored counter has no single owner here: it is
     * incremented by grouping, but membership also disappears through pure database cascades
     * (DATASOURCE_TREATMENTS → ITEM_TREATMENTS → FACE_EMBEDDINGS) that run no Java at all, so no
     * decrement can be hung off a delete. Left stored it only ever grew, and because it is the
     * weight {@code n} in the running-mean centroid update, an inflated count quietly froze a
     * cluster around its earliest members. Counting costs one aggregate over the
     * {@code idx_face_embeddings_group} index on a path that already loads every centroid and then
     * runs inference.
     *
     * <p>The {@code face_count} column is dropped rather than kept as an unread second answer.
     */
    private static final String SELECT_GROUP = """
            SELECT g.id, g.label, g.centroid, g.model_hash, g.date_created, g.date_modified,
                   (SELECT count(*) FROM FACE_EMBEDDINGS e WHERE e.face_group_id = g.id) AS face_count
            FROM FACE_GROUPS g
            """;

    /** Groups whose centroid lives in the given model's embedding space. */
    public List<FaceGroup> getGroupsByModelHash(String modelHash) {
        return dbAccess.query(SELECT_GROUP + "WHERE g.model_hash = ? ORDER BY g.date_created",
                new FaceGroupRowMapper(), modelHash);
    }

    public List<FaceGroup> getAllFaceGroups() {
        return dbAccess.query(SELECT_GROUP + "ORDER BY g.label", new FaceGroupRowMapper());
    }

    public Optional<FaceGroup> getFaceGroupById(String id) {
        try {
            return Optional.of(dbAccess.queryForObject(SELECT_GROUP + "WHERE g.id = ?",
                    new FaceGroupRowMapper(), id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<FaceGroup> getFaceGroupByLabel(String label) {
        try {
            return Optional.of(dbAccess.queryForObject(
                    SELECT_GROUP + "WHERE g.label = ? ORDER BY g.date_created LIMIT 1",
                    new FaceGroupRowMapper(), label));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** Insert a group with a caller-chosen id/label. Test and internal seam. */
    public FaceGroup createFaceGroup(String label, byte[] centroid, String modelHash) {
        String id = Utilities.generateHexId();
        LocalDateTime dateCreated = LocalDateTime.now();
        String sql = """
                INSERT INTO FACE_GROUPS (id, label, centroid, model_hash, date_created)
                VALUES (?, ?, ?, ?, ?)
                """;
        dbAccess.update(sql, id, label, centroid, modelHash, Timestamp.valueOf(dateCreated));
        // Face count is whatever the embeddings say; a group with no members yet reports 0.
        return new FaceGroup(id, label, centroid, modelHash, 0, dateCreated, null);
    }

    public void updateFaceGroupCentroid(String id, byte[] centroid, String modelHash) {
        String sql = """
                UPDATE FACE_GROUPS
                SET centroid = ?, model_hash = ?, date_modified = ?
                WHERE id = ?
                """;
        dbAccess.update(sql, centroid, modelHash, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    /**
     * Set a group's durable label by id, carrying the change onto its {@code detectedface} results.
     * Used by the post-drain label-carryover propagation pass.
     *
     * <p>The results rewrite is not optional bookkeeping: those rows are what the search facet and
     * the item view read, so a group relabeled without them is a carryover nobody can see — the
     * cluster says "Alice" while every photo still says "Person 12", and a search for Alice finds
     * nothing. Matching on the old value is the only way to reach them, since
     * ITEM_TREATMENT_RESULTS has no face index; that is exact here because the label being replaced
     * is the group's own, and an auto "Person N" is generated unique.
     */
    public void setLabel(String id, String label) {
        String currentLabel = getFaceGroupById(id).map(FaceGroup::getLabel).orElse(null);
        if (label.equals(currentLabel)) {
            return;
        }

        String sql = """
                UPDATE FACE_GROUPS
                SET label = ?, date_modified = ?
                WHERE id = ?
                """;
        dbAccess.update(sql, label, Timestamp.valueOf(LocalDateTime.now()), id);

        if (currentLabel != null) {
            renameDetectedFaceResults(currentLabel, label);
        }
    }

    /**
     * Rewrite the denormalized copy of a group label held in the {@code detectedface} treatment
     * results. Every writer of {@code FACE_GROUPS.label} owes this call — the two are kept in step
     * only by convention, and both {@link #renameFaceLabel} and the carryover pass go through here
     * so there is one place that knows it.
     */
    private int renameDetectedFaceResults(String oldLabel, String newLabel) {
        return dbAccess.update("""
                UPDATE ITEM_TREATMENT_RESULTS
                SET value = ?
                WHERE key = 'detectedface' AND value = ?
                """, newLabel, oldLabel);
    }

    /**
     * Delete every {@code detectedface} result row — the denormalized copy of the group labels.
     *
     * <p>Ordinarily these are replaced per item as recognition re-drains, so a model change only
     * shows stale names for the length of the drain. Switching the model off has no drain: readiness
     * is false, so the recognition rows marked {@code UPDATED} are never claimed and nothing would
     * ever overwrite them. Without this the item view and the search facet keep offering names whose
     * groups have been deleted, beside crops that now render with no caption.
     */
    public int clearDetectedFaceResults() {
        return dbAccess.update("DELETE FROM ITEM_TREATMENT_RESULTS WHERE key = 'detectedface'");
    }

    /**
     * Replace one item treatment's {@code detectedface} results with the given labels.
     *
     * <p>The narrow counterpart to {@link #clearDetectedFaceResults}, for the recluster: it rewrites
     * assignments without re-running the treatment, so the copy of the labels has to be brought along
     * by hand. Ordinary processing gets this for free — the save deletes an {@code UPDATED} row's
     * results wholesale and re-inserts what the handler produced.
     */
    public void replaceDetectedFaceResults(String itemTreatmentId, List<String> labels) {
        dbAccess.update("""
                DELETE FROM ITEM_TREATMENT_RESULTS
                WHERE item_treatment_id = ? AND key = ?
                """, itemTreatmentId, FieldNameConstants.FIELD_DETECTED_FACE);

        Timestamp dateCreated = Timestamp.valueOf(LocalDateTime.now());
        for (String label : labels) {
            dbAccess.update("""
                    INSERT INTO ITEM_TREATMENT_RESULTS (id, item_treatment_id, key, value, date_created)
                    VALUES (?, ?, ?, ?, ?)
                    """, Utilities.generateHexId(), itemTreatmentId,
                    FieldNameConstants.FIELD_DETECTED_FACE, label, dateCreated);
        }
    }

    /**
     * Clear every group. Used at the start of a total regroup (a model change): the centroids belong
     * to the old embedding space and cannot be reused, so the store is emptied and rebuilt from
     * scratch as recognition rows re-drain. FACE_EMBEDDINGS carries no foreign key into FACE_GROUPS,
     * so this leaves the (about-to-be-replaced) embedding rows in place rather than cascading them.
     */
    public int clearAllFaceGroups() {
        return dbAccess.update("DELETE FROM FACE_GROUPS");
    }

    public boolean deleteFaceGroup(String id) {
        return dbAccess.update("DELETE FROM FACE_GROUPS WHERE id = ?", id) > 0;
    }

    /**
     * Rename a human label: rewrites it on <b>every</b> group carrying it and on every
     * {@code detectedface} result that does.
     *
     * <p><b>A label may name several groups, and renaming onto an existing label is how they are
     * merged.</b> One person occupies several clusters whenever their appearance drifts far enough
     * between photographs — most obviously across an age gap, where a child and an adult are
     * genuinely far apart in embedding space. A single centroid cannot represent that span, so the
     * clusters are kept separate and *the label* is what unifies them. Nothing downstream objects:
     * {@code detectedface} results store the label string, so several groups emitting "Dina" already
     * yield one search facet with the combined count and one caption in the item view.
     *
     * <p>Renaming a duplicated label moves all of its groups at once, which is right — "Dina is
     * actually Ella" should carry every one of her clusters.
     *
     * <p><b>Merging is not reversible through this API</b>, because no group id is exposed anywhere
     * in the schema and this operates on label strings. The undo is a recluster
     * ({@code setFaceGroupingThreshold} at the current value): the label carryover in
     * {@link #computeLabelAssignments} gives a label to only its largest claimant, so duplicates
     * collapse and the other clusters return as fresh "Person N" to be re-curated. That undo is
     * global — it resets every merge, not the one you regret.
     *
     * @throws NotFoundException if no group carries {@code oldLabel}
     */
    @Transactional
    public void renameFaceLabel(String oldLabel, String newLabel) {
        if (getFaceGroupByLabel(oldLabel).isEmpty()) {
            throw new NotFoundException("Face label not found: " + oldLabel);
        }

        dbAccess.update("""
                UPDATE FACE_GROUPS
                SET label = ?, date_modified = ?
                WHERE label = ?
                """, newLabel, Timestamp.valueOf(LocalDateTime.now()), oldLabel);

        int updatedResults = renameDetectedFaceResults(oldLabel, newLabel);

        logger.info("Renamed face label from '{}' to '{}', updated {} treatment result(s)",
                oldLabel, newLabel, updatedResults);
    }

    // ==================== Model change & label carryover ====================

    /**
     * Mark every FACE_RECOGNITION item treatment {@code UPDATED} (except those mid-flight) so the
     * processor re-drives them with no rescan. The heart of a total regroup: after the groups are
     * cleared, this re-drains every face into the new embedding space.
     */
    public int markAllRecognitionUpdated() {
        String sql = """
                UPDATE ITEM_TREATMENTS it
                SET status = 'UPDATED', date_modified = ?, version = it.version + 1
                FROM DATASOURCE_TREATMENTS dt
                WHERE it.datasource_treatment_id = dt.id
                  AND dt.treatment = 'FACE_RECOGNITION'
                  AND it.status <> 'INPROGRESS'
                """;
        return dbAccess.update(sql, Timestamp.valueOf(LocalDateTime.now()));
    }

    /**
     * How many datasources have FACE_RECOGNITION enabled — the blast radius the settings card shows,
     * because changing the model re-embeds across all of them.
     */
    public int countDatasourcesWithRecognition() {
        Integer count = dbAccess.queryForObject("""
                SELECT count(*) FROM DATASOURCE_TREATMENTS WHERE treatment = 'FACE_RECOGNITION'
                """, Integer.class);
        return count == null ? 0 : count;
    }

    /** FACE_RECOGNITION rows still to process — the "faces remaining" in a drain. */
    public int countPendingRecognition() {
        String sql = """
                SELECT count(*) FROM ITEM_TREATMENTS it
                JOIN DATASOURCE_TREATMENTS dt ON it.datasource_treatment_id = dt.id
                WHERE dt.treatment = 'FACE_RECOGNITION'
                  AND it.status IN ('NEW', 'UPDATED', 'INPROGRESS')
                """;
        Integer count = dbAccess.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * A group label the operator chose, as opposed to a machine-assigned "Person N" that re-numbers
     * freely. Only these are worth carrying across a model change.
     */
    private static final String HUMAN_NAMED = "fg.label !~ '^Person [0-9]+$'";

    /**
     * Replace the pending snapshot with the labels of the human-named groups standing right now,
     * ahead of a regroup that clears them. The key {@code (item_treatment_id, face_index)} is stable
     * across the change because detection is untouched, which is what makes overlap computable
     * without a per-face anchor.
     *
     * <p><b>An empty set of human-named groups leaves any pending snapshot alone.</b> Turning the
     * picker off clears the groups but keeps the snapshot; the next transition — turning it back on —
     * would otherwise clear that snapshot and replace it with nothing, destroying the names it exists
     * to protect. Nothing is lost by declining: with no human-named group there is nothing to record.
     */
    public int refreshSnapshotFromGroups() {
        Integer humanNamed = dbAccess.queryForObject(
                "SELECT count(*) FROM FACE_GROUPS fg WHERE %s".formatted(HUMAN_NAMED), Integer.class);
        if (humanNamed == null || humanNamed == 0) {
            return 0;
        }

        clearSnapshot();
        String sql = """
                INSERT INTO FACE_LABEL_SNAPSHOT (item_treatment_id, face_index, label)
                SELECT fe.item_treatment_id, fe.face_index, fg.label
                FROM FACE_EMBEDDINGS fe
                JOIN FACE_GROUPS fg ON fe.face_group_id = fg.id
                WHERE %s
                ON CONFLICT (item_treatment_id, face_index) DO UPDATE SET label = EXCLUDED.label
                """.formatted(HUMAN_NAMED);
        return dbAccess.update(sql);
    }

    public int snapshotCount() {
        Integer count = dbAccess.queryForObject("SELECT count(*) FROM FACE_LABEL_SNAPSHOT", Integer.class);
        return count == null ? 0 : count;
    }

    public void clearSnapshot() {
        dbAccess.update("DELETE FROM FACE_LABEL_SNAPSHOT");
    }

    /**
     * The post-drain label-carryover pass: give each new cluster the <b>plurality</b> old label
     * among its members, resolving contested labels to the larger cluster, then drop the snapshot.
     *
     * <p>A no-op if the snapshot is already gone, so it is safe to invoke speculatively after each
     * recognition item completes. It runs only once the drain has no pending recognition rows (its
     * caller checks), so no concurrent grouping is in flight to serialize against; and it writes only
     * labels — the group's and the {@code detectedface} copies of it, via {@link #setLabel} — never
     * a centroid or an assignment, which is what a grouping run writes. Ties and clusters with no labeled
     * members stay auto-labeled ("unlabeled" in UI terms). A label whose plurality is contested by
     * two clusters goes to the strictly larger; an exact tie leaves both auto.
     */
    public void propagateSnapshotLabels() {
        Map<String, String> snapshot = new java.util.HashMap<>();
        dbAccess.query("SELECT item_treatment_id, face_index, label FROM FACE_LABEL_SNAPSHOT",
                (rs, n) -> {
                    snapshot.put(rs.getString("item_treatment_id") + "#" + rs.getInt("face_index"),
                            rs.getString("label"));
                    return null;
                });
        if (snapshot.isEmpty()) {
            return;
        }

        // group id -> (old label -> count among this cluster's members)
        Map<String, Map<String, Integer>> tally = new java.util.HashMap<>();
        dbAccess.query("""
                SELECT item_treatment_id, face_index, face_group_id
                FROM FACE_EMBEDDINGS
                WHERE face_group_id IS NOT NULL
                """, (rs, n) -> {
            String label = snapshot.get(rs.getString("item_treatment_id") + "#" + rs.getInt("face_index"));
            if (label != null) {
                tally.computeIfAbsent(rs.getString("face_group_id"), k -> new java.util.HashMap<>())
                        .merge(label, 1, Integer::sum);
            }
            return null;
        });

        Map<String, String> assignments = computeLabelAssignments(tally);
        for (Map.Entry<String, String> entry : assignments.entrySet()) {
            setLabel(entry.getKey(), entry.getValue());
        }

        clearSnapshot();
        logger.info("Label carryover: propagated {} human label(s) across a model change",
                assignments.size());
    }

    /**
     * Pure plurality-with-global-uniqueness assignment, extracted so it can be unit-tested against
     * fabricated tallies without a database or a model.
     *
     * <p>Input: {@code groupId -> (oldLabel -> count of that label among the group's members)}.
     * Output: {@code groupId -> label} for the groups that win a label. Each group votes its
     * plurality label (a tie inside a group yields no vote); then each label goes to the largest
     * group claiming it. Every group that wins nothing keeps whatever auto "Person N" label it
     * already has.
     *
     * <p><b>The global-uniqueness rule is load-bearing, not tidiness.</b> Since a label may now name
     * several groups — that is how {@link #renameFaceLabel} merges one person's age-separated
     * clusters — and no group id is exposed by the API, a merge cannot be undone by renaming. This
     * rule is the undo: on a recluster the label goes to one claimant only, so merged clusters
     * return as unlabeled "Person N" to be re-curated. Relaxing it so every claimant keeps the label
     * would let merges survive a recluster and leave no way back at all.
     *
     * <p><b>An exact tie across groups is broken by group id, not left unassigned.</b> Dropping a
     * contested label sounds like the careful choice, and it is wrong here: the commonest way to
     * produce a tie is <em>splitting</em> a person — raise the grouping threshold and Alice's two
     * faces become two one-face clusters, each claiming "Alice" with a count of one. Refusing to
     * choose loses the name outright, and the operator cannot even tell which clusters used to be
     * her. Choosing arbitrarily but deterministically leaves the name on one half, where it is
     * visible and can be corrected by a rename. The id is a stable arbitrary key: nothing about it
     * is meaningful, which is the point — it makes an unprincipled choice reproducible rather than
     * dependent on hash iteration order.
     */
    static Map<String, String> computeLabelAssignments(Map<String, Map<String, Integer>> tally) {
        // Per group, its plurality label (ties → none).
        Map<String, LabelVote> groupWinner = new java.util.HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> entry : tally.entrySet()) {
            LabelVote winner = plurality(entry.getValue());
            if (winner != null) {
                groupWinner.put(entry.getKey(), winner);
            }
        }

        // Global uniqueness: each label goes to the largest claiming cluster, ties broken by id.
        Map<String, String> labelToGroup = new java.util.HashMap<>();
        Map<String, Integer> labelBestCount = new java.util.HashMap<>();
        for (Map.Entry<String, LabelVote> entry : groupWinner.entrySet()) {
            String groupId = entry.getKey();
            String label = entry.getValue().label();
            int count = entry.getValue().count();
            Integer best = labelBestCount.get(label);
            if (best == null || count > best
                    || (count == best && groupId.compareTo(labelToGroup.get(label)) < 0)) {
                labelBestCount.put(label, count);
                labelToGroup.put(label, groupId);
            }
        }

        Map<String, String> assignments = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : labelToGroup.entrySet()) {
            assignments.put(entry.getValue(), entry.getKey());
        }
        return assignments;
    }

    private static LabelVote plurality(Map<String, Integer> counts) {
        String bestLabel = null;
        int bestCount = 0;
        boolean tie = false;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                bestLabel = entry.getKey();
                tie = false;
            } else if (entry.getValue() == bestCount) {
                tie = true;
            }
        }
        return (bestLabel == null || tie) ? null : new LabelVote(bestLabel, bestCount);
    }

    private record LabelVote(String label, int count) {}

    private static class FaceGroupRowMapper implements RowMapper<FaceGroup> {
        @Override
        public FaceGroup mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            FaceGroup group = new FaceGroup();
            group.setId(rs.getString("id"));
            group.setLabel(rs.getString("label"));
            group.setCentroid(rs.getBytes("centroid"));
            group.setModelHash(rs.getString("model_hash"));
            group.setFaceCount(rs.getInt("face_count"));

            Timestamp dateCreated = rs.getTimestamp("date_created");
            if (dateCreated != null) {
                group.setDateCreated(dateCreated.toLocalDateTime());
            }
            Timestamp dateModified = rs.getTimestamp("date_modified");
            if (dateModified != null) {
                group.setDateModified(dateModified.toLocalDateTime());
            }
            return group;
        }
    }
}
