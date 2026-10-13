package enterfind.server.scan.treatment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.TestFileUtils;
import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.ItemDao;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.LocalFileSystemDataSource;
import enterfind.server.scan.Item;
import enterfind.server.scan.LocalFilesDataProvider;
import enterfind.server.scan.Scanner;
import enterfind.server.scan.Treatments;

/**
 * Native-memory lifecycle regression tests for {@link ObjectDetectionTreatmentHandler}.
 *
 * <p>These lock the load/unload contract that decides whether the ONNX model's off-heap allocation
 * is released: the model must load lazily on first inference, unload on explicit close and on idle
 * timeout, reload cleanly afterwards, and never load for a file the handler skips. They assert the
 * observable contract ({@code isModelLoaded()}) rather than literal native bytes freed, which is
 * nondeterministic — the reference being dropped and {@code close()} being called is what causes
 * reclamation, so that is what we pin.
 *
 * <p>Model state is instance state on the handler bean, and {@code @DirtiesContext} gives each
 * method a fresh bean, so the test-only {@code setIdleTimeoutMsForTest()} override cannot leak
 * between methods.
 *
 * <p><b>Prerequisite:</b> PostgreSQL must be running ({@code service postgresql start}).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ObjectDetectionModelLifecycleTest extends BaseDatabaseTest {

    // JPEG in testshare that reliably produces detections.
    private static final String TEST_IMAGE_NAME =
        "98594ed9-8559-4607-a0c0-b4ce7df43a26-2020-10-06T15_00_00.000Z_4.jpeg";

    @Autowired
    private Scanner scanner;

    @Autowired
    private DataSourceDao dataSourceDao;

    @Autowired
    private ItemDao itemDao;

    @Autowired
    private ObjectDetectionTreatmentHandler handler;

    private Path tempTestDir;
    private LocalFileSystemDataSource testDataSource;

    @BeforeEach
    void setUpTest() throws Exception {
        wipeDatabase();

        tempTestDir = Files.createTempDirectory("od-lifecycle-test-");
        TestFileUtils.copyTestShareToTempDir(tempTestDir);

        testDataSource = new LocalFileSystemDataSource(
            null,
            "OD Lifecycle Test Source",
            "0 0 * * *",
            LocalDateTime.now(),
            null,
            DataSourceStatus.IDLE,
            tempTestDir.toString(),
            null
        );
        testDataSource = (LocalFileSystemDataSource) dataSourceDao.create(testDataSource);
        scanner.scan(testDataSource);
    }

    @AfterEach
    void tearDownTest() throws Exception {
        handler.closeModel();
        if (tempTestDir != null && Files.exists(tempTestDir)) {
            TestFileUtils.deleteDirectoryRecursively(tempTestDir);
        }
    }

    private Item item(String name) {
        return itemDao.findItemsByDatasource(testDataSource.id()).stream()
            .filter(i -> i.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("item not found in scanned share: " + name));
    }

    private void runInference(Item item) {
        Treatments.OBJECT_DETECTION.applyTreatment(
            item, new LocalFilesDataProvider(testDataSource), testDataSource);
    }

    @Test
    void modelIsNotLoadedBeforeFirstInference() {
        assertThat(handler.isModelLoaded()).isFalse();
    }

    @Test
    void modelLoadsOnInferenceAndUnloadsOnClose() {
        runInference(item(TEST_IMAGE_NAME));
        assertThat(handler.isModelLoaded())
            .as("model should be loaded after an inference")
            .isTrue();

        handler.closeModel();
        assertThat(handler.isModelLoaded())
            .as("model should be unloaded after closeModel()")
            .isFalse();

        // Idempotent: a second close on an already-unloaded model is a no-op, not a double free.
        handler.closeModel();
        assertThat(handler.isModelLoaded()).isFalse();
    }

    @Test
    void idleUnloadReleasesModel() {
        // Any positive idle duration now counts as idle. The inference above already advanced the
        // last-access clock, and predict() takes many ms, so the elapsed time is comfortably > 0.
        handler.setIdleTimeoutMsForTest(0);
        runInference(item(TEST_IMAGE_NAME));
        assertThat(handler.isModelLoaded()).isTrue();

        handler.closeIdleModel();
        assertThat(handler.isModelLoaded())
            .as("idle model should be unloaded by closeIdleModel()")
            .isFalse();
    }

    @Test
    void modelReloadsAfterClose() {
        runInference(item(TEST_IMAGE_NAME));
        handler.closeModel();
        assertThat(handler.isModelLoaded()).isFalse();

        runInference(item(TEST_IMAGE_NAME));
        assertThat(handler.isModelLoaded())
            .as("model should reload on the next inference after being closed")
            .isTrue();
    }

    @Test
    void skippedFileDoesNotLoadModel() {
        // A non-image file: apply() returns without ever touching the model.
        Item textItem = itemDao.findItemsByDatasource(testDataSource.id()).stream()
            .filter(i -> i.getName().toLowerCase().endsWith(".txt"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no .txt item in test share"));

        runInference(textItem);
        assertThat(handler.isModelLoaded())
            .as("processing a skipped (non-image) file must not load the model")
            .isFalse();
    }
}
