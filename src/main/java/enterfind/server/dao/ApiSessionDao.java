package enterfind.server.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Repository;

import enterfind.server.model.ApiSession;

/**
 * DAO for API session operations.
 */
@Repository
public class ApiSessionDao {

    private final DBAccess dbAccess;

    public ApiSessionDao(DBAccess dbAccess) {
        this.dbAccess = dbAccess;
    }

    /**
     * Create a new API session.
     */
    public ApiSession create(String token, String apiUserId, LocalDateTime dateExpires) {
        LocalDateTime dateCreated = LocalDateTime.now();

        String sql = """
            INSERT INTO API_SESSIONS (token, api_user_id, date_created, date_expires, is_valid)
            VALUES (?, ?, ?, ?, true)
            """;

        dbAccess.update(sql, token, apiUserId, Timestamp.valueOf(dateCreated),
            Timestamp.valueOf(dateExpires));

        return new ApiSession(token, apiUserId, dateCreated, dateExpires, true);
    }

    /**
     * Find an API session by token.
     */
    public Optional<ApiSession> findByToken(String token) {
        String sql = """
            SELECT token, api_user_id, date_created, date_expires, is_valid
            FROM API_SESSIONS
            WHERE token = ?
            """;

        try {
            ApiSession session = dbAccess.queryForObject(sql, new ApiSessionRowMapper(), token);
            return Optional.ofNullable(session);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Find a valid API session by token (not expired and not invalidated).
     */
    public Optional<ApiSession> findValidByToken(String token) {
        String sql = """
            SELECT token, api_user_id, date_created, date_expires, is_valid
            FROM API_SESSIONS
            WHERE token = ? AND is_valid = true AND date_expires > ?
            """;

        try {
            ApiSession session = dbAccess.queryForObject(sql, new ApiSessionRowMapper(),
                token, Timestamp.valueOf(LocalDateTime.now()));
            return Optional.ofNullable(session);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Invalidate an API session by token.
     */
    public boolean invalidate(String token) {
        String sql = "UPDATE API_SESSIONS SET is_valid = false WHERE token = ?";
        int rowsAffected = dbAccess.update(sql, token);
        return rowsAffected > 0;
    }

    /**
     * Invalidate all API sessions for an API user.
     */
    public int invalidateAllForApiUser(String apiUserId) {
        String sql = "UPDATE API_SESSIONS SET is_valid = false WHERE api_user_id = ?";
        return dbAccess.update(sql, apiUserId);
    }

    /**
     * Delete expired API sessions.
     */
    public int cleanupExpired() {
        String sql = "DELETE FROM API_SESSIONS WHERE date_expires < ? OR is_valid = false";
        return dbAccess.update(sql, Timestamp.valueOf(LocalDateTime.now()));
    }

    /**
     * Find all valid API sessions for an API user.
     */
    public List<ApiSession> findValidByApiUserId(String apiUserId) {
        String sql = """
            SELECT token, api_user_id, date_created, date_expires, is_valid
            FROM API_SESSIONS
            WHERE api_user_id = ? AND is_valid = true AND date_expires > ?
            ORDER BY date_created DESC
            """;

        return dbAccess.query(sql, new ApiSessionRowMapper(), apiUserId,
            Timestamp.valueOf(LocalDateTime.now()));
    }

    /**
     * Count active API sessions for an API user.
     */
    public int countValidSessionsForApiUser(String apiUserId) {
        String sql = """
            SELECT COUNT(*) FROM API_SESSIONS
            WHERE api_user_id = ? AND is_valid = true AND date_expires > ?
            """;
        Integer count = dbAccess.queryForObject(sql, Integer.class, apiUserId,
            Timestamp.valueOf(LocalDateTime.now()));
        return count != null ? count : 0;
    }

    /**
     * Row mapper for ApiSession.
     */
    private static class ApiSessionRowMapper implements RowMapper<ApiSession> {
        @Override
        public ApiSession mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            String token = rs.getString("token");
            String apiUserId = rs.getString("api_user_id");
            LocalDateTime dateCreated = rs.getTimestamp("date_created").toLocalDateTime();
            LocalDateTime dateExpires = rs.getTimestamp("date_expires").toLocalDateTime();
            boolean isValid = rs.getBoolean("is_valid");

            return new ApiSession(token, apiUserId, dateCreated, dateExpires, isValid);
        }
    }
}
