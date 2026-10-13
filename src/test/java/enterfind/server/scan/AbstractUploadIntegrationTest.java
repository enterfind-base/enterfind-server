package enterfind.server.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.dao.ItemDao;
import enterfind.server.dao.ItemPreviewDao;
import enterfind.server.dao.TreatmentDao;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceType;
import enterfind.server.model.ItemPreview;
import enterfind.server.model.ItemPreviewStatus;
import enterfind.server.model.ItemTreatment;
import enterfind.server.model.ItemTreatmentStatus;

/**
 * Abstract integration tests for the UploadController (PUT / DELETE).
 *
 * Each test verifies both the HTTP response AND the database state via ItemDao.
 * Storage state is verified via the data provider's own read-back API.
 *
 * Concrete subclasses supply Spring-injected beans and storage-specific setup
 * following the same pattern as AbstractScannerIntegrationTest.
 */
@AutoConfigureTestRestTemplate
public abstract class AbstractUploadIntegrationTest extends BaseDatabaseTest {

    // -----------------------------------------------------------------------
    // Shared Spring-injected beans (populated from concrete class context)
    // -----------------------------------------------------------------------

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ItemDao itemDao;

    @Autowired
    private TreatmentDao treatmentDao;

    @Autowired
    private ItemPreviewDao itemPreviewDao;

    @Autowired
    private Scanner scanner;


    // -----------------------------------------------------------------------
    // Abstract contract - only datasource is subclass-specific
    // -----------------------------------------------------------------------

    /** The fully created+persisted datasource for the current test. */
    protected abstract DataSourceInterface getTestDataSource();

    /**
     * Called before the subfolder test. Override where the storage requires the
     * directory to exist before writing (e.g. SMB). Default is a no-op.
     */
    protected void ensureFolderExistsInStorage(String folderPath) throws IOException {}

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @AfterEach
    void shutdownProviders() {
        DataSourceType.shutdownAllProviders();
    }

    // -----------------------------------------------------------------------
    // URL / request helpers
    // -----------------------------------------------------------------------

    private String uploadUrl(String fileName) {
        return uploadUrl(null, fileName);
    }

    private String uploadUrl(String folder, String fileName) {
        DataSourceInterface ds = getTestDataSource();
        String typePath = ds.getType().name().toLowerCase().replace('_', '-');
        String encodedName = URLEncoder.encode(ds.getName(), StandardCharsets.UTF_8);
        String filePath = (folder != null && !folder.isEmpty()) ? folder + "/" + fileName : fileName;
        return "http://localhost:" + port + "/content/" + typePath + "/" + encodedName + "/" + filePath;
    }

    private ResponseEntity<Void> doPut(String url, byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentLength(body.length);
        return restTemplate.exchange(url, HttpMethod.PUT, new HttpEntity<>(body, headers), Void.class);
    }

    private ResponseEntity<Void> doDelete(String url) {
        return restTemplate.exchange(url, HttpMethod.DELETE, HttpEntity.EMPTY, Void.class);
    }

    // -----------------------------------------------------------------------
    // Storage read-back via provider
    // -----------------------------------------------------------------------

    private boolean fileExistsInStorage(String path, String name) {
        DataProviderInterface provider = providerForDs();
        List<Item> items = provider.getItems(path);
        return items.stream().anyMatch(i -> name.equals(i.getName()));
    }

    private byte[] readFromStorage(String path, String name) throws IOException {
        Item item = new Item();
        item.setName(name);
        item.setPath(path);
        try (InputStream is = providerForDs().getInputStream(item)) {
            return is.readAllBytes();
        }
    }

    private DataProviderInterface providerForDs() {
        DataSourceInterface ds = getTestDataSource();
        return ds.getType().getDataProviderForTypeAndSource(ds);
    }

    private String datasourceId() {
        DataSourceInterface ds = getTestDataSource();
        return ds.getType().extractDatasourceId(ds);
    }

    private void writeFileToStorageDirect(String folder, String name, byte[] content) throws IOException {
        Item item = new Item();
        item.setName(name);
        item.setPath(folder);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(content)) {
            providerForDs().writeFile(item, bais);
        }
    }

    @Test
    void putNewFile() throws IOException {
        byte[] content = "hello upload".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<Void> resp = doPut(uploadUrl("upload.txt"), content);

        // HTTP
        assertThat(resp.getStatusCode().value()).isEqualTo(201);

        // DB: item row
        Item item = itemDao.findItemByDatasourceAndPath(datasourceId(), null, "upload.txt").orElseThrow();
        assertThat(item.getName()).isEqualTo("upload.txt");
        assertThat(item.getDatasourceId()).isEqualTo(datasourceId());
        assertThat(item.getSizeBytes()).isEqualTo((long) content.length);

        // DB: treatment queued
        List<ItemTreatment> treatments = treatmentDao.getItemTreatmentsByItem(item.getId());
        assertThat(treatments).hasSize(1);
        assertThat(treatments).extracting(ItemTreatment::getStatus).containsExactly(ItemTreatmentStatus.NEW);

        // DB: preview record
        Optional<ItemPreview> preview = itemPreviewDao.getByItemId(item.getId());
        assertThat(preview).isPresent();
        assertThat(preview.get().getStatus()).isEqualTo(ItemPreviewStatus.NEW);

        // Storage: bytes written
        assertThat(fileExistsInStorage(null, "upload.txt")).isTrue();
        assertThat(readFromStorage(null, "upload.txt")).isEqualTo(content);
    }

    @Test
    void putNewFileInSubfolder() throws IOException {
        ensureFolderExistsInStorage("subdir");
        byte[] content = "subfolder content".getBytes(StandardCharsets.UTF_8);

        ResponseEntity<Void> resp = doPut(uploadUrl("subdir", "nested.txt"), content);

        // HTTP
        assertThat(resp.getStatusCode().value()).isEqualTo(201);

        // DB: item row with correct path
        Item item = itemDao.findItemByDatasourceAndPath(datasourceId(), "subdir", "nested.txt").orElseThrow();
        assertThat(item.getPath()).isEqualTo("subdir");

        // DB: treatment queued
        List<ItemTreatment> subTreatments = treatmentDao.getItemTreatmentsByItem(item.getId());
        assertThat(subTreatments).hasSize(1);
        assertThat(subTreatments).extracting(ItemTreatment::getStatus).containsExactly(ItemTreatmentStatus.NEW);

        // Storage: bytes written in subfolder
        assertThat(fileExistsInStorage("subdir", "nested.txt")).isTrue();
        assertThat(readFromStorage("subdir", "nested.txt")).isEqualTo(content);
    }

    @Test
    void putOverwriteExistingFile() throws IOException {
        byte[] first  = "first version".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second version is longer than first".getBytes(StandardCharsets.UTF_8);

        doPut(uploadUrl("overwrite.txt"), first);
        Item after1 = itemDao.findItemByDatasourceAndPath(datasourceId(), null, "overwrite.txt").orElseThrow();

        doPut(uploadUrl("overwrite.txt"), second);
        Item after2 = itemDao.findItemByDatasourceAndPath(datasourceId(), null, "overwrite.txt").orElseThrow();

        // DB: same row, metadata updated
        assertThat(after2.getId()).isEqualTo(after1.getId());
        assertThat(after2.getSizeBytes()).isEqualTo((long) second.length);

        // DB: UPDATED treatment queued (existing records updated in-place, so count stays same)
        List<ItemTreatment> treatments = treatmentDao.getItemTreatmentsByItem(after2.getId());
        assertThat(treatments).hasSize(1);
        assertThat(treatments).extracting(ItemTreatment::getStatus).containsExactly(ItemTreatmentStatus.UPDATED);

        // Storage: new content visible
        assertThat(readFromStorage(null, "overwrite.txt")).isEqualTo(second);
    }

    @Test
    void deleteFile() throws IOException {
        byte[] content = "to be deleted".getBytes(StandardCharsets.UTF_8);
        doPut(uploadUrl("gone.txt"), content);
        Item item = itemDao.findItemByDatasourceAndPath(datasourceId(), null, "gone.txt").orElseThrow();
        String itemId = item.getId();

        ResponseEntity<Void> resp = doDelete(uploadUrl("gone.txt"));

        // HTTP
        assertThat(resp.getStatusCode().value()).isEqualTo(204);

        // DB: item and all dependent rows removed via cascade
        assertThat(itemDao.findItemByDatasourceAndPath(datasourceId(), null, "gone.txt")).isEmpty();
        assertThat(treatmentDao.getItemTreatmentsByItem(itemId)).isEmpty();
        assertThat(itemPreviewDao.existsByItemId(itemId)).isFalse();

        // Storage: file gone
        assertThat(fileExistsInStorage(null, "gone.txt")).isFalse();
    }

    @Test
    void putToUnknownDatasourceShouldReturn404() {
        DataSourceInterface ds = getTestDataSource();
        String typePath = ds.getType().name().toLowerCase().replace('_', '-');
        String url = "http://localhost:" + port + "/content/" + typePath + "/no-such-datasource/file.txt";

        ResponseEntity<Void> resp = doPut(url, "x".getBytes(StandardCharsets.UTF_8));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    // -----------------------------------------------------------------------
    // Scan interaction tests
    // -----------------------------------------------------------------------

    @Test
    void uploadThenRescan_noDuplicatesCreated() throws IOException {
        byte[] content = "rescan after upload".getBytes(StandardCharsets.UTF_8);
        doPut(uploadUrl("rescan.txt"), content);
        Item before = itemDao.findItemByDatasourceAndPath(datasourceId(), null, "rescan.txt").orElseThrow();

        scanner.scan(getTestDataSource());

        // Scanner must not create a duplicate item row
        Item after = itemDao.findItemByDatasourceAndPath(datasourceId(), null, "rescan.txt").orElseThrow();
        assertThat(after.getId()).isEqualTo(before.getId());

        // Scanner must not create a duplicate treatment row
        assertThat(treatmentDao.getItemTreatmentsByItem(before.getId())).hasSize(1);
    }

    @Test
    void scanThenUploadOverwrite_treatmentBecomesUpdated() throws IOException {
        byte[] original  = "original content".getBytes(StandardCharsets.UTF_8);
        byte[] overwrite = "overwritten - content is now longer than original".getBytes(StandardCharsets.UTF_8);

        // Write file directly to storage so the scanner (not the upload controller) creates the item
        writeFileToStorageDirect(null, "scanfirst.txt", original);
        scanner.scan(getTestDataSource());

        Item item = itemDao.findItemByDatasourceAndPath(datasourceId(), null, "scanfirst.txt").orElseThrow();
        assertThat(treatmentDao.getItemTreatmentsByItem(item.getId()))
            .hasSize(1)
            .extracting(ItemTreatment::getStatus)
            .containsExactly(ItemTreatmentStatus.NEW);

        // Overwrite via the upload controller: must update the existing treatment in-place
        doPut(uploadUrl("scanfirst.txt"), overwrite);

        assertThat(treatmentDao.getItemTreatmentsByItem(item.getId()))
            .hasSize(1)
            .extracting(ItemTreatment::getStatus)
            .containsExactly(ItemTreatmentStatus.UPDATED);
    }

    @Test
    void uploadDeleteFromStorageThenRescan_scannerRemovesItemFromDb() throws IOException {
        byte[] content = "will be removed from storage".getBytes(StandardCharsets.UTF_8);
        doPut(uploadUrl("orphan.txt"), content);
        Item item = itemDao.findItemByDatasourceAndPath(datasourceId(), null, "orphan.txt").orElseThrow();

        // Delete the file directly from storage without going through the DELETE endpoint
        // (simulates an external deletion or a crash between storage write and DB update)
        Item storageRef = new Item();
        storageRef.setName("orphan.txt");
        storageRef.setPath(null);
        providerForDs().deleteFile(storageRef);

        // Scanner detects the file is gone from storage and removes the item from DB
        scanner.scan(getTestDataSource());

        assertThat(itemDao.findItemByDatasourceAndPath(datasourceId(), null, "orphan.txt")).isEmpty();
        assertThat(treatmentDao.getItemTreatmentsByItem(item.getId())).isEmpty();
    }
}
