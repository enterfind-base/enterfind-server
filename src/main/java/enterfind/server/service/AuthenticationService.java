package enterfind.server.service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import enterfind.server.dao.ApiSessionDao;
import enterfind.server.dao.ApiUserDao;
import enterfind.server.dao.HumanUserDao;
import enterfind.server.dao.SessionDao;
import enterfind.server.dao.UserSettingsDao;
import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.model.ApiSession;
import enterfind.server.model.ApiUser;
import enterfind.server.model.HumanUser;
import enterfind.server.model.Session;
import enterfind.server.model.UserLevel;
import enterfind.server.model.UserSettingName;

/**
 * Service for authentication operations.
 */
@Service
public class AuthenticationService {

    private static final Logger logger = LoggerFactory.getLogger(AuthenticationService.class);
    private static final int TOKEN_LENGTH = 64;
    private static final String TOKEN_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int DEFAULT_SESSION_DURATION_MINUTES = 1440; // 24 hours
    private static final int DEFAULT_REMEMBER_ME_DURATION_DAYS = 30;
    private static final String API_KEY_PREFIX = "efk_";
    private static final int API_KEY_SUFFIX_LENGTH = 32;

    private final HumanUserDao humanUserDao;
    private final SessionDao sessionDao;
    private final ApiUserDao apiUserDao;
    private final ApiSessionDao apiSessionDao;
    private final UserSettingsDao userSettingsDao;
    private final TotpService totpService;
    private final SecureRandom secureRandom;

    public AuthenticationService(HumanUserDao humanUserDao, SessionDao sessionDao,
            ApiUserDao apiUserDao, ApiSessionDao apiSessionDao,
            UserSettingsDao userSettingsDao, TotpService totpService) {
        this.humanUserDao = humanUserDao;
        this.sessionDao = sessionDao;
        this.apiUserDao = apiUserDao;
        this.apiSessionDao = apiSessionDao;
        this.userSettingsDao = userSettingsDao;
        this.totpService = totpService;
        this.secureRandom = new SecureRandom();
    }

    /**
     * Check if authentication is required (true if any users exist).
     */
    public boolean isAuthenticationRequired() {
        return humanUserDao.hasAnyUsers();
    }

    /**
     * Login with nickname and TOTP code.
     */
    public Session login(String nickname, String totpCode, boolean rememberMe) {
        // Find user
        Optional<HumanUser> optUser = humanUserDao.findByNickname(nickname);
        if (optUser.isEmpty()) {
            logAuthEvent("LOGIN_FAILED_USER_NOT_FOUND", null, nickname);
            throw new InvalidArgumentException("Invalid credentials");
        }

        HumanUser user = optUser.get();

        // Check if user is active
        if (!user.isActive()) {
            logAuthEvent("LOGIN_FAILED_USER_INACTIVE", user.id(), nickname);
            throw new InvalidArgumentException("Account is disabled");
        }

        // Verify TOTP code
        if (!totpService.verifyCode(user.totpSecret(), totpCode)) {
            logAuthEvent("LOGIN_FAILED_INVALID_TOTP", user.id(), nickname);
            throw new InvalidArgumentException("Invalid credentials");
        }

        // Create session
        Session session = createSession(user.id(), rememberMe);

        // Update last login
        humanUserDao.updateLastLogin(user.id());

        logAuthEvent("LOGIN_SUCCESS", user.id(), nickname);
        return session;
    }

    /**
     * Login with recovery code.
     */
    public Session loginWithRecoveryCode(String nickname, String recoveryCode) {
        // Find user
        Optional<HumanUser> optUser = humanUserDao.findByNickname(nickname);
        if (optUser.isEmpty()) {
            logAuthEvent("LOGIN_FAILED_USER_NOT_FOUND", null, nickname);
            throw new InvalidArgumentException("Invalid credentials");
        }

        HumanUser user = optUser.get();

        // Check if user is active
        if (!user.isActive()) {
            logAuthEvent("LOGIN_FAILED_USER_INACTIVE", user.id(), nickname);
            throw new InvalidArgumentException("Account is disabled");
        }

        // Verify and consume recovery code
        String matchedCode = totpService.verifyRecoveryCode(recoveryCode, user.recoveryCodes());
        if (matchedCode == null) {
            logAuthEvent("LOGIN_FAILED_INVALID_RECOVERY_CODE", user.id(), nickname);
            throw new InvalidArgumentException("Invalid recovery code");
        }

        // Remove used recovery code
        String updatedCodes = totpService.consumeRecoveryCode(matchedCode, user.recoveryCodes());
        humanUserDao.updateRecoveryCodes(user.id(), updatedCodes);

        // Create session (recovery login does not use remember me)
        Session session = createSession(user.id(), false);

        // Update last login
        humanUserDao.updateLastLogin(user.id());

        logAuthEvent("LOGIN_WITH_RECOVERY_CODE", user.id(), nickname);
        return session;
    }

    /**
     * Logout by invalidating the session.
     */
    public boolean logout(String token) {
        Optional<Session> optSession = sessionDao.findByToken(token);
        if (optSession.isEmpty()) {
            return false;
        }

        Session session = optSession.get();
        sessionDao.invalidate(token);

        // Get user for logging
        humanUserDao.findById(session.userId()).ifPresent(user -> {
            logAuthEvent("LOGOUT", user.id(), user.nickname());
        });

        return true;
    }

    /**
     * Validate a session token and return the associated user.
     * Checks both human sessions and API sessions.
     * Returns HumanUser for human sessions, or a "virtual" HumanUser for API sessions.
     */
    public Optional<HumanUser> validateToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        // Check human sessions first
        Optional<Session> optSession = sessionDao.findValidByToken(token);
        if (optSession.isPresent()) {
            return humanUserDao.findById(optSession.get().userId());
        }

        // Check API sessions
        Optional<ApiSession> optApiSession = apiSessionDao.findValidByToken(token);
        if (optApiSession.isPresent()) {
            Optional<ApiUser> optApiUser = apiUserDao.findById(optApiSession.get().apiUserId());
            if (optApiUser.isPresent()) {
                ApiUser apiUser = optApiUser.get();
                // Update last used timestamp
                apiUserDao.updateLastUsed(apiUser.id());
                // Return a "virtual" HumanUser for API users
                return Optional.of(new HumanUser(
                    apiUser.id(),
                    apiUser.nickname(),
                    null, // no TOTP secret
                    null, // no recovery codes
                    apiUser.userLevel(),
                    apiUser.dateCreated(),
                    apiUser.dateLastUsed(),
                    apiUser.isActive()
                ));
            }
        }

        return Optional.empty();
    }

    /**
     * Check if a user has the required permission level.
     */
    public boolean hasPermission(String token, UserLevel requiredLevel) {
        Optional<HumanUser> optUser = validateToken(token);
        if (optUser.isEmpty()) {
            return false;
        }

        HumanUser user = optUser.get();
        if (!user.isActive()) {
            return false;
        }

        // ADMIN has all permissions
        if (user.userLevel() == UserLevel.ADMIN) {
            return true;
        }

        // READER can only access READER-level endpoints
        return requiredLevel == UserLevel.READER;
    }

    /**
     * Create a new user with TOTP setup.
     * Returns the user along with the TOTP secret and recovery codes (before encryption).
     */
    public UserCreationResult createUser(String nickname, UserLevel userLevel, String createdByNickname) {
        // Validate nickname
        if (nickname == null || nickname.isBlank()) {
            throw new InvalidArgumentException("Nickname cannot be empty");
        }

        if (humanUserDao.existsByNickname(nickname)) {
            throw new InvalidArgumentException("Nickname already exists");
        }

        // Generate TOTP secret and recovery codes
        String totpSecret = totpService.generateSecret();
        List<String> recoveryCodes = totpService.generateRecoveryCodes();
        String serializedRecoveryCodes = totpService.serializeRecoveryCodes(recoveryCodes);

        // Create user
        HumanUser user = humanUserDao.create(nickname, totpSecret, serializedRecoveryCodes, userLevel);

        logAuthEvent("USER_CREATED", user.id(), nickname,
            "createdBy=" + (createdByNickname != null ? createdByNickname : "system"));

        return new UserCreationResult(user, totpSecret, recoveryCodes);
    }

    /**
     * Regenerate TOTP secret for a user.
     */
    public TotpRegenerationResult regenerateTotpSecret(String userId, String regeneratedByNickname) {
        Optional<HumanUser> optUser = humanUserDao.findById(userId);
        if (optUser.isEmpty()) {
            throw new InvalidArgumentException("User not found");
        }

        HumanUser user = optUser.get();

        // Generate new TOTP secret and recovery codes
        String newTotpSecret = totpService.generateSecret();
        List<String> newRecoveryCodes = totpService.generateRecoveryCodes();
        String serializedRecoveryCodes = totpService.serializeRecoveryCodes(newRecoveryCodes);

        // Update user
        humanUserDao.updateTotpSecret(userId, newTotpSecret);
        humanUserDao.updateRecoveryCodes(userId, serializedRecoveryCodes);

        // Invalidate all existing sessions
        sessionDao.invalidateAllForUser(userId);

        logAuthEvent("TOTP_SECRET_REGENERATED", userId, user.nickname(),
            "regeneratedBy=" + (regeneratedByNickname != null ? regeneratedByNickname : "system"));

        return new TotpRegenerationResult(newTotpSecret, newRecoveryCodes);
    }

    /**
     * Regenerate recovery codes for a user.
     */
    public List<String> regenerateRecoveryCodes(String userId, String regeneratedByNickname) {
        Optional<HumanUser> optUser = humanUserDao.findById(userId);
        if (optUser.isEmpty()) {
            throw new InvalidArgumentException("User not found");
        }

        HumanUser user = optUser.get();

        // Generate new recovery codes
        List<String> newRecoveryCodes = totpService.generateRecoveryCodes();
        String serializedRecoveryCodes = totpService.serializeRecoveryCodes(newRecoveryCodes);

        // Update user
        humanUserDao.updateRecoveryCodes(userId, serializedRecoveryCodes);

        logAuthEvent("RECOVERY_CODES_REGENERATED", userId, user.nickname(),
            "regeneratedBy=" + (regeneratedByNickname != null ? regeneratedByNickname : "system"));

        return newRecoveryCodes;
    }

    /**
     * Delete a user.
     */
    public boolean deleteUser(String userId, String deletedByNickname) {
        Optional<HumanUser> optUser = humanUserDao.findById(userId);
        if (optUser.isEmpty()) {
            return false;
        }

        HumanUser user = optUser.get();
        String nickname = user.nickname();

        boolean deleted = humanUserDao.delete(userId);

        if (deleted) {
            logAuthEvent("USER_DELETED", userId, nickname,
                "deletedBy=" + (deletedByNickname != null ? deletedByNickname : "system"));
        }

        return deleted;
    }

    /**
     * Create a session for a user.
     */
    private Session createSession(String userId, boolean rememberMe) {
        String token = generateSessionToken();
        LocalDateTime expiresAt = calculateSessionExpiry(rememberMe);
        return sessionDao.create(token, userId, expiresAt, rememberMe);
    }

    /**
     * Generate a secure session token.
     */
    private String generateSessionToken() {
        StringBuilder token = new StringBuilder(TOKEN_LENGTH);
        for (int i = 0; i < TOKEN_LENGTH; i++) {
            int index = secureRandom.nextInt(TOKEN_CHARS.length());
            token.append(TOKEN_CHARS.charAt(index));
        }
        return token.toString();
    }

    /**
     * Generate an API key with efk_ prefix.
     */
    private String generateApiKey() {
        StringBuilder key = new StringBuilder(API_KEY_PREFIX);
        for (int i = 0; i < API_KEY_SUFFIX_LENGTH; i++) {
            int index = secureRandom.nextInt(TOKEN_CHARS.length());
            key.append(TOKEN_CHARS.charAt(index));
        }
        return key.toString();
    }

    /**
     * Create an API session for an API user.
     */
    private ApiSession createApiSession(String apiUserId, LocalDateTime expiresAt) {
        String token = generateSessionToken();
        return apiSessionDao.create(token, apiUserId, expiresAt);
    }

    /**
     * Calculate session expiry based on remember me setting.
     */
    private LocalDateTime calculateSessionExpiry(boolean rememberMe) {
        if (rememberMe) {
            int days = getRememberMeDurationDays();
            return LocalDateTime.now().plusDays(days);
        } else {
            int minutes = getSessionDurationMinutes();
            return LocalDateTime.now().plusMinutes(minutes);
        }
    }

    /**
     * Get session duration from settings.
     */
    public int getSessionDurationMinutes() {
        return userSettingsDao.getSetting(UserSettingName.SESSION_DURATION_MINUTES)
            .map(Integer::parseInt)
            .orElse(DEFAULT_SESSION_DURATION_MINUTES);
    }

    /**
     * Get remember me duration from settings.
     */
    public int getRememberMeDurationDays() {
        return userSettingsDao.getSetting(UserSettingName.REMEMBER_ME_DURATION_DAYS)
            .map(Integer::parseInt)
            .orElse(DEFAULT_REMEMBER_ME_DURATION_DAYS);
    }

    /**
     * Log an authentication event.
     */
    private void logAuthEvent(String type, String userId, String nickname) {
        logAuthEvent(type, userId, nickname, null);
    }

    /**
     * Log an authentication event with additional details.
     */
    private void logAuthEvent(String type, String userId, String nickname, String details) {
        StringBuilder logMessage = new StringBuilder();
        logMessage.append("AUTH_EVENT: type=").append(type);
        logMessage.append(", user=").append(nickname != null ? nickname : "unknown");

        if (userId != null) {
            logMessage.append(", userId=").append(userId);
        }

        if (details != null) {
            logMessage.append(", ").append(details);
        }

        logger.info(logMessage.toString());
    }

    /**
     * Login with API key.
     */
    public ApiSession loginWithApiKey(String nickname, String keyValue) {
        // Find API user
        Optional<ApiUser> optApiUser = apiUserDao.findByNickname(nickname);
        if (optApiUser.isEmpty()) {
            logAuthEvent("API_LOGIN_FAILED_USER_NOT_FOUND", null, nickname);
            throw new InvalidArgumentException("Invalid credentials");
        }

        ApiUser apiUser = optApiUser.get();

        // Check if API user is active
        if (!apiUser.isActive()) {
            logAuthEvent("API_LOGIN_FAILED_USER_INACTIVE", apiUser.id(), nickname);
            throw new InvalidArgumentException("API key is disabled");
        }

        // Check if API key has expired
        if (apiUser.isExpired()) {
            logAuthEvent("API_LOGIN_FAILED_KEY_EXPIRED", apiUser.id(), nickname);
            throw new InvalidArgumentException("API key has expired");
        }

        // Verify key value
        if (!apiUser.keyValue().equals(keyValue)) {
            logAuthEvent("API_LOGIN_FAILED_INVALID_KEY", apiUser.id(), nickname);
            throw new InvalidArgumentException("Invalid credentials");
        }

        // Create API session (expires when the API key expires)
        ApiSession session = createApiSession(apiUser.id(), apiUser.dateExpires());

        // Update last used
        apiUserDao.updateLastUsed(apiUser.id());

        logAuthEvent("API_LOGIN_SUCCESS", apiUser.id(), nickname);
        return session;
    }

    /**
     * Create an API user.
     */
    public ApiUserCreationResult createApiUser(String nickname, UserLevel userLevel,
                                               int expirationDays, String createdByUserId) {
        // Ensure at least one human user exists before allowing API key creation
        if (!humanUserDao.hasAnyUsers()) {
            throw new InvalidArgumentException("Cannot create API keys: no human users configured");
        }

        // Validate nickname
        if (nickname == null || nickname.isBlank()) {
            throw new InvalidArgumentException("Nickname cannot be empty");
        }

        if (apiUserDao.existsByNickname(nickname)) {
            throw new InvalidArgumentException("API user nickname already exists");
        }

        // Validate expiration days
        if (expirationDays <= 0) {
            throw new InvalidArgumentException("Expiration days must be positive");
        }

        // Generate API key
        String keyValue = generateApiKey();
        LocalDateTime dateExpires = LocalDateTime.now().plusDays(expirationDays);

        // Create API user
        ApiUser apiUser = apiUserDao.create(nickname, keyValue, userLevel, createdByUserId, dateExpires);

        // Get creator nickname for logging
        String creatorNickname = null;
        if (createdByUserId != null) {
            creatorNickname = humanUserDao.findById(createdByUserId)
                .map(HumanUser::nickname)
                .orElse(null);
        }

        logAuthEvent("API_USER_CREATED", apiUser.id(), nickname,
            "createdBy=" + (creatorNickname != null ? creatorNickname : "system") +
            ", expiresIn=" + expirationDays + "days");

        return new ApiUserCreationResult(apiUser, keyValue);
    }

    /**
     * Delete an API user.
     */
    public boolean deleteApiUser(String apiUserId, String deletedByNickname) {
        Optional<ApiUser> optApiUser = apiUserDao.findById(apiUserId);
        if (optApiUser.isEmpty()) {
            return false;
        }

        ApiUser apiUser = optApiUser.get();
        String nickname = apiUser.nickname();

        // Invalidate all API sessions
        apiSessionDao.invalidateAllForApiUser(apiUserId);

        boolean deleted = apiUserDao.delete(apiUserId);

        if (deleted) {
            logAuthEvent("API_USER_DELETED", apiUserId, nickname,
                "deletedBy=" + (deletedByNickname != null ? deletedByNickname : "system"));
        }

        return deleted;
    }

    /**
     * Cleanup expired sessions.
     */
    public int cleanupExpiredSessions() {
        int humanSessionsDeleted = sessionDao.cleanupExpired();
        int apiSessionsDeleted = apiSessionDao.cleanupExpired();
        return humanSessionsDeleted + apiSessionsDeleted;
    }

    /**
     * Result of user creation containing user and plaintext secrets.
     */
    public record UserCreationResult(HumanUser user, String totpSecret, List<String> recoveryCodes) {}

    /**
     * Result of TOTP regeneration containing plaintext secrets.
     */
    public record TotpRegenerationResult(String totpSecret, List<String> recoveryCodes) {}

    /**
     * Result of API user creation containing user and plaintext API key.
     */
    public record ApiUserCreationResult(ApiUser apiUser, String keyValue) {}
}
