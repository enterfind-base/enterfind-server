package enterfind.server.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.dao.ApiSessionDao;
import enterfind.server.dao.ApiUserDao;
import enterfind.server.model.ApiUser;
import enterfind.server.model.UserLevel;
import enterfind.server.service.AuthenticationService;

@SpringBootTest
@ActiveProfiles("alpha")
class ApiKeyCredentialResolverTest extends BaseDatabaseTest {

    @Autowired
    private ApiKeyCredentialResolver resolver;

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private ApiUserDao apiUserDao;

    @Autowired
    private ApiSessionDao apiSessionDao;

    private String adminId;
    private ApiUser apiUser;
    private String key;

    @Override
    @BeforeEach
    protected void setUp() {
        super.setUp();
        adminId = authenticationService.createUser("admin", UserLevel.ADMIN, null).user().id();
        var created = authenticationService.createApiUser("connector", UserLevel.READER, 365, adminId);
        apiUser = created.apiUser();
        key = created.keyValue();
    }

    @Test
    void noHeaderIsAbsent() {
        assertInstanceOf(ApiKeyExchange.Absent.class, resolver.resolve(null));
        assertInstanceOf(ApiKeyExchange.Absent.class, resolver.resolve("  "));
    }

    @Test
    void headerIsIgnoredWithoutUsers() {
        dbAccess.execute("DELETE FROM API_SESSIONS");
        dbAccess.execute("DELETE FROM API_USERS");
        dbAccess.execute("DELETE FROM HUMAN_USERS");

        assertInstanceOf(ApiKeyExchange.Ignored.class, resolver.resolve("Bearer efk_whatever"));
    }

    @Test
    void acceptsTheKeyWithOrWithoutBearer() {
        String bare = token(resolver.resolve(key));

        assertEquals(bare, token(resolver.resolve("Bearer " + key)));
        assertEquals(bare, token(resolver.resolve("bearer " + key)));
        assertEquals(bare, token(resolver.resolve("  Bearer   " + key + "  ")));
    }

    @Test
    void reusesTheLatestExpiringFreshSession() {
        apiSessionDao.create("fresh-a", apiUser.id(), LocalDateTime.now().plusMinutes(40));
        apiSessionDao.create("fresh-b", apiUser.id(), LocalDateTime.now().plusMinutes(50));

        assertEquals("fresh-b", token(resolver.resolve(key)));
        assertEquals(2, apiSessionDao.countValidSessionsForApiUser(apiUser.id()));
    }

    @Test
    void logsInAgainWhenOnlyAStaleSessionIsLeft() {
        // Less than half of the default 60-minute lifetime left
        apiSessionDao.create("stale", apiUser.id(), LocalDateTime.now().plusMinutes(20));

        String token = token(resolver.resolve(key));

        assertNotEquals("stale", token);
        assertEquals(2, apiSessionDao.countValidSessionsForApiUser(apiUser.id()));
    }

    @Test
    void reusesASessionThatLastsUntilTheKeyExpires() {
        LocalDateTime keyExpiry = LocalDateTime.now().plusMinutes(10);
        dbAccess.update("UPDATE API_USERS SET date_expires = ? WHERE id = ?",
            Timestamp.valueOf(keyExpiry), apiUser.id());
        apiSessionDao.create("until-key-expiry", apiUser.id(), keyExpiry);

        assertEquals("until-key-expiry", token(resolver.resolve(key)));
    }

    @Test
    void secondCallReusesTheFirstCallsSession() {
        assertEquals(token(resolver.resolve(key)), token(resolver.resolve(key)));
        assertEquals(1, apiSessionDao.countValidSessionsForApiUser(apiUser.id()));
    }

    @Test
    void nonApiKeyValueFails() {
        assertTrue(reason(resolver.resolve("Bearer some-session-token")).contains("efk_"));
    }

    @Test
    void unknownKeyFailsWithoutEchoingIt() {
        String reason = reason(resolver.resolve("Bearer efk_notakeyofthisinstance"));

        assertTrue(reason.contains("not valid"));
        assertFalse(reason.contains("efk_notakeyofthisinstance"));
    }

    @Test
    void expiredKeyFailsWithReason() {
        dbAccess.update("UPDATE API_USERS SET date_expires = ? WHERE id = ?",
            Timestamp.valueOf(LocalDateTime.now().minusDays(1)), apiUser.id());

        assertTrue(reason(resolver.resolve(key)).contains("API key has expired"));
    }

    @Test
    void deactivatedUserFailsEvenWithALeftoverSession() {
        apiSessionDao.create("leftover", apiUser.id(), LocalDateTime.now().plusMinutes(50));
        apiUserDao.deactivate(apiUser.id());

        assertTrue(reason(resolver.resolve(key)).contains("API key is disabled"));
    }

    @Test
    void deletedApiUserFails() {
        token(resolver.resolve(key));
        authenticationService.deleteApiUser(apiUser.id(), "admin");

        assertTrue(reason(resolver.resolve(key)).contains("not valid"));
    }

    private static String token(ApiKeyExchange exchange) {
        return assertInstanceOf(ApiKeyExchange.Resolved.class, exchange).sessionToken();
    }

    private static String reason(ApiKeyExchange exchange) {
        return assertInstanceOf(ApiKeyExchange.Failed.class, exchange).reason();
    }
}
