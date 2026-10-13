package enterfind.server.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.exception.SystemIssueException;
import enterfind.server.scan.Folder;

/**
 * Tests for ItemDao delete-by-path methods, verifying the '/%' LIKE pattern
 * correctly handles slash-separated paths including folder names with commas.
 */
class ItemDaoDeleteTest extends BaseDatabaseTest {

    @Autowired
    private ItemDao itemDao;

    private String dsId;

    @BeforeEach
    void createDataSource() {
        dsId = "DELDST001";
        dbAccess.update("""
            INSERT INTO DATA_SOURCES (id, name, cron_pattern, type, config)
            VALUES (?, ?, ?, ?, ?::jsonb)
            """, dsId, "Delete Test DS", "0 0 * * *", "LOCAL_FILESYSTEM",
            "{\"localPath\":\"/test\"}");
    }

    private void insertItem(String id, String name, String path) {
        dbAccess.update("""
            INSERT INTO ITEMS (id, datasource_id, name, path, size_bytes, date_scanned)
            VALUES (?, ?, ?, ?, 0, NOW())
            """, id, dsId, name, path);
    }

    private boolean itemExists(String id) {
        Integer count = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM ITEMS WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    // ---- deleteItemsByDatasourceAndPath ----

    @Test
    void deleteItems_exactPathMatch_deletesItem() {
        insertItem("i1", "file.pdf", "Rust Servers, Services, and Apps");
        itemDao.deleteItemsByDatasourceAndPath(dsId, "Rust Servers, Services, and Apps");
        assertFalse(itemExists("i1"), "Item with exact path should be deleted");
    }

    @Test
    void deleteItems_subfolderMatch_deletesChild() {
        insertItem("i1", "file.pdf", "Rust Servers, Services, and Apps");
        insertItem("i2", "child.pdf", "Rust Servers, Services, and Apps/chapter");
        itemDao.deleteItemsByDatasourceAndPath(dsId, "Rust Servers, Services, and Apps");
        assertFalse(itemExists("i1"), "Direct item should be deleted");
        assertFalse(itemExists("i2"), "Child in subfolder should also be deleted");
    }

    @Test
    void deleteItems_percentInPath_notTreatedAsWildcard() {
        // Verify that a literal comma-in-name path (which may appear in data after migration
        // if someone had %2C) is not accidentally matched by LIKE wildcard expansion.
        // The real concern: slashPath should NOT match unrelated items
        insertItem("i1", "file.pdf", "Rust Servers, Services, and Apps");
        insertItem("i_other", "other.pdf", "other");
        itemDao.deleteItemsByDatasourceAndPath(dsId, "Rust Servers, Services, and Apps");
        assertFalse(itemExists("i1"), "Target item deleted");
        assertTrue(itemExists("i_other"), "Unrelated item not affected");
    }

    @Test
    void deleteItems_noFalsePositives_unrelatedItemUntouched() {
        insertItem("i1", "file.pdf", "Manning Books");
        insertItem("i_other", "other.pdf", "other");
        itemDao.deleteItemsByDatasourceAndPath(dsId, "Manning Books");
        assertFalse(itemExists("i1"), "Manning Books item deleted");
        assertTrue(itemExists("i_other"), "Other item untouched");
    }

    @Test
    void deleteItems_slashSeparatorNotMatchingPrefix() {
        // "foo" should NOT delete items with path "foobar"
        insertItem("i1", "file.pdf", "foo");
        insertItem("i2", "file2.pdf", "foobar");
        itemDao.deleteItemsByDatasourceAndPath(dsId, "foo");
        assertFalse(itemExists("i1"), "Exact match deleted");
        assertTrue(itemExists("i2"), "foobar should NOT be deleted by path=foo");
    }

    // ---- helpers for the chunked-delete tests ----

    private void insertFolder(String id, String name, String path) {
        dbAccess.update("""
            INSERT INTO FOLDERS (id, datasource_id, name, path, date_scanned)
            VALUES (?, ?, ?, ?, NOW())
            """, id, dsId, name, path);
    }

    private int countItemsUnder(String path) {
        Integer c = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM ITEMS WHERE datasource_id = ? AND (path = ? OR starts_with(path, ? || '/'))",
            Integer.class, dsId, path, path);
        return c == null ? 0 : c;
    }

    private int countFoldersUnder(String path) {
        Integer c = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM FOLDERS WHERE datasource_id = ? AND (path = ? OR starts_with(path, ? || '/'))",
            Integer.class, dsId, path, path);
        return c == null ? 0 : c;
    }

    // ---- chunking ----

    @Test
    void deleteItems_acrossChunkBoundary_deletesEveryRow() {
        int count = ItemDao.DELETE_CHUNK_SIZE + 50;
        for (int i = 0; i < count; i++) {
            insertItem("chunk" + i, "f" + i + ".txt", "big");
        }
        insertItem("outside", "other.txt", "elsewhere");

        int deleted = itemDao.deleteItemsByDatasourceAndPath(dsId, "big");

        assertEquals(count, deleted,
            "Return value must be the sum across chunks, not the last chunk's count");
        assertEquals(0, countItemsUnder("big"), "Every item in the subtree should be gone");
        assertTrue(itemExists("outside"), "Items outside the subtree are untouched");
    }

    @Test
    void deleteItems_acrossChunkBoundary_cascadesToTreatments() {
        dbAccess.update("""
            INSERT INTO DATASOURCE_TREATMENTS (id, datasource_id, treatment)
            VALUES (?, ?, ?)
            """, "dst1", dsId, "CONTENT");

        int count = ItemDao.DELETE_CHUNK_SIZE + 50;
        for (int i = 0; i < count; i++) {
            insertItem("chunk" + i, "f" + i + ".txt", "big");
            dbAccess.update("""
                INSERT INTO ITEM_TREATMENTS (id, datasource_treatment_id, item_id, status)
                VALUES (?, ?, ?, ?)
                """, "it" + i, "dst1", "chunk" + i, "DONE");
            dbAccess.update("""
                INSERT INTO ITEM_TREATMENT_RESULTS (id, item_treatment_id, key, value)
                VALUES (?, ?, ?, ?)
                """, "res" + i, "it" + i, "content", "text");
        }

        itemDao.deleteItemsByDatasourceAndPath(dsId, "big");

        Integer treatments = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM ITEM_TREATMENTS", Integer.class);
        Integer results = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM ITEM_TREATMENT_RESULTS", Integer.class);
        assertEquals(0, treatments, "Chunked delete must still cascade to item_treatments");
        assertEquals(0, results, "Chunked delete must still cascade to item_treatment_results");
    }

    @Test
    void deleteItems_emptySubtree_returnsZero() {
        insertItem("i_other", "other.pdf", "elsewhere");
        assertEquals(0, itemDao.deleteItemsByDatasourceAndPath(dsId, "nothing/here"));
        assertTrue(itemExists("i_other"));
    }

    // ---- path semantics: a path is data, not a LIKE pattern ----

    @Test
    void deleteItems_underscoreInPath_notTreatedAsWildcard() {
        // Under LIKE, the pattern 'my_files/%' also matches 'myXfiles/...'.
        insertItem("i_target", "a.txt", "my_files");
        insertItem("i_child", "b.txt", "my_files/sub");
        insertItem("i_victim", "c.txt", "myXfiles");
        insertItem("i_victim2", "d.txt", "myXfiles/sub");

        itemDao.deleteItemsByDatasourceAndPath(dsId, "my_files");

        assertFalse(itemExists("i_target"), "Target deleted");
        assertFalse(itemExists("i_child"), "Target's child deleted");
        assertTrue(itemExists("i_victim"), "'_' must be a literal, not a wildcard");
        assertTrue(itemExists("i_victim2"), "'_' must be a literal, not a wildcard");
    }

    @Test
    void deleteItems_percentInPath_literalNotWildcard() {
        // Under LIKE, the pattern '100%/%' matches anything starting '100'.
        insertItem("i_target", "a.txt", "100%");
        insertItem("i_victim", "b.txt", "100other");
        insertItem("i_victim2", "c.txt", "100other/sub");

        itemDao.deleteItemsByDatasourceAndPath(dsId, "100%");

        assertFalse(itemExists("i_target"), "Target deleted");
        assertTrue(itemExists("i_victim"), "'%' must be a literal, not a wildcard");
        assertTrue(itemExists("i_victim2"), "'%' must be a literal, not a wildcard");
    }

    // ---- folder ordering and the no-orphan invariant ----

    @Test
    void deleteFolders_multiLevelSubtree_deletesEveryDescendant() {
        insertFolder("f_root", "root", null);
        insertFolder("f_a", "a", "root");
        insertFolder("f_b", "b", "root/a");
        insertFolder("f_c", "c", "root/a/b");
        insertFolder("f_other", "other", null);

        int deleted = itemDao.deleteFoldersByDatasourceAndPath(dsId, "root");

        assertEquals(3, deleted, "a, b and c are under 'root'");
        assertEquals(0, countFoldersUnder("root"));
        // deleteFoldersByDatasourceAndPath deletes descendants, never the folder itself:
        // 'root' has path NULL, so it cannot match its own subtree predicate.
        Integer rootStillThere = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM FOLDERS WHERE id = ?", Integer.class, "f_root");
        assertEquals(1, rootStillThere,
            "The folder itself is removed separately by deleteFolderById");
    }

    @Test
    void deleteFolders_interruptedDeepestFirstPrefix_leavesNoOrphans() {
        // Simulate a run interrupted partway through the deepest-first order by removing
        // only the deepest folders, then assert the survivors are still ancestor-closed.
        insertFolder("f_root", "root", null);
        insertFolder("f_a", "a", "root");
        insertFolder("f_b", "b", "root/a");
        insertFolder("f_c", "c", "root/a/b");

        dbAccess.update("DELETE FROM FOLDERS WHERE id = ?", "f_c");

        List<Folder> orphans = itemDao.findOrphanedFolders(dsId);
        assertTrue(orphans.isEmpty(),
            "A deepest-first prefix must never orphan a surviving folder, but found: " + orphans);
        assertTrue(itemDao.findOrphanedItemIds(dsId).isEmpty(), "and no orphaned items");

        // A subsequent run picks up where the interrupted one stopped.
        itemDao.deleteFoldersByDatasourceAndPath(dsId, "root");
        assertEquals(0, countFoldersUnder("root"));
    }

    @Test
    void delete_whenThreadInterrupted_throwsRatherThanPartiallySucceeding() {
        for (int i = 0; i < 10; i++) {
            insertItem("chunk" + i, "f" + i + ".txt", "big");
        }

        Thread.currentThread().interrupt();
        try {
            assertThrows(SystemIssueException.class,
                () -> itemDao.deleteItemsByDatasourceAndPath(dsId, "big"),
                "A partial delete must abort the caller's sequence, not return normally");
            assertEquals(10, countItemsUnder("big"), "Nothing deleted after aborting");
        } finally {
            // Clear the flag so it cannot leak into other tests on this thread.
            Thread.interrupted();
        }
    }
}
