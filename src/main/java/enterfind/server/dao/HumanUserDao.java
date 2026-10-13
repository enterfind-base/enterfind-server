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

import enterfind.server.model.HumanUser;
import enterfind.server.model.UserLevel;
import enterfind.server.security.ConfigEncryptor;
import enterfind.server.util.Utilities;

/**
 * DAO for human user operations.
 */
@Repository
public class HumanUserDao {

    private final DBAccess dbAccess;
    private final ConfigEncryptor configEncryptor;

    public HumanUserDao(DBAccess dbAccess, ConfigEncryptor configEncryptor) {
        this.dbAccess = dbAccess;
        this.configEncryptor = configEncryptor;
    }

    /**
     * Create a new human user with encrypted TOTP secret and recovery codes.
     */
    public HumanUser create(String nickname, String totpSecret, String recoveryCodes, UserLevel userLevel) {
        String id = Utilities.generateHexId();
        LocalDateTime dateCreated = LocalDateTime.now();

        String encryptedTotpSecret = configEncryptor.encrypt(totpSecret);
        String encryptedRecoveryCodes = recoveryCodes != null ? configEncryptor.encrypt(recoveryCodes) : null;

        String sql = """
            INSERT INTO HUMAN_USERS (id, nickname, totp_secret, recovery_codes, user_level, date_created, is_active)
            VALUES (?, ?, ?, ?, ?, ?, true)
            """;

        dbAccess.update(sql, id, nickname, encryptedTotpSecret, encryptedRecoveryCodes,
            userLevel.name(), Timestamp.valueOf(dateCreated));

        return new HumanUser(id, nickname, totpSecret, recoveryCodes, userLevel, dateCreated, null, true);
    }

    /**
     * Find a user by ID.
     */
    public Optional<HumanUser> findById(String id) {
        String sql = """
            SELECT id, nickname, totp_secret, recovery_codes, user_level, date_created, date_last_login, is_active
            FROM HUMAN_USERS
            WHERE id = ?
            """;

        try {
            HumanUser user = dbAccess.queryForObject(sql, new HumanUserRowMapper(), id);
            return Optional.ofNullable(user);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Find a user by nickname (case-insensitive).
     */
    public Optional<HumanUser> findByNickname(String nickname) {
        String sql = """
            SELECT id, nickname, totp_secret, recovery_codes, user_level, date_created, date_last_login, is_active
            FROM HUMAN_USERS
            WHERE LOWER(nickname) = LOWER(?)
            """;

        try {
            HumanUser user = dbAccess.queryForObject(sql, new HumanUserRowMapper(), nickname);
            return Optional.ofNullable(user);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Find all users.
     */
    public List<HumanUser> findAll() {
        String sql = """
            SELECT id, nickname, totp_secret, recovery_codes, user_level, date_created, date_last_login, is_active
            FROM HUMAN_USERS
            ORDER BY date_created DESC
            """;

        return dbAccess.query(sql, new HumanUserRowMapper());
    }

    /**
     * Update a user's details.
     */
    public boolean update(String id, String nickname, UserLevel userLevel, Boolean isActive) {
        StringBuilder sql = new StringBuilder("UPDATE HUMAN_USERS SET ");
        java.util.List<Object> params = new java.util.ArrayList<>();
        boolean hasUpdate = false;

        if (nickname != null) {
            sql.append("nickname = ?");
            params.add(nickname);
            hasUpdate = true;
        }

        if (userLevel != null) {
            if (hasUpdate) sql.append(", ");
            sql.append("user_level = ?");
            params.add(userLevel.name());
            hasUpdate = true;
        }

        if (isActive != null) {
            if (hasUpdate) sql.append(", ");
            sql.append("is_active = ?");
            params.add(isActive);
            hasUpdate = true;
        }

        if (!hasUpdate) {
            return false;
        }

        sql.append(" WHERE id = ?");
        params.add(id);

        int rowsAffected = dbAccess.update(sql.toString(), params.toArray());
        return rowsAffected > 0;
    }

    /**
     * Update user's last login timestamp.
     */
    public boolean updateLastLogin(String id) {
        String sql = "UPDATE HUMAN_USERS SET date_last_login = ? WHERE id = ?";
        int rowsAffected = dbAccess.update(sql, Timestamp.valueOf(LocalDateTime.now()), id);
        return rowsAffected > 0;
    }

    /**
     * Update user's TOTP secret (encrypted).
     */
    public boolean updateTotpSecret(String id, String totpSecret) {
        String encryptedSecret = configEncryptor.encrypt(totpSecret);
        String sql = "UPDATE HUMAN_USERS SET totp_secret = ? WHERE id = ?";
        int rowsAffected = dbAccess.update(sql, encryptedSecret, id);
        return rowsAffected > 0;
    }

    /**
     * Update user's TOTP secret and recovery codes (encrypted).
     */
    public boolean updateTotpSecret(String id, String totpSecret, String recoveryCodes) {
        String encryptedSecret = configEncryptor.encrypt(totpSecret);
        String encryptedCodes = recoveryCodes != null ? configEncryptor.encrypt(recoveryCodes) : null;
        String sql = "UPDATE HUMAN_USERS SET totp_secret = ?, recovery_codes = ? WHERE id = ?";
        int rowsAffected = dbAccess.update(sql, encryptedSecret, encryptedCodes, id);
        return rowsAffected > 0;
    }

    /**
     * Update user's recovery codes (encrypted).
     */
    public boolean updateRecoveryCodes(String id, String recoveryCodes) {
        String encryptedCodes = recoveryCodes != null ? configEncryptor.encrypt(recoveryCodes) : null;
        String sql = "UPDATE HUMAN_USERS SET recovery_codes = ? WHERE id = ?";
        int rowsAffected = dbAccess.update(sql, encryptedCodes, id);
        return rowsAffected > 0;
    }

    /**
     * Delete a user by ID.
     */
    public boolean delete(String id) {
        String sql = "DELETE FROM HUMAN_USERS WHERE id = ?";
        int rowsAffected = dbAccess.update(sql, id);
        return rowsAffected > 0;
    }

    /**
     * Check if any users exist.
     */
    public boolean hasAnyUsers() {
        String sql = "SELECT COUNT(*) FROM HUMAN_USERS";
        Integer count = dbAccess.queryForObject(sql, Integer.class);
        return count != null && count > 0;
    }

    /**
     * Check if a nickname is already taken (case-insensitive).
     */
    public boolean existsByNickname(String nickname) {
        String sql = "SELECT COUNT(*) FROM HUMAN_USERS WHERE LOWER(nickname) = LOWER(?)";
        Integer count = dbAccess.queryForObject(sql, Integer.class, nickname);
        return count != null && count > 0;
    }

    /**
     * Count total number of users.
     */
    public int countUsers() {
        String sql = "SELECT COUNT(*) FROM HUMAN_USERS";
        Integer count = dbAccess.queryForObject(sql, Integer.class);
        return count != null ? count : 0;
    }

    /**
     * Count active users with ADMIN level, who are able to administer other users.
     */
    public int countActiveAdmins() {
        String sql = "SELECT COUNT(*) FROM HUMAN_USERS WHERE user_level = ? AND is_active = ?";
        Integer count = dbAccess.queryForObject(sql, Integer.class, UserLevel.ADMIN.name(), true);
        return count != null ? count : 0;
    }

    /**
     * Count remaining recovery codes for a user.
     */
    public int countRecoveryCodes(String id) {
        return findById(id)
            .map(user -> {
                String codes = user.recoveryCodes();
                if (codes == null || codes.isBlank()) {
                    return 0;
                }
                return codes.split(",").length;
            })
            .orElse(0);
    }

    /**
     * Row mapper for HumanUser.
     */
    private class HumanUserRowMapper implements RowMapper<HumanUser> {
        @Override
        public HumanUser mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            String id = rs.getString("id");
            String nickname = rs.getString("nickname");
            String encryptedTotpSecret = rs.getString("totp_secret");
            String encryptedRecoveryCodes = rs.getString("recovery_codes");
            UserLevel userLevel = UserLevel.valueOf(rs.getString("user_level"));
            LocalDateTime dateCreated = rs.getTimestamp("date_created").toLocalDateTime();
            Timestamp lastLoginTs = rs.getTimestamp("date_last_login");
            LocalDateTime dateLastLogin = lastLoginTs != null ? lastLoginTs.toLocalDateTime() : null;
            boolean isActive = rs.getBoolean("is_active");

            // Decrypt secrets
            String totpSecret = configEncryptor.decrypt(encryptedTotpSecret);
            String recoveryCodes = encryptedRecoveryCodes != null
                ? configEncryptor.decrypt(encryptedRecoveryCodes)
                : null;

            return new HumanUser(id, nickname, totpSecret, recoveryCodes, userLevel,
                dateCreated, dateLastLogin, isActive);
        }
    }
}
