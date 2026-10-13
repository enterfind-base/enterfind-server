package enterfind.server.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.model.HumanUser;
import enterfind.server.model.UserLevel;

@SpringBootTest
@ActiveProfiles("alpha")
public class HumanUserDaoTest extends BaseDatabaseTest {

    @Autowired
    private HumanUserDao humanUserDao;

    @Override
    @BeforeEach
    protected void setUp() {
        super.setUp();
        // Clean up auth tables
        dbAccess.execute("DELETE FROM SESSIONS");
        dbAccess.execute("DELETE FROM HUMAN_USERS");
    }

    @Test
    public void testCreate() {
        HumanUser user = humanUserDao.create("testuser", "secret123", "codes", UserLevel.ADMIN);

        assertNotNull(user);
        assertNotNull(user.id());
        assertEquals("testuser", user.nickname());
        assertEquals(UserLevel.ADMIN, user.userLevel());
        assertTrue(user.isActive());
        assertNotNull(user.dateCreated());
        assertNull(user.dateLastLogin());
    }

    @Test
    public void testFindById() {
        HumanUser created = humanUserDao.create("testuser", "secret123", "codes", UserLevel.READER);

        Optional<HumanUser> found = humanUserDao.findById(created.id());

        assertTrue(found.isPresent());
        assertEquals(created.id(), found.get().id());
        assertEquals("testuser", found.get().nickname());
    }

    @Test
    public void testFindById_notFound() {
        Optional<HumanUser> found = humanUserDao.findById("nonexistent");

        assertFalse(found.isPresent());
    }

    @Test
    public void testFindByNickname() {
        humanUserDao.create("testuser", "secret123", "codes", UserLevel.ADMIN);

        Optional<HumanUser> found = humanUserDao.findByNickname("testuser");

        assertTrue(found.isPresent());
        assertEquals("testuser", found.get().nickname());
    }

    @Test
    public void testFindByNickname_caseInsensitive() {
        humanUserDao.create("TestUser", "secret123", "codes", UserLevel.ADMIN);

        Optional<HumanUser> found = humanUserDao.findByNickname("testuser");

        assertTrue(found.isPresent());
        assertEquals("TestUser", found.get().nickname());
    }

    @Test
    public void testFindByNickname_notFound() {
        Optional<HumanUser> found = humanUserDao.findByNickname("nonexistent");

        assertFalse(found.isPresent());
    }

    @Test
    public void testFindAll() {
        humanUserDao.create("user1", "secret1", "codes1", UserLevel.ADMIN);
        humanUserDao.create("user2", "secret2", "codes2", UserLevel.READER);

        List<HumanUser> users = humanUserDao.findAll();

        assertEquals(2, users.size());
    }

    @Test
    public void testFindAll_empty() {
        List<HumanUser> users = humanUserDao.findAll();

        assertTrue(users.isEmpty());
    }

    @Test
    public void testExistsByNickname() {
        humanUserDao.create("testuser", "secret123", "codes", UserLevel.ADMIN);

        assertTrue(humanUserDao.existsByNickname("testuser"));
        assertTrue(humanUserDao.existsByNickname("TESTUSER")); // Case insensitive
        assertFalse(humanUserDao.existsByNickname("nonexistent"));
    }

    @Test
    public void testHasAnyUsers() {
        assertFalse(humanUserDao.hasAnyUsers());

        humanUserDao.create("testuser", "secret123", "codes", UserLevel.ADMIN);

        assertTrue(humanUserDao.hasAnyUsers());
    }

    @Test
    public void testUpdate() {
        HumanUser user = humanUserDao.create("testuser", "secret123", "codes", UserLevel.READER);

        humanUserDao.update(user.id(), "newname", UserLevel.ADMIN, false);

        Optional<HumanUser> updated = humanUserDao.findById(user.id());
        assertTrue(updated.isPresent());
        assertEquals("newname", updated.get().nickname());
        assertEquals(UserLevel.ADMIN, updated.get().userLevel());
        assertFalse(updated.get().isActive());

        // Verify changes are persisted through different query paths
        var foundByName = humanUserDao.findByNickname("newname");
        assertTrue(foundByName.isPresent(), "Updated user should be findable by new nickname");
        assertEquals(UserLevel.ADMIN, foundByName.get().userLevel());
        assertFalse(foundByName.get().isActive());

        // Verify old nickname no longer finds the user
        var oldName = humanUserDao.findByNickname("testuser");
        assertFalse(oldName.isPresent(), "Old nickname should no longer find the user");
    }

    @Test
    public void testUpdate_partialFields() {
        HumanUser user = humanUserDao.create("testuser", "secret123", "codes", UserLevel.READER);

        // Update only nickname
        humanUserDao.update(user.id(), "newname", null, null);

        Optional<HumanUser> updated = humanUserDao.findById(user.id());
        assertTrue(updated.isPresent());
        assertEquals("newname", updated.get().nickname());
        assertEquals(UserLevel.READER, updated.get().userLevel()); // Unchanged
        assertTrue(updated.get().isActive()); // Unchanged
    }

    @Test
    public void testDelete() {
        HumanUser user = humanUserDao.create("testuser", "secret123", "codes", UserLevel.ADMIN);

        boolean deleted = humanUserDao.delete(user.id());

        assertTrue(deleted);
        assertFalse(humanUserDao.findById(user.id()).isPresent());

        // Verify deletion through multiple query paths
        assertFalse(humanUserDao.findByNickname("testuser").isPresent(),
            "Deleted user should not be findable by nickname");
        assertFalse(humanUserDao.existsByNickname("testuser"),
            "Deleted user should not exist by nickname check");
    }

    @Test
    public void testDelete_notFound() {
        boolean deleted = humanUserDao.delete("nonexistent");

        assertFalse(deleted);
    }

    @Test
    public void testUpdateLastLogin() {
        HumanUser user = humanUserDao.create("testuser", "secret123", "codes", UserLevel.ADMIN);
        assertNull(user.dateLastLogin());

        humanUserDao.updateLastLogin(user.id());

        Optional<HumanUser> updated = humanUserDao.findById(user.id());
        assertTrue(updated.isPresent());
        assertNotNull(updated.get().dateLastLogin());

        // Verify last login timestamp is recent (within last minute)
        long secondsAgo = java.time.temporal.ChronoUnit.SECONDS.between(
            updated.get().dateLastLogin(), java.time.LocalDateTime.now());
        assertTrue(secondsAgo >= 0 && secondsAgo < 60,
            "Last login should be within the last minute");
    }

    @Test
    public void testUpdateTotpSecret() {
        HumanUser user = humanUserDao.create("testuser", "secret123", "codes", UserLevel.ADMIN);

        humanUserDao.updateTotpSecret(user.id(), "newsecret");

        Optional<HumanUser> updated = humanUserDao.findById(user.id());
        assertTrue(updated.isPresent());
        // Note: totpSecret is encrypted, so we can't directly compare
        assertNotNull(updated.get().totpSecret());
    }

    @Test
    public void testUpdateRecoveryCodes() {
        HumanUser user = humanUserDao.create("testuser", "secret123", "codes", UserLevel.ADMIN);

        humanUserDao.updateRecoveryCodes(user.id(), "newcodes");

        Optional<HumanUser> updated = humanUserDao.findById(user.id());
        assertTrue(updated.isPresent());
        // Note: recoveryCodes are encrypted, so we can't directly compare
        assertNotNull(updated.get().recoveryCodes());
    }

    @Test
    public void testFindAll_mixedActiveInactive() {
        humanUserDao.create("active1", "secret1", "codes1", UserLevel.ADMIN);
        HumanUser inactive = humanUserDao.create("inactive", "secret2", "codes2", UserLevel.READER);
        humanUserDao.update(inactive.id(), null, null, false);
        humanUserDao.create("active2", "secret3", "codes3", UserLevel.READER);

        List<HumanUser> allUsers = humanUserDao.findAll();

        assertEquals(3, allUsers.size());
        // Verify one of them is inactive
        assertEquals(1, allUsers.stream().filter(u -> !u.isActive()).count());
    }
}
