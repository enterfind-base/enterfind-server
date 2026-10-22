package enterfind.server.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;

import enterfind.server.BaseDatabaseTest;
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
import enterfind.server.service.AuthenticationService.TotpRegenerationResult;
import enterfind.server.service.AuthenticationService.UserCreationResult;

@SpringBootTest
@ActiveProfiles("alpha")
public class AuthenticationServiceTest extends BaseDatabaseTest {

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private HumanUserDao humanUserDao;

    @Autowired
    private UserSettingsDao userSettingsDao;

    @SuppressWarnings("unused")
    @Autowired
    private SessionDao sessionDao;

    @SuppressWarnings("unused")
    @Autowired
    private TotpService totpService;

    private MockHttpServletRequest mockRequest;

    @Override
    @BeforeEach
    protected void setUp() {
        super.setUp();
        // Clean up auth tables
        dbAccess.execute("DELETE FROM SESSIONS");
        dbAccess.execute("DELETE FROM HUMAN_USERS");

        // Create mock request
        mockRequest = new MockHttpServletRequest();
        mockRequest.setRemoteAddr("127.0.0.1");
    }

    @Test
    public void testIsAuthenticationRequired_noUsers() {
        assertFalse(authenticationService.isAuthenticationRequired());
    }

    @Test
    public void testIsAuthenticationRequired_withUsers() {
        humanUserDao.create("testuser", "secret", "codes", UserLevel.ADMIN);

        assertTrue(authenticationService.isAuthenticationRequired());
    }

    @Test
    public void testCreateUser() {
        UserCreationResult result = authenticationService.createUser(
            "testuser",
            UserLevel.ADMIN,
            null
        );

        assertNotNull(result);
        assertNotNull(result.user());
        assertNotNull(result.totpSecret());
        assertNotNull(result.recoveryCodes());
        assertEquals(10, result.recoveryCodes().size());
        assertEquals("testuser", result.user().nickname());
        assertEquals(UserLevel.ADMIN, result.user().userLevel());
    }

    @Test
    public void testCreateUser_duplicateNickname() {
        authenticationService.createUser("testuser", UserLevel.ADMIN, null);

        assertThrows(InvalidArgumentException.class, () -> {
            authenticationService.createUser("testuser", UserLevel.READER, null);
        });
    }

    @Test
    public void testCreateUser_duplicateNicknameCaseInsensitive() {
        authenticationService.createUser("TestUser", UserLevel.ADMIN, null);

        assertThrows(InvalidArgumentException.class, () -> {
            authenticationService.createUser("testuser", UserLevel.READER, null);
        });
    }

    @Test
    public void testLogin_invalidNickname() {
        assertThrows(InvalidArgumentException.class, () -> {
            authenticationService.login("nonexistent", "123456", false);
        });
    }

    @Test
    public void testLogin_inactiveUser() {
        UserCreationResult created = authenticationService.createUser("testuser", UserLevel.ADMIN, null);
        humanUserDao.update(created.user().id(), null, null, false);

        assertThrows(InvalidArgumentException.class, () -> {
            authenticationService.login("testuser", "123456", false);
        });
    }

    @Test
    public void testLogin_invalidTotpCode() {
        authenticationService.createUser("testuser", UserLevel.ADMIN, null);

        assertThrows(InvalidArgumentException.class, () -> {
            authenticationService.login("testuser", "000000", false);
        });
    }

    @Test
    public void testLoginWithRecoveryCode_valid() {
        UserCreationResult created = authenticationService.createUser("testuser", UserLevel.ADMIN, null);
        String recoveryCode = created.recoveryCodes().get(0);

        Session session = authenticationService.loginWithRecoveryCode(
            "testuser",
            recoveryCode
        );

        assertNotNull(session);
        assertNotNull(session.token());
        assertEquals(created.user().id(), session.userId());
    }

    @Test
    public void testLoginWithRecoveryCode_invalid() {
        authenticationService.createUser("testuser", UserLevel.ADMIN, null);

        assertThrows(InvalidArgumentException.class, () -> {
            authenticationService.loginWithRecoveryCode("testuser", "INVALID1");
        });
    }

    @Test
    public void testLoginWithRecoveryCode_cannotReuse() {
        UserCreationResult created = authenticationService.createUser("testuser", UserLevel.ADMIN, null);
        String recoveryCode = created.recoveryCodes().get(0);

        // First use should succeed
        authenticationService.loginWithRecoveryCode("testuser", recoveryCode);

        // Verify recovery codes were updated in database (one code consumed)
        var updatedUser = humanUserDao.findByNickname("testuser");
        assertTrue(updatedUser.isPresent(), "User should still exist in database");
        int remainingCodes = humanUserDao.countRecoveryCodes(updatedUser.get().id());
        assertEquals(9, remainingCodes, "Should have 9 recovery codes remaining after using one");

        // Second use should fail
        assertThrows(InvalidArgumentException.class, () -> {
            authenticationService.loginWithRecoveryCode("testuser", recoveryCode);
        });

        // Verify recovery code count is still 9 after failed attempt
        var userAfterFailedAttempt = humanUserDao.findByNickname("testuser");
        int remainingCodesAfterFailedAttempt = humanUserDao.countRecoveryCodes(userAfterFailedAttempt.get().id());
        assertEquals(9, remainingCodesAfterFailedAttempt,
            "Recovery code count should not change after failed reuse attempt");
    }

    @Test
    public void testValidateToken_valid() {
        UserCreationResult created = authenticationService.createUser("testuser", UserLevel.ADMIN, null);
        String recoveryCode = created.recoveryCodes().get(0);
        Session session = authenticationService.loginWithRecoveryCode("testuser", recoveryCode);

        Optional<HumanUser> user = authenticationService.validateToken(session.token());

        assertTrue(user.isPresent());
        assertEquals("testuser", user.get().nickname());
    }

    @Test
    public void testValidateToken_invalid() {
        Optional<HumanUser> user = authenticationService.validateToken("invalid_token");

        assertFalse(user.isPresent());
    }

    @Test
    public void testLogout() {
        UserCreationResult created = authenticationService.createUser("testuser", UserLevel.ADMIN, null);
        String recoveryCode = created.recoveryCodes().get(0);
        Session session = authenticationService.loginWithRecoveryCode("testuser", recoveryCode);

        boolean loggedOut = authenticationService.logout(session.token());

        assertTrue(loggedOut);

        // Token should no longer be valid
        Optional<HumanUser> user = authenticationService.validateToken(session.token());
        assertFalse(user.isPresent());
    }

    @Test
    public void testLogout_invalidToken() {
        boolean loggedOut = authenticationService.logout("invalid_token");

        assertFalse(loggedOut);
    }

    @Test
    public void testDeleteUser() {
        UserCreationResult created = authenticationService.createUser("testuser", UserLevel.ADMIN, null);

        boolean deleted = authenticationService.deleteUser(created.user().id(), "admin");

        assertTrue(deleted);
        assertFalse(humanUserDao.findById(created.user().id()).isPresent());
    }

    @Test
    public void testDeleteUser_notFound() {
        boolean deleted = authenticationService.deleteUser("nonexistent", "admin");

        assertFalse(deleted);
    }

    @Test
    public void testRegenerateTotpSecret() {
        UserCreationResult created = authenticationService.createUser("testuser", UserLevel.ADMIN, null);
        String originalSecret = created.totpSecret();

        TotpRegenerationResult result = authenticationService.regenerateTotpSecret(
            created.user().id(),
            "admin"
        );

        assertNotNull(result);
        assertNotNull(result.totpSecret());
        assertNotNull(result.recoveryCodes());
        assertNotEquals(originalSecret, result.totpSecret());
        assertEquals(10, result.recoveryCodes().size());
    }

    @Test
    public void testRegenerateTotpSecret_notFound() {
        assertThrows(InvalidArgumentException.class, () -> {
            authenticationService.regenerateTotpSecret("nonexistent", "admin");
        });
    }

    @Test
    public void testRegenerateRecoveryCodes() {
        UserCreationResult created = authenticationService.createUser("testuser", UserLevel.ADMIN, null);
        String originalFirstCode = created.recoveryCodes().get(0);

        List<String> newCodes = authenticationService.regenerateRecoveryCodes(
            created.user().id(),
            "admin"
        );

        assertNotNull(newCodes);
        assertEquals(10, newCodes.size());

        // Verify new codes are persisted in database
        var userAfterRegen = humanUserDao.findById(created.user().id());
        assertTrue(userAfterRegen.isPresent(), "User should exist in database");
        int remainingCodes = humanUserDao.countRecoveryCodes(userAfterRegen.get().id());
        assertEquals(10, remainingCodes, "Should have 10 new recovery codes in database");

        // Old recovery codes should no longer work
        assertThrows(InvalidArgumentException.class, () -> {
            authenticationService.loginWithRecoveryCode("testuser", originalFirstCode);
        }, "Old recovery code should not work after regeneration");

        // New recovery codes should work
        Session session = authenticationService.loginWithRecoveryCode("testuser", newCodes.get(0));
        assertNotNull(session);

        // Verify that the new code was consumed and persisted
        var userAfterNewCodeUsed = humanUserDao.findById(created.user().id());
        int remainingCodesAfterUse = humanUserDao.countRecoveryCodes(userAfterNewCodeUsed.get().id());
        assertEquals(9, remainingCodesAfterUse,
            "Should have 9 recovery codes remaining after using one of the new codes");
    }

    @Test
    public void testRegenerateRecoveryCodes_notFound() {
        assertThrows(InvalidArgumentException.class, () -> {
            authenticationService.regenerateRecoveryCodes("nonexistent", "admin");
        });
    }

    @Test
    public void testHasPermission_adminToken() {
        UserCreationResult created = authenticationService.createUser("admin", UserLevel.ADMIN, null);
        String recoveryCode = created.recoveryCodes().get(0);
        Session session = authenticationService.loginWithRecoveryCode("admin", recoveryCode);

        // Admin should have all permissions
        assertTrue(authenticationService.hasPermission(session.token(), UserLevel.READER));
        assertTrue(authenticationService.hasPermission(session.token(), UserLevel.ADMIN));
    }

    @Test
    public void testHasPermission_readerToken() {
        UserCreationResult created = authenticationService.createUser("reader", UserLevel.READER, null);
        String recoveryCode = created.recoveryCodes().get(0);
        Session session = authenticationService.loginWithRecoveryCode("reader", recoveryCode);

        // Reader should have READER permission
        assertTrue(authenticationService.hasPermission(session.token(), UserLevel.READER));

        // Reader should not have ADMIN permission
        assertFalse(authenticationService.hasPermission(session.token(), UserLevel.ADMIN));
    }

    @Test
    public void testHasPermission_invalidToken() {
        // Invalid token should not have any permissions
        assertFalse(authenticationService.hasPermission("invalid_token", UserLevel.READER));
        assertFalse(authenticationService.hasPermission("invalid_token", UserLevel.ADMIN));
    }

    @Test
    public void testHasPermission_inactiveUser() {
        UserCreationResult created = authenticationService.createUser("admin", UserLevel.ADMIN, null);
        String recoveryCode = created.recoveryCodes().get(0);
        Session session = authenticationService.loginWithRecoveryCode("admin", recoveryCode);

        // Deactivate the user
        humanUserDao.update(created.user().id(), null, null, false);

        // Inactive user should not have any permissions
        assertFalse(authenticationService.hasPermission(session.token(), UserLevel.READER));
    }

    // ========== API session lifetime ==========

    @Test
    public void testApiSessionExpiry_defaultsToSixtyMinutes() {
        ApiUser apiUser = apiUserExpiringAt(LocalDateTime.now().plusDays(365));

        LocalDateTime expiry = authenticationService.calculateApiSessionExpiry(apiUser);

        assertEquals(60, authenticationService.getApiSessionDurationMinutes());
        assertWithinSeconds(LocalDateTime.now().plusMinutes(60), expiry);
    }

    @Test
    public void testApiSessionExpiry_neverOutlivesItsKey() {
        LocalDateTime keyExpiry = LocalDateTime.now().plusMinutes(10);

        assertEquals(keyExpiry, authenticationService.calculateApiSessionExpiry(apiUserExpiringAt(keyExpiry)));
    }

    @Test
    public void testApiSessionDuration_isClampedToItsBounds() {
        userSettingsDao.setSetting(UserSettingName.API_SESSION_DURATION_MINUTES, "0");
        assertEquals(1, authenticationService.getApiSessionDurationMinutes());

        userSettingsDao.setSetting(UserSettingName.API_SESSION_DURATION_MINUTES, "5000");
        assertEquals(AuthenticationService.MAX_API_SESSION_DURATION_MINUTES,
            authenticationService.getApiSessionDurationMinutes());
    }

    @Test
    public void testLoginWithApiKey_sessionUsesApiSessionDuration() {
        UserCreationResult admin = authenticationService.createUser("admin", UserLevel.ADMIN, null);
        var created = authenticationService.createApiUser("script", UserLevel.READER, 365, admin.user().id());

        ApiSession session = authenticationService.loginWithApiKey("script", created.keyValue());

        assertWithinSeconds(LocalDateTime.now().plusMinutes(60), session.dateExpires());
    }

    @Test
    public void testFindApiUserByKey() {
        UserCreationResult admin = authenticationService.createUser("admin", UserLevel.ADMIN, null);
        var created = authenticationService.createApiUser("script", UserLevel.READER, 365, admin.user().id());

        assertEquals("script", authenticationService.findApiUserByKey(created.keyValue()).orElseThrow().nickname());
        assertTrue(authenticationService.findApiUserByKey("efk_unknown").isEmpty());
    }

    private static ApiUser apiUserExpiringAt(LocalDateTime dateExpires) {
        return new ApiUser("api1", "api", "efk_x", UserLevel.READER, null,
            LocalDateTime.now(), dateExpires, null, true);
    }

    private static void assertWithinSeconds(LocalDateTime expected, LocalDateTime actual) {
        long seconds = Math.abs(Duration.between(expected, actual).toSeconds());
        assertTrue(seconds <= 5, "expected " + expected + " but was " + actual);
    }
}
