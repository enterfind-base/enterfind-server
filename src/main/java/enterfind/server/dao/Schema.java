package enterfind.server.dao;

import static enterfind.server.model.FieldNameConstants.FIELD_NAME;
import static enterfind.server.model.FieldNameConstants.FIELD_PATH;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import enterfind.server.model.AiSettingName;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.UserSettingName;
import enterfind.server.scan.treatment.FaceRecognitionTreatmentHandler;
import enterfind.server.scan.treatment.TreatmentUtils;
import enterfind.server.security.ConfigEncryptor;
import enterfind.server.service.AuthenticationService;
import jakarta.annotation.PostConstruct;

/**
 * Database schema initialization component.
 * Creates the database tables. Called by StartupRunner.
 */
@Component
public class Schema {

        private static final Logger logger = LoggerFactory.getLogger(Schema.class);

        private volatile boolean schemaUpdateInProgress = true;

        private final DBAccess dbAccess;
        private final DataSourceDao dataSourceDao;
        private final TreatmentDao treatmentDao;
        private final ConfigEncryptor configEncryptor;

        public Schema(DBAccess dbAccess, DataSourceDao dataSourceDao, TreatmentDao treatmentDao,
                        ConfigEncryptor configEncryptor) {
                this.dbAccess = dbAccess;
                this.dataSourceDao = dataSourceDao;
                this.treatmentDao = treatmentDao;
                this.configEncryptor = configEncryptor;
        }

        public boolean isSchemaUpdateInProgress() {
                return schemaUpdateInProgress;
        }

        @PostConstruct
        public void initializeSchema() {
                schemaUpdateInProgress = true;
                try {
                        // Must run before createSchema(), which creates FACE_DETECTIONS in its new shape.
                        dropLegacyFaceTables();
                        createSchema();
                        migrateUnencryptedDataSources();
                        migratePathSeparatorToSlash();
                        addFailureCountColumn();
                        moveAiSettingsToSystemSettings();
                        removeTreatmentBatchSizeSetting();
                        moveGroupingThresholdToSystemSettings();
                        dropFaceGroupCountColumn();
                        removeInapplicableItemTreatments();
                        resetStuckDataSources();
                        addItrKeyValueIndex();
                        addFolderScanFailureCountColumn();
                        invalidateLongLivedApiSessions();
                } finally {
                        schemaUpdateInProgress = false;
                }
        }

        void migratePathSeparatorToSlash() {
                // Only migrate items in old comma-separated format (commas but no slashes).
                // Items already containing slashes are either in the new format or already migrated —
                // their remaining commas are part of folder/file names and must not be replaced.
                int itemsUpdated = dbAccess.update(
                        "UPDATE items SET path = replace(path, ',', '/') WHERE path IS NOT NULL AND path LIKE '%,%' AND path NOT LIKE '%/%'");
                int foldersUpdated = dbAccess.update(
                        "UPDATE folders SET path = replace(path, ',', '/') WHERE path IS NOT NULL AND path LIKE '%,%' AND path NOT LIKE '%/%'");
                if (itemsUpdated > 0 || foldersUpdated > 0) {
                        logger.info("Migrated path separator to slash: updated {} item(s) and {} folder(s)",
                                itemsUpdated, foldersUpdated);
                }
        }

        /**
         * One-shot clean slate for the face pipeline: drops the  FACE_DETECTIONS and
         * FACE_GROUPS tables and removes the FACE_RECOGNITION treatment from every datasource.
         *
         * <p>Follows {@link #migratePathSeparatorToSlash}'s idiom — guard on a data shape the
         * migration itself destroys. The guard is the absence of {@code item_treatment_id}, the
         * column only the current shape has, so this runs exactly once and is a no-op on every later
         * boot. That is also what lets the table name be reused instead of scarred into a
         * {@code _V2}: dropping before {@link #createSchema()} means createSchema then builds the
         * new shape cleanly, where the other ordering would have the two fighting on every boot.
         *
         * <p>Keying on {@code item_treatment_id} rather than on the legacy {@code embedding} column
         * also catches the intermediate shape — detections keyed by item alone — that existed only
         * on this unreleased branch. Rebuilding it costs a re-detection on the affected alpha
         * databases and nothing anywhere else, which is cheaper than an ALTER path for a shape no
         * deployment ever ran.
         *
         * <p>Deleting the DATASOURCE_TREATMENTS rows is mandatory, not tidiness: that column stores
         * the enum as a string, and now that FACE_RECOGNITION has temporarily left {@code Treatments}, a
         * surviving row throws on {@code Treatments.valueOf(...)} the first time treatments are
         * listed for that datasource. The delete cascades through ITEM_TREATMENTS to
         * ITEM_TREATMENT_RESULTS(_CONTENT), so the old {@code detectedface} result rows go with it.
         *
         * <p>This discards every face group and every renamed label. That loss is deliberate and
         * accepted: identity returns on a different embedding model, so the vectors would
         * not have survived the transition anyway.
         */
        void dropLegacyFaceTables() {
                if (!tableExists("face_detections") || isCurrentFaceDetectionsShape()) {
                        return;
                }

                int treatmentsDeleted = dbAccess.update(
                        "DELETE FROM datasource_treatments WHERE treatment = 'FACE_RECOGNITION'");

                // face_detections first: it has a foreign key into face_groups.
                dbAccess.executeAdmin("DROP TABLE IF EXISTS FACE_DETECTIONS");
                dbAccess.executeAdmin("DROP TABLE IF EXISTS FACE_GROUPS");

                logger.info("Dropped legacy face recognition tables (FACE_DETECTIONS, FACE_GROUPS) and "
                                + "removed {} FACE_RECOGNITION datasource treatment(s); face groups and labels "
                                + "are discarded, face detection must be re-enabled per datasource",
                                treatmentsDeleted);
        }

        /**
         * True if FACE_DETECTIONS already has the shape {@link #createFaceDetectionsTable} builds:
         * anchored on the item treatment, with no {@code item_id} of its own.
         *
         * <p>Stated positively, as a description of the wanted shape, rather than as a list of the
         * unwanted ones. Every shape this table has had original with embeddings, item-keyed,
         * and briefly keyed by both — differs from the current one in some column, but a guard that
         * names only one of them silently passes the others through to a
         * {@code CREATE TABLE IF NOT EXISTS} that then does nothing, leaving a table whose columns
         * no longer match the inserts. That is not hypothetical: keying the guard on
         * {@code item_treatment_id} alone let the both-columns shape survive with
         * {@code item_id NOT NULL}, and every insert failed until the table was dropped by hand.
         */
        private boolean isCurrentFaceDetectionsShape() {
                return columnExists("face_detections", "item_treatment_id")
                                && !columnExists("face_detections", "item_id");
        }

        /**
         * True if the given table exists in the current schema. Compared lower-cased, as PostgreSQL
         * stores unquoted identifiers.
         */
        boolean tableExists(String tableName) {
                String sql = """
                                SELECT count(*) FROM information_schema.tables
                                WHERE table_schema = current_schema()
                                  AND lower(table_name) = ?
                                """;
                Integer count = dbAccess.queryForObject(sql, Integer.class, tableName.toLowerCase());
                return count != null && count > 0;
        }

        /**
         * True if the given table exists and has the given column. Table and column names are
         * compared lower-cased, as PostgreSQL stores unquoted identifiers.
         */
        boolean columnExists(String tableName, String columnName) {
                String sql = """
                                SELECT count(*) FROM information_schema.columns
                                WHERE table_schema = current_schema()
                                  AND lower(table_name) = ?
                                  AND lower(column_name) = ?
                                """;
                Integer count = dbAccess.queryForObject(sql, Integer.class,
                                tableName.toLowerCase(), columnName.toLowerCase());
                return count != null && count > 0;
        }

        private void createSystemSettingsTable() {
                String createTableSql = """
                                CREATE TABLE IF NOT EXISTS SYSTEM_SETTINGS (
                                    setting_name VARCHAR(128) PRIMARY KEY,
                                    value        TEXT NOT NULL,
                                    date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    date_modified TIMESTAMP
                                )
                                """;
                dbAccess.executeAdmin(createTableSql);
        }

        private void createUserSettingsTable() {
                String createTableSql = """
                                CREATE TABLE IF NOT EXISTS USER_SETTINGS (
                                        setting_name VARCHAR(128) PRIMARY KEY,
                                        value TEXT NOT NULL
                                )
                                """;
                dbAccess.executeAdmin(createTableSql);
        }

        private void createHumanUsersTable() {
                String createTableSql = """
                                CREATE TABLE IF NOT EXISTS HUMAN_USERS (
                                        id VARCHAR(16) PRIMARY KEY,
                                        nickname VARCHAR(255) NOT NULL UNIQUE,
                                        totp_secret VARCHAR(512) NOT NULL,
                                        recovery_codes VARCHAR(2048),
                                        user_level VARCHAR(20) NOT NULL,
                                        date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                        date_last_login TIMESTAMP,
                                        is_active BOOLEAN NOT NULL DEFAULT true
                                )
                                """;
                dbAccess.executeAdmin(createTableSql);

                String createNicknameIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_human_users_nickname
                                ON HUMAN_USERS (nickname)
                                """;
                dbAccess.executeAdmin(createNicknameIndexSql);
        }

        private void createSessionsTable() {
                String createTableSql = """
                                CREATE TABLE IF NOT EXISTS SESSIONS (
                                        token VARCHAR(64) PRIMARY KEY,
                                        user_id VARCHAR(16) NOT NULL REFERENCES HUMAN_USERS(id) ON DELETE CASCADE,
                                        date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                        date_expires TIMESTAMP NOT NULL,
                                        is_remember_me BOOLEAN NOT NULL DEFAULT false,
                                        is_valid BOOLEAN NOT NULL DEFAULT true
                                )
                                """;
                dbAccess.executeAdmin(createTableSql);

                String createUserIdIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_sessions_user_id
                                ON SESSIONS (user_id)
                                """;
                dbAccess.executeAdmin(createUserIdIndexSql);

                String createExpiresIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_sessions_expires
                                ON SESSIONS (date_expires)
                                WHERE is_valid = true
                                """;
                dbAccess.executeAdmin(createExpiresIndexSql);
        }

        private void createApiUsersTable() {
                String createTableSql = """
                                CREATE TABLE IF NOT EXISTS API_USERS (
                                        id VARCHAR(16) PRIMARY KEY,
                                        nickname VARCHAR(255) NOT NULL UNIQUE,
                                        key_value VARCHAR(512) NOT NULL UNIQUE,
                                        user_level VARCHAR(20) NOT NULL,
                                        created_by_user_id VARCHAR(16) REFERENCES HUMAN_USERS(id) ON DELETE SET NULL,
                                        date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                        date_expires TIMESTAMP NOT NULL,
                                        date_last_used TIMESTAMP,
                                        is_active BOOLEAN NOT NULL DEFAULT true
                                )
                                """;
                dbAccess.executeAdmin(createTableSql);

                String createNicknameIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_api_users_nickname
                                ON API_USERS (nickname)
                                """;
                dbAccess.executeAdmin(createNicknameIndexSql);
        }

        private void createApiSessionsTable() {
                String createTableSql = """
                                CREATE TABLE IF NOT EXISTS API_SESSIONS (
                                        token VARCHAR(64) PRIMARY KEY,
                                        api_user_id VARCHAR(16) NOT NULL REFERENCES API_USERS(id) ON DELETE CASCADE,
                                        date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                        date_expires TIMESTAMP NOT NULL,
                                        is_valid BOOLEAN NOT NULL DEFAULT true
                                )
                                """;
                dbAccess.executeAdmin(createTableSql);

                String createApiUserIdIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_api_sessions_api_user_id
                                ON API_SESSIONS (api_user_id)
                                """;
                dbAccess.executeAdmin(createApiUserIdIndexSql);

                String createExpiresIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_api_sessions_expires
                                ON API_SESSIONS (date_expires)
                                WHERE is_valid = true
                                """;
                dbAccess.executeAdmin(createExpiresIndexSql);
        }

        private void createSavedQueriesTable() {
                String createTableSql = """
                                CREATE TABLE IF NOT EXISTS SAVED_QUERIES (
                                        id VARCHAR(16) PRIMARY KEY,
                                        user_id VARCHAR(16) NOT NULL REFERENCES HUMAN_USERS(id) ON DELETE CASCADE,
                                        name VARCHAR(255) NOT NULL,
                                        search_string VARCHAR(1000),
                                        query_conditions JSONB NOT NULL,
                                        sort_direction VARCHAR(4) NOT NULL DEFAULT 'DESC',
                                        date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                                )
                                """;
                dbAccess.executeAdmin(createTableSql);

                String createUserIdIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_saved_queries_user_id
                                ON SAVED_QUERIES (user_id)
                                """;
                dbAccess.executeAdmin(createUserIdIndexSql);

                String createNameIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_saved_queries_name
                                ON SAVED_QUERIES (user_id, name)
                                """;
                dbAccess.executeAdmin(createNameIndexSql);
        }

        /**
         * Creates all database tables and indexes.
         */
        public void createSchema() {
                createExtensions();
                createDataSourcesTable();
                createItemsTable();
                createFoldersTable();
                createDataSourceTreatmentsTable();
                createItemTreatmentsTable();
                createItemTreatmentResultsTable();
                createItemPreviewsTable();
                createSystemSettingsTable();
                createUserSettingsTable();
                createHumanUsersTable();
                createSessionsTable();
                createApiUsersTable();
                createApiSessionsTable();
                createSavedQueriesTable();
                createFaceDetectionsTable();
                createFaceGroupsTable();
                createFaceEmbeddingsTable();
                createFaceLabelSnapshotTable();
                createSettings();
        }

        /**
         * FACE_DETECTIONS holds model-independent face geometry, owned by the FACE_DETECTION
         * treatment. Only a change of *detection* model invalidates it — embeddings and group
         * membership live elsewhere, so swapping the embedding model never forces a
         * re-detection of the whole corpus.
         *
         * <p>{@code landmarks} is 5 (x, y) float pairs = 40 bytes. Nothing reads it yet; it is
         * persisted from day one precisely so FACE_RECOGNITION can re-align without re-detecting.
         *
         * <p>{@code face_index} is the face's stable 0-based position within its item and is what
         * the /detected-face crop URL addresses. The UNIQUE constraint makes a gap or a duplicate a
         * database error instead of a silently wrong cutout.
         *
         * <p>{@code item_treatment_id} is the only link out, and it carries both meanings. It is
         * ownership: detections are the FACE_DETECTION treatment's output, exactly as
         * ITEM_TREATMENT_RESULTS rows are every other treatment's, so removing the treatment
         * cascades DATASOURCE_TREATMENTS → ITEM_TREATMENTS → here and geometry cannot outlive what
         * produced it. And it is the route to the item, which the crop endpoint reaches by joining
         * one table.
         *
         * <p>There is deliberately no {@code item_id} column. It would be a second copy of a link
         * this row already has, and nothing in SQL would keep the two agreeing: a row naming one
         * item's treatment and another item would satisfy both foreign keys and quietly serve a face
         * cropped from the wrong photo.
         *
         * <p>The cost of that choice is that UNIQUE is per item treatment rather than per item, so
         * "face 2 of item X" is unambiguous only while one treatment writes here. That holds by
         * construction — a datasource has at most one FACE_DETECTION treatment and an item belongs
         * to one datasource — and recognition's embeddings get their own table rather than more rows in
         * this one.
         */
        private void createFaceDetectionsTable() {
                String createTableSql = """
                                CREATE TABLE IF NOT EXISTS FACE_DETECTIONS (
                                    id VARCHAR(16) PRIMARY KEY,
                                    item_treatment_id VARCHAR(16) NOT NULL,
                                    face_index INTEGER NOT NULL,
                                    confidence REAL NOT NULL,
                                    bbox_x REAL,
                                    bbox_y REAL,
                                    bbox_w REAL,
                                    bbox_h REAL,
                                    landmarks BYTEA,
                                    date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    FOREIGN KEY (item_treatment_id) REFERENCES ITEM_TREATMENTS(id) ON DELETE CASCADE,
                                    UNIQUE (item_treatment_id, face_index)
                                )
                                """;
                dbAccess.executeAdmin(createTableSql);

                // The UNIQUE constraint above already indexes (item_treatment_id, face_index), which
                // serves the cascade from ITEM_TREATMENTS and every re-run's delete on its leading
                // column. No separate index is needed.
        }

        /**
         * FACE_GROUPS is instance-global identity, produced by the FACE_RECOGNITION treatment. Its
         * columns have two lifetimes: {@code label} is durable (a human name or an auto "Person N"),
         * while {@code centroid} and {@code model_hash} are a rebuildable cache in one embedding
         * model's space, cleared and rebuilt from scratch on a model change.
         *
         * <p>There is deliberately no face-count column: membership is counted from FACE_EMBEDDINGS
         * on read. A stored counter had no owner — grouping incremented it, but members also leave
         * through database cascades that run no application code — so it only ever grew, and it is
         * the weight in the running-mean centroid update. See {@code FaceGroupDao}.
         *
         * <p>There is no unique constraint on {@code label}: the label-carryover pass may hold two
         * clusters that both voted the same old name for a moment before the global assignment picks
         * the larger, and {@code renameFaceLabel} enforces its own no-collision rule in application
         * code. {@code model_hash} is a plain content-derived string, not a foreign key — it answers
         * only "is this centroid in the current model's space?", which is a string comparison.
         */
        private void createFaceGroupsTable() {
                String createTableSql = """
                                CREATE TABLE IF NOT EXISTS FACE_GROUPS (
                                    id VARCHAR(16) PRIMARY KEY,
                                    label VARCHAR(255) NOT NULL,
                                    centroid BYTEA,
                                    model_hash VARCHAR(64),
                                    date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    date_modified TIMESTAMP
                                )
                                """;
                dbAccess.executeAdmin(createTableSql);

                // Grouping loads every current-model group each assignment and renameFaceLabel looks
                // one up by label; a non-unique index on label serves the latter without forbidding
                // the transient duplicates label carryover produces.
                dbAccess.executeAdmin("""
                                CREATE INDEX IF NOT EXISTS idx_face_groups_label
                                ON FACE_GROUPS (label)
                                """);
        }

        /**
         * FACE_EMBEDDINGS is a rebuildable per-face cache owned by the FACE_RECOGNITION treatment.
         *
         * <p>Anchored on {@code (item_treatment_id, face_index)} of the <em>recognition</em>
         * treatment — mirroring FACE_DETECTIONS — not on a detection-row id. Detection rows are
         * delete-and-reinserted with fresh ids on every run, so a foreign key into them would
         * cascade every embedding away on an ordinary rescan. The link to a face's geometry is
         * logical: the same {@code face_index}, resolved through the shared item.
         *
         * <p>{@code face_group_id} is a soft reference with no foreign key, like {@code model_hash}:
         * a total regroup clears FACE_GROUPS wholesale and re-drives every recognition row, so a
         * cascading or restricting constraint here would either delete the embeddings being rebuilt
         * or block the clear. {@code model_hash} tags the embedding space so grouping compares only
         * within the current model during a regroup drain.
         */
        private void createFaceEmbeddingsTable() {
                String createTableSql = """
                                CREATE TABLE IF NOT EXISTS FACE_EMBEDDINGS (
                                    id VARCHAR(16) PRIMARY KEY,
                                    item_treatment_id VARCHAR(16) NOT NULL,
                                    face_index INTEGER NOT NULL,
                                    face_group_id VARCHAR(16),
                                    embedding BYTEA NOT NULL,
                                    model_hash VARCHAR(64) NOT NULL,
                                    date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    FOREIGN KEY (item_treatment_id) REFERENCES ITEM_TREATMENTS(id) ON DELETE CASCADE,
                                    UNIQUE (item_treatment_id, face_index)
                                )
                                """;
                dbAccess.executeAdmin(createTableSql);

                // Group membership is read per group during the post-drain propagation pass and when
                // re-linking a re-added datasource's faces to still-valid groups.
                dbAccess.executeAdmin("""
                                CREATE INDEX IF NOT EXISTS idx_face_embeddings_group
                                ON FACE_EMBEDDINGS (face_group_id)
                                """);
        }

        /**
         * FACE_LABEL_SNAPSHOT is transient scaffolding that exists only during a model change, to
         * carry human-typed labels across the total regroup by membership overlap.
         *
         * <p>The picker mutation records {@code (item_treatment_id, face_index) -> label} for every
         * face in a human-named group before anything re-embeds; the post-drain propagation pass
         * reads it to give each new cluster its plurality old label, then drops every row. The key
         * is stable across the change because detection is untouched — which is the whole reason
         * overlap is computable without a per-face anchor.
         */
        private void createFaceLabelSnapshotTable() {
                String createTableSql = """
                                CREATE TABLE IF NOT EXISTS FACE_LABEL_SNAPSHOT (
                                    item_treatment_id VARCHAR(16) NOT NULL,
                                    face_index INTEGER NOT NULL,
                                    label VARCHAR(255) NOT NULL,
                                    PRIMARY KEY (item_treatment_id, face_index)
                                )
                                """;
                dbAccess.executeAdmin(createTableSql);
        }

        void createSettings() {
                // Set default for ConcurrentTreatmentProcessors if not present
                int cpuCount = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
                setDefaultUserSetting(UserSettingName.CONCURRENT_TREATMENT_PROCESSORS.name(), String.valueOf(cpuCount));
                // Set default for ConcurrentOCRHandlers if not present
                setDefaultUserSetting(UserSettingName.CONCURRENT_OCR_HANDLERS.name(), "2");
                // Set default for ConcurrentObjectDetectionHandlers if not present
                setDefaultUserSetting(UserSettingName.CONCURRENT_OBJECT_DETECTION_HANDLERS.name(), "2");
                // Set default for ConcurrentFaceDetectionHandlers if not present
                setDefaultUserSetting(UserSettingName.CONCURRENT_FACE_DETECTION_HANDLERS.name(), "2");
                // Set default for ConcurrentFaceRecognitionHandlers if not present
                setDefaultUserSetting(UserSettingName.CONCURRENT_FACE_RECOGNITION_HANDLERS.name(), "2");
                // Set default for FaceDetectionConfidenceThreshold if not present
                setDefaultUserSetting(UserSettingName.FACE_DETECTION_CONFIDENCE_THRESHOLD.name(), "0.7");
                // Set default for FaceRecognitionMinConfidence if not present. Above the detection
                // floor on purpose: detect generously, identify conservatively.
                setDefaultUserSetting(UserSettingName.FACE_RECOGNITION_MIN_CONFIDENCE.name(),
                        Float.toString(FaceRecognitionTreatmentHandler.DEFAULT_MIN_CONFIDENCE));
                // Set default for TreatmentMaxFailureCount if not present
                setDefaultUserSetting(UserSettingName.TREATMENT_MAX_FAILURE_COUNT.name(), "4");
                // Set default session duration (24 hours in minutes)
                setDefaultUserSetting(UserSettingName.SESSION_DURATION_MINUTES.name(), "1440");
                // Set default "remember me" duration (30 days)
                setDefaultUserSetting(UserSettingName.REMEMBER_ME_DURATION_DAYS.name(), "30");
                // Set default API session duration (60 minutes)
                setDefaultUserSetting(UserSettingName.API_SESSION_DURATION_MINUTES.name(), "60");

                // AI provider defaults live in SYSTEM_SETTINGS.
                setDefaultSystemSetting(AiSettingName.AI_PROVIDER.name(), "NONE");
                setDefaultSystemSetting(AiSettingName.OLLAMA_API_URL.name(), "");
                setDefaultSystemSetting(AiSettingName.OLLAMA_MODEL_NAME.name(), "");
                setDefaultSystemSetting(AiSettingName.OLLAMA_API_CODE.name(), "");
                setDefaultSystemSetting(AiSettingName.OPENAI_API_KEY.name(), "");
                setDefaultSystemSetting(AiSettingName.OPENAI_MODEL_NAME.name(), "gpt-4o-mini");
                setDefaultSystemSetting(AiSettingName.OPENAI_BASE_URL.name(), "");
                setDefaultSystemSetting(AiSettingName.ANTHROPIC_API_KEY.name(), "");
                setDefaultSystemSetting(AiSettingName.ANTHROPIC_MODEL_NAME.name(), "claude-sonnet-4-20250514");
                setDefaultSystemSetting(AiSettingName.ANTHROPIC_BASE_URL.name(), "");

                // UI settings defaults
                setDefaultUserSetting(UserSettingName.BANNER.name(), "");

                // Remote access defaults
                setDefaultSystemSetting("RemoteAccessEnabled", "false");
                setDefaultSystemSetting("RemoteAccessLastConnected", "");
        }

        /**
         * Sets a default value for a system setting if it does not already exist.
         *
         * @param settingName  the name of the setting
         * @param defaultValue the value to insert if not present
         */
        private void setDefaultSystemSetting(String settingName, String defaultValue) {
                String checkSql = "SELECT COUNT(*) FROM SYSTEM_SETTINGS WHERE setting_name = ?";
                Integer count = dbAccess.queryForObject(checkSql, Integer.class, settingName);
                if (count == null || count == 0) {
                        String insertSql = "INSERT INTO SYSTEM_SETTINGS (setting_name, value) VALUES (?, ?)";
                        dbAccess.update(insertSql, settingName, defaultValue);
                }
        }

        /**
         * Sets a default value for a user setting if it does not already exist.
         *
         * @param settingName  the name of the setting
         * @param defaultValue the value to insert if not present
         */
        private void setDefaultUserSetting(String settingName, String defaultValue) {
                String checkSql = "SELECT COUNT(*) FROM USER_SETTINGS WHERE setting_name = ?";
                Integer count = dbAccess.queryForObject(checkSql, Integer.class, settingName);
                if (count == null || count == 0) {
                        String insertSql = "INSERT INTO USER_SETTINGS (setting_name, value) VALUES (?, ?)";
                        dbAccess.update(insertSql, settingName, defaultValue);
                }
        }

        /**
         * Migrate unencrypted datasource configurations to encrypted format.
         * Only runs if encryption is enabled and there are unencrypted datasources.
         */
        public void migrateUnencryptedDataSources() {
                if (!configEncryptor.isEncryptionEnabled()) {
                        logger.info("Encryption not enabled, skipping datasource config migration");
                        return;
                }

                // Query raw config values to check encryption status
                String sql = "SELECT id, config::text as config FROM DATA_SOURCES";
                var unencryptedIds = dbAccess.query(sql, (rs, rowNum) -> {
                        String id = rs.getString("id");
                        String config = rs.getString("config");
                        // Check if config is not encrypted
                        // Encrypted configs are wrapped in JSON: {"encryptedValue": "ENC:..."}
                        if (config != null && !config.contains("\"encryptedValue\"")) {
                                return id;
                        }
                        return null;
                }).stream().filter(id -> id != null).toList();

                if (unencryptedIds.isEmpty()) {
                        logger.info("All datasource configurations are already encrypted");
                        return;
                }

                logger.info("Found {} datasource(s) with unencrypted configuration, migrating...", unencryptedIds.size());

                int migrated = 0;
                for (String id : unencryptedIds) {
                        try {
                                // Read the datasource (decrypts if needed, but these are plaintext)
                                var optDs = dataSourceDao.findById(id);
                                if (optDs.isPresent()) {
                                        DataSourceInterface ds = optDs.get();
                                        // Update triggers re-serialization with encryption
                                        dataSourceDao.update(id, ds);
                                        migrated++;
                                }
                        } catch (Exception e) {
                                logger.error("Failed to migrate datasource {}: {}", id, e.getMessage());
                        }
                }

                logger.info("Successfully migrated {} datasource configuration(s) to encrypted format", migrated);
        }

        /**
         * Reset any datasources stuck in SCANNING status to IDLE and
         * reset any item treatments stuck in INPROGRESS status to FAILED.
         * This handles cases where the server crashed or was killed during processing,
         * leaving records in an inconsistent state.
         */
        private void addFailureCountColumn() {
                dbAccess.executeAdmin("""
                                ALTER TABLE ITEM_TREATMENTS
                                ADD COLUMN IF NOT EXISTS failure_count INTEGER NOT NULL DEFAULT 0
                                """);
        }

        /**
         * Counter of consecutive failed attempts to list a folder during a scan, mirroring
         * ITEM_TREATMENTS.failure_count one level up. Reset to 0 whenever the folder lists
         * successfully, so a non-zero value means the folder's contents are currently stale.
         */
        private void addFolderScanFailureCountColumn() {
                dbAccess.executeAdmin("""
                                ALTER TABLE FOLDERS
                                ADD COLUMN IF NOT EXISTS scan_failure_count INTEGER NOT NULL DEFAULT 0
                                """);
        }

        /**
         * End every API session created under the old rule, which let a session live as long as its
         * key (up to two years). Sessions now last at most
         * {@link AuthenticationService#MAX_API_SESSION_DURATION_MINUTES}, so a valid session longer
         * than that can only be an old one; clients holding one log in again.
         *
         * <p>Guarded on that shape, which the migration itself destroys, per the idiom of the other
         * migrations here. Old sessions shorter than the bound are left alone: they are short already.
         */
        void invalidateLongLivedApiSessions() {
                int invalidated = dbAccess.update("""
                                UPDATE API_SESSIONS SET is_valid = false
                                WHERE is_valid = true
                                  AND date_expires > date_created + make_interval(mins => ?)
                                """, AuthenticationService.MAX_API_SESSION_DURATION_MINUTES);
                if (invalidated > 0) {
                        logger.info("Invalidated {} API session(s) longer than {} minutes", invalidated,
                                        AuthenticationService.MAX_API_SESSION_DURATION_MINUTES);
                }
        }

        private void addItrKeyValueIndex() {
                dbAccess.executeAdmin("""
                                CREATE INDEX IF NOT EXISTS idx_item_treatment_results_key_value
                                ON ITEM_TREATMENT_RESULTS (key, value)
                                WHERE length(value) <= 2000
                                """);
        }

        /**
         * Carry a pre-existing grouping threshold from USER_SETTINGS to SYSTEM_SETTINGS, where it now
         * lives beside the embedding model config. Deployed installs already carry the old row —
         * FaceRecognitionConfidenceThreshold survived even with no reader — and its meaning
         * is unchanged, so an operator's tuned value follows the setting rather than being reset.
         *
         * <p>Guarded on the old row's presence, which the migration itself removes, per the idiom of
         * the other migrations here.
         */
        private void moveGroupingThresholdToSystemSettings() {
                String legacyName = "FACE_RECOGNITION_CONFIDENCE_THRESHOLD";
                List<String> values = dbAccess.query(
                                "SELECT value FROM USER_SETTINGS WHERE setting_name = ?",
                                (rs, rowNum) -> rs.getString("value"), legacyName);
                if (values.isEmpty()) {
                        return;
                }

                dbAccess.update("""
                                INSERT INTO SYSTEM_SETTINGS (setting_name, value)
                                VALUES (?, ?)
                                ON CONFLICT (setting_name) DO NOTHING
                                """, "FaceGroupingThreshold", values.getFirst());
                dbAccess.update("DELETE FROM USER_SETTINGS WHERE setting_name = ?", legacyName);
                logger.info("Moved grouping threshold {} from USER_SETTINGS to SYSTEM_SETTINGS",
                                values.getFirst());
        }

        /**
         * Move AI provider settings from USER_SETTINGS to SYSTEM_SETTINGS.
         *
         * <p>Guarded on old-row presence, per the same one-shot idiom as other migrations in this
         * class. Values are copied as-is: encrypted API keys remain encrypted and plaintext remains
         * plaintext, preserving backward compatibility with ConfigEncryptor's decrypt fallback.
         */
        void moveAiSettingsToSystemSettings() {
                List<String> aiSettingNames = java.util.Arrays.stream(AiSettingName.values()).map(Enum::name).toList();
                String placeholders = String.join(",", aiSettingNames.stream().map(name -> "?").toList());

                Integer legacyCount = dbAccess.queryForObject(
                                "SELECT COUNT(*) FROM USER_SETTINGS WHERE setting_name IN (" + placeholders + ")",
                                Integer.class,
                                aiSettingNames.toArray());
                if (legacyCount == null || legacyCount == 0) {
                        return;
                }

                dbAccess.update("""
                                INSERT INTO SYSTEM_SETTINGS (setting_name, value)
                                SELECT setting_name, value
                                FROM USER_SETTINGS
                                WHERE setting_name IN (%s)
                                ON CONFLICT (setting_name) DO UPDATE
                                SET value = EXCLUDED.value, date_modified = CURRENT_TIMESTAMP
                                """.formatted(placeholders), aiSettingNames.toArray());

                int deleted = dbAccess.update(
                                "DELETE FROM USER_SETTINGS WHERE setting_name IN (" + placeholders + ")",
                                aiSettingNames.toArray());

                logger.info("Moved {} AI setting row(s) from USER_SETTINGS to SYSTEM_SETTINGS", deleted);
        }

        /**
         * Drop the drifted {@code FACE_GROUPS.face_count} column; membership is counted from
         * FACE_EMBEDDINGS on read instead. Idempotent, and safe on a table that never had it.
         */
        private void dropFaceGroupCountColumn() {
                if (!columnExists("face_groups", "face_count")) {
                        return;
                }
                dbAccess.executeAdmin("ALTER TABLE FACE_GROUPS DROP COLUMN face_count");
                logger.info("Dropped FACE_GROUPS.face_count; group membership is now counted on read");
        }

        private void removeTreatmentBatchSizeSetting() {
                int deleted = dbAccess.update(
                        "DELETE FROM USER_SETTINGS WHERE setting_name = 'TREATMENT_BATCH_SIZE'");
                if (deleted > 0) {
                        logger.info("Removed obsolete TREATMENT_BATCH_SIZE user setting");
                }
        }

        void removeInapplicableItemTreatments() {
                // OCR applies to images and PDFs only
                int ocrDeleted = dbAccess.update("""
                                DELETE FROM item_treatments
                                USING datasource_treatments, items
                                WHERE item_treatments.datasource_treatment_id = datasource_treatments.id
                                  AND item_treatments.item_id = items.id
                                  AND datasource_treatments.treatment = 'OCR'
                                  AND lower(items.name) !~ '\\.(jpg|jpeg|png|gif|bmp|tiff|tif|pdf)$'
                                """);

                // OBJECT_DETECTION, FACE_RECOGNITION and FACE_DETECTION apply to images only.
                // FACE_RECOGNITION is kept in the list so any row left over from before FACE_RECOGNITION change is
                // still cleaned up; the treatment itself no longer exists.
                int imageOnlyDeleted = dbAccess.update("""
                                DELETE FROM item_treatments
                                USING datasource_treatments, items
                                WHERE item_treatments.datasource_treatment_id = datasource_treatments.id
                                  AND item_treatments.item_id = items.id
                                  AND datasource_treatments.treatment IN ('OBJECT_DETECTION', 'FACE_RECOGNITION', 'FACE_DETECTION')
                                  AND lower(items.name) !~ '\\.(jpg|jpeg|png|gif|bmp|tiff|tif)$'
                                """);

                int total = ocrDeleted + imageOnlyDeleted;
                if (total > 0) {
                        logger.info("Removed {} inapplicable item treatment(s): {} OCR, {} image-only treatments",
                                        total, ocrDeleted, imageOnlyDeleted);
                }
        }

        private void resetStuckDataSources() {
                // Reset datasources stuck in SCANNING
                int datasourcesReset = dataSourceDao.resetScanningToIdle();
                if (datasourcesReset > 0) {
                        logger.warn("Reset {} datasource(s) from SCANNING to IDLE status on startup", datasourcesReset);
                } else {
                        logger.info("No datasources stuck in SCANNING status");
                }

                // Reset item treatments stuck in INPROGRESS
                int treatmentsReset = treatmentDao.resetInProgressToFailed();
                if (treatmentsReset > 0) {
                        logger.warn("Reset {} item treatment(s) from INPROGRESS to FAILED status on startup", treatmentsReset);
                } else {
                        logger.info("No item treatments stuck in INPROGRESS status");
                }
        }

        /**
         * Creates required PostgreSQL extensions.
         */
        private void createExtensions() {
                // Enable pg_trgm extension for trigram-based text search (ILIKE optimization)
                String enableTrgmExtensionSql = """
                                CREATE EXTENSION IF NOT EXISTS pg_trgm
                                """;
                dbAccess.executeAdmin(enableTrgmExtensionSql);

                // Enable pg_stat_statements for query performance monitoring
                // Tracks execution statistics of all SQL statements
                // Query with: SELECT * FROM pg_stat_statements ORDER BY total_exec_time DESC;
                String enableStatStatementsSql = """
                                CREATE EXTENSION IF NOT EXISTS pg_stat_statements
                                """;
                try {
                        dbAccess.executeAdmin(enableStatStatementsSql);
                } catch (Exception e) {
                        // pg_stat_statements requires shared_preload_libraries configuration
                        // If not configured, log warning but don't fail startup
                        org.slf4j.LoggerFactory.getLogger(Schema.class)
                                        .warn("Could not enable pg_stat_statements extension. " +
                                                        "To enable, add 'pg_stat_statements' to shared_preload_libraries in postgresql.conf");
                }
        }

        private void createDataSourcesTable() {
                String createTableSql = """
                                CREATE TABLE IF NOT EXISTS DATA_SOURCES (
                                    id VARCHAR(16) PRIMARY KEY,
                                    name VARCHAR(255) NOT NULL,
                                    cron_pattern VARCHAR(255),
                                    date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    date_last_scanned TIMESTAMP,
                                    type VARCHAR(50) NOT NULL,
                                    config JSONB NOT NULL,
                                    status VARCHAR(20) NOT NULL DEFAULT 'IDLE'
                                )
                                """;
                dbAccess.executeAdmin(createTableSql);

                String createUniqueIndexSql = """
                                CREATE UNIQUE INDEX IF NOT EXISTS idx_data_sources_name_type
                                ON DATA_SOURCES (name, type)
                                """;
                dbAccess.executeAdmin(createUniqueIndexSql);
        }

        private void createItemsTable() {
                String createTableSql = """
                                CREATE TABLE IF NOT EXISTS ITEMS (
                                    id VARCHAR(16) PRIMARY KEY,
                                    datasource_id VARCHAR(16) NOT NULL,
                                    name TEXT NOT NULL,
                                    path TEXT,
                                    time_created TIMESTAMP,
                                    time_modified TIMESTAMP,
                                    size_bytes BIGINT,
                                    content_hash VARCHAR(64),
                                    mime_type VARCHAR(100),
                                    date_scanned TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    FOREIGN KEY (datasource_id) REFERENCES DATA_SOURCES(id) ON DELETE CASCADE
                                )
                                """;
                dbAccess.executeAdmin(createTableSql);

                String createIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_items_datasource_name
                                ON ITEMS (datasource_id, name)
                                """;
                dbAccess.executeAdmin(createIndexSql);

                String createPathIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_items_datasource_parent_path
                                ON ITEMS (datasource_id, path)
                                """;
                dbAccess.executeAdmin(createPathIndexSql);

                String createUniqueIndexSql = """
                                CREATE UNIQUE INDEX IF NOT EXISTS idx_items_unique_path
                                ON ITEMS (datasource_id, path, name)
                                """;
                dbAccess.executeAdmin(createUniqueIndexSql);

                String createTimeModifiedIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_items_time_modified
                                ON ITEMS (time_modified DESC)
                                """;
                dbAccess.executeAdmin(createTimeModifiedIndexSql);

                String createDatasourceIdIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_items_datasource_id
                                ON ITEMS (datasource_id)
                                """;
                dbAccess.executeAdmin(createDatasourceIdIndexSql);

                String createItemsNameTrgmIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_items_name_trgm
                                ON ITEMS USING GIN (name gin_trgm_ops)
                                """;
                dbAccess.executeAdmin(createItemsNameTrgmIndexSql);

                String createItemsPathTrgmIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_items_path_trgm
                                ON ITEMS USING GIN (path gin_trgm_ops)
                                """;
                dbAccess.executeAdmin(createItemsPathTrgmIndexSql);
        }

        private void createFoldersTable() {
                String createTableSql = """
                                CREATE TABLE IF NOT EXISTS FOLDERS (
                                    id VARCHAR(16) PRIMARY KEY,
                                    datasource_id VARCHAR(16) NOT NULL,
                                    name TEXT NOT NULL,
                                    path TEXT,
                                    time_created TIMESTAMP,
                                    time_modified TIMESTAMP,
                                    date_scanned TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    FOREIGN KEY (datasource_id) REFERENCES DATA_SOURCES(id) ON DELETE CASCADE
                                )
                                """;
                dbAccess.executeAdmin(createTableSql);

                String createIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_folders_datasource_name
                                ON FOLDERS (datasource_id, name)
                                """;
                dbAccess.executeAdmin(createIndexSql);

                String createPathIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_folders_datasource_parent_path
                                ON FOLDERS (datasource_id, path)
                                """;
                dbAccess.executeAdmin(createPathIndexSql);

                String createUniqueIndexSql = """
                                CREATE UNIQUE INDEX IF NOT EXISTS idx_folders_unique_path
                                ON FOLDERS (datasource_id, path, name)
                                """;
                dbAccess.executeAdmin(createUniqueIndexSql);
        }

        private void createDataSourceTreatmentsTable() {
                String createTableSql = """
                                CREATE TABLE IF NOT EXISTS DATASOURCE_TREATMENTS (
                                    id VARCHAR(16) PRIMARY KEY,
                                    datasource_id VARCHAR(16) NOT NULL,
                                    treatment VARCHAR(50) NOT NULL,
                                    config JSONB,
                                    date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    date_modified TIMESTAMP,
                                    FOREIGN KEY (datasource_id) REFERENCES DATA_SOURCES(id) ON DELETE CASCADE
                                )
                                """;
                dbAccess.executeAdmin(createTableSql);

                String createIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_datasource_treatments_datasource_id
                                ON DATASOURCE_TREATMENTS (datasource_id)
                                """;
                dbAccess.executeAdmin(createIndexSql);

                String createUniqueIndexSql = """
                                CREATE UNIQUE INDEX IF NOT EXISTS idx_datasource_treatments_unique
                                ON DATASOURCE_TREATMENTS (datasource_id, treatment)
                                """;
                dbAccess.executeAdmin(createUniqueIndexSql);
        }

        private void createItemTreatmentsTable() {
                String createTableSql = """
                                CREATE TABLE IF NOT EXISTS ITEM_TREATMENTS (
                                    id VARCHAR(16) PRIMARY KEY,
                                    datasource_treatment_id VARCHAR(16) NOT NULL,
                                    item_id VARCHAR(16) NOT NULL,
                                    status VARCHAR(20) NOT NULL,
                                    date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    date_modified TIMESTAMP,
                                    version INTEGER DEFAULT 1,
                                    FOREIGN KEY (datasource_treatment_id) REFERENCES DATASOURCE_TREATMENTS(id) ON DELETE CASCADE,
                                    FOREIGN KEY (item_id) REFERENCES ITEMS(id) ON DELETE CASCADE
                                )
                                """;
                dbAccess.executeAdmin(createTableSql);

                String createDsTreatmentIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_item_treatments_ds_treatment
                                ON ITEM_TREATMENTS (datasource_treatment_id)
                                """;
                dbAccess.executeAdmin(createDsTreatmentIndexSql);

                String createItemIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_item_treatments_item_id
                                ON ITEM_TREATMENTS (item_id)
                                """;
                dbAccess.executeAdmin(createItemIndexSql);

                String createStatusIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_item_treatments_status
                                ON ITEM_TREATMENTS (status)
                                """;
                dbAccess.executeAdmin(createStatusIndexSql);

                String createStatusItemIdIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_item_treatments_status_item_id
                                ON ITEM_TREATMENTS (status, item_id)
                                """;
                dbAccess.executeAdmin(createStatusItemIdIndexSql);

                // The claim query (TreatmentDao.claimItemsForProcessing) filters on the pending
                // statuses and orders by item_id. Neither index above can serve both: status alone
                // cannot produce item_id order, and (status, item_id) yields one ordered range per
                // status value, which Postgres will not merge into a single ordered stream for a
                // three-value IN. Without this it walks idx_item_treatments_item_id over the whole
                // table and discards every non-pending row — and gets *slower* as the queue drains,
                // since pending rows grow sparser in item_id order. Partial keeps it to the pending
                // backlog, so it shrinks as work completes.
                // NB: the predicate repeats the claim query's status list literally. Change one and
                // the index silently stops matching, reverting the plan with no error.
                String createPendingItemIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_item_treatments_pending_item
                                ON ITEM_TREATMENTS (item_id)
                                WHERE status IN ('NEW', 'UPDATED', 'FAILED')
                                """;
                dbAccess.executeAdmin(createPendingItemIndexSql);

                String createUniqueIndexSql = """
                                CREATE UNIQUE INDEX IF NOT EXISTS idx_item_treatments_unique
                                ON ITEM_TREATMENTS (datasource_treatment_id, item_id)
                                """;
                dbAccess.executeAdmin(createUniqueIndexSql);

                String createCoveringIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_item_treatments_id_item_id
                                ON ITEM_TREATMENTS (id, item_id)
                                """;
                dbAccess.executeAdmin(createCoveringIndexSql);
        }

        private void createItemTreatmentResultsTable() {
                String createTableSql = """
                                CREATE TABLE IF NOT EXISTS ITEM_TREATMENT_RESULTS (
                                    id VARCHAR(16) PRIMARY KEY,
                                    item_treatment_id VARCHAR(16) NOT NULL,
                                    key TEXT NOT NULL,
                                    value TEXT NOT NULL,
                                    date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    FOREIGN KEY (item_treatment_id) REFERENCES ITEM_TREATMENTS(id) ON DELETE CASCADE
                                )
                                """;
                dbAccess.executeAdmin(createTableSql);

                String createItemTreatmentIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_item_treatment_results_item_treatment_id
                                ON ITEM_TREATMENT_RESULTS (item_treatment_id)
                                """;
                dbAccess.executeAdmin(createItemTreatmentIndexSql);

                String createKeyIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_item_treatment_results_key
                                ON ITEM_TREATMENT_RESULTS (key)
                                """;
                dbAccess.executeAdmin(createKeyIndexSql);

                String createKeyAggIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_item_treatment_results_treatment_key
                                ON ITEM_TREATMENT_RESULTS (item_treatment_id, key)
                                """;
                dbAccess.executeAdmin(createKeyAggIndexSql);

                String createValueHashIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_item_treatment_results_value_hash
                                ON ITEM_TREATMENT_RESULTS USING HASH (value)
                                """;
                dbAccess.executeAdmin(createValueHashIndexSql);

                String createFacetIndexSql = Objects.requireNonNull(String.format("""
                                CREATE INDEX IF NOT EXISTS idx_itr_facets
                                ON ITEM_TREATMENT_RESULTS (key)
                                WHERE key NOT IN ('%s', '%s')
                                """, FIELD_NAME, FIELD_PATH));
                dbAccess.executeAdmin(createFacetIndexSql);

                String createMetadataTrgmIndexSql = """
                                CREATE INDEX IF NOT EXISTS idx_item_treatment_results_value_trgm
                                ON ITEM_TREATMENT_RESULTS USING GIN (value gin_trgm_ops)
                                """;
                dbAccess.executeAdmin(createMetadataTrgmIndexSql);

                createItemTreatmentResultsContentTable();
        }

        private void createItemTreatmentResultsContentTable() {
                String createTableSql = """
                        CREATE TABLE IF NOT EXISTS ITEM_TREATMENT_RESULTS_CONTENT (
                            id VARCHAR(16) PRIMARY KEY,
                            item_treatment_id VARCHAR(16) NOT NULL,
                            key TEXT NOT NULL,
                            value TEXT NOT NULL,
                            date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            value_tsv tsvector,
                            FOREIGN KEY (item_treatment_id) REFERENCES ITEM_TREATMENTS(id) ON DELETE CASCADE
                        )
                        """;
                dbAccess.executeAdmin(createTableSql);

                String createTsvectorFunctionSql = Objects.requireNonNull(String.format("""
                        CREATE OR REPLACE FUNCTION item_treatment_results_content_tsv_trigger() RETURNS trigger AS $$
                        BEGIN
                          IF length(NEW.value) > 0 THEN
                            NEW.value_tsv := to_tsvector('simple', left(NEW.value, %d));
                          ELSE
                            NEW.value_tsv := NULL;
                          END IF;
                          RETURN NEW;
                        END
                        $$ LANGUAGE plpgsql
                        """, TreatmentUtils.FTS_CHUNK_SIZE));
                dbAccess.executeAdmin(createTsvectorFunctionSql);

                String createTsvectorTriggerSql = """
                        DROP TRIGGER IF EXISTS tsvector_update ON ITEM_TREATMENT_RESULTS_CONTENT;
                        CREATE TRIGGER tsvector_update
                        BEFORE INSERT OR UPDATE ON ITEM_TREATMENT_RESULTS_CONTENT
                        FOR EACH ROW EXECUTE FUNCTION item_treatment_results_content_tsv_trigger()
                        """;
                dbAccess.executeAdmin(createTsvectorTriggerSql);

                String createItemTreatmentIndexSql = """
                        CREATE INDEX IF NOT EXISTS idx_itr_content_item_treatment_id
                        ON ITEM_TREATMENT_RESULTS_CONTENT (item_treatment_id)
                        """;
                dbAccess.executeAdmin(createItemTreatmentIndexSql);

                String createKeyIndexSql = """
                        CREATE INDEX IF NOT EXISTS idx_itr_content_key
                        ON ITEM_TREATMENT_RESULTS_CONTENT (key)
                        """;
                dbAccess.executeAdmin(createKeyIndexSql);

                String createTreatmentKeyIndexSql = """
                        CREATE INDEX IF NOT EXISTS idx_itr_content_treatment_key
                        ON ITEM_TREATMENT_RESULTS_CONTENT (item_treatment_id, key)
                        """;
                dbAccess.executeAdmin(createTreatmentKeyIndexSql);

                String createTsvectorIndexSql = """
                        CREATE INDEX IF NOT EXISTS idx_itr_content_value_tsv
                        ON ITEM_TREATMENT_RESULTS_CONTENT USING GIN (value_tsv)
                        WHERE value_tsv IS NOT NULL
                        """;
                dbAccess.executeAdmin(createTsvectorIndexSql);
        }

        private void createItemPreviewsTable() {
                String createTableSql = """
                        CREATE TABLE IF NOT EXISTS ITEM_PREVIEWS (
                            item_id VARCHAR(16) PRIMARY KEY,
                            status VARCHAR(20) NOT NULL,
                            preview_data BYTEA,
                            content_type VARCHAR(100),
                            date_created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                            date_modified TIMESTAMP,
                            version INTEGER DEFAULT 1
                        )
                        """;
                dbAccess.executeAdmin(createTableSql);

                String createStatusIndexSql = """
                        CREATE INDEX IF NOT EXISTS idx_item_previews_status
                        ON ITEM_PREVIEWS (status)
                        """;
                dbAccess.executeAdmin(createStatusIndexSql);

                // Clean up orphaned previews and add cascade delete FK
                dbAccess.update("DELETE FROM ITEM_PREVIEWS WHERE item_id NOT IN (SELECT id FROM ITEMS)");
                try {
                        dbAccess.executeAdmin("""
                                ALTER TABLE ITEM_PREVIEWS
                                ADD CONSTRAINT fk_item_previews_item_id
                                FOREIGN KEY (item_id) REFERENCES ITEMS(id) ON DELETE CASCADE
                                """);
                } catch (Exception e) {
                        // Constraint already exists
                }
        }
}
