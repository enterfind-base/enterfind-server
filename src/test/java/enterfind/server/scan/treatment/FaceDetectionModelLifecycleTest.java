package enterfind.server.scan.treatment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
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
import enterfind.server.model.LocalFileSystemDataSource;
import enterfind.server.scan.Item;
import enterfind.server.scan.LocalFilesDataProvider;
import enterfind.server.scan.Scanner;
import enterfind.server.scan.Treatments;

/**
 * Native-memory lifecycle regression tests for {@link FaceDetectionTreatmentHandler}.
 *
 * <p>Mirrors {@link ObjectDetectionModelLifecycleTest}: the YuNet ONNX model must load lazily on
 * first inference, unload on explicit close and on idle timeout, reload cleanly afterwards, and
 * never load for a file the handler skips. Asserts the observable {@code isModelLoaded()} contract
 * rather than literal native bytes freed.
 *
 * <p><b>Prerequisite:</b> PostgreSQL must be running ({@code service postgresql start}).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class FaceDetectionModelLifecycleTest extends BaseDatabaseTest {

    // Face-test image known to contain at least one detectable face.
    private static final String TEST_IMAGE_NAME = "IMG_20251019_185903.jpg";

    @Autowired
    private Scanner scanner;

    @Autowired
    private DataSourceDao dataSourceDao;

    @Autowired
    private ItemDao itemDao;

    @Autowired
    private FaceDetectionTreatmentHandler handler;

    private Path tempTestDir;
    private LocalFileSystemDataSource testDataSource;

    @BeforeEach
    void setUpTest() throws Exception {
        wipeDatabase();

        tempTestDir = Files.createTempDirectory("face-lifecycle-test-");
        TestFileUtils.copyTestShareToTempDir(tempTestDir);
        copyFaceTestImages(tempTestDir);

        testDataSource = new LocalFileSystemDataSource(
            null,
            "Face Lifecycle Test Source",
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
        handler.closeAllModels();
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
        Treatments.FACE_DETECTION.applyTreatment(
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

        handler.closeAllModels();
        assertThat(handler.isModelLoaded())
            .as("model should be unloaded after closeAllModels()")
            .isFalse();

        // Idempotent: a second close on an already-unloaded model is a no-op, not a double free.
        handler.closeAllModels();
        assertThat(handler.isModelLoaded()).isFalse();
    }

    @Test
    void idleUnloadReleasesModel() {
        // Any positive idle duration now counts as idle; predict() takes many ms so elapsed > 0.
        handler.setIdleTimeoutMsForTest(0);
        runInference(item(TEST_IMAGE_NAME));
        assertThat(handler.isModelLoaded()).isTrue();

        handler.closeIdleModels();
        assertThat(handler.isModelLoaded())
            .as("idle model should be unloaded by closeIdleModels()")
            .isFalse();
    }

    @Test
    void modelReloadsAfterClose() {
        runInference(item(TEST_IMAGE_NAME));
        handler.closeAllModels();
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
