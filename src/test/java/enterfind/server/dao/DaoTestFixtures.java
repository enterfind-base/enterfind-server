package enterfind.server.dao;

/**
 * Row seeding shared by the DAO and schema-migration tests.
 *
 * <p>These inserts were copied privately into each test class that needed them, so the same
 * INSERT drifted in three places. One owner instead: construct with the datasource id the test
 * works under, and every seeded row hangs off it.
 *
 * <p>Deliberately plain JDBC through {@link DBAccess} rather than the production DAOs — a migration
 * test needs to write rows in shapes the DAOs may no longer produce (that is the whole point of
 * testing a migration), so going through them would beg the question.
 */
final class DaoTestFixtures {

    private final DBAccess dbAccess;
    private final String datasourceId;

    DaoTestFixtures(DBAccess dbAccess, String datasourceId) {
        this.dbAccess = dbAccess;
        this.datasourceId = datasourceId;
    }

    String datasourceId() {
        return datasourceId;
    }

    /** Insert the datasource every other fixture row references. */
    void insertDataSource(String name) {
        dbAccess.update("""
            INSERT INTO DATA_SOURCES (id, name, cron_pattern, type, config)
            VALUES (?, ?, ?, ?, ?::jsonb)
            """, datasourceId, name, "0 0 * * *", "LOCAL_FILESYSTEM",
            "{\"localPath\":\"/test\"}");
    }

    void insertFolder(String id, String name, String path) {
        dbAccess.update("""
            INSERT INTO FOLDERS (id, datasource_id, name, path, date_scanned)
            VALUES (?, ?, ?, ?, NOW())
            """, id, datasourceId, name, path);
    }

    void insertItem(String id, String name, String path) {
        dbAccess.update("""
            INSERT INTO ITEMS (id, datasource_id, name, path, size_bytes, date_scanned)
            VALUES (?, ?, ?, ?, 0, NOW())
            """, id, datasourceId, name, path);
    }

    /** @return the id, so callers can chain it straight into {@link #insertItemTreatment}. */
    String insertDatasourceTreatment(String id, String treatment) {
        dbAccess.update("""
            INSERT INTO DATASOURCE_TREATMENTS (id, datasource_id, treatment)
            VALUES (?, ?, ?)
            """, id, datasourceId, treatment);
        return id;
    }

    String insertItemTreatment(String id, String datasourceTreatmentId, String itemId) {
        dbAccess.update("""
            INSERT INTO ITEM_TREATMENTS (id, datasource_treatment_id, item_id, status, version)
            VALUES (?, ?, ?, 'NEW', 1)
            """, id, datasourceTreatmentId, itemId);
        return id;
    }

    int countItemTreatments() {
        return count("SELECT COUNT(*) FROM item_treatments");
    }

    int count(String sql) {
        Integer count = dbAccess.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    /** True if the named table exists in the current schema. */
    boolean tableExists(String tableName) {
        Integer count = dbAccess.queryForObject("""
            SELECT count(*) FROM information_schema.tables
            WHERE table_schema = current_schema() AND lower(table_name) = ?
            """, Integer.class, tableName.toLowerCase());
        return count != null && count > 0;
    }
}
