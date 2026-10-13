package enterfind.server.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.model.HumanUser;
import enterfind.server.model.Session;
import enterfind.server.model.UserLevel;

@SpringBootTest
@ActiveProfiles("alpha")
public class SessionDaoTest extends BaseDatabaseTest {

    @Autowired
    private SessionDao sessionDao;

    @Autowired
    private HumanUserDao humanUserDao;

    private HumanUser testUser;
    private int tokenCounter = 0;

    @Override
    @BeforeEach
    protected void setUp() {
        super.setUp();
        // Clean up auth tables
        dbAccess.execute("DELETE FROM SESSIONS");
        dbAccess.execute("DELETE FROM HUMAN_USERS");

        // Create a test user
        testUser = humanUserDao.create("testuser", "secret123", "codes", UserLevel.ADMIN);
        tokenCounter = 0;
    }

    /**
     * Generate a unique token for testing.
     */
    private String generateTestToken() {
        tokenCounter++;
        return String.format("test_token_%d_%d", System.currentTimeMillis(), tokenCounter);
    }

    @Test
    public void testCreate() {
        String token = generateTestToken();
        LocalDateTime expires = LocalDateTime.now().plusHours(1);
        Session session = sessionDao.create(token, testUser.id(), expires, false);

        assertNotNull(session);
        assertEquals(token, session.token());
        assertEquals(testUser.id(), session.userId());
        assertTrue(session.isValid());
        assertFalse(session.isRememberMe());
        assertNotNull(session.dateCreated());
    }

    @Test
    public void testCreate_rememberMe() {
        String token = generateTestToken();
        LocalDateTime expires = LocalDateTime.now().plusDays(30);
        Session session = sessionDao.create(token, testUser.id(), expires, true);

        assertNotNull(session);
        assertTrue(session.isRememberMe());
    }

    @Test
    public void testFindByToken() {
        String token = generateTestToken();
        LocalDateTime expires = LocalDateTime.now().plusHours(1);
        Session created = sessionDao.create(token, testUser.id(), expires, false);

        Optional<Session> found = sessionDao.findByToken(created.token());

        assertTrue(found.isPresent());
        assertEquals(created.token(), found.get().token());
        assertEquals(testUser.id(), found.get().userId());
    }

    @Test
    public void testFindByToken_notFound() {
        Optional<Session> found = sessionDao.findByToken("nonexistent_token");

        assertFalse(found.isPresent());
    }

    @Test
    public void testFindValidByToken() {
        String token = generateTestToken();
        LocalDateTime expires = LocalDateTime.now().plusHours(1);
        Session session = sessionDao.create(token, testUser.id(), expires, false);

        Optional<Session> found = sessionDao.findValidByToken(session.token());

        assertTrue(found.isPresent());
        assertTrue(found.get().isValid());
    }

    @Test
    public void testFindValidByToken_invalidated() {
        String token = generateTestToken();
        LocalDateTime expires = LocalDateTime.now().plusHours(1);
        Session session = sessionDao.create(token, testUser.id(), expires, false);

        // Invalidate the session
        sessionDao.invalidate(session.token());

        Optional<Session> found = sessionDao.findValidByToken(session.token());

        assertFalse(found.isPresent());
    }

    @Test
    public void testFindValidByToken_expired() {
        // Create a session that's already expired
        String token = generateTestToken();
        LocalDateTime expires = LocalDateTime.now().minusHours(1);
        Session session = sessionDao.create(token, testUser.id(), expires, false);

        Optional<Session> found = sessionDao.findValidByToken(session.token());

        assertFalse(found.isPresent());
    }

    @Test
    public void testInvalidate() {
        String token = generateTestToken();
        LocalDateTime expires = LocalDateTime.now().plusHours(1);
        Session session = sessionDao.create(token, testUser.id(), expires, false);

        boolean invalidated = sessionDao.invalidate(session.token());

        assertTrue(invalidated);

        Optional<Session> found = sessionDao.findByToken(session.token());
        assertTrue(found.isPresent());
        assertFalse(found.get().isValid());
    }

    @Test
    public void testInvalidate_notFound() {
        boolean invalidated = sessionDao.invalidate("nonexistent_token");

        assertFalse(invalidated);
    }

    @Test
    public void testInvalidateAllForUser() {
        LocalDateTime expires = LocalDateTime.now().plusHours(1);

        // Create multiple sessions for the user
        String token1 = generateTestToken();
        String token2 = generateTestToken();
        Session session1 = sessionDao.create(token1, testUser.id(), expires, false);
        Session session2 = sessionDao.create(token2, testUser.id(), expires, true);

        int invalidated = sessionDao.invalidateAllForUser(testUser.id());

        assertEquals(2, invalidated);

        // Verify both sessions are invalidated and not valid
        assertFalse(sessionDao.findValidByToken(session1.token()).isPresent());
        assertFalse(sessionDao.findValidByToken(session2.token()).isPresent());

        // Verify they still exist in database but are marked invalid
        var session1Found = sessionDao.findByToken(session1.token());
        assertTrue(session1Found.isPresent(), "Session 1 should still exist in database");
        assertFalse(session1Found.get().isValid(), "Session 1 should be marked as invalid");

        var session2Found = sessionDao.findByToken(session2.token());
        assertTrue(session2Found.isPresent(), "Session 2 should still exist in database");
        assertFalse(session2Found.get().isValid(), "Session 2 should be marked as invalid");

        // Verify count of valid sessions for user is now 0
        int validSessionCount = sessionDao.countValidSessionsForUser(testUser.id());
        assertEquals(0, validSessionCount, "User should have no valid sessions after invalidateAll");
    }

    @Test
    public void testCleanupExpired() {
        // Create an expired session
        String expiredToken = generateTestToken();
        LocalDateTime expiredTime = LocalDateTime.now().minusHours(1);
        @SuppressWarnings("unused")
        Session expiredSession = sessionDao.create(expiredToken, testUser.id(), expiredTime, false);

        // Create a valid session
        String validToken = generateTestToken();
        LocalDateTime validTime = LocalDateTime.now().plusHours(1);
        Session validSession = sessionDao.create(validToken, testUser.id(), validTime, false);

        int cleaned = sessionDao.cleanupExpired();

        // The expired session should be deleted
        assertTrue(cleaned >= 1);

        // Valid session should still exist
        assertTrue(sessionDao.findByToken(validSession.token()).isPresent());
    }

    @Test
    public void testCascadeDeleteOnUserDeletion() {
        String token = generateTestToken();
        LocalDateTime expires = LocalDateTime.now().plusHours(1);
        Session session = sessionDao.create(token, testUser.id(), expires, false);

        // Delete the user
        humanUserDao.delete(testUser.id());

        // Session should be deleted due to cascade
        Optional<Session> found = sessionDao.findByToken(session.token());
        assertFalse(found.isPresent());
    }
}
