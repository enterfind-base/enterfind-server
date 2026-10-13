package enterfind.server;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import enterfind.server.dao.DBAccess;

/**
 * Base class for integration tests that require database access.
 * Provides automatic database cleanup before each test method.
 *
 * <p><b>{@link #wipeDatabase()} is the isolation mechanism — do not add {@code @Transactional} to a
 * test class.</b> The wipe runs before every test method and covers every table, directly or by
 * {@code ON DELETE CASCADE} (only {@code item_treatment_results_content} relies on the cascade, from
 * {@code item_treatments}). Rollback-based isolation on top of that is not merely redundant, it
 * misleads and it bites:
 *
 * <ul>
 *   <li><b>It silently reverses the wipe.</b> Spring opens the test transaction <em>before</em>
 *       {@code @BeforeEach}, so {@code wipeDatabase()} itself runs inside it and is rolled back at
 *       the end of the method. Rows a previous test committed reappear.</li>
 *   <li><b>It hides work done on other connections.</b> {@code DBAccess.executeAdmin} takes its own
 *       pooled connection, so a migration's DDL cannot see the test's uncommitted rows and blocks on
 *       their locks instead — and it sets no query timeout, so the run hangs rather than fails. The
 *       same applies to anything the code under test does on another thread.</li>
 *   <li><b>It weakens what is under test.</b> A production {@code @Transactional} method called from
 *       a transactional test joins the test's transaction rather than opening its own, so its real
 *       commit boundary and lock lifetime are never exercised — which matters for the
 *       {@code FOR UPDATE ... SKIP LOCKED} claim path in {@code TreatmentDao}.</li>
 * </ul>
 *
 * <p>The cost of not being transactional is only that a finished test's rows stay committed until
 * the next test wipes them. That is visible if you inspect the database by hand; it does not affect
 * any test.</p>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
public abstract class BaseDatabaseTest {

    @Autowired
    protected DBAccess dbAccess;

    @BeforeEach
    protected void setUp() {
        wipeDatabase();
    }

    /**
     * Wipe all database tables to ensure clean test state.
     * This method deletes all data from all application tables in the correct order
     * to handle foreign key constraints properly.
     * 
     * Override this method in subclasses if additional cleanup is needed.
     */
    protected void wipeDatabase() {
        try {
            // Delete data from all tables (in dependency order to handle foreign keys)
            dbAccess.execute("DELETE FROM FACE_LABEL_SNAPSHOT");
            dbAccess.execute("DELETE FROM FACE_EMBEDDINGS");
            dbAccess.execute("DELETE FROM FACE_GROUPS");
            dbAccess.execute("DELETE FROM FACE_DETECTIONS");
            dbAccess.execute("DELETE FROM ITEM_TREATMENT_RESULTS");
            dbAccess.execute("DELETE FROM ITEM_TREATMENTS");
            dbAccess.execute("DELETE FROM ITEM_PREVIEWS");
            dbAccess.execute("DELETE FROM ITEMS");
            dbAccess.execute("DELETE FROM FOLDERS");
            dbAccess.execute("DELETE FROM DATASOURCE_TREATMENTS");
            dbAccess.execute("DELETE FROM DATA_SOURCES");
            dbAccess.execute("DELETE FROM USER_SETTINGS");
            dbAccess.execute("DELETE FROM SYSTEM_SETTINGS");
            dbAccess.execute("DELETE FROM API_SESSIONS");
            dbAccess.execute("DELETE FROM API_USERS");
            dbAccess.execute("DELETE FROM SAVED_QUERIES");
            dbAccess.execute("DELETE FROM SESSIONS");
            dbAccess.execute("DELETE FROM HUMAN_USERS");
            
            // Reset any auto-increment sequences if needed in the future
            // (Not needed currently since we use custom hex IDs)
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to wipe database", e);
        }
    }
}