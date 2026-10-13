package enterfind.server.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.model.ApiSession;
import enterfind.server.model.ApiUser;
import enterfind.server.model.UserLevel;

@SpringBootTest
@ActiveProfiles("alpha")
public class ApiSessionDaoTest extends BaseDatabaseTest {

    @Autowired
    private ApiSessionDao apiSessionDao;

    @Autowired
    private ApiUserDao apiUserDao;

    @Autowired
    private HumanUserDao humanUserDao;

    private String apiUserId;

    @Override
    @BeforeEach
    protected void setUp() {
        super.setUp();
        // Clean up tables
        dbAccess.execute("DELETE FROM API_SESSIONS");
        dbAccess.execute("DELETE FROM API_USERS");
        dbAccess.execute("DELETE FROM SESSIONS");
        dbAccess.execute("DELETE FROM HUMAN_USERS");

        // Create test API user
        var creator = humanUserDao.create("admin", "secret", "codes", UserLevel.ADMIN);
        LocalDateTime expires = LocalDateTime.now().plusDays(365);
        ApiUser apiUser = apiUserDao.create("test-api", "efk_test123", UserLevel.READER, creator.id(), expires);
        apiUserId = apiUser.id();
    }

    @Test
    public void testCreate() {
        LocalDateTime expires = LocalDateTime.now().plusHours(24);
        ApiSession session = apiSessionDao.create("token123", apiUserId, expires);

        assertNotNull(session);
        assertEquals("token123", session.token());
        assertEquals(apiUserId, session.apiUserId());
        assertTrue(session.isValid());
        assertNotNull(session.dateCreated());
        assertEquals(expires, session.dateExpires());
    }

    @Test
    public void testFindByToken() {
        LocalDateTime expires = LocalDateTime.now().plusHours(24);
        apiSessionDao.create("token123", apiUserId, expires);

        Optional<ApiSession> found = apiSessionDao.findByToken("token123");

        assertTrue(found.isPresent());
        assertEquals("token123", found.get().token());
        assertEquals(apiUserId, found.get().apiUserId());
    }

    @Test
    public void testFindByToken_notFound() {
        Optional<ApiSession> found = apiSessionDao.findByToken("nonexistent");

        assertFalse(found.isPresent());
    }

    @Test
    public void testFindValidByToken() {
        LocalDateTime futureExpiry = LocalDateTime.now().plusHours(24);
        apiSessionDao.create("valid_token", apiUserId, futureExpiry);

        LocalDateTime pastExpiry = LocalDateTime.now().minusHours(1);
        apiSessionDao.create("expired_token", apiUserId, pastExpiry);

        Optional<ApiSession> validSession = apiSessionDao.findValidByToken("valid_token");
        assertTrue(validSession.isPresent());

        Optional<ApiSession> expiredSession = apiSessionDao.findValidByToken("expired_token");
        assertFalse(expiredSession.isPresent());
    }

    @Test
    public void testInvalidate() {
        LocalDateTime expires = LocalDateTime.now().plusHours(24);
        apiSessionDao.create("token123", apiUserId, expires);

        boolean invalidated = apiSessionDao.invalidate("token123");

        assertTrue(invalidated);

        Optional<ApiSession> found = apiSessionDao.findValidByToken("token123");
        assertFalse(found.isPresent());
    }

    @Test
    public void testInvalidateAllForApiUser() {
        LocalDateTime expires = LocalDateTime.now().plusHours(24);
        apiSessionDao.create("token1", apiUserId, expires);
        apiSessionDao.create("token2", apiUserId, expires);
        apiSessionDao.create("token3", apiUserId, expires);

        int invalidated = apiSessionDao.invalidateAllForApiUser(apiUserId);

        assertEquals(3, invalidated);

        assertFalse(apiSessionDao.findValidByToken("token1").isPresent());
        assertFalse(apiSessionDao.findValidByToken("token2").isPresent());
        assertFalse(apiSessionDao.findValidByToken("token3").isPresent());
    }

    @Test
    public void testCleanupExpired() {
        // Create expired session
        LocalDateTime pastExpiry = LocalDateTime.now().minusHours(1);
        apiSessionDao.create("expired_token", apiUserId, pastExpiry);

        // Create valid session
        LocalDateTime futureExpiry = LocalDateTime.now().plusHours(24);
        apiSessionDao.create("valid_token", apiUserId, futureExpiry);

        int deleted = apiSessionDao.cleanupExpired();

        assertEquals(1, deleted);
        assertFalse(apiSessionDao.findByToken("expired_token").isPresent());
        assertTrue(apiSessionDao.findByToken("valid_token").isPresent());
    }

    @Test
    public void testFindValidByApiUserId() {
        LocalDateTime expires = LocalDateTime.now().plusHours(24);
        apiSessionDao.create("token1", apiUserId, expires);
        apiSessionDao.create("token2", apiUserId, expires);

        List<ApiSession> sessions = apiSessionDao.findValidByApiUserId(apiUserId);

        assertEquals(2, sessions.size());
    }

    @Test
    public void testCountValidSessionsForApiUser() {
        LocalDateTime expires = LocalDateTime.now().plusHours(24);
        apiSessionDao.create("token1", apiUserId, expires);
        apiSessionDao.create("token2", apiUserId, expires);

        int count = apiSessionDao.countValidSessionsForApiUser(apiUserId);

        assertEquals(2, count);
    }

    @Test
    public void testIsExpired() {
        LocalDateTime pastExpiry = LocalDateTime.now().minusHours(1);
        ApiSession expired = apiSessionDao.create("expired_token", apiUserId, pastExpiry);
        assertTrue(expired.isExpired());

        LocalDateTime futureExpiry = LocalDateTime.now().plusHours(24);
        ApiSession valid = apiSessionDao.create("valid_token", apiUserId, futureExpiry);
        assertFalse(valid.isExpired());
    }

    @Test
    public void testIsCurrentlyValid() {
        LocalDateTime futureExpiry = LocalDateTime.now().plusHours(24);
        ApiSession session = apiSessionDao.create("token", apiUserId, futureExpiry);
        assertTrue(session.isCurrentlyValid());

        apiSessionDao.invalidate("token");
        ApiSession invalidated = apiSessionDao.findByToken("token").get();
        assertFalse(invalidated.isCurrentlyValid());
    }
}
