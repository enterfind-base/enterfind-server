package enterfind.server.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import enterfind.server.exception.SystemIssueException;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceStats;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.DataSourceType;
import enterfind.server.model.ItemTreatmentStatus;
import enterfind.server.model.TreatmentStatusCount;
import enterfind.server.scan.Treatments;
import enterfind.server.security.ConfigEncryptor;
import enterfind.server.util.Utilities;

/**
 * Single DAO class for all datasource database operations using Spring JDBC
 */
@Repository
public class DataSourceDao {

    private static record DataSourceInfo(DataSourceStatus status, LocalDateTime dateLastScanned) {}

    private final DBAccess dbAccess;
    private final ObjectMapper objectMapper;
    private final TreatmentDao treatmentDao;
    private final ConfigEncryptor configEncryptor;
    private final ItemDao itemDao;

    public DataSourceDao(DBAccess dbAccess, ObjectMapper objectMapper, TreatmentDao treatmentDao,
            ConfigEncryptor configEncryptor, ItemDao itemDao) {
        this.dbAccess = dbAccess;
        this.objectMapper = objectMapper;
        this.treatmentDao = treatmentDao;
        this.configEncryptor = configEncryptor;
        this.itemDao = itemDao;
    }

    /**
     * Create a new datasource
     */
    public DataSourceInterface create(DataSourceInterface dataSource) {
        String id = Utilities.generateHexId();
        LocalDateTime dateCreated = LocalDateTime.now();

        String sql = """
            INSERT INTO DATA_SOURCES (id, name, cron_pattern, type, config, date_created, status)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
            """;

        String configJson = serializeConfig(dataSource);

        dbAccess.update(sql, id, dataSource.getName(), dataSource.getCronPattern(),
            dataSource.getType().name(), configJson, dateCreated, dataSource.getStatus().name());

        // Add default NAME_AND_PATH treatment for basic search functionality
        treatmentDao.addTreatment(id, Treatments.NAME_AND_PATH, null);

        return createDataSourceWithId(dataSource, id, dateCreated, null, dataSource.getStatus());
    }

    /**
     * Find datasource by ID
     */
    public Optional<DataSourceInterface> findById(String id) {
        String sql = """
            SELECT id, name, cron_pattern, date_created, date_last_scanned, status, type, config
            FROM DATA_SOURCES
            WHERE id = ?
            """;

        try {
            DataSourceInterface dataSource = dbAccess.queryForObject(sql, new DataSourceRowMapper(), id);
            return Optional.ofNullable(dataSource);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Find datasource by name and type
     */
    public Optional<DataSourceInterface> findByNameAndType(String name, DataSourceType type) {
        String sql = """
            SELECT id, name, cron_pattern, date_created, date_last_scanned, status, type, config
            FROM DATA_SOURCES
            WHERE name = ? AND type = ?
            """;

        try {
            DataSourceInterface dataSource = dbAccess.queryForObject(sql, new DataSourceRowMapper(), name, type.name());
            return Optional.ofNullable(dataSource);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Find all datasources
     */
    public List<DataSourceInterface> findAll() {
        String sql = """
            SELECT id, name, cron_pattern, date_created, date_last_scanned, status, type, config
            FROM DATA_SOURCES
            ORDER BY date_created DESC
            """;

        return dbAccess.query(sql, new DataSourceRowMapper());
    }

    /**
     * Update an existing datasource by ID
     */
    public Optional<DataSourceInterface> update(String id, DataSourceInterface dataSource) {
        String sql = """
            UPDATE DATA_SOURCES
            SET name = ?, cron_pattern = ?, type = ?, config = ?::jsonb
            WHERE id = ?
            """;

        int rowsAffected = dbAccess.update(sql,
            dataSource.getName(),
            dataSource.getCronPattern(),
            dataSource.getType().name(),
            serializeConfig(dataSource),
            id);

        if (rowsAffected > 0) {
            return findById(id);
        }
        return Optional.empty();
    }

    /**
     * Update an existing datasource by name and type
     */
    public Optional<DataSourceInterface> updateByNameAndType(String name, DataSourceType type, DataSourceInterface dataSource) {
        String sql = """
            UPDATE DATA_SOURCES
            SET name = ?, cron_pattern = ?, type = ?, config = ?::jsonb
            WHERE name = ? AND type = ?
            """;

        int rowsAffected = dbAccess.update(sql,
            dataSource.getName(),
            dataSource.getCronPattern(),
            dataSource.getType().name(),
            serializeConfig(dataSource),
            name,
            type.name());

        if (rowsAffected > 0) {
            return findByNameAndType(dataSource.getName(), dataSource.getType());
        }
        return Optional.empty();
    }

    /**
     * Update last scanned timestamp by ID
     */
    public boolean updateLastScanned(String id, LocalDateTime lastScanned) {
        String sql = """
            UPDATE DATA_SOURCES
            SET date_last_scanned = ?
            WHERE id = ?
            """;

        int rowsAffected = dbAccess.update(sql, Timestamp.valueOf(lastScanned), id);
        return rowsAffected > 0;
    }

    /**
     * Update last scanned timestamp by name and type
     */
    public boolean updateLastScannedByNameAndType(String name, DataSourceType type, LocalDateTime lastScanned) {
        String sql = """
            UPDATE DATA_SOURCES
            SET date_last_scanned = ?
            WHERE name = ? AND type = ?
            """;

        int rowsAffected = dbAccess.update(sql, Timestamp.valueOf(lastScanned), name, type.name());
        return rowsAffected > 0;
    }

    /**
     * Update status by ID
     */
    public boolean updateStatus(String id, DataSourceStatus status) {
        String sql = """
            UPDATE DATA_SOURCES
            SET status = ?
            WHERE id = ?
            """;

        int rowsAffected = dbAccess.update(sql, status.name(), id);
        return rowsAffected > 0;
    }

    /**
     * Update status by name and type
     */
    public boolean updateStatusByNameAndType(String name, DataSourceType type, DataSourceStatus status) {
        String sql = """
            UPDATE DATA_SOURCES
            SET status = ?
            WHERE name = ? AND type = ?
            """;

        int rowsAffected = dbAccess.update(sql, status.name(), name, type.name());
        return rowsAffected > 0;
    }

    /**
     * Delete datasource by ID
     */
    public boolean deleteById(String id) {
        String sql = "DELETE FROM DATA_SOURCES WHERE id = ?";
        int rowsAffected = dbAccess.executeAdmin(sql, id);
        return rowsAffected > 0;
    }

    /**
     * Delete datasource by name and type
     */
    public boolean deleteByNameAndType(String name, DataSourceType type) {
        String sql = "DELETE FROM DATA_SOURCES WHERE name = ? AND type = ?";
        int rowsAffected = dbAccess.executeAdmin(sql, name, type.name());
        return rowsAffected > 0;
    }

    /**
     * Clear all data for a datasource (items, folders, and their treatments) while keeping the datasource definition.
     * Returns the count of deleted items.
     */
    public int clearDataSourceData(String id) {
        // Delete all items for this datasource (cascade will delete item_treatments and item_treatment_results)
        String deleteItemsSql = "DELETE FROM ITEMS WHERE datasource_id = ?";
        int itemsDeleted = dbAccess.executeAdmin(deleteItemsSql, id);
        
        // Delete all folders for this datasource
        String deleteFoldersSql = "DELETE FROM FOLDERS WHERE datasource_id = ?";
        dbAccess.executeAdmin(deleteFoldersSql, id);
        
        // Reset the last scanned date to null
        String resetLastScannedSql = "UPDATE DATA_SOURCES SET date_last_scanned = NULL WHERE id = ?";
        dbAccess.executeAdmin(resetLastScannedSql, id);
        
        return itemsDeleted;
    }

    /**
     * Check if datasource exists by name
     */
    public boolean existsByName(String name) {
        String sql = "SELECT COUNT(*) FROM DATA_SOURCES WHERE name = ?";
        Integer count = dbAccess.queryForObject(sql, Integer.class, name);
        return count != null && count > 0;
    }

    /**
     * Check if datasource exists by name and type
     */
    public boolean existsByNameAndType(String name, DataSourceType type) {
        String sql = "SELECT COUNT(*) FROM DATA_SOURCES WHERE name = ? AND type = ?";
        Integer count = dbAccess.queryForObject(sql, Integer.class, name, type.name());
        return count != null && count > 0;
    }

    /**
     * Reset all SCANNING datasources to IDLE status.
     * Used on startup to recover from crashed/interrupted scans.
     *
     * @return the number of datasources reset
     */
    public int resetScanningToIdle() {
        String sql = """
                UPDATE DATA_SOURCES
                SET status = ?, date_last_scanned = LOCALTIMESTAMP
                WHERE status = ?
                """;

        return dbAccess.update(sql, DataSourceStatus.IDLE.name(), DataSourceStatus.SCANNING.name());
    }

    /**
     * Get statistics for a datasource including total items and treatment status counts.
     */
    public DataSourceStats getDataSourceStats(String datasourceId) {
        // Get total item count
        String itemCountSql = "SELECT COUNT(*) FROM ITEMS WHERE datasource_id = ?";
        Integer totalItems = dbAccess.queryForObject(itemCountSql, Integer.class, datasourceId);
        
        // Get treatment status counts
        String statusCountSql = """
            SELECT it.status, COUNT(*) as count
            FROM ITEM_TREATMENTS it
            JOIN DATASOURCE_TREATMENTS dt ON it.datasource_treatment_id = dt.id
            WHERE dt.datasource_id = ?
            GROUP BY it.status
            """;
        
        Map<ItemTreatmentStatus, Integer> statusCounts = new EnumMap<>(ItemTreatmentStatus.class);
        // Initialize all statuses to 0
        for (ItemTreatmentStatus status : ItemTreatmentStatus.values()) {
            statusCounts.put(status, 0);
        }
        
        dbAccess.query(statusCountSql, (rs, rowNum) -> {
            String statusName = rs.getString("status");
            int count = rs.getInt("count");
            ItemTreatmentStatus status = ItemTreatmentStatus.valueOf(statusName);
            statusCounts.put(status, count);
            return null;
        }, datasourceId);
        
        // Convert map to list of TreatmentStatusCount
        List<TreatmentStatusCount> treatmentStatusCounts = new ArrayList<>();
        for (Map.Entry<ItemTreatmentStatus, Integer> entry : statusCounts.entrySet()) {
            treatmentStatusCounts.add(new TreatmentStatusCount(entry.getKey(), entry.getValue()));
        }
        
        // Get data source status and date last scanned in one query
        String dataSourceInfoSql = "SELECT status, date_last_scanned FROM DATA_SOURCES WHERE id = ?";
        DataSourceInfo result = dbAccess.queryForObject(dataSourceInfoSql, new RowMapper<>() {
            @Override
            public DataSourceInfo mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
                String statusString = rs.getString("status");
                DataSourceStatus dataSourceStatus = DataSourceStatus.valueOf(statusString);
                Timestamp timestamp = rs.getTimestamp("date_last_scanned");
                LocalDateTime dateLastScanned = timestamp != null ? timestamp.toLocalDateTime() : null;
                return new DataSourceInfo(dataSourceStatus, dateLastScanned);
            }
        }, datasourceId);
        
        if (result == null) {
            throw new SystemIssueException("DataSource not found: " + datasourceId);
        }
        
        DataSourceStatus dataSourceStatus = result.status();
        LocalDateTime dateLastScanned = result.dateLastScanned();
        
        // Get count of item previews to do (status = NEW)
        String previewsToDoSql = """
            SELECT COUNT(*)
            FROM ITEM_PREVIEWS ip
            JOIN ITEMS i ON ip.item_id = i.id
            WHERE i.datasource_id = ? AND ip.status = 'NEW'
            """;
        Integer previewsToDo = dbAccess.queryForObject(previewsToDoSql, Integer.class, datasourceId);

        // Folders whose most recent listing attempt failed during a scan. A non-zero value means
        // those folders' contents are stale; the scan log names the paths and the reasons.
        int foldersWithProblems = itemDao.countFoldersWithProblems(datasourceId);

        return new DataSourceStats(totalItems != null ? totalItems : 0, treatmentStatusCounts, 
                                    dataSourceStatus, dateLastScanned, previewsToDo != null ? previewsToDo : 0,
                                    foldersWithProblems);
    }

    /**
     * Serialize datasource config to JSON string, encrypting if encryption is enabled.
     * 
     * If encryption is enabled:
     *   - Encrypts the config JSON and wraps in: {"encryptedValue": "ENC:..."}
     * 
     * If encryption is disabled:
     *   - Returns the config JSON as-is (plaintext)
     */
    private String serializeConfig(DataSourceInterface dataSource) {
        try {
            Map<String, Object> config = dataSource.getType().serializeConfig(dataSource);
            String json = objectMapper.writeValueAsString(config);
            String result = configEncryptor.encrypt(json);
            
            // If encryption is enabled, result will be encrypted (starts with "ENC:")
            // Wrap it so the entire document is valid JSON for the JSONB column
            if (configEncryptor.isEncrypted(result)) {
                Map<String, Object> wrappedConfig = new java.util.HashMap<>();
                wrappedConfig.put("encryptedValue", result);
                return objectMapper.writeValueAsString(wrappedConfig);
            }
            
            // Encryption disabled: encrypt() returns plaintext unchanged
            // Return the plain JSON config
            return result;
        } catch (JacksonException e) {
            throw new SystemIssueException("Failed to serialize datasource config", e);
        }
    }

    /**
     * Create datasource instance based on type and configuration
     */
    private DataSourceInterface createDataSourceInstance(DataSourceType type, String id, String name,
            String cronPattern, LocalDateTime dateCreated, LocalDateTime dateLastScanned,
            DataSourceStatus status, JsonNode config) throws JacksonException {
        return type.createDataSourceInstance(id, name, cronPattern, dateCreated, dateLastScanned, status, config);
    }

    /**
     * Create datasource instance with ID and timestamps from existing datasource
     */
    private DataSourceInterface createDataSourceWithId(DataSourceInterface original, String id, LocalDateTime dateCreated, LocalDateTime dateLastScanned, DataSourceStatus status) {
        try {
            String configJson = serializeConfig(original);
            JsonNode config = objectMapper.readTree(configJson);
            return createDataSourceInstance(original.getType(), id, original.getName(),
                original.getCronPattern(), dateCreated, dateLastScanned, status, config);
        } catch (JacksonException e) {
            throw new SystemIssueException("Failed to create datasource with ID", e);
        }
    }

    /**
     * Row mapper for DataSource results
     */
    private class DataSourceRowMapper implements RowMapper<DataSourceInterface> {
        @Override
        public DataSourceInterface mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            String id = rs.getString("id");
            String name = rs.getString("name");
            String cronPattern = rs.getString("cron_pattern");
            LocalDateTime dateCreated = rs.getTimestamp("date_created").toLocalDateTime();
            Timestamp lastScannedTs = rs.getTimestamp("date_last_scanned");
            LocalDateTime dateLastScanned = lastScannedTs != null ? lastScannedTs.toLocalDateTime() : null;
            DataSourceStatus status = DataSourceStatus.valueOf(rs.getString("status"));
            DataSourceType type = DataSourceType.valueOf(rs.getString("type"));
            String configJson = rs.getString("config");

            try {
                String configToDeserialize = configJson;
                
                // If encryption is enabled, config is wrapped as {"encryptedValue": "ENC:..."}
                // Extract the encrypted value before decrypting
                if (configJson != null) {
                    try {
                        JsonNode configNode = objectMapper.readTree(configJson);
                        // Check if this is a wrapped encrypted config (has encryptedValue key only)
                        if (configNode.isObject() && configNode.has("encryptedValue") && 
                            configNode.size() == 1 && configNode.get("encryptedValue") != null) {
                            configToDeserialize = configNode.get("encryptedValue").asString();
                        }
                        // Otherwise it's plain JSON config, keep as-is
                    } catch (JacksonException e) {
                        // If parsing fails, treat entire string as the config to decrypt
                    }
                }
                
                // Decrypt (passes through unchanged if encryption is disabled)
                String decryptedJson = configEncryptor.decrypt(configToDeserialize);
                JsonNode config = objectMapper.readTree(decryptedJson);
                return createDataSourceInstance(type, id, name, cronPattern, dateCreated, dateLastScanned, status, config);
            } catch (JacksonException e) {
                throw new SQLException("Failed to deserialize datasource config", e);
            }
        }
    }

}