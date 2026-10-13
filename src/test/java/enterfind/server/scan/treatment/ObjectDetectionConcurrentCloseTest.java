package enterfind.server.scan.treatment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
import enterfind.server.model.FieldNameConstants;
import enterfind.server.model.LocalFileSystemDataSource;
import enterfind.server.scan.Item;
import enterfind.server.scan.LocalFilesDataProvider;
import enterfind.server.scan.Scanner;
import enterfind.server.scan.TreatmentOutput;
import enterfind.server.scan.TreatmentResult;
import enterfind.server.scan.Treatments;

/**
 * Regression test for the race condition where closeModel() frees native DJL
 * resources while a concurrent predict() call is in-flight.
 *
 * <p>The race is reproduced deterministically using {@code inferenceStartHook}: a
 * package-private test hook that pauses execution after the model reference is
 * acquired but before {@code newPredictor()} is called, creating the exact window
 * in which a concurrent {@code closeModel()} can free native resources.
 *
 * <p><b>Before the fix:</b> {@code closeModel()} completes immediately while
 * Thread A is paused in the hook. When Thread A resumes and calls
 * {@code model.newPredictor()}, the model's native handle is already freed,
 * causing an {@code IllegalStateException} that is caught and swallowed —
 * leaving the results empty. The assertion then fails.
 *
 * <p><b>After the fix:</b> {@code closeModel()} waits for all in-flight
 * inferences (read-lock holders) to complete before freeing native resources.
 * Thread A's inference succeeds, results contain detected objects, and the
 * assertion passes.
 *
 * <p><b>Prerequisite:</b> PostgreSQL must be running ({@code service postgresql start}).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ObjectDetectionConcurrentCloseTest extends BaseDatabaseTest {

    // JPEG in testshare that reliably produces "person" detections.
    private static final String TEST_IMAGE_NAME =
        "98594ed9-8559-4607-a0c0-b4ce7df43a26-2020-10-06T15_00_00.000Z_4.jpeg";

    @Autowired
    private Scanner scanner;

    @Autowired
    private DataSourceDao dataSourceDao;

    @Autowired
    private ItemDao itemDao;

    @Autowired
    private ObjectDetectionTreatmentHandler objectDetectionHandler;

    private Path tempTestDir;
    private LocalFileSystemDataSource testDataSource;

    @BeforeEach
    void setUpTest() throws Exception {
        wipeDatabase();

        tempTestDir = Files.createTempDirectory("od-concurrent-close-test-");
        TestFileUtils.copyTestShareToTempDir(tempTestDir);

        testDataSource = new LocalFileSystemDataSource(
            null,
            "OD Concurrent Close Test Source",
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
    void tearDownTest() throws IOException {
        // Always clear the hook and release any latched thread to avoid test pollution.
        objectDetectionHandler.inferenceStartHook = null;
        objectDetectionHandler.closeModel();
        if (tempTestDir != null && Files.exists(tempTestDir)) {
            TestFileUtils.deleteDirectoryRecursively(tempTestDir);
        }
    }

    @Test
    void testCloseModelDuringActiveInferenceDoesNotCorruptResult() throws Exception {
        List<Item> items = itemDao.findItemsByDatasource(testDataSource.id());
        Item imageItem = items.stream()
            .filter(i -> i.getName().equals(TEST_IMAGE_NAME))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Test JPEG '" + TEST_IMAGE_NAME + "' not found in scanned items"));

        LocalFilesDataProvider dataProvider = new LocalFilesDataProvider(testDataSource);

        // Latch 1: Thread A signals it has acquired the model and is paused in the hook.
        CountDownLatch modelAcquired = new CountDownLatch(1);
        // Latch 2: test releases Thread A to proceed into newPredictor() + predict().
        CountDownLatch allowProceed = new CountDownLatch(1);

        objectDetectionHandler.inferenceStartHook = () -> {
            modelAcquired.countDown();
            try {
                allowProceed.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        // Thread A: start inference; it blocks at the hook after acquiring the model.
        CompletableFuture<TreatmentOutput> inferFuture = CompletableFuture.supplyAsync(() ->
            Treatments.OBJECT_DETECTION.applyTreatment(imageItem, dataProvider, testDataSource));

        // Wait until Thread A is inside the hook (model reference obtained, not yet used).
        assertThat(modelAcquired.await(60, TimeUnit.SECONDS))
            .as("Inference thread should acquire the model within 60 seconds "
                + "(first call loads the ONNX model from disk)")
            .isTrue();

        // Thread B: close the model while Thread A is in the hook window.
        // Before fix: closes immediately; native resources freed before Thread A calls predict().
        // After fix:  blocks on write lock until Thread A completes predict() and releases read lock.
        Thread closeThread = new Thread(
            objectDetectionHandler::closeModel, "TestCloseThread");
        closeThread.start();

        // Give Thread B time to enter closeModel() and either complete (before fix)
        // or block on the write lock (after fix), then release Thread A.
        closeThread.join(200);
        allowProceed.countDown();

        List<TreatmentResult> results = inferFuture.get(120, TimeUnit.SECONDS).results();
        closeThread.join(30_000);

        // After the fix: inference completes with detected objects.
        // Before the fix: the caught exception leaves results without any FIELD_OBJECT_DETECTED entry.
        assertThat(results)
            .as("Inference must succeed even when closeModel() is called concurrently. "
                + "Failure here indicates the native resource race: closeModel() freed the DJL "
                + "model handle while predict() was in-flight.")
            .anyMatch(r -> FieldNameConstants.FIELD_OBJECT_DETECTED.equals(r.getKey()));
    }
}
