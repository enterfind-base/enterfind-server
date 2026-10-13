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

import enterfind.server.model.Session;

/**
 * DAO for session operations.
 */
@Repository
public class SessionDao {

    private final DBAccess dbAccess;

    public SessionDao(DBAccess dbAccess) {
        this.dbAccess = dbAccess;
    }

    /**
     * Create a new session.
     */
    public Session create(String token, String userId, LocalDateTime dateExpires, boolean isRememberMe) {
        LocalDateTime dateCreated = LocalDateTime.now();

        String sql = """
            INSERT INTO SESSIONS (token, user_id, date_created, date_expires, is_remember_me, is_valid)
            VALUES (?, ?, ?, ?, ?, true)
            """;

        dbAccess.update(sql, token, userId, Timestamp.valueOf(dateCreated),
            Timestamp.valueOf(dateExpires), isRememberMe);

        return new Session(token, userId, dateCreated, dateExpires, isRememberMe, true);
    }

    /**
     * Find a session by token.
     */
    public Optional<Session> findByToken(String token) {
        String sql = """
            SELECT token, user_id, date_created, date_expires, is_remember_me, is_valid
            FROM SESSIONS
            WHERE token = ?
            """;

        try {
            Session session = dbAccess.queryForObject(sql, new SessionRowMapper(), token);
            return Optional.ofNullable(session);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Find a valid session by token (not expired and not invalidated).
     */
    public Optional<Session> findValidByToken(String token) {
        String sql = """
            SELECT token, user_id, date_created, date_expires, is_remember_me, is_valid
            FROM SESSIONS
            WHERE token = ? AND is_valid = true AND date_expires > ?
            """;

        try {
            Session session = dbAccess.queryForObject(sql, new SessionRowMapper(),
                token, Timestamp.valueOf(LocalDateTime.now()));
            return Optional.ofNullable(session);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Invalidate a session by token.
     */
    public boolean invalidate(String token) {
        String sql = "UPDATE SESSIONS SET is_valid = false WHERE token = ?";
        int rowsAffected = dbAccess.update(sql, token);
        return rowsAffected > 0;
    }

    /**
     * Invalidate all sessions for a user.
     */
    public int invalidateAllForUser(String userId) {
        String sql = "UPDATE SESSIONS SET is_valid = false WHERE user_id = ?";
        return dbAccess.update(sql, userId);
    }

    /**
     * Delete expired sessions.
     */
    public int cleanupExpired() {
        String sql = "DELETE FROM SESSIONS WHERE date_expires < ? OR is_valid = false";
        return dbAccess.update(sql, Timestamp.valueOf(LocalDateTime.now()));
    }

    /**
     * Find all valid sessions for a user.
     */
    public List<Session> findValidByUserId(String userId) {
        String sql = """
            SELECT token, user_id, date_created, date_expires, is_remember_me, is_valid
            FROM SESSIONS
            WHERE user_id = ? AND is_valid = true AND date_expires > ?
            ORDER BY date_created DESC
            """;

        return dbAccess.query(sql, new SessionRowMapper(), userId, Timestamp.valueOf(LocalDateTime.now()));
    }

    /**
     * Count active sessions for a user.
     */
    public int countValidSessionsForUser(String userId) {
        String sql = """
            SELECT COUNT(*) FROM SESSIONS
            WHERE user_id = ? AND is_valid = true AND date_expires > ?
            """;
        Integer count = dbAccess.queryForObject(sql, Integer.class, userId, Timestamp.valueOf(LocalDateTime.now()));
        return count != null ? count : 0;
    }

    /**
     * Row mapper for Session.
     */
    private static class SessionRowMapper implements RowMapper<Session> {
        @Override
        public Session mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            String token = rs.getString("token");
            String userId = rs.getString("user_id");
            LocalDateTime dateCreated = rs.getTimestamp("date_created").toLocalDateTime();
            LocalDateTime dateExpires = rs.getTimestamp("date_expires").toLocalDateTime();
            boolean isRememberMe = rs.getBoolean("is_remember_me");
            boolean isValid = rs.getBoolean("is_valid");

            return new Session(token, userId, dateCreated, dateExpires, isRememberMe, isValid);
        }
    }
}
