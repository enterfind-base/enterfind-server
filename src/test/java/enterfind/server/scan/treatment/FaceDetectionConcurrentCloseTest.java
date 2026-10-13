package enterfind.server.scan.treatment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
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
 * Regression test for the race condition where closeAllModels() frees native DJL
 * resources while a concurrent face-detection predict() call is in-flight.
 *
 * <p>Mirrors {@link ObjectDetectionConcurrentCloseTest} against
 * {@code FaceDetectionTreatmentHandler}. The {@code inferenceStartHook} fires after
 * the detection model reference is acquired but before {@code newPredictor()} is
 * called, creating the exact window in which a concurrent {@code closeAllModels()}
 * can free the model's native handle.
 *
 * <p>This guards the same read/write-lock machinery the embedding model's hot-swap
 * depends on; see {@link FaceRecognitionConcurrentCloseTest}.
 *
 * <p><b>Before the fix:</b> {@code closeAllModels()} completes immediately while
 * Thread A is paused in the hook. {@code detModel.newPredictor()} then throws
 * {@code NDManager has been closed already}, which is caught and swallowed —
 * leaving the results empty. The assertion fails.
 *
 * <p><b>After the fix:</b> {@code closeAllModels()} calls
 * {@code closeDetectionModel()} which tries to acquire the write lock, but Thread
 * A holds the read lock. It blocks until Thread A's inference completes, after
 * which both models are closed safely. Inference results are intact and the
 * assertion passes.
 *
 * <p><b>Prerequisite:</b> PostgreSQL must be running ({@code service postgresql start}).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class FaceDetectionConcurrentCloseTest extends BaseDatabaseTest {

    // Face-test image known to contain at least one detectable face.
    private static final String TEST_IMAGE_NAME = "IMG_20251019_185903.jpg";

    @Autowired
    private Scanner scanner;

    @Autowired
    private DataSourceDao dataSourceDao;

    @Autowired
    private ItemDao itemDao;

    @Autowired
    private FaceDetectionTreatmentHandler faceDetectionHandler;

    private Path tempTestDir;
    private LocalFileSystemDataSource testDataSource;

    @BeforeEach
    void setUpTest() throws Exception {
        wipeDatabase();

        tempTestDir = Files.createTempDirectory("face-concurrent-close-test-");
        TestFileUtils.copyTestShareToTempDir(tempTestDir);
        copyFaceTestImages(tempTestDir);

        testDataSource = new LocalFileSystemDataSource(
            null,
            "Face Concurrent Close Test Source",
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
        faceDetectionHandler.inferenceStartHook = null;
        faceDetectionHandler.closeAllModels();
        if (tempTestDir != null && Files.exists(tempTestDir)) {
            TestFileUtils.deleteDirectoryRecursively(tempTestDir);
        }
    }

    @Test
    void testCloseAllModelsDuringActiveInferenceDoesNotCorruptResult() throws Exception {
        List<Item> items = itemDao.findItemsByDatasource(testDataSource.id());
        Item imageItem = items.stream()
            .filter(i -> i.getName().equals(TEST_IMAGE_NAME))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Test image '" + TEST_IMAGE_NAME + "' not found in scanned items"));

        LocalFilesDataProvider dataProvider = new LocalFilesDataProvider(testDataSource);

        // Latch 1: Thread A signals it has acquired the detection model and is paused.
        CountDownLatch modelAcquired = new CountDownLatch(1);
        // Latch 2: test releases Thread A to proceed into newPredictor() + predict().
        CountDownLatch allowProceed = new CountDownLatch(1);

        faceDetectionHandler.inferenceStartHook = () -> {
            modelAcquired.countDown();
            try {
                allowProceed.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        // Thread A: start inference; blocks at the hook after acquiring the detection model.
        CompletableFuture<TreatmentOutput> inferFuture = CompletableFuture.supplyAsync(() ->
            Treatments.FACE_DETECTION.applyTreatment(imageItem, dataProvider, testDataSource));

        // Wait until Thread A is inside the hook (detection model reference obtained).
        assertThat(modelAcquired.await(120, TimeUnit.SECONDS))
            .as("Inference thread should acquire the face detection model within 120 seconds "
                + "(first call downloads and loads the ONNX model from disk)")
            .isTrue();

        // Thread B: close all models while Thread A holds the read lock and is mid-hook.
        // Before fix: closeDetectionModel() completes immediately, freeing native resources.
        // After fix:  write lock in closeDetectionModel() blocks until Thread A releases readLock.
        Thread closeThread = new Thread(
            faceDetectionHandler::closeAllModels, "TestCloseThread");
        closeThread.start();

        // Give Thread B time to enter closeAllModels() and either complete (before fix)
        // or block on the write lock (after fix), then release Thread A.
        closeThread.join(200);
        allowProceed.countDown();

        List<TreatmentResult> results = inferFuture.get(120, TimeUnit.SECONDS).results();
        closeThread.join(30_000);

        // After the fix: inference completes with detected faces.
        // Before the fix: the caught exception leaves results without any objectdetected entry.
        assertThat(results)
            .as("Face detection inference must succeed even when closeAllModels() is called "
                + "concurrently. Failure here indicates the native resource race: closeAllModels() "
                + "freed the DJL model handle while predict() was in-flight.")
            .anyMatch(r -> FieldNameConstants.FIELD_OBJECT_DETECTED.equals(r.getKey()));
    }

    private static void copyFaceTestImages(Path destDir) throws IOException {
        Path source = new ClassPathResource("face-test-images").getFile().toPath();
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(src -> {
                try {
                    Path dest = destDir.resolve(source.relativize(src));
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Failed to copy face test image: " + src, e);
                }
            });
        }
    }
}
