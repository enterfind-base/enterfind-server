package enterfind.server.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.model.FieldNameConstants;
import enterfind.server.model.HumanUser;
import enterfind.server.model.SavedQuery;
import enterfind.server.model.SearchCondition;
import enterfind.server.model.UserLevel;

@SpringBootTest
@ActiveProfiles("alpha")
public class SavedQueryDaoTest extends BaseDatabaseTest {

    @Autowired
    private SavedQueryDao savedQueryDao;

    @Autowired
    private HumanUserDao humanUserDao;

    private HumanUser testUser1;
    private HumanUser testUser2;

    @Override
    @BeforeEach
    protected void setUp() {
        super.setUp();
        
        // Create test users
        testUser1 = humanUserDao.create("testuser1", "secret123", "codes", UserLevel.ADMIN);
        testUser2 = humanUserDao.create("testuser2", "secret456", "codes", UserLevel.READER);
    }

    @Test
    public void testCreate() {
        List<SearchCondition> conditions = List.of(
            new SearchCondition("path", SearchOperation.LIKE, "/documents"),
            new SearchCondition(FieldNameConstants.FIELD_CONTENT_TYPE, SearchOperation.EQUAL, "application/pdf")
        );
        
        SavedQuery savedQuery = savedQueryDao.create(testUser1.id(), "pdf files", conditions, SortDirection.DESC);

        assertNotNull(savedQuery);
        assertNotNull(savedQuery.id());
        assertEquals(testUser1.id(), savedQuery.userId());
        assertNotNull(savedQuery.name()); // Name is auto-generated
        assertEquals("pdf files", savedQuery.searchString());
        assertEquals(2, savedQuery.queryConditions().size());
        assertEquals(SortDirection.DESC, savedQuery.sortDirection());
        assertNotNull(savedQuery.dateCreated());
    }

    @Test
    public void testFindById() {
        List<SearchCondition> conditions = List.of(
            new SearchCondition("name", SearchOperation.LIKE, "report")
        );
        
        SavedQuery created = savedQueryDao.create(testUser1.id(), null, conditions, SortDirection.DESC);
        Optional<SavedQuery> found = savedQueryDao.findById(created.id());

        assertTrue(found.isPresent());
        assertEquals(created.id(), found.get().id());
        assertNotNull(found.get().name()); // Name is auto-generated
        assertEquals(1, found.get().queryConditions().size());
    }

    @Test
    public void testFindById_notFound() {
        Optional<SavedQuery> found = savedQueryDao.findById("nonexistent_id");
        assertFalse(found.isPresent());
    }

    @Test
    public void testFindByUserId() {
        List<SearchCondition> conditions1 = List.of(
            new SearchCondition("path", SearchOperation.LIKE, "/documents")
        );
        List<SearchCondition> conditions2 = List.of(
            new SearchCondition(FieldNameConstants.FIELD_CONTENT_TYPE, SearchOperation.EQUAL, "image/jpeg")
        );
        List<SearchCondition> conditions3 = List.of(
            new SearchCondition("name", SearchOperation.LIKE, "budget")
        );

        savedQueryDao.create(testUser1.id(), "search1", conditions1, SortDirection.DESC);
        savedQueryDao.create(testUser1.id(), "search2", conditions2, SortDirection.ASC);
        savedQueryDao.create(testUser2.id(), null, conditions3, SortDirection.DESC);

        List<SavedQuery> user1Queries = savedQueryDao.findByUserId(testUser1.id());
        List<SavedQuery> user2Queries = savedQueryDao.findByUserId(testUser2.id());

        assertEquals(2, user1Queries.size());
        assertEquals(1, user2Queries.size());
        assertTrue(user1Queries.stream().allMatch(q -> q.userId().equals(testUser1.id())));
        assertTrue(user2Queries.stream().allMatch(q -> q.userId().equals(testUser2.id())));
    }

    @Test
    public void testFindByUserId_empty() {
        List<SavedQuery> queries = savedQueryDao.findByUserId(testUser1.id());
        assertTrue(queries.isEmpty());
    }

    @Test
    public void testDelete() {
        List<SearchCondition> conditions = List.of(
            new SearchCondition("path", SearchOperation.LIKE, "/documents")
        );
        
        SavedQuery created = savedQueryDao.create(testUser1.id(), null, conditions, SortDirection.DESC);
        boolean deleted = savedQueryDao.delete(created.id());

        assertTrue(deleted);
        Optional<SavedQuery> found = savedQueryDao.findById(created.id());
        assertFalse(found.isPresent());
    }

    @Test
    public void testDelete_notFound() {
        boolean deleted = savedQueryDao.delete("nonexistent_id");
        assertFalse(deleted);
    }

    @Test
    public void testDeleteByIdAndUserId() {
        List<SearchCondition> conditions = List.of(
            new SearchCondition("path", SearchOperation.LIKE, "/documents")
        );
        
        SavedQuery created = savedQueryDao.create(testUser1.id(), null, conditions, SortDirection.DESC);
        boolean deleted = savedQueryDao.deleteByIdAndUserId(created.id(), testUser1.id());

        assertTrue(deleted);
        Optional<SavedQuery> found = savedQueryDao.findById(created.id());
        assertFalse(found.isPresent());
    }

    @Test
    public void testDeleteByIdAndUserId_wrongUser() {
        List<SearchCondition> conditions = List.of(
            new SearchCondition("path", SearchOperation.LIKE, "/documents")
        );
        
        SavedQuery created = savedQueryDao.create(testUser1.id(), null, conditions, SortDirection.DESC);
        boolean deleted = savedQueryDao.deleteByIdAndUserId(created.id(), testUser2.id());

        assertFalse(deleted);
        Optional<SavedQuery> found = savedQueryDao.findById(created.id());
        assertTrue(found.isPresent()); // Should still exist
    }

    @Test
    public void testCreateWithMultipleConditions() {
        List<SearchCondition> conditions = List.of(
            new SearchCondition("path", SearchOperation.LIKE, "/documents"),
            new SearchCondition(FieldNameConstants.FIELD_CONTENT_TYPE, SearchOperation.EQUAL, "application/pdf"),
            new SearchCondition("name", SearchOperation.NOT_LIKE, "draft"),
            new SearchCondition("datasourcename", SearchOperation.EQUAL, "MainServer")
        );
        
        SavedQuery savedQuery = savedQueryDao.create(testUser1.id(), "complex search", conditions, SortDirection.ASC);

        assertNotNull(savedQuery);
        assertEquals(4, savedQuery.queryConditions().size());
        assertEquals("complex search", savedQuery.searchString());
        assertEquals(SortDirection.ASC, savedQuery.sortDirection());
        
        Optional<SavedQuery> found = savedQueryDao.findById(savedQuery.id());
        assertTrue(found.isPresent());
        assertEquals(4, found.get().queryConditions().size());
        
        // Verify all conditions are preserved
        List<SearchCondition> retrievedConditions = found.get().queryConditions();
        assertEquals("path", retrievedConditions.get(0).name());
        assertEquals(SearchOperation.LIKE, retrievedConditions.get(0).operation());
        assertEquals("/documents", retrievedConditions.get(0).value());
    }

    @Test
    public void testUserDeletionCascades() {
        List<SearchCondition> conditions = List.of(
            new SearchCondition("path", SearchOperation.LIKE, "/documents")
        );
        
        SavedQuery created = savedQueryDao.create(testUser1.id(), null, conditions, SortDirection.DESC);
        
        // Delete the user
        String deleteSql = "DELETE FROM HUMAN_USERS WHERE id = ?";
        dbAccess.update(deleteSql, testUser1.id());
        
        // Saved query should also be deleted due to cascade
        Optional<SavedQuery> found = savedQueryDao.findById(created.id());
        assertFalse(found.isPresent());
    }
}
