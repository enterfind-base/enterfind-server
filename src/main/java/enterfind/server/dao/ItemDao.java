package enterfind.server.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Repository;

import enterfind.server.exception.SystemIssueException;
import enterfind.server.scan.Folder;
import enterfind.server.scan.Item;
import enterfind.server.util.Utilities;

/**
 * DAO class for items and folders database operations using Spring JDBC
 */
@Repository
public class ItemDao {

    private static final Logger logger = LoggerFactory.getLogger(ItemDao.class);

    /**
     * Matches a folder's own contents and everything beneath it: rows directly in the
     * folder (exact path match) plus rows in any subfolder. Parameters, in order:
     * datasource_id, path, path.
     *
     * Uses starts_with() rather than LIKE because a path is data, not a pattern: '_' and
     * '%' are legal in folder names and would act as wildcards under LIKE, matching
     * unrelated sibling subtrees. The trailing '/' is what stops 'foo' matching 'foobar'.
     */
    private static final String SUBTREE_PREDICATE =
        "datasource_id = ? AND (path = ? OR starts_with(path, ? || '/'))";

    /**
     * Matches one row by its parent path and name, where a root-level row has a NULL path and so
     * cannot be found by equality. Parameters, in order: datasource_id, path, path, name.
     *
     * The CAST is not cosmetic: a bare '? IS NULL' gives PostgreSQL nothing to infer the
     * parameter's type from, and the statement fails to prepare with "could not determine data
     * type of parameter" - so without it the NULL branch does not merely fail to match, it
     * throws, which is the only case this predicate exists to handle.
     */
    private static final String PATH_AND_NAME_PREDICATE =
        "datasource_id = ? AND (path = ? OR (path IS NULL AND CAST(? AS TEXT) IS NULL)) AND name = ?";

    /**
     * Rows deleted per statement when removing a subtree. Deleting a whole subtree in one
     * statement can exceed the DBAccess query timeout on large folders: a single item can
     * cascade to ~20 rows across item_treatments, item_treatment_results and item_previews,
     * so a 70k-item subtree is ~1.6M row deletions plus trigram index maintenance.
     * Package-private so tests can exercise the chunk boundary.
     */
    static final int DELETE_CHUNK_SIZE = 500;

    private final DBAccess dbAccess;

    public ItemDao(DBAccess dbAccess) {
        this.dbAccess = dbAccess;
    }

    /**
     * Collect the ids of every row in the given table at or beneath a folder's full path.
     *
     * Deliberately unlimited: adding a LIMIT makes the planner assume it can stop early and
     * abandon the (datasource_id, path) index for a sequential scan, which would make the
     * chunked delete quadratic. Unlimited, this is an index-only scan.
     *
     * @param table    ITEMS or FOLDERS - an internal literal, never caller-supplied
     * @param ordering trailing ORDER BY clause, or "" for none - also an internal literal
     */
    private List<String> findSubtreeIds(String table, String datasourceId, String path, String ordering) {
        String sql = "SELECT id FROM " + table + " WHERE " + SUBTREE_PREDICATE + ordering;
        return dbAccess.queryForList(sql, String.class, datasourceId, path, path);
    }

    /**
     * Delete the given rows by primary key, in chunks small enough to stay inside the
     * query timeout.
     *
     * Chunks are committed individually, so this is not atomic. Callers rely on the whole
     * delete either finishing or throwing - a partially completed delete must never return
     * normally, or the caller will move on to the next step while rows it depends on still
     * exist. See Scanner.deletedFolder, which deletes items, then subfolders, then the
     * folder itself, and depends on each step being all-or-nothing to avoid orphaning rows.
     *
     * @param table an internal literal (ITEMS or FOLDERS), never caller-supplied
     * @return the total number of rows deleted across all chunks
     */
    private int deleteByIdsInChunks(String table, List<String> ids) {
        String sql = "DELETE FROM " + table + " WHERE id = ANY(?)";
        int deleted = 0;

        for (int i = 0; i < ids.size(); i += DELETE_CHUNK_SIZE) {
            if (Thread.currentThread().isInterrupted()) {
                // Abort rather than return a partial count: returning would let the caller
                // proceed to its next delete step and orphan whatever is left here.
                throw new SystemIssueException("Interrupted after deleting " + deleted
                    + " of " + ids.size() + " rows from " + table);
            }

            List<String> batch = ids.subList(i, Math.min(i + DELETE_CHUNK_SIZE, ids.size()));
            deleted += dbAccess.updateWithPreparedStatement(connection -> {
                var ps = connection.prepareStatement(sql);
                ps.setArray(1, connection.createArrayOf("varchar", batch.toArray(new String[0])));
                return ps;
            });
        }

        return deleted;
    }

    // ============= ITEM OPERATIONS =============

    /**
     * Create a new item in the database
     */
    public Item createItem(Item item) {
        String id = Utilities.generateHexId();
        LocalDateTime dateScanned = LocalDateTime.now();
        
        String sql = """
            INSERT INTO ITEMS (id, datasource_id, name, path, time_created, 
                              time_modified, size_bytes, content_hash, mime_type, date_scanned)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
            
        dbAccess.update(sql, id, item.getDatasourceId(), item.getName(), 
            item.getPath(), 
            item.getTimeCreated() != null ? Timestamp.valueOf(item.getTimeCreated()) : null,
            item.getTimeModified() != null ? Timestamp.valueOf(item.getTimeModified()) : null,
            item.getSizeBytes(), item.getContentHash(), item.getMimeType(),
            Timestamp.valueOf(dateScanned));
        
        item.setId(id);
        item.setDateScanned(dateScanned);
        return item;
    }

    /**
     * Find items by datasource ID and path
     */
    public List<Item> findItemsByDatasourceAndPath(String datasourceId, String path) {
        String sql;
        Object[] params;
        
        if (path == null) {
            sql = """
                SELECT id, datasource_id, name, path, time_created, time_modified, 
                       size_bytes, content_hash, mime_type, date_scanned
                FROM ITEMS
                WHERE datasource_id = ? AND path IS NULL
                ORDER BY name
                """;
            params = new Object[]{datasourceId};
        } else {
            sql = """
                SELECT id, datasource_id, name, path, time_created, time_modified, 
                       size_bytes, content_hash, mime_type, date_scanned
                FROM ITEMS
                WHERE datasource_id = ? AND path = ?
                ORDER BY name
                """;
            params = new Object[]{datasourceId, path};
        }

        return dbAccess.query(sql, new ItemRowMapper(), params);
    }

    /**
     * Find all items by datasource ID
     */
    public List<Item> findItemsByDatasource(String datasourceId) {
        String sql = """
            SELECT id, datasource_id, name, path, time_created, time_modified, 
                   size_bytes, content_hash, mime_type, date_scanned
            FROM ITEMS
            WHERE datasource_id = ?
            ORDER BY path, name
            """;

        return dbAccess.query(sql, new ItemRowMapper(), datasourceId);
    }

    /**
     * Find item by datasource ID, path and name
     */
    public Optional<Item> findItemByDatasourceAndPath(String datasourceId, String path, String name) {
        String sql;
        Object[] params;
        
        if (path == null) {
            sql = """
                SELECT id, datasource_id, name, path, time_created, time_modified, 
                       size_bytes, content_hash, mime_type, date_scanned
                FROM ITEMS
                WHERE datasource_id = ? AND path IS NULL AND name = ?
                """;
            params = new Object[]{datasourceId, name};
        } else {
            sql = """
                SELECT id, datasource_id, name, path, time_created, time_modified, 
                       size_bytes, content_hash, mime_type, date_scanned
                FROM ITEMS
                WHERE datasource_id = ? AND path = ? AND name = ?
                """;
            params = new Object[]{datasourceId, path, name};
        }

        try {
            Item item = dbAccess.queryForObject(sql, new ItemRowMapper(), params);
            return Optional.ofNullable(item);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Find item by ID
     */
    public Optional<Item> findItemById(String id) {
        String sql = """
            SELECT id, datasource_id, name, path, time_created, time_modified, 
                   size_bytes, content_hash, mime_type, date_scanned
            FROM ITEMS
            WHERE id = ?
            """;

        try {
            Item item = dbAccess.queryForObject(sql, new ItemRowMapper(), id);
            return Optional.ofNullable(item);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Update an existing item
     */
    public boolean updateItem(Item item) {
        String sql = """
            UPDATE ITEMS
            SET name = ?, path = ?, time_created = ?, time_modified = ?,
                size_bytes = ?, content_hash = ?, mime_type = ?, date_scanned = ?
            WHERE id = ?
            """;

        int rowsAffected = dbAccess.update(sql,
            item.getName(), item.getPath(),
            item.getTimeCreated() != null ? Timestamp.valueOf(item.getTimeCreated()) : null,
            item.getTimeModified() != null ? Timestamp.valueOf(item.getTimeModified()) : null,
            item.getSizeBytes(), item.getContentHash(), item.getMimeType(),
            Timestamp.valueOf(LocalDateTime.now()),
            item.getId());

        return rowsAffected > 0;
    }

    /**
     * Delete item by ID
     */
    public boolean deleteItemById(String id) {
        String sql = "DELETE FROM ITEMS WHERE id = ?";
        int rowsAffected = dbAccess.update(sql, id);
        return rowsAffected > 0;
    }

    /**
     * Delete item by datasource ID, path and name
     */
    public boolean deleteItemByDatasourceAndPath(String datasourceId, String path, String name) {
        String sql = "DELETE FROM ITEMS WHERE " + PATH_AND_NAME_PREDICATE;
        int rowsAffected = dbAccess.update(sql, datasourceId, path, path, name);
        return rowsAffected > 0;
    }

    /**
     * Delete all items for a datasource
     */
    public int deleteItemsByDatasource(String datasourceId) {
        String sql = "DELETE FROM ITEMS WHERE datasource_id = ?";
        return dbAccess.update(sql, datasourceId);
    }

    /**
     * Delete all items in a specific path (used when a folder is deleted)
     * This deletes items directly in the path AND all items in subdirectories
     */
    public int deleteItemsByDatasourceAndPath(String datasourceId, String path) {
        // No ordering: items are leaves, so no item's deletion affects any other's, and
        // sorting the whole subtree would be pure cost.
        List<String> ids = findSubtreeIds("ITEMS", datasourceId, path, "");
        int deleted = deleteByIdsInChunks("ITEMS", ids);
        if (deleted > 0) {
            logger.info("Deleted {} items under path '{}' for datasource {}", deleted, path, datasourceId);
        }
        return deleted;
    }

    // ============= FOLDER OPERATIONS =============

    /**
     * Create a new folder in the database
     */
    public Folder createFolder(Folder folder) {
        String id = Utilities.generateHexId();
        LocalDateTime dateScanned = LocalDateTime.now();
        
        String sql = """
            INSERT INTO FOLDERS (id, datasource_id, name, path, time_created, 
                               time_modified, date_scanned)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
            
        dbAccess.update(sql, id, folder.getDatasourceId(), folder.getName(), 
            folder.getPath(), 
            folder.getTimeCreated() != null ? Timestamp.valueOf(folder.getTimeCreated()) : null,
            folder.getTimeModified() != null ? Timestamp.valueOf(folder.getTimeModified()) : null,
            Timestamp.valueOf(dateScanned));
        
        folder.setId(id);
        folder.setDateScanned(dateScanned);
        return folder;
    }

    /**
     * Find folders by datasource ID and path
     */
    public List<Folder> findFoldersByDatasourceAndPath(String datasourceId, String path) {
        String sql;
        Object[] params;
        
        if (path == null) {
            sql = """
                SELECT id, datasource_id, name, path, time_created, time_modified, date_scanned
                FROM FOLDERS
                WHERE datasource_id = ? AND path IS NULL
                ORDER BY name
                """;
            params = new Object[]{datasourceId};
        } else {
            sql = """
                SELECT id, datasource_id, name, path, time_created, time_modified, date_scanned
                FROM FOLDERS
                WHERE datasource_id = ? AND path = ?
                ORDER BY name
                """;
            params = new Object[]{datasourceId, path};
        }

        return dbAccess.query(sql, new FolderRowMapper(), params);
    }

    /**
     * Find all folders by datasource ID
     */
    public List<Folder> findFoldersByDatasource(String datasourceId) {
        String sql = """
            SELECT id, datasource_id, name, path, time_created, time_modified, date_scanned
            FROM FOLDERS
            WHERE datasource_id = ?
            ORDER BY path, name
            """;

        return dbAccess.query(sql, new FolderRowMapper(), datasourceId);
    }

    /**
     * Find folder by datasource ID, path and name
     */
    public Optional<Folder> findFolderByDatasourceAndPath(String datasourceId, String path, String name) {
        String sql = "SELECT id, datasource_id, name, path, time_created, time_modified, date_scanned "
            + "FROM FOLDERS WHERE " + PATH_AND_NAME_PREDICATE;

        try {
            Folder folder = dbAccess.queryForObject(sql, new FolderRowMapper(), datasourceId, path, path, name);
            return Optional.ofNullable(folder);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Delete folder by ID
     */
    public boolean deleteFolderById(String id) {
        String sql = "DELETE FROM FOLDERS WHERE id = ?";
        int rowsAffected = dbAccess.update(sql, id);
        return rowsAffected > 0;
    }

    /**
     * Delete folder by datasource ID, path and name
     */
    public boolean deleteFolderByDatasourceAndPath(String datasourceId, String path, String name) {
        String sql = "DELETE FROM FOLDERS WHERE " + PATH_AND_NAME_PREDICATE;
        int rowsAffected = dbAccess.update(sql, datasourceId, path, path, name);
        return rowsAffected > 0;
    }

    /**
     * Delete all folders in a specific path (used when a folder is deleted)
     * This deletes folders directly in the path AND all folders in subdirectories
     */
    public int deleteFoldersByDatasourceAndPath(String datasourceId, String path) {
        // Deepest-first, so that if this is interrupted the surviving folders are still
        // ancestor-closed and nothing is orphaned; the next scan re-enters deletedFolder()
        // and resumes. A descendant's path always contains its ancestor's full path as a
        // prefix and is therefore strictly longer, so ordering by path length descending is
        // a valid topological order for the ancestor relation. (It is not a depth ordering -
        // unrelated branches may interleave - but unrelated folders need no ordering.)
        List<String> ids = findSubtreeIds("FOLDERS", datasourceId, path, " ORDER BY length(path) DESC");
        int deleted = deleteByIdsInChunks("FOLDERS", ids);
        if (deleted > 0) {
            logger.info("Deleted {} folders under path '{}' for datasource {}", deleted, path, datasourceId);
        }
        return deleted;
    }

    /**
     * Delete all folders for a datasource
     */
    public int deleteFoldersByDatasource(String datasourceId) {
        String sql = "DELETE FROM FOLDERS WHERE datasource_id = ?";
        return dbAccess.update(sql, datasourceId);
    }

    /**
     * Find orphaned folders whose parent path doesn't exist in the database.
     * These are folders that have a parent path, but no corresponding parent folder exists.
     */
    public List<Folder> findOrphanedFolders(String datasourceId) {
        String sql = """
            SELECT f.id, f.datasource_id, f.name, f.path, f.time_created, f.time_modified, f.date_scanned
            FROM folders f
            WHERE f.datasource_id = ?
              AND f.path IS NOT NULL
              AND f.path <> ''
              AND NOT EXISTS (
                  SELECT 1 FROM folders parent
                  WHERE parent.datasource_id = f.datasource_id
                    AND (
                        CASE
                            WHEN parent.path IS NULL OR parent.path = '' THEN parent.name
                            ELSE parent.path || '/' || parent.name
                        END
                    ) = f.path
              )
            ORDER BY f.path, f.name
            """;

        return dbAccess.query(sql, (rs, rowNum) -> {
            Folder folder = new Folder();
            folder.setId(rs.getString("id"));
            folder.setDatasourceId(rs.getString("datasource_id"));
            folder.setName(rs.getString("name"));
            folder.setPath(rs.getString("path"));

            Timestamp timeCreated = rs.getTimestamp("time_created");
            if (timeCreated != null) {
                folder.setTimeCreated(timeCreated.toLocalDateTime());
            }

            Timestamp timeModified = rs.getTimestamp("time_modified");
            if (timeModified != null) {
                folder.setTimeModified(timeModified.toLocalDateTime());
            }

            Timestamp dateScanned = rs.getTimestamp("date_scanned");
            if (dateScanned != null) {
                folder.setDateScanned(dateScanned.toLocalDateTime());
            }

            return folder;
        }, datasourceId);
    }

    /**
     * Find orphaned items whose parent folder doesn't exist in the database.
     * These are items that have a parent path, but no corresponding folder exists at that path.
     */
    public List<String> findOrphanedItemIds(String datasourceId) {
        String sql = """
            SELECT i.id
            FROM items i
            WHERE i.datasource_id = ?
              AND i.path IS NOT NULL
              AND i.path <> ''
              AND NOT EXISTS (
                  SELECT 1 FROM folders parent
                  WHERE parent.datasource_id = i.datasource_id
                    AND (
                        CASE
                            WHEN parent.path IS NULL OR parent.path = '' THEN parent.name
                            ELSE parent.path || '/' || parent.name
                        END
                    ) = i.path
              )
            """;

        return dbAccess.queryForList(sql, String.class, datasourceId);
    }

    // ============= UTILITY METHODS =============

    /**
     * Check if item exists by datasource ID, path and name
     */
    public boolean itemExists(String datasourceId, String path, String name) {
        String sql = "SELECT COUNT(*) FROM ITEMS WHERE " + PATH_AND_NAME_PREDICATE;
        Integer count = dbAccess.queryForObject(sql, Integer.class, datasourceId, path, path, name);
        return count != null && count > 0;
    }

    /**
     * Record that a scan failed to list this folder. Mirrors ITEM_TREATMENTS.failure_count one
     * level up: a non-zero count means the folder's contents could not be read on the most recent
     * attempt and are therefore stale. Returns the number of rows updated, which is 0 when the
     * folder has no row (the scan root).
     */
    public int incrementFolderScanFailureCount(String datasourceId, String path, String name) {
        String sql = "UPDATE FOLDERS SET scan_failure_count = scan_failure_count + 1 "
            + "WHERE " + PATH_AND_NAME_PREDICATE;
        return dbAccess.update(sql, datasourceId, path, path, name);
    }

    /**
     * Clear the scan failure count for a folder that has just listed successfully.
     * Guarded on a non-zero count so a clean scan issues no writes at all.
     */
    public int clearFolderScanFailureCount(String datasourceId, String path, String name) {
        String sql = "UPDATE FOLDERS SET scan_failure_count = 0 "
            + "WHERE " + PATH_AND_NAME_PREDICATE + " AND scan_failure_count <> 0";
        return dbAccess.update(sql, datasourceId, path, path, name);
    }

    /**
     * Count folders in this datasource whose most recent listing attempt failed.
     */
    public int countFoldersWithProblems(String datasourceId) {
        String sql = "SELECT COUNT(*) FROM FOLDERS WHERE datasource_id = ? AND scan_failure_count > 0";
        Integer count = dbAccess.queryForObject(sql, Integer.class, datasourceId);
        return count != null ? count : 0;
    }

    /**
     * Check if folder exists by datasource ID, path and name
     */
    public boolean folderExists(String datasourceId, String path, String name) {
        String sql = "SELECT COUNT(*) FROM FOLDERS WHERE " + PATH_AND_NAME_PREDICATE;
        Integer count = dbAccess.queryForObject(sql, Integer.class, datasourceId, path, path, name);
        return count != null && count > 0;
    }

    // ============= ROW MAPPERS =============

    /**
     * Row mapper for Item results
     */
    private static class ItemRowMapper implements RowMapper<Item> {
        @Override
        public Item mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
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
            
            return item;
        }
    }

    /**
     * Row mapper for Folder results
     */
    private static class FolderRowMapper implements RowMapper<Folder> {
        @Override
        public Folder mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            Folder folder = new Folder();
            folder.setId(rs.getString("id"));
            folder.setDatasourceId(rs.getString("datasource_id"));
            folder.setName(rs.getString("name"));
            folder.setPath(rs.getString("path"));
            
            Timestamp timeCreated = rs.getTimestamp("time_created");
            if (timeCreated != null) {
                folder.setTimeCreated(timeCreated.toLocalDateTime());
            }
            
            Timestamp timeModified = rs.getTimestamp("time_modified");
            if (timeModified != null) {
                folder.setTimeModified(timeModified.toLocalDateTime());
            }
            
            folder.setDateScanned(rs.getTimestamp("date_scanned").toLocalDateTime());
            
            return folder;
        }
    }
}