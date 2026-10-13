package enterfind.server.dao;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.scan.Folder;

/**
 * Root-level items and folders have a NULL path, and every by-path method in ItemDao carries a
 * {@code path IS NULL} branch for exactly that case. That branch compares a bare parameter to NULL,
 * which PostgreSQL cannot type ("could not determine data type of parameter") unless the SQL casts
 * it — so without the cast the branch does not merely miss, it throws.
 *
 * <p>None of these methods currently has a production caller, which is why the defect went
 * unnoticed; these tests hold the behaviour so it stays fixed if one acquires a caller.
 */
class ItemDaoRootPathTest extends BaseDatabaseTest {

    @Autowired
    private ItemDao itemDao;

    private String dsId;

    @BeforeEach
    void createDataSource() {
        dsId = "ROOTPATH001";
        dbAccess.update("""
            INSERT INTO DATA_SOURCES (id, name, cron_pattern, type, config)
            VALUES (?, ?, ?, ?, ?::jsonb)
            """, dsId, "Root Path Test DS", "0 0 * * *", "LOCAL_FILESYSTEM",
            "{\"localPath\":\"/test\"}");
        dbAccess.update("""
            INSERT INTO ITEMS (id, datasource_id, name, path, size_bytes, date_scanned)
            VALUES (?, ?, ?, NULL, 0, NOW())
            """, "rootitem", dsId, "readme.txt");
        dbAccess.update("""
            INSERT INTO FOLDERS (id, datasource_id, name, path, date_scanned)
            VALUES (?, ?, ?, NULL, NOW())
            """, "rootfolder", dsId, "documents");
    }

    @Test
    void itemExistsFindsARootLevelItem() {
        assertTrue(itemDao.itemExists(dsId, null, "readme.txt"));
        assertFalse(itemDao.itemExists(dsId, null, "absent.txt"));
    }

    @Test
    void folderExistsFindsARootLevelFolder() {
        assertTrue(itemDao.folderExists(dsId, null, "documents"));
        assertFalse(itemDao.folderExists(dsId, null, "absent"));
    }

    @Test
    void findFolderByDatasourceAndPathFindsARootLevelFolder() {
        Optional<Folder> found = itemDao.findFolderByDatasourceAndPath(dsId, null, "documents");
        assertTrue(found.isPresent());
        assertTrue(found.get().getPath() == null, "a root-level folder has a NULL path");
        assertTrue(itemDao.findFolderByDatasourceAndPath(dsId, null, "absent").isEmpty());
    }

    @Test
    void deleteItemByDatasourceAndPathDeletesARootLevelItem() {
        assertTrue(itemDao.deleteItemByDatasourceAndPath(dsId, null, "readme.txt"));
        assertFalse(itemDao.itemExists(dsId, null, "readme.txt"));
    }

    @Test
    void deleteFolderByDatasourceAndPathDeletesARootLevelFolder() {
        assertTrue(itemDao.deleteFolderByDatasourceAndPath(dsId, null, "documents"));
        assertFalse(itemDao.folderExists(dsId, null, "documents"));
    }

    @Test
    void nonRootPathsStillMatchNormally() {
        dbAccess.update("""
            INSERT INTO ITEMS (id, datasource_id, name, path, size_bytes, date_scanned)
            VALUES (?, ?, ?, ?, 0, NOW())
            """, "nesteditem", dsId, "report.pdf", "documents");

        assertTrue(itemDao.itemExists(dsId, "documents", "report.pdf"));
        assertFalse(itemDao.itemExists(dsId, "documents", "readme.txt"),
                "the root-level item must not match a non-root path");
        assertFalse(itemDao.itemExists(dsId, null, "report.pdf"),
                "the nested item must not match a NULL path");
    }
}
