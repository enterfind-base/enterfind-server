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

import enterfind.server.model.ApiUser;
import enterfind.server.model.UserLevel;
import enterfind.server.security.ConfigEncryptor;
import enterfind.server.util.Utilities;

/**
 * DAO for API user operations.
 */
@Repository
public class ApiUserDao {

    private final DBAccess dbAccess;
    private final ConfigEncryptor configEncryptor;

    public ApiUserDao(DBAccess dbAccess, ConfigEncryptor configEncryptor) {
        this.dbAccess = dbAccess;
        this.configEncryptor = configEncryptor;
    }

    /**
     * Create a new API user with encrypted key value.
     */
    public ApiUser create(String nickname, String keyValue, UserLevel userLevel,
                          String createdByUserId, LocalDateTime dateExpires) {
        String id = Utilities.generateHexId();
        LocalDateTime dateCreated = LocalDateTime.now();

        String encryptedKeyValue = configEncryptor.encrypt(keyValue);

        String sql = """
            INSERT INTO API_USERS (id, nickname, key_value, user_level, created_by_user_id,
                                   date_created, date_expires, is_active)
            VALUES (?, ?, ?, ?, ?, ?, ?, true)
            """;

        dbAccess.update(sql, id, nickname, encryptedKeyValue, userLevel.name(),
            createdByUserId, Timestamp.valueOf(dateCreated), Timestamp.valueOf(dateExpires));

        return new ApiUser(id, nickname, keyValue, userLevel, createdByUserId,
            dateCreated, dateExpires, null, true);
    }

    /**
     * Find an API user by ID.
     */
    public Optional<ApiUser> findById(String id) {
        String sql = """
            SELECT id, nickname, key_value, user_level, created_by_user_id,
                   date_created, date_expires, date_last_used, is_active
            FROM API_USERS
            WHERE id = ?
            """;

        try {
            ApiUser apiUser = dbAccess.queryForObject(sql, new ApiUserRowMapper(), id);
            return Optional.ofNullable(apiUser);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Find an API user by nickname (case-insensitive).
     */
    public Optional<ApiUser> findByNickname(String nickname) {
        String sql = """
            SELECT id, nickname, key_value, user_level, created_by_user_id,
                   date_created, date_expires, date_last_used, is_active
            FROM API_USERS
            WHERE LOWER(nickname) = LOWER(?)
            """;

        try {
            ApiUser apiUser = dbAccess.queryForObject(sql, new ApiUserRowMapper(), nickname);
            return Optional.ofNullable(apiUser);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Find an API user by key value.
     */
    public Optional<ApiUser> findByKeyValue(String keyValue) {
        String encryptedKeyValue = configEncryptor.encrypt(keyValue);
        
        String sql = """
            SELECT id, nickname, key_value, user_level, created_by_user_id,
                   date_created, date_expires, date_last_used, is_active
            FROM API_USERS
            WHERE key_value = ?
            """;

        try {
            ApiUser apiUser = dbAccess.queryForObject(sql, new ApiUserRowMapper(), encryptedKeyValue);
            return Optional.ofNullable(apiUser);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Find all API users.
     */
    public List<ApiUser> findAll() {
        String sql = """
            SELECT id, nickname, key_value, user_level, created_by_user_id,
                   date_created, date_expires, date_last_used, is_active
            FROM API_USERS
            ORDER BY date_created DESC
            """;

        return dbAccess.query(sql, new ApiUserRowMapper());
    }

    /**
     * Check if an API user with the given nickname exists.
     */
    public boolean existsByNickname(String nickname) {
        String sql = "SELECT COUNT(*) FROM API_USERS WHERE LOWER(nickname) = LOWER(?)";
        Integer count = dbAccess.queryForObject(sql, Integer.class, nickname);
        return count != null && count > 0;
    }

    /**
     * Update API user's last used timestamp.
     */
    public boolean updateLastUsed(String id) {
        String sql = "UPDATE API_USERS SET date_last_used = ? WHERE id = ?";
        int rowsAffected = dbAccess.update(sql, Timestamp.valueOf(LocalDateTime.now()), id);
        return rowsAffected > 0;
    }

    /**
     * Deactivate an API user.
     */
    public boolean deactivate(String id) {
        String sql = "UPDATE API_USERS SET is_active = false WHERE id = ?";
        int rowsAffected = dbAccess.update(sql, id);
        return rowsAffected > 0;
    }

    /**
     * Delete an API user.
     */
    public boolean delete(String id) {
        String sql = "DELETE FROM API_USERS WHERE id = ?";
        int rowsAffected = dbAccess.update(sql, id);
        return rowsAffected > 0;
    }

    /**
     * Count all API users.
     */
    public int count() {
        String sql = "SELECT COUNT(*) FROM API_USERS";
        Integer count = dbAccess.queryForObject(sql, Integer.class);
        return count != null ? count : 0;
    }

    /**
     * Row mapper for ApiUser.
     */
    private class ApiUserRowMapper implements RowMapper<ApiUser> {
        @Override
        public ApiUser mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            String id = rs.getString("id");
            String nickname = rs.getString("nickname");
            String encryptedKeyValue = rs.getString("key_value");
            String userLevelStr = rs.getString("user_level");
            String createdByUserId = rs.getString("created_by_user_id");
            LocalDateTime dateCreated = rs.getTimestamp("date_created").toLocalDateTime();
            LocalDateTime dateExpires = rs.getTimestamp("date_expires").toLocalDateTime();
            Timestamp lastUsedTs = rs.getTimestamp("date_last_used");
            LocalDateTime dateLastUsed = lastUsedTs != null ? lastUsedTs.toLocalDateTime() : null;
            boolean isActive = rs.getBoolean("is_active");

            // Decrypt key value
            String keyValue = configEncryptor.decrypt(encryptedKeyValue);

            UserLevel userLevel = UserLevel.valueOf(userLevelStr);

            return new ApiUser(id, nickname, keyValue, userLevel, createdByUserId,
                dateCreated, dateExpires, dateLastUsed, isActive);
        }
    }
}
