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
import enterfind.server.model.ApiUser;
import enterfind.server.model.UserLevel;

@SpringBootTest
@ActiveProfiles("alpha")
public class ApiUserDaoTest extends BaseDatabaseTest {

    @Autowired
    private ApiUserDao apiUserDao;

    @Autowired
    private HumanUserDao humanUserDao;

    private String creatorId;

    @Override
    @BeforeEach
    protected void setUp() {
        super.setUp();
        // Clean up auth tables
        dbAccess.execute("DELETE FROM API_SESSIONS");
        dbAccess.execute("DELETE FROM API_USERS");
        dbAccess.execute("DELETE FROM SESSIONS");
        dbAccess.execute("DELETE FROM HUMAN_USERS");

        // Create a human user as creator
        var creator = humanUserDao.create("admin", "secret", "codes", UserLevel.ADMIN);
        creatorId = creator.id();
    }

    @Test
    public void testCreate() {
        LocalDateTime expires = LocalDateTime.now().plusDays(30);
        ApiUser apiUser = apiUserDao.create("test-api", "efk_test123", UserLevel.READER, creatorId, expires);

        assertNotNull(apiUser);
        assertNotNull(apiUser.id());
        assertEquals("test-api", apiUser.nickname());
        assertEquals("efk_test123", apiUser.keyValue());
        assertEquals(UserLevel.READER, apiUser.userLevel());
        assertEquals(creatorId, apiUser.createdByUserId());
        assertTrue(apiUser.isActive());
        assertNotNull(apiUser.dateCreated());
        assertNotNull(apiUser.dateExpires());
    }

    @Test
    public void testFindById() {
        LocalDateTime expires = LocalDateTime.now().plusDays(30);
        ApiUser created = apiUserDao.create("test-api", "efk_test123", UserLevel.ADMIN, creatorId, expires);

        Optional<ApiUser> found = apiUserDao.findById(created.id());

        assertTrue(found.isPresent());
        assertEquals(created.id(), found.get().id());
        assertEquals("test-api", found.get().nickname());
    }

    @Test
    public void testFindById_notFound() {
        Optional<ApiUser> found = apiUserDao.findById("nonexistent");

        assertFalse(found.isPresent());
    }

    @Test
    public void testFindByNickname() {
        LocalDateTime expires = LocalDateTime.now().plusDays(30);
        apiUserDao.create("test-api", "efk_test123", UserLevel.READER, creatorId, expires);

        Optional<ApiUser> found = apiUserDao.findByNickname("test-api");

        assertTrue(found.isPresent());
        assertEquals("test-api", found.get().nickname());
    }

    @Test
    public void testFindByNickname_caseInsensitive() {
        LocalDateTime expires = LocalDateTime.now().plusDays(30);
        apiUserDao.create("TestAPI", "efk_test123", UserLevel.READER, creatorId, expires);

        Optional<ApiUser> found = apiUserDao.findByNickname("testapi");

        assertTrue(found.isPresent());
        assertEquals("TestAPI", found.get().nickname());
    }

    @Test
    public void testFindByKeyValue() {
        LocalDateTime expires = LocalDateTime.now().plusDays(30);
        apiUserDao.create("test-api", "efk_test123456", UserLevel.READER, creatorId, expires);

        Optional<ApiUser> found = apiUserDao.findByKeyValue("efk_test123456");

        assertTrue(found.isPresent());
        assertEquals("test-api", found.get().nickname());
        assertEquals("efk_test123456", found.get().keyValue());
    }

    @Test
    public void testFindAll() {
        LocalDateTime expires = LocalDateTime.now().plusDays(30);
        apiUserDao.create("api1", "efk_key1", UserLevel.ADMIN, creatorId, expires);
        apiUserDao.create("api2", "efk_key2", UserLevel.READER, creatorId, expires);

        List<ApiUser> all = apiUserDao.findAll();

        assertEquals(2, all.size());
    }

    @Test
    public void testExistsByNickname() {
        LocalDateTime expires = LocalDateTime.now().plusDays(30);
        apiUserDao.create("test-api", "efk_test123", UserLevel.READER, creatorId, expires);

        assertTrue(apiUserDao.existsByNickname("test-api"));
        assertTrue(apiUserDao.existsByNickname("TEST-API")); // case insensitive
        assertFalse(apiUserDao.existsByNickname("nonexistent"));
    }

    @Test
    public void testUpdateLastUsed() {
        LocalDateTime expires = LocalDateTime.now().plusDays(30);
        ApiUser created = apiUserDao.create("test-api", "efk_test123", UserLevel.READER, creatorId, expires);

        boolean updated = apiUserDao.updateLastUsed(created.id());

        assertTrue(updated);

        Optional<ApiUser> found = apiUserDao.findById(created.id());
        assertTrue(found.isPresent());
        assertNotNull(found.get().dateLastUsed());
    }

    @Test
    public void testDeactivate() {
        LocalDateTime expires = LocalDateTime.now().plusDays(30);
        ApiUser created = apiUserDao.create("test-api", "efk_test123", UserLevel.READER, creatorId, expires);

        boolean deactivated = apiUserDao.deactivate(created.id());

        assertTrue(deactivated);

        Optional<ApiUser> found = apiUserDao.findById(created.id());
        assertTrue(found.isPresent());
        assertFalse(found.get().isActive());
    }

    @Test
    public void testDelete() {
        LocalDateTime expires = LocalDateTime.now().plusDays(30);
        ApiUser created = apiUserDao.create("test-api", "efk_test123", UserLevel.READER, creatorId, expires);

        boolean deleted = apiUserDao.delete(created.id());

        assertTrue(deleted);
        assertFalse(apiUserDao.findById(created.id()).isPresent());
    }

    @Test
    public void testCount() {
        LocalDateTime expires = LocalDateTime.now().plusDays(30);
        assertEquals(0, apiUserDao.count());

        apiUserDao.create("api1", "efk_key1", UserLevel.READER, creatorId, expires);
        assertEquals(1, apiUserDao.count());

        apiUserDao.create("api2", "efk_key2", UserLevel.ADMIN, creatorId, expires);
        assertEquals(2, apiUserDao.count());
    }

    @Test
    public void testIsExpired() {
        LocalDateTime pastExpiry = LocalDateTime.now().minusDays(1);
        ApiUser expired = apiUserDao.create("expired-api", "efk_expired", UserLevel.READER, creatorId, pastExpiry);

        assertTrue(expired.isExpired());

        LocalDateTime futureExpiry = LocalDateTime.now().plusDays(30);
        ApiUser valid = apiUserDao.create("valid-api", "efk_valid", UserLevel.READER, creatorId, futureExpiry);

        assertFalse(valid.isExpired());
    }

    @Test
    public void testKeyValuePreview() {
        LocalDateTime expires = LocalDateTime.now().plusDays(30);
        ApiUser apiUser = apiUserDao.create("test-api", "efk_test123456", UserLevel.READER, creatorId, expires);

        String preview = apiUser.getKeyValuePreview();

        assertTrue(preview.startsWith("..."));
        assertTrue(preview.endsWith("3456"));
    }
}
