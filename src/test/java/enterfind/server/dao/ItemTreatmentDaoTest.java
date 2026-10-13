package enterfind.server.dao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.model.DataSourceTreatment;
import enterfind.server.model.ItemTreatment;
import enterfind.server.model.ItemTreatmentStatus;
import enterfind.server.scan.Item;
import enterfind.server.scan.Treatments;

/**
 * Unit tests for ItemTreatment DAO operations
 */
class ItemTreatmentDaoTest extends BaseDatabaseTest {

    @Autowired
    private TreatmentDao treatmentDao;

    @Autowired
    private ItemDao itemDao;

    private String testDataSourceId;
    private String testItemId;
    private String testDataSourceTreatmentId;

    @BeforeEach
    void setUpTest() {
        testDataSourceId = createTestDataSource();
        testItemId = createTestItem();
        testDataSourceTreatmentId = createTestDataSourceTreatment();
    }

    private String createTestDataSource() {
        String id = "TESTSRC1";
        dbAccess.update("""
            INSERT INTO DATA_SOURCES (id, name, cron_pattern, type, config)
            VALUES (?, ?, ?, ?, ?::jsonb)
            """, id, "Test DataSource", "0 0 * * * *", "LOCAL_FILESYSTEM", "{\"localPath\":\"/test\"}");
        return id;
    }

    private String createTestItem() {
        Item item = new Item();
        item.setDatasourceId(testDataSourceId);
        item.setName("test-file.txt");
        item.setPath("test/path");
        item.setTimeCreated(LocalDateTime.now());
        item.setTimeModified(LocalDateTime.now());
        item.setSizeBytes(1024L);
        item.setContentHash("hash123");
        item.setMimeType("text/plain");
        
        Item createdItem = itemDao.createItem(item);
        return createdItem.getId();
    }

    private String createTestDataSourceTreatment() {
        DataSourceTreatment treatment = treatmentDao.addTreatment(testDataSourceId, Treatments.CONTENT, null);
        return treatment.getId();
    }

    @Test
    void testAddItemTreatment() {
        // When
        ItemTreatment itemTreatment = treatmentDao.addItemTreatment(
            testDataSourceTreatmentId, testItemId, ItemTreatmentStatus.NEW);

        // Then
        assertThat(itemTreatment).isNotNull();
        assertThat(itemTreatment.getId()).isNotEmpty();
        assertThat(itemTreatment.getDatasourceTreatmentId()).isEqualTo(testDataSourceTreatmentId);
        assertThat(itemTreatment.getItemId()).isEqualTo(testItemId);
        assertThat(itemTreatment.getStatus()).isEqualTo(ItemTreatmentStatus.NEW);
        assertThat(itemTreatment.getDateCreated()).isNotNull();
        assertThat(itemTreatment.getDateModified()).isNull();

        // Verify database directly
        Integer count = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM ITEM_TREATMENTS WHERE datasource_treatment_id = ? AND item_id = ?",
            Integer.class, testDataSourceTreatmentId, testItemId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void testGetItemTreatmentsByDatasourceTreatment() {
        // Given
        treatmentDao.addItemTreatment(testDataSourceTreatmentId, testItemId, ItemTreatmentStatus.NEW);

        // When
        List<ItemTreatment> treatments = treatmentDao.getItemTreatmentsByDatasourceTreatment(testDataSourceTreatmentId);

        // Then
        assertThat(treatments).hasSize(1);
        assertThat(treatments.get(0).getDatasourceTreatmentId()).isEqualTo(testDataSourceTreatmentId);
        assertThat(treatments.get(0).getItemId()).isEqualTo(testItemId);
        assertThat(treatments.get(0).getStatus()).isEqualTo(ItemTreatmentStatus.NEW);
    }

    @Test
    void testGetItemTreatmentsByItem() {
        // Given
        treatmentDao.addItemTreatment(testDataSourceTreatmentId, testItemId, ItemTreatmentStatus.NEW);

        // When
        List<ItemTreatment> treatments = treatmentDao.getItemTreatmentsByItem(testItemId);

        // Then
        assertThat(treatments).hasSize(1);
        assertThat(treatments.get(0).getDatasourceTreatmentId()).isEqualTo(testDataSourceTreatmentId);
        assertThat(treatments.get(0).getItemId()).isEqualTo(testItemId);
        assertThat(treatments.get(0).getStatus()).isEqualTo(ItemTreatmentStatus.NEW);
    }

    @Test
    void testGetItemTreatmentsByStatus() {
        // Given
        treatmentDao.addItemTreatment(testDataSourceTreatmentId, testItemId, ItemTreatmentStatus.NEW);

        // When
        List<ItemTreatment> treatments = treatmentDao.getItemTreatmentsByStatus(ItemTreatmentStatus.NEW);

        // Then
        assertThat(treatments).hasSize(1);
        assertThat(treatments.get(0).getStatus()).isEqualTo(ItemTreatmentStatus.NEW);
    }

    @Test
    void testUpdateItemTreatmentStatus() {
        // Given
        treatmentDao.addItemTreatment(testDataSourceTreatmentId, testItemId, ItemTreatmentStatus.NEW);
        Optional<ItemTreatment> itemTreatment = treatmentDao.getItemTreatment(testDataSourceTreatmentId, testItemId);
        assertThat(itemTreatment).isPresent();

        // When
        Optional<ItemTreatment> updated = treatmentDao.updateItemTreatmentStatus(
            itemTreatment.get().getId(), ItemTreatmentStatus.INPROGRESS, itemTreatment.get().getVersion());
        assertThat(updated).isPresent();
        assertThat(updated.get().getStatus()).isEqualTo(ItemTreatmentStatus.INPROGRESS);
        assertThat(updated.get().getDateModified()).isNotNull();

        // Verify database directly
        String status = dbAccess.queryForObject(
            "SELECT status FROM ITEM_TREATMENTS WHERE datasource_treatment_id = ? AND item_id = ?",
            String.class, testDataSourceTreatmentId, testItemId);
        assertThat(status).isEqualTo("INPROGRESS");
    }

    @Test
    void testDeleteItemTreatment() {
        // Given
        treatmentDao.addItemTreatment(testDataSourceTreatmentId, testItemId, ItemTreatmentStatus.NEW);

        // When
        boolean deleted = treatmentDao.deleteItemTreatment(testDataSourceTreatmentId, testItemId);

        // Then
        assertThat(deleted).isTrue();

        // Verify database directly
        Integer count = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM ITEM_TREATMENTS WHERE datasource_treatment_id = ? AND item_id = ?",
            Integer.class, testDataSourceTreatmentId, testItemId);
        assertThat(count).isEqualTo(0);
    }

    @Test
    void testCascadeDeleteFromDatasourceTreatment() {
        // Given
        treatmentDao.addItemTreatment(testDataSourceTreatmentId, testItemId, ItemTreatmentStatus.NEW);

        // Verify item treatment exists
        Integer countBefore = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM ITEM_TREATMENTS WHERE datasource_treatment_id = ?",
            Integer.class, testDataSourceTreatmentId);
        assertThat(countBefore).isEqualTo(1);

        // When - delete the datasource treatment
        treatmentDao.removeTreatment(testDataSourceId, Treatments.CONTENT);

        // Then - item treatment should be cascade deleted
        Integer countAfter = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM ITEM_TREATMENTS WHERE datasource_treatment_id = ?",
            Integer.class, testDataSourceTreatmentId);
        assertThat(countAfter).isEqualTo(0);
    }

    @Test
    void testCascadeDeleteFromItem() {
        // Given
        treatmentDao.addItemTreatment(testDataSourceTreatmentId, testItemId, ItemTreatmentStatus.NEW);

        // Verify item treatment exists
        Integer countBefore = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM ITEM_TREATMENTS WHERE item_id = ?",
            Integer.class, testItemId);
        assertThat(countBefore).isEqualTo(1);

        // When - delete the item
        itemDao.deleteItemById(testItemId);

        // Then - item treatment should be cascade deleted
        Integer countAfter = dbAccess.queryForObject(
            "SELECT COUNT(*) FROM ITEM_TREATMENTS WHERE item_id = ?",
            Integer.class, testItemId);
        assertThat(countAfter).isEqualTo(0);
    }

    @Test
    void testItemTreatmentExists() {
        // Given
        treatmentDao.addItemTreatment(testDataSourceTreatmentId, testItemId, ItemTreatmentStatus.NEW);

        // When/Then
        assertThat(treatmentDao.itemTreatmentExists(testDataSourceTreatmentId, testItemId)).isTrue();
        assertThat(treatmentDao.itemTreatmentExists("INVALID", testItemId)).isFalse();
        assertThat(treatmentDao.itemTreatmentExists(testDataSourceTreatmentId, "INVALID")).isFalse();
    }
}