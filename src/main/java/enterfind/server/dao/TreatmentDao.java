package enterfind.server.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.exception.SystemIssueException;
import enterfind.server.model.DataSourceTreatment;
import enterfind.server.model.DataSourceType;
import enterfind.server.model.FaceDetection;
import enterfind.server.model.FaceEmbedding;
import enterfind.server.model.FaceGroupMutation;
import enterfind.server.model.ItemTreatment;
import enterfind.server.model.ItemTreatmentResult;
import enterfind.server.model.ItemTreatmentStatus;
import enterfind.server.model.ItemTreatmentWithDetails;
import enterfind.server.model.UserSettingName;
import enterfind.server.scan.Item;
import enterfind.server.scan.TreatmentOutput;
import enterfind.server.scan.TreatmentResult;
import enterfind.server.scan.Treatments;
import enterfind.server.util.Utilities;

/**
 * DAO class for datasource treatment database operations using Spring JDBC
 */
@Repository
public class TreatmentDao {

    private static final Logger logger = LoggerFactory.getLogger(TreatmentDao.class);

    /**
     * Maximum size for metadata values (10 KB).
     * Values exceeding this limit must be in the whitelist or will be rejected.
     */
    private static final int MAX_METADATA_VALUE_SIZE = 10000;

    private final DBAccess dbAccess;
    private final ObjectMapper objectMapper;
    private final FaceDao faceDao;
    private final FaceGroupDao faceGroupDao;
    private final UserSettingsDao userSettingsDao;

    public TreatmentDao(DBAccess dbAccess, ObjectMapper objectMapper, FaceDao faceDao,
            FaceGroupDao faceGroupDao, UserSettingsDao userSettingsDao) {
        this.dbAccess = dbAccess;
        this.objectMapper = objectMapper;
        this.faceDao = faceDao;
        this.faceGroupDao = faceGroupDao;
        this.userSettingsDao = userSettingsDao;
    }

    /**
     * Determines if a field is classified as a large content field that should go to the content table.
     * Uses explicit whitelist approach: only known large fields go to content table.
     *
     * Whitelist includes:
     * - content (main extracted content)
     * - content-N (numbered content chunks: content-0, content-1, etc.)
     * - ocr-content* (all OCR chunks: ocr-content, ocr-content-0, etc.)
     * - icc_red_trc, icc_green_trc, icc_blue_trc (ICC color profile tone curves)
     *
     * NOT in whitelist (metadata fields):
     * - content-type (MIME type)
     * - content-encoding (character encoding)
     * - content-language (language code)
     * - content-type-hint (suggested MIME type)
     *
     * @param key the field name to classify
     * @return true if field should go to content table, false for metadata table
     */
    private boolean isKnownLargeField(String key) {
        if (key == null) {
            return false;
        }
        // Exact match for 'content' or numbered chunks like 'content-0', 'content-1', etc.
        // Must NOT match metadata fields: content-type, content-encoding, content-language, content-type-hint
        if (key.equals("content") || key.matches("^content-[0-9]+$")) {
            return true;
        }
        return key.startsWith("ocr-content") ||
               key.equals("icc_red_trc") ||
               key.equals("icc_green_trc") ||
               key.equals("icc_blue_trc");
    }

    /**
     * Add a treatment to a datasource
     */
    public DataSourceTreatment addTreatment(String dataSourceId, Treatments treatment, JsonNode config) {
        // FACE_RECOGNITION consumes FACE_DETECTION's output, so enabling it enables detection too.
        // Auto-enabling (rather than guarding) closes the "recognition on, detection off" stall: with
        // no detection rows the ordering EXISTS would never become true and recognition rows would
        // sit in NEW forever with no error. loadTreatments() re-renders after the toggle, so the
        // companion checkbox flips on its own. The readiness guard (a usable embedding model must
        // exist) is enforced by the caller — only the operator can supply that, so it is a guard, not
        // an auto-enable.
        if (treatment == Treatments.FACE_RECOGNITION
                && !treatmentExists(dataSourceId, Treatments.FACE_DETECTION)) {
            addTreatment(dataSourceId, Treatments.FACE_DETECTION, null);
        }

        String id = Utilities.generateHexId();
        LocalDateTime dateCreated = LocalDateTime.now();

        String sql = """
                INSERT INTO DATASOURCE_TREATMENTS (id, datasource_id, treatment, config, date_created)
                VALUES (?, ?, ?, ?::jsonb, ?)
                """;

        try {
            String configJson = config != null ? objectMapper.writeValueAsString(config) : null;
            dbAccess.update(sql, id, dataSourceId, treatment.name(), configJson,
                    Timestamp.valueOf(dateCreated));

            return new DataSourceTreatment(id, dataSourceId, treatment, config, dateCreated, null);
        } catch (JacksonException e) {
            throw new SystemIssueException("Failed to serialize treatment config", e);
        }
    }

    /**
     * Remove a treatment from a datasource
     */
    public boolean removeTreatment(String dataSourceId, Treatments treatment) {
        if (treatment == Treatments.NAME_AND_PATH) {
            throw new InvalidArgumentException("NAME_AND_PATH treatment is required and cannot be removed");
        }

        // Mirror of the auto-enable: recognition cannot run without detection, so removing detection
        // removes recognition too rather than stranding it. The removal confirm text warns the
        // operator that recognition goes with it.
        if (treatment == Treatments.FACE_DETECTION) {
            removeTreatment(dataSourceId, Treatments.FACE_RECOGNITION);
        }
        String sql = """
            DELETE FROM DATASOURCE_TREATMENTS
            WHERE datasource_id = ? AND treatment = ?
            """;
        int rowsAffected = dbAccess.update(sql, dataSourceId, treatment.name());
        return rowsAffected > 0;
    }

    /**
     * Get all treatments for a specific datasource
     */
    public List<DataSourceTreatment> getTreatmentsByDataSource(String dataSourceId) {
        String sql = """
                SELECT id, datasource_id, treatment, config, date_created, date_modified
                FROM DATASOURCE_TREATMENTS
                WHERE datasource_id = ?
                ORDER BY treatment
                """;

        return dbAccess.query(sql, new DataSourceTreatmentRowMapper(), dataSourceId);
    }

    /**
     * Get a specific treatment for a datasource
     */
    public Optional<DataSourceTreatment> getTreatment(String dataSourceId, Treatments treatment) {
        String sql = """
                SELECT id, datasource_id, treatment, config, date_created, date_modified
                FROM DATASOURCE_TREATMENTS
                WHERE datasource_id = ? AND treatment = ?
                """;

        try {
            DataSourceTreatment result = dbAccess.queryForObject(sql,
                    new DataSourceTreatmentRowMapper(), dataSourceId, treatment.name());
            return Optional.of(result);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Update treatment configuration
     */
    public Optional<DataSourceTreatment> updateTreatmentConfig(String dataSourceId, Treatments treatment,
            JsonNode config) {
        LocalDateTime dateModified = LocalDateTime.now();

        String sql = """
                UPDATE DATASOURCE_TREATMENTS
                SET config = ?::jsonb, date_modified = ?
                WHERE datasource_id = ? AND treatment = ?
                """;

        try {
            String configJson = config != null ? objectMapper.writeValueAsString(config) : null;
            int rowsAffected = dbAccess.update(sql, configJson, Timestamp.valueOf(dateModified),
                    dataSourceId, treatment.name());

            if (rowsAffected > 0) {
                return getTreatment(dataSourceId, treatment);
            }
            return Optional.empty();
        } catch (JacksonException e) {
            throw new SystemIssueException("Failed to serialize treatment config", e);
        }
    }

    /**
     * Check if a treatment exists for a datasource
     */
    public boolean treatmentExists(String dataSourceId, Treatments treatment) {
        String sql = """
                SELECT COUNT(*) FROM DATASOURCE_TREATMENTS
                WHERE datasource_id = ? AND treatment = ?
                """;

        Integer count = dbAccess.queryForObject(sql, Integer.class, dataSourceId, treatment.name());
        return count != null && count > 0;
    }

    // Item Treatment methods

    /**
     * Add an item treatment
     */
    public ItemTreatment addItemTreatment(String datasourceTreatmentId, String itemId, ItemTreatmentStatus status) {
        String id = Utilities.generateHexId();
        LocalDateTime dateCreated = LocalDateTime.now();

        String sql = """
                INSERT INTO ITEM_TREATMENTS (id, datasource_treatment_id, item_id, status, date_created)
                VALUES (?, ?, ?, ?, ?)
                """;

        dbAccess.update(sql, id, datasourceTreatmentId, itemId, status.name(),
                Timestamp.valueOf(dateCreated));

        return new ItemTreatment(id, datasourceTreatmentId, itemId, status, dateCreated, null, 1);
    }

    /**
     * Get all item treatments for a specific datasource treatment
     */
    public List<ItemTreatment> getItemTreatmentsByDatasourceTreatment(String datasourceTreatmentId) {
        String sql = """
                SELECT id, datasource_treatment_id, item_id, status, date_created, date_modified, version
                FROM ITEM_TREATMENTS
                WHERE datasource_treatment_id = ?
                ORDER BY date_created
                """;

        return dbAccess.query(sql, new ItemTreatmentRowMapper(), datasourceTreatmentId);
    }

    /**
     * Get all item treatments for a specific item
     */
    public List<ItemTreatment> getItemTreatmentsByItem(String itemId) {
        String sql = """
                SELECT id, datasource_treatment_id, item_id, status, date_created, date_modified, version
                FROM ITEM_TREATMENTS
                WHERE item_id = ?
                ORDER BY date_created
                """;

        return dbAccess.query(sql, new ItemTreatmentRowMapper(), itemId);
    }

    /**
     * Get item treatments for multiple items in batches.
     * Uses chunking to handle large folders efficiently without memory spikes.
     *
     * @param itemIds list of item IDs to fetch treatments for
     * @return map of item_id to list of ItemTreatment
     */
    public Map<String, List<ItemTreatment>> getItemTreatmentsByItemBatch(List<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return new HashMap<>();
        }

        final int BATCH_SIZE = 500; // Conservative chunk size to avoid memory spikes
        Map<String, List<ItemTreatment>> allResults = new HashMap<>();

        // Process in chunks to avoid memory spikes and query performance issues
        for (int i = 0; i < itemIds.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, itemIds.size());
            List<String> batch = itemIds.subList(i, end);

            String sql = """
                SELECT id, datasource_treatment_id, item_id, status, date_created, date_modified, version
                FROM ITEM_TREATMENTS
                WHERE item_id = ANY(?)
                ORDER BY item_id, date_created
                """;

            // Use DBAccess's new method for custom PreparedStatementCreator
            List<ItemTreatment> batchResults = dbAccess.queryWithPreparedStatement(
                connection -> {
                    var ps = connection.prepareStatement(sql);
                    java.sql.Array sqlArray = connection.createArrayOf("varchar", batch.toArray(new String[0]));
                    ps.setArray(1, sqlArray);
                    return ps;
                },
                new ItemTreatmentRowMapper()
            );

            // Merge results into map
            for (ItemTreatment treatment : batchResults) {
                allResults.computeIfAbsent(treatment.getItemId(), k -> new ArrayList<>())
                         .add(treatment);
            }
        }

        return allResults;
    }

    /**
     * Get all item treatments with a specific status
     */
    public List<ItemTreatment> getItemTreatmentsByStatus(ItemTreatmentStatus status) {
        String sql = """
                SELECT id, datasource_treatment_id, item_id, status, date_created, date_modified, version
                FROM ITEM_TREATMENTS
                WHERE status = ?
                ORDER BY date_created
                """;

        return dbAccess.query(sql, new ItemTreatmentRowMapper(), status.name());
    }

    /**
     * Get item treatments ready for processing (status NEW, UPDATED, or FAILED)
     * with full item details and treatment names, sorted by item id.
     * Limited to specified number of records and excludes last item if limit is
     * reached to avoid incomplete processing.
     */
    public List<ItemTreatmentWithDetails> getItemTreatmentsReadyForProcessing(int limit, int maxFailureCount,
            boolean embeddingModelReady) {
        // FACE_RECOGNITION carries two extra claim conditions, folded into one guard so unclaimable
        // rows never reach LIMIT (the processor claims one row at a time, ordered by item id, and
        // would otherwise re-select and re-skip the same row forever):
        //   - Readiness: an instance-global embedding model must be usable. mode lives in
        //     SYSTEM_SETTINGS, which SQL cannot read, so it enters as a boolean bind parameter
        //     computed once per claim batch.
        //   - Ordering: the same item's FACE_DETECTION row must be DONE, via a correlated EXISTS.
        //     FOR UPDATE OF it names only the outer alias, so the subquery over ITEM_TREATMENTS is
        //     legal beside it.
        //
        // The status list below is mirrored by the partial index idx_item_treatments_pending_item
        // (Schema.createItemTreatmentsTable), which is what keeps this claim off a full walk of
        // ITEM_TREATMENTS. Editing the list here without editing the index predicate silently
        // reverts the plan — no error, just a scan that worsens as the queue drains.
        String sql = """
                SELECT it.id as item_treatment_id, dt.treatment as treatment_name, ds.type as datasource_type,
                    it.status, it.version, i.id, i.datasource_id, i.name, i.path, i.time_created, i.time_modified,
                    i.size_bytes, i.content_hash, i.mime_type, i.date_scanned
                FROM ITEM_TREATMENTS it
                JOIN DATASOURCE_TREATMENTS dt ON it.datasource_treatment_id = dt.id
                JOIN ITEMS i ON it.item_id = i.id
                JOIN DATA_SOURCES ds ON i.datasource_id = ds.id
                WHERE it.status IN ('NEW', 'UPDATED', 'FAILED')
                  AND it.failure_count < ?
                  AND (dt.treatment <> 'FACE_RECOGNITION' OR (? AND EXISTS (
                        SELECT 1 FROM ITEM_TREATMENTS det
                        JOIN DATASOURCE_TREATMENTS ddt ON det.datasource_treatment_id = ddt.id
                        WHERE det.item_id = it.item_id
                          AND ddt.datasource_id = dt.datasource_id
                          AND ddt.treatment = 'FACE_DETECTION'
                          AND det.status = 'DONE'
                  )))
                ORDER BY i.id
                LIMIT ?
                FOR UPDATE OF it SKIP LOCKED
                """;

        return dbAccess.query(sql, new ItemTreatmentWithDetailsRowMapper(),
                maxFailureCount, embeddingModelReady, limit);
    }

    /**
     * Get a specific item treatment
     */
    public Optional<ItemTreatment> getItemTreatment(String datasourceTreatmentId, String itemId) {
        String sql = """
                SELECT id, datasource_treatment_id, item_id, status, date_created, date_modified, version
                FROM ITEM_TREATMENTS
                WHERE datasource_treatment_id = ? AND item_id = ?
                """;

        try {
            ItemTreatment result = dbAccess.queryForObject(sql,
                    new ItemTreatmentRowMapper(), datasourceTreatmentId, itemId);
            return Optional.of(result);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Get a specific item treatment by ID
     */
    public Optional<ItemTreatment> getItemTreatmentById(String itemTreatmentId) {
        String sql = """
                SELECT id, datasource_treatment_id, item_id, status, date_created, date_modified, version
                FROM ITEM_TREATMENTS
                WHERE id = ?
                """;

        try {
            ItemTreatment result = dbAccess.queryForObject(sql,
                    new ItemTreatmentRowMapper(), itemTreatmentId);
            return Optional.of(result);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Update item treatment status
     */
    public Optional<ItemTreatment> updateItemTreatmentStatus(String itemTreatmentId, ItemTreatmentStatus status,
            int currentVersion) {
        LocalDateTime dateModified = LocalDateTime.now();
        String sql = status == ItemTreatmentStatus.FAILED ? """
                UPDATE ITEM_TREATMENTS
                SET status = ?, date_modified = ?, version = version + 1, failure_count = failure_count + 1
                WHERE id = ? AND version = ?
                """ : """
                UPDATE ITEM_TREATMENTS
                SET status = ?, date_modified = ?, version = version + 1
                WHERE id = ? AND version = ?
                """;
        int rowsAffected = dbAccess.update(sql, status.name(), Timestamp.valueOf(dateModified), itemTreatmentId,
                currentVersion);
        if (rowsAffected > 0) {
            return getItemTreatmentById(itemTreatmentId);
        }
        return Optional.empty();
    }

    /**
     * Delete an item treatment
     */
    public boolean deleteItemTreatment(String datasourceTreatmentId, String itemId) {
        String sql = """
                DELETE FROM ITEM_TREATMENTS
                WHERE datasource_treatment_id = ? AND item_id = ?
                """;

        int rowsAffected = dbAccess.update(sql, datasourceTreatmentId, itemId);
        return rowsAffected > 0;
    }

    /**
     * Delete all item treatments for a specific datasource treatment
     */
    public int deleteItemTreatmentsByDatasourceTreatment(String datasourceTreatmentId) {
        String sql = """
                DELETE FROM ITEM_TREATMENTS
                WHERE datasource_treatment_id = ?
                """;

        return dbAccess.update(sql, datasourceTreatmentId);
    }

    /**
     * Delete all item treatments for a specific item
     */
    public int deleteItemTreatmentsByItem(String itemId) {
        String sql = """
                DELETE FROM ITEM_TREATMENTS
                WHERE item_id = ?
                """;

        return dbAccess.update(sql, itemId);
    }

    /**
     * Check if an item treatment exists
     */
    public boolean itemTreatmentExists(String datasourceTreatmentId, String itemId) {
        String sql = """
                SELECT COUNT(*) FROM ITEM_TREATMENTS
                WHERE datasource_treatment_id = ? AND item_id = ?
                """;

        Integer count = dbAccess.queryForObject(sql, Integer.class, datasourceTreatmentId, itemId);
        return count != null && count > 0;
    }

    // Item Treatment Results methods

    /**
     * Bulk insert item treatment results into appropriate tables (metadata or content).
     * Uses explicit whitelist approach to route fields:
     * - Known large fields (content*, ocr-content*, icc_*_trc) → ITEM_TREATMENT_RESULTS_CONTENT
     * - All other fields → ITEM_TREATMENT_RESULTS (with 10KB size limit)
     * - Oversized metadata fields are rejected with error logging
     */
    public List<ItemTreatmentResult> bulkInsertItemTreatmentResults(String itemTreatmentId,
            List<TreatmentResult> treatmentResults) {
        if (treatmentResults == null || treatmentResults.isEmpty()) {
            return new ArrayList<>();
        }

        // Separate results by destination table
        List<TreatmentResult> metadataResults = new ArrayList<>();
        List<TreatmentResult> contentResults = new ArrayList<>();
        List<TreatmentResult> rejectedResults = new ArrayList<>();

        for (TreatmentResult result : treatmentResults) {
            if (isKnownLargeField(result.getKey())) {
                contentResults.add(result);
            } else {
                // Metadata field - check size limit
                int valueSize = result.getValue() != null ? result.getValue().length() : 0;
                if (valueSize > MAX_METADATA_VALUE_SIZE) {
                    rejectedResults.add(result);
                    String valueSample = result.getValue() != null
                            ? result.getValue().substring(0, Math.min(100, result.getValue().length()))
                            : "";
                    logger.error("Rejected oversized metadata field '{}': {} bytes (max {}). " +
                                "Sample: {}... Add to whitelist if this is expected large content.",
                                result.getKey(), valueSize, MAX_METADATA_VALUE_SIZE, valueSample);
                } else {
                    metadataResults.add(result);
                }
            }
        }

        // Log summary if any rejections
        if (!rejectedResults.isEmpty()) {
            List<String> rejectedKeys = rejectedResults.stream()
                    .map(TreatmentResult::getKey)
                    .toList();
            logger.warn("Rejected {} oversized metadata fields for item_treatment_id {}: {}",
                       rejectedResults.size(), itemTreatmentId, String.join(", ", rejectedKeys));
        }

        List<ItemTreatmentResult> insertedResults = new ArrayList<>();

        // Insert metadata results
        if (!metadataResults.isEmpty()) {
            insertedResults.addAll(insertMetadataResults(itemTreatmentId, metadataResults));
        }

        // Insert content results
        if (!contentResults.isEmpty()) {
            insertedResults.addAll(insertContentResults(itemTreatmentId, contentResults));
        }

        return insertedResults;
    }

    /**
     * Insert metadata results into ITEM_TREATMENT_RESULTS table
     */
    private List<ItemTreatmentResult> insertMetadataResults(String itemTreatmentId,
            List<TreatmentResult> metadataResults) {
        String sql = """
                INSERT INTO ITEM_TREATMENT_RESULTS (id, item_treatment_id, key, value, date_created)
                VALUES (?, ?, ?, ?, ?)
                """;

        LocalDateTime dateCreated = LocalDateTime.now();
        List<ItemTreatmentResult> insertedResults = new ArrayList<>();

        List<Object[]> batchArgs = new ArrayList<>();
        for (TreatmentResult result : metadataResults) {
            String id = Utilities.generateHexId();
            String normalizedKey = result.getKey().toLowerCase();
            batchArgs.add(new Object[] {
                    id,
                    itemTreatmentId,
                    normalizedKey,
                    result.getValue(),
                    Timestamp.valueOf(dateCreated)
            });

            insertedResults.add(new ItemTreatmentResult(id, itemTreatmentId,
                    normalizedKey, result.getValue(), dateCreated));
        }

        dbAccess.batchUpdate(sql, batchArgs);
        return insertedResults;
    }

    /**
     * Insert content results into ITEM_TREATMENT_RESULTS_CONTENT table
     */
    private List<ItemTreatmentResult> insertContentResults(String itemTreatmentId,
            List<TreatmentResult> contentResults) {
        String sql = """
                INSERT INTO ITEM_TREATMENT_RESULTS_CONTENT (id, item_treatment_id, key, value, date_created)
                VALUES (?, ?, ?, ?, ?)
                """;

        LocalDateTime dateCreated = LocalDateTime.now();
        List<ItemTreatmentResult> insertedResults = new ArrayList<>();

        List<Object[]> batchArgs = new ArrayList<>();
        for (TreatmentResult result : contentResults) {
            String id = Utilities.generateHexId();
            String normalizedKey = result.getKey().toLowerCase();
            batchArgs.add(new Object[] {
                    id,
                    itemTreatmentId,
                    normalizedKey,
                    result.getValue(),
                    Timestamp.valueOf(dateCreated)
            });

            insertedResults.add(new ItemTreatmentResult(id, itemTreatmentId,
                    normalizedKey, result.getValue(), dateCreated));
        }

        dbAccess.batchUpdate(sql, batchArgs);
        return insertedResults;
    }

    /**
     * Get all item treatment results for a specific item treatment from both metadata and content tables
     */
    public List<ItemTreatmentResult> getItemTreatmentResults(String itemTreatmentId) {
        List<ItemTreatmentResult> results = new ArrayList<>();

        // Get metadata results
        String metadataSql = """
                SELECT id, item_treatment_id, key, value, date_created
                FROM ITEM_TREATMENT_RESULTS
                WHERE item_treatment_id = ?
                """;
        results.addAll(dbAccess.query(metadataSql, new ItemTreatmentResultRowMapper(), itemTreatmentId));

        // Get content results
        String contentSql = """
                SELECT id, item_treatment_id, key, value, date_created
                FROM ITEM_TREATMENT_RESULTS_CONTENT
                WHERE item_treatment_id = ?
                """;
        results.addAll(dbAccess.query(contentSql, new ItemTreatmentResultRowMapper(), itemTreatmentId));

        // Sort combined results
        results.sort((a, b) -> {
            int dateCompare = a.getDateCreated().compareTo(b.getDateCreated());
            return dateCompare != 0 ? dateCompare : a.getKey().compareTo(b.getKey());
        });

        return results;
    }

    /**
     * Delete all item treatment results for a specific item treatment from both metadata and content tables
     */
    public int deleteItemTreatmentResults(String itemTreatmentId) {
        int deleted = 0;

        // Delete from metadata table
        String metadataSql = """
                DELETE FROM ITEM_TREATMENT_RESULTS
                WHERE item_treatment_id = ?
                """;
        deleted += dbAccess.update(metadataSql, itemTreatmentId);

        // Delete from content table
        String contentSql = """
                DELETE FROM ITEM_TREATMENT_RESULTS_CONTENT
                WHERE item_treatment_id = ?
                """;
        deleted += dbAccess.update(contentSql, itemTreatmentId);

        return deleted;
    }

    /**
     * Claim a batch of items for processing in a short transaction.
     * Uses FOR UPDATE SKIP LOCKED to prevent contention between threads.
     * Marks all claimed items as INPROGRESS before committing.
     * @param batchSize maximum number of items to claim
     * @return list of claimed items with details
     */
    @Transactional
    public List<ItemTreatmentWithDetails> claimItemsForProcessing(int batchSize, int maxFailureCount,
            boolean embeddingModelReady) {
        List<ItemTreatmentWithDetails> results =
                getItemTreatmentsReadyForProcessing(batchSize, maxFailureCount, embeddingModelReady);
        logger.info("Selected {} item treatments for processing", results.size());

        if (results.isEmpty()) {
            return results;
        }

        // Mark all claimed items as INPROGRESS in this short transaction
        for (ItemTreatmentWithDetails itemTreatment : results) {
            boolean locked = updateItemTreatmentStatus(
                    itemTreatment.getItemTreatmentId(), ItemTreatmentStatus.INPROGRESS,
                    itemTreatment.getVersion())
                    .isPresent();
            if (!locked) {
                // This should be rare with FOR UPDATE SKIP LOCKED, but handle it
                logger.warn("Optimistic lock failed for item treatment {}: another thread/process updated this item",
                        itemTreatment.getItemTreatmentId());
                // Mark this item as null so we can filter it out later
                results.set(results.indexOf(itemTreatment), null);
            } else {
                // Increment version since we updated the status
                itemTreatment.setVersion(itemTreatment.getVersion() + 1);
            }
        }

        // Remove any items that failed to lock
        results.removeIf(item -> item == null);

        logger.info("Successfully claimed {} item treatments", results.size());
        return results;
    }

    /**
     * Save the results of processing a single item treatment in a short transaction.
     * @param itemTreatmentId the item treatment ID
     * @param version the current version for optimistic locking
     * @param status the status to update (DONE or FAILED)
     * @param treatmentResults the results to save (can be null)
     * @param deleteExistingResults whether to delete existing results first
     */
    // @Transactional in its own right, not merely by delegation: this is a self-invocation, so it
    // does not pass back through the Spring proxy and would otherwise run without a transaction.
    @Transactional
    public void saveItemTreatmentResult(String itemTreatmentId, int version,
            ItemTreatmentStatus status, List<TreatmentResult> treatmentResults, boolean deleteExistingResults) {
        saveItemTreatmentResult(itemTreatmentId, version, status, treatmentResults, deleteExistingResults,
                null, null);
    }

    /**
     * Save the results of processing a single item treatment in a short transaction, including any
     * face geometry the treatment produced.
     *
     * <p>The face rows are written here rather than by the handler so that they and
     * {@code facecount} become visible together: the count is what tells the item view how many
     * crops to request, and a reader that catches the two mid-update asks for a face whose row is
     * not there yet.
     *
     * @param faceDetections face geometry this run produced, or <b>null</b> to publish none and
     *        leave the stored rows alone. Null and empty differ, and the difference is the FAILED
     *        path: a failed run keeps the previous results, so it must keep the geometry that
     *        matches them, whereas an empty list from a run that completed means "no face here now"
     *        and clears what a previous run stored. Rows are scoped to this item treatment, so a
     *        treatment that produces no faces cannot disturb another's.
     */
    @Transactional
    public void saveItemTreatmentResult(String itemTreatmentId, int version,
            ItemTreatmentStatus status, List<TreatmentResult> treatmentResults, boolean deleteExistingResults,
            List<FaceDetection> faceDetections) {
        saveItemTreatmentResult(itemTreatmentId, version, status, treatmentResults, deleteExistingResults,
                faceDetections, null);
    }

    @Transactional
    public void saveItemTreatmentResult(String itemTreatmentId, int version,
            ItemTreatmentStatus status, List<TreatmentResult> treatmentResults, boolean deleteExistingResults,
            List<FaceDetection> faceDetections, List<FaceEmbedding> faceEmbeddings) {
        saveItemTreatmentResult(itemTreatmentId, version, status, treatmentResults, deleteExistingResults,
                faceDetections, faceEmbeddings, null);
    }

    /** Persist a handler's full output (results, geometry, embeddings, group upserts) in one transaction. */
    @Transactional
    public void saveTreatmentOutput(String itemTreatmentId, int version, ItemTreatmentStatus status,
            boolean deleteExistingResults, TreatmentOutput output) {
        saveItemTreatmentResult(itemTreatmentId, version, status, output.results(), deleteExistingResults,
                output.faceDetections(), output.faceEmbeddings(), output.groupMutations());
    }

    /**
     * Save one item treatment's output in one transaction: results, face geometry (FACE_DETECTION),
     * face embeddings and the FACE_GROUPS upserts (FACE_RECOGNITION).
     *
     * <p>A dumb applier. The grouping decision — which group each face joined, the new centroids, the
     * {@code detectedface} labels — was made by the handler and handed here as {@code faceEmbeddings}
     * (already carrying group ids), {@code groupMutations} and {@code detectedface} entries already
     * in {@code treatmentResults}. There is no cosine, no threshold and no label logic here.
     *
     * <p>Any grouping lock the handler holds spans this commit and is released by the
     * {@code TreatmentProcessor} in the {@code finally} around the apply→save lifecycle — after this
     * external {@code @Transactional} call returns, i.e. after the commit. This method knows nothing
     * about locks.
     *
     * @param faceDetections face geometry, or <b>null</b> to leave stored rows alone (FAILED path)
     * @param faceEmbeddings embeddings with assigned group ids, or <b>null</b> to leave them alone
     * @param groupMutations FACE_GROUPS upserts to apply, or null/empty for none
     */
    @Transactional
    public void saveItemTreatmentResult(String itemTreatmentId, int version,
            ItemTreatmentStatus status, List<TreatmentResult> treatmentResults, boolean deleteExistingResults,
            List<FaceDetection> faceDetections, List<FaceEmbedding> faceEmbeddings,
            List<FaceGroupMutation> groupMutations) {

        // If we need to delete existing results, do it first
        if (deleteExistingResults) {
            deleteItemTreatmentResults(itemTreatmentId);
        }

        // Apply the group upserts the handler decided before writing the embeddings that reference
        // them (order is cosmetic — FACE_EMBEDDINGS.face_group_id is a soft reference, no FK).
        if (groupMutations != null) {
            for (FaceGroupMutation mutation : groupMutations) {
                faceGroupDao.upsertGroup(mutation);
            }
        }

        if (treatmentResults != null && !treatmentResults.isEmpty()) {
            bulkInsertItemTreatmentResults(itemTreatmentId, treatmentResults);
        }

        // The face rows live in FaceDao but are written from here, inside this transaction — Spring
        // propagation is per-thread, so a collaborating bean joins it rather than opening its own.
        // That is what keeps geometry, embeddings and the facecount/detectedface results that
        // address them atomic, exactly as the faceGroupDao call above is.
        if (faceDetections != null) {
            faceDao.replaceFaceDetections(itemTreatmentId, faceDetections);
        }

        if (faceEmbeddings != null) {
            faceDao.replaceFaceEmbeddings(itemTreatmentId, faceEmbeddings);
        }

        // Update status to DONE or FAILED
        updateItemTreatmentStatus(itemTreatmentId, status, version);

        // Dependency upkeep, in the same transaction as the detection write. Both statements are
        // no-ops unless the saved row is a FACE_DETECTION, so they are safe to run for any treatment.
        if (status == ItemTreatmentStatus.DONE) {
            // A completed (re-)detection means any previously-DONE recognition now describes geometry
            // that may have changed; mark it UPDATED so it re-embeds with no rescan.
            invalidateDependentRecognition(itemTreatmentId);
        } else if (status == ItemTreatmentStatus.FAILED) {
            // A detection that has exhausted its retries will never reach DONE, so its recognition
            // row would be a permanent NEW zombie the ordering EXISTS can never release. Fail it too.
            failTerminalDependentRecognition(itemTreatmentId);
        }
    }


    /**
     * When {@code itemTreatmentId} is a completed FACE_DETECTION, flip the same item's DONE
     * FACE_RECOGNITION row to UPDATED so it re-drives. No-op for any other treatment.
     */
    private void invalidateDependentRecognition(String detectionItemTreatmentId) {
        // The recognition-side datasource treatment is matched by EXISTS, not by another FROM join:
        // Postgres forbids referencing the UPDATE target (rec) from a join's ON in the FROM clause,
        // but allows it anywhere in WHERE.
        String sql = """
                UPDATE ITEM_TREATMENTS rec
                SET status = 'UPDATED', date_modified = ?, version = rec.version + 1
                FROM ITEM_TREATMENTS det
                JOIN DATASOURCE_TREATMENTS ddt_det ON det.datasource_treatment_id = ddt_det.id
                WHERE det.id = ?
                  AND ddt_det.treatment = 'FACE_DETECTION'
                  AND rec.item_id = det.item_id
                  AND rec.status = 'DONE'
                  AND EXISTS (
                      SELECT 1 FROM DATASOURCE_TREATMENTS ddt_rec
                      WHERE ddt_rec.id = rec.datasource_treatment_id
                        AND ddt_rec.treatment = 'FACE_RECOGNITION'
                        AND ddt_rec.datasource_id = ddt_det.datasource_id
                  )
                """;
        dbAccess.update(sql, Timestamp.valueOf(LocalDateTime.now()), detectionItemTreatmentId);
    }

    /**
     * When {@code itemTreatmentId} is a FACE_DETECTION that has reached the failure ceiling, fail the
     * same item's FACE_RECOGNITION row too, pinning its failure_count at the ceiling so it is not
     * retried. No-op for any other treatment or a not-yet-terminal failure.
     */
    private void failTerminalDependentRecognition(String detectionItemTreatmentId) {
        int maxFailureCount;
        try {
            maxFailureCount = Integer.parseInt(
                    userSettingsDao.getSetting(UserSettingName.TREATMENT_MAX_FAILURE_COUNT).orElse("4"));
        } catch (NumberFormatException e) {
            maxFailureCount = 4;
        }
        // EXISTS rather than a second FROM join, for the reason given in
        // invalidateDependentRecognition: the UPDATE target cannot be referenced from a join's ON.
        String sql = """
                UPDATE ITEM_TREATMENTS rec
                SET status = 'FAILED', date_modified = ?, version = rec.version + 1,
                    failure_count = GREATEST(rec.failure_count, ?)
                FROM ITEM_TREATMENTS det
                JOIN DATASOURCE_TREATMENTS ddt_det ON det.datasource_treatment_id = ddt_det.id
                WHERE det.id = ?
                  AND ddt_det.treatment = 'FACE_DETECTION'
                  AND det.failure_count >= ?
                  AND rec.item_id = det.item_id
                  AND rec.status <> 'FAILED'
                  AND EXISTS (
                      SELECT 1 FROM DATASOURCE_TREATMENTS ddt_rec
                      WHERE ddt_rec.id = rec.datasource_treatment_id
                        AND ddt_rec.treatment = 'FACE_RECOGNITION'
                        AND ddt_rec.datasource_id = ddt_det.datasource_id
                  )
                """;
        dbAccess.update(sql, Timestamp.valueOf(LocalDateTime.now()), maxFailureCount,
                detectionItemTreatmentId, maxFailureCount);
    }


    /**
     * Row mapper for DataSourceTreatment
     */
    private class DataSourceTreatmentRowMapper implements RowMapper<DataSourceTreatment> {
        @Override
        public DataSourceTreatment mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            DataSourceTreatment treatment = new DataSourceTreatment();
            treatment.setId(rs.getString("id"));
            treatment.setDataSourceId(rs.getString("datasource_id"));
            treatment.setTreatment(Treatments.valueOf(rs.getString("treatment")));

            // Handle date_created
            Timestamp dateCreated = rs.getTimestamp("date_created");
            if (dateCreated != null) {
                treatment.setDateCreated(dateCreated.toLocalDateTime());
            }

            // Handle date_modified
            Timestamp dateModified = rs.getTimestamp("date_modified");
            if (dateModified != null) {
                treatment.setDateModified(dateModified.toLocalDateTime());
            }

            // Handle config JSON
            String configJson = rs.getString("config");
            if (configJson != null && !configJson.isEmpty()) {
                try {
                    JsonNode config = objectMapper.readTree(configJson);
                    treatment.setConfig(config);
                } catch (JacksonException e) {
                    throw new SQLException("Failed to parse treatment config JSON", e);
                }
            }

            return treatment;
        }
    }

    /**
     * Row mapper for ItemTreatment
     */
    private class ItemTreatmentRowMapper implements RowMapper<ItemTreatment> {
        @Override
        public ItemTreatment mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            ItemTreatment itemTreatment = new ItemTreatment();
            itemTreatment.setId(rs.getString("id"));
            itemTreatment.setDatasourceTreatmentId(rs.getString("datasource_treatment_id"));
            itemTreatment.setItemId(rs.getString("item_id"));
            itemTreatment.setStatus(ItemTreatmentStatus.valueOf(rs.getString("status")));

            // Handle date_created
            Timestamp dateCreated = rs.getTimestamp("date_created");
            if (dateCreated != null) {
                itemTreatment.setDateCreated(dateCreated.toLocalDateTime());
            }

            // Handle date_modified
            Timestamp dateModified = rs.getTimestamp("date_modified");
            if (dateModified != null) {
                itemTreatment.setDateModified(dateModified.toLocalDateTime());
            }

            itemTreatment.setVersion(rs.getInt("version"));

            return itemTreatment;
        }
    }

    /**
     * Row mapper for ItemTreatmentWithDetails
     */
    private class ItemTreatmentWithDetailsRowMapper implements RowMapper<ItemTreatmentWithDetails> {
        @Override
        public ItemTreatmentWithDetails mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            // Map the item first
            Item item = new Item();
            item.setId(rs.getString("id"));
            item.setDatasourceId(rs.getString("datasource_id"));
            item.setName(rs.getString("name"));
            item.setPath(rs.getString("path"));

            Timestamp timeCreated = rs.getTimestamp("time_created");
            if (timeCreated != null) {
                item.setTimeCreated(timeCreated.toLocalDateTime());
            }

            Timestamp timeModified = rs.getTimestamp("time_modified");
            if (timeModified != null) {
                item.setTimeModified(timeModified.toLocalDateTime());
            }

            item.setSizeBytes(rs.getLong("size_bytes"));
            if (rs.wasNull()) {
                item.setSizeBytes(null);
            }

            item.setContentHash(rs.getString("content_hash"));
            item.setMimeType(rs.getString("mime_type"));
            item.setDateScanned(rs.getTimestamp("date_scanned").toLocalDateTime());

            // Create the result object
            ItemTreatmentWithDetails result = new ItemTreatmentWithDetails();
            result.setItemTreatmentId(rs.getString("item_treatment_id"));
            result.setTreatmentName(Treatments.valueOf(rs.getString("treatment_name")));
            result.setDataSourceType(DataSourceType.valueOf(rs.getString("datasource_type")));
            result.setDataSourceId(rs.getString("datasource_id"));
            result.setStatus(ItemTreatmentStatus.valueOf(rs.getString("status")));
            result.setVersion(rs.getInt("version"));
            result.setItem(item);

            return result;
        }
    }

    /**
     * Row mapper for ItemTreatmentResult
     */
    private class ItemTreatmentResultRowMapper implements RowMapper<ItemTreatmentResult> {
        @Override
        public ItemTreatmentResult mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            ItemTreatmentResult result = new ItemTreatmentResult();
            result.setId(rs.getString("id"));
            result.setItemTreatmentId(rs.getString("item_treatment_id"));
            result.setKey(rs.getString("key"));
            result.setValue(rs.getString("value"));

            // Handle date_created
            Timestamp dateCreated = rs.getTimestamp("date_created");
            if (dateCreated != null) {
                result.setDateCreated(dateCreated.toLocalDateTime());
            }

            return result;
        }
    }

    /**
     * Reset all INPROGRESS item treatments to FAILED status.
     * Used on startup to recover from crashed/interrupted processing.
     * 
     * @return the number of treatments reset
     */
    public int resetInProgressToFailed() {
        String sql = """
                UPDATE ITEM_TREATMENTS
                SET status = 'FAILED', date_modified = ?, version = version + 1, failure_count = failure_count + 1
                WHERE status = 'INPROGRESS'
                """;

        return dbAccess.update(sql, Timestamp.valueOf(LocalDateTime.now()));
    }
}