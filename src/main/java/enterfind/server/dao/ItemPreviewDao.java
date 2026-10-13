package enterfind.server.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import enterfind.server.model.ItemPreview;
import enterfind.server.model.ItemPreviewStatus;

/**
 * DAO class for item preview database operations using Spring JDBC
 */
@Repository
public class ItemPreviewDao {

    private final DBAccess dbAccess;

    public ItemPreviewDao(DBAccess dbAccess) {
        this.dbAccess = dbAccess;
    }

    /**
     * Create a new item preview record with status NEW
     */
    public ItemPreview createItemPreview(String itemId) {
        LocalDateTime dateCreated = LocalDateTime.now();

        String sql = """
                INSERT INTO ITEM_PREVIEWS (item_id, status, date_created, version)
                VALUES (?, ?, ?, 1)
                """;

        dbAccess.update(sql, itemId, ItemPreviewStatus.NEW.name(), Timestamp.valueOf(dateCreated));

        return new ItemPreview(itemId, ItemPreviewStatus.NEW, null, null, dateCreated, null, 1);
    }

    /**
     * Get an item preview by item ID
     */
    public Optional<ItemPreview> getByItemId(String itemId) {
        String sql = """
                SELECT item_id, status, preview_data, content_type, date_created, date_modified, version
                FROM ITEM_PREVIEWS
                WHERE item_id = ?
                """;

        List<ItemPreview> results = dbAccess.query(sql, new ItemPreviewRowMapper(), itemId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Update item preview status to NEW (for updated items)
     */
    @Transactional
    public void markAsNew(String itemId) {
        String sql = """
                UPDATE ITEM_PREVIEWS
                SET status = ?, date_modified = ?, version = version + 1
                WHERE item_id = ?
                """;

        dbAccess.update(sql, ItemPreviewStatus.NEW.name(), Timestamp.valueOf(LocalDateTime.now()), itemId);
    }

    /**
     * Claim previews for processing (set status to INPROGRESS)
     * Returns list of previews with status NEW, ordered by date_created
     */
    @Transactional
    public List<ItemPreview> claimPreviewsForProcessing(int batchSize) {
        // First, select previews to claim
        String selectSql = """
                SELECT item_id, status, preview_data, content_type, date_created, date_modified, version
                FROM ITEM_PREVIEWS
                WHERE status = ?
                ORDER BY date_created ASC
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """;

        List<ItemPreview> previews = dbAccess.query(selectSql, new ItemPreviewRowMapper(), 
                                                     ItemPreviewStatus.NEW.name(), batchSize);

        if (previews.isEmpty()) {
            return previews;
        }

        // Update status to INPROGRESS
        String updateSql = """
                UPDATE ITEM_PREVIEWS
                SET status = ?, date_modified = ?, version = version + 1
                WHERE item_id = ?
                """;

        LocalDateTime now = LocalDateTime.now();
        for (ItemPreview preview : previews) {
            dbAccess.update(updateSql, ItemPreviewStatus.INPROGRESS.name(), Timestamp.valueOf(now), preview.getItemId());
            preview.setStatus(ItemPreviewStatus.INPROGRESS);
            preview.setDateModified(now);
            preview.setVersion(preview.getVersion() + 1);
        }

        return previews;
    }

    /**
     * Save preview data and mark as DONE
     */
    @Transactional
    public void savePreviewData(String itemId, int version, byte[] previewData, String contentType, ItemPreviewStatus status) {
        String sql = """
                UPDATE ITEM_PREVIEWS
                SET preview_data = ?, content_type = ?, status = ?, date_modified = ?, version = version + 1
                WHERE item_id = ? AND version = ?
                """;

        int updated = dbAccess.update(sql, previewData, contentType, status.name(), 
                                      Timestamp.valueOf(LocalDateTime.now()), itemId, version);

        if (updated == 0) {
            throw new IllegalStateException("Preview version mismatch or preview not found: " + itemId);
        }
    }

    /**
     * Check if item preview exists
     */
    public boolean existsByItemId(String itemId) {
        String sql = "SELECT COUNT(*) FROM ITEM_PREVIEWS WHERE item_id = ?";
        Integer count = dbAccess.queryForObject(sql, Integer.class, itemId);
        return count != null && count > 0;
    }

    /**
     * Row mapper for ItemPreview
     */
    private static class ItemPreviewRowMapper implements RowMapper<ItemPreview> {
        @Override
        public ItemPreview mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            ItemPreview preview = new ItemPreview();
            preview.setItemId(rs.getString("item_id"));
            preview.setStatus(ItemPreviewStatus.valueOf(rs.getString("status")));
            preview.setPreviewData(rs.getBytes("preview_data"));
            preview.setContentType(rs.getString("content_type"));
            
            Timestamp dateCreated = rs.getTimestamp("date_created");
            if (dateCreated != null) {
                preview.setDateCreated(dateCreated.toLocalDateTime());
            }
            
            Timestamp dateModified = rs.getTimestamp("date_modified");
            if (dateModified != null) {
                preview.setDateModified(dateModified.toLocalDateTime());
            }
            
            preview.setVersion(rs.getInt("version"));
            
            return preview;
        }
    }
}
