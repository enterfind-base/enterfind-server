package enterfind.server.dao;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.model.DataSourceStats;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.DataSourceTreatment;
import enterfind.server.model.ItemTreatmentStatus;
import enterfind.server.model.TreatmentStatusCount;
import enterfind.server.scan.Item;
import enterfind.server.scan.Treatments;

/**
 * Unit tests for DataSource Stats DAO operations
 */
class DataSourceStatsDaoTest extends BaseDatabaseTest {

    @Autowired
    private DataSourceDao dataSourceDao;

    @Autowired
    private TreatmentDao treatmentDao;

    @Autowired
    private ItemDao itemDao;

    private String testDataSourceId;

    @Override
    @BeforeEach
    protected void setUp() {
        super.setUp();
        testDataSourceId = createTestDataSource();
    }

    private String createTestDataSource() {
        String id = "TESTSRC1";
        dbAccess.update("""
            INSERT INTO DATA_SOURCES (id, name, cron_pattern, type, config)
            VALUES (?, ?, ?, ?, ?::jsonb)
            """, id, "Test DataSource", "0 0 * * * *", "LOCAL_FILESYSTEM", "{\"localPath\":\"/test\"}");
        return id;
    }

    private String createTestItem(String name) {
        Item item = new Item();
        item.setDatasourceId(testDataSourceId);
        item.setName(name);
        item.setPath("test/path");
        item.setTimeCreated(LocalDateTime.now());
        item.setTimeModified(LocalDateTime.now());
        item.setSizeBytes(1024L);
        item.setContentHash("hash123");
        item.setMimeType("text/plain");
        
        Item createdItem = itemDao.createItem(item);
        return createdItem.getId();
    }

    @Test
    void testGetDataSourceStats_emptyDataSource() {
        // When
        DataSourceStats stats = dataSourceDao.getDataSourceStats(testDataSourceId);

        // Then
        assertThat(stats).isNotNull();
        assertThat(stats.totalItems()).isEqualTo(0);
        assertThat(stats.previewsToDo()).isEqualTo(0);
        assertThat(stats.treatmentStatusCounts()).hasSize(5); // All ItemTreatmentStatus values
        
        // All counts should be 0
        for (TreatmentStatusCount count : stats.treatmentStatusCounts()) {
            assertThat(count.count()).isEqualTo(0);
        }
        
        assertThat(stats.dataSourceStatus()).isEqualTo(DataSourceStatus.IDLE);
    }

    @Test
    void testGetDataSourceStats_withItems() {
        // Given - create 3 items
        createTestItem("file1.txt");
        createTestItem("file2.txt");
        createTestItem("file3.txt");

        // When
        DataSourceStats stats = dataSourceDao.getDataSourceStats(testDataSourceId);

        // Then
        assertThat(stats.totalItems()).isEqualTo(3);
        assertThat(stats.previewsToDo()).isEqualTo(0);
        // No treatments yet, so all status counts should be 0
        for (TreatmentStatusCount count : stats.treatmentStatusCounts()) {
            assertThat(count.count()).isEqualTo(0);
        }
        assertThat(stats.dataSourceStatus()).isEqualTo(DataSourceStatus.IDLE);
        assertThat(stats.dateLastScanned()).isNull();
    }

    @Test
    void testGetDataSourceStats_withItemTreatments() {
        // Given - create items and treatments
        String item1Id = createTestItem("file1.txt");
        String item2Id = createTestItem("file2.txt");
        String item3Id = createTestItem("file3.txt");
        
        // Add a treatment to the datasource
        DataSourceTreatment treatment = treatmentDao.addTreatment(testDataSourceId, Treatments.CONTENT, null);
        
        // Create item treatments with various statuses
        treatmentDao.addItemTreatment(treatment.getId(), item1Id, ItemTreatmentStatus.NEW);
        treatmentDao.addItemTreatment(treatment.getId(), item2Id, ItemTreatmentStatus.DONE);
        treatmentDao.addItemTreatment(treatment.getId(), item3Id, ItemTreatmentStatus.DONE);

        // When
        DataSourceStats stats = dataSourceDao.getDataSourceStats(testDataSourceId);

        // Then
        assertThat(stats.totalItems()).isEqualTo(3);
        assertThat(stats.previewsToDo()).isEqualTo(0);
        
        // Check specific status counts
        assertThat(findCountForStatus(stats, ItemTreatmentStatus.NEW)).isEqualTo(1);
        assertThat(findCountForStatus(stats, ItemTreatmentStatus.DONE)).isEqualTo(2);
        assertThat(findCountForStatus(stats, ItemTreatmentStatus.UPDATED)).isEqualTo(0);
        assertThat(findCountForStatus(stats, ItemTreatmentStatus.INPROGRESS)).isEqualTo(0);
        assertThat(findCountForStatus(stats, ItemTreatmentStatus.FAILED)).isEqualTo(0);
        assertThat(stats.dataSourceStatus()).isEqualTo(DataSourceStatus.IDLE);
        assertThat(stats.dateLastScanned()).isNull();
    }

    @Test
    void testGetDataSourceStats_multipleTreatments() {
        // Given - create items and multiple treatments
        String item1Id = createTestItem("file1.txt");
        String item2Id = createTestItem("file2.txt");
        
        // Add two treatments to the datasource
        DataSourceTreatment contentTreatment = treatmentDao.addTreatment(testDataSourceId, Treatments.CONTENT, null);
        DataSourceTreatment ocrTreatment = treatmentDao.addTreatment(testDataSourceId, Treatments.OCR, null);
        
        // Create item treatments for both treatments
        treatmentDao.addItemTreatment(contentTreatment.getId(), item1Id, ItemTreatmentStatus.DONE);
        treatmentDao.addItemTreatment(contentTreatment.getId(), item2Id, ItemTreatmentStatus.DONE);
        treatmentDao.addItemTreatment(ocrTreatment.getId(), item1Id, ItemTreatmentStatus.NEW);
        treatmentDao.addItemTreatment(ocrTreatment.getId(), item2Id, ItemTreatmentStatus.INPROGRESS);

        // When
        DataSourceStats stats = dataSourceDao.getDataSourceStats(testDataSourceId);

        // Then
        assertThat(stats.totalItems()).isEqualTo(2);
        assertThat(stats.previewsToDo()).isEqualTo(0);
        
        // Total treatment counts across all treatments
        assertThat(findCountForStatus(stats, ItemTreatmentStatus.NEW)).isEqualTo(1);
        assertThat(findCountForStatus(stats, ItemTreatmentStatus.DONE)).isEqualTo(2);
        assertThat(findCountForStatus(stats, ItemTreatmentStatus.INPROGRESS)).isEqualTo(1);
        assertThat(findCountForStatus(stats, ItemTreatmentStatus.UPDATED)).isEqualTo(0);
        assertThat(findCountForStatus(stats, ItemTreatmentStatus.FAILED)).isEqualTo(0);
        assertThat(stats.dataSourceStatus()).isEqualTo(DataSourceStatus.IDLE);
        assertThat(stats.dateLastScanned()).isNull();
    }

    private int findCountForStatus(DataSourceStats stats, ItemTreatmentStatus status) {
        return stats.treatmentStatusCounts().stream()
            .filter(c -> c.status() == status)
            .findFirst()
            .map(TreatmentStatusCount::count)
            .orElse(-1);
    }
}
