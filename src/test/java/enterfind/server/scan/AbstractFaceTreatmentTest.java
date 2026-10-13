package enterfind.server.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.TestFileUtils;
import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.FaceDao;
import enterfind.server.dao.ItemDao;
import enterfind.server.dao.TreatmentDao;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.LocalFileSystemDataSource;

/**
 * Shared fixture for tests that run face treatments over the two real photographs in
 * {@code src/test/resources/face-test-images}.
 *
 * <p>Both photos contain the same two people, which is what makes them useful to a grouping test.
 * Detection and recognition tests need the identical setup, so it lives here. Follows the
 * {@link AbstractScannerIntegrationTest} precedent.
 *
 * <p>Subclasses declare which treatments to enable via {@link #treatmentsToEnable()}; the fixture
 * creates a temporary datasource over a copy of the shared test share plus the face photos, adds
 * those treatments, and scans.
 */
public abstract class AbstractFaceTreatmentTest extends BaseDatabaseTest {

    /** Test photo containing two clearly visible faces. */
    protected static final String TEST_IMAGE_1 = "IMG_20251019_185903.jpg";
    /** Second test photo, containing the same two people. */
    protected static final String TEST_IMAGE_2 = "IMG_20251023_194554.jpg";

    @Autowired
    protected Scanner scanner;

    @Autowired
    protected DataSourceDao dataSourceDao;

    @Autowired
    protected ItemDao itemDao;

    @Autowired
    protected TreatmentDao treatmentDao;

    @Autowired
    protected FaceDao faceDao;

    protected Path tempTestDir;
    protected LocalFileSystemDataSource testDataSource;

    /** Treatments to enable on the fixture datasource before scanning. */
    protected abstract List<Treatments> treatmentsToEnable();

    /**
     * Hook for instance-global configuration a subclass needs in place before its treatments are
     * added and processed. Runs after {@link #wipeDatabase()}, which clears {@code SYSTEM_SETTINGS}
     * along with everything else — so the embedding-model selection the recognition fixture depends
     * on has to be re-made here, per test method, rather than once for the class. Detection needs
     * nothing, hence the no-op default.
     */
    protected void configureInstanceBeforeTreatments() {
    }

    /** Name for the fixture datasource; unique per test class keeps parallel runs honest. */
    protected String dataSourceName() {
        return getClass().getSimpleName() + " Source";
    }

    @BeforeEach
    void setUpFaceFixture() throws Exception {
        wipeDatabase();

        tempTestDir = Files.createTempDirectory("face-treatment-test-");
        TestFileUtils.copyTestShareToTempDir(tempTestDir);
        copyFaceTestImages(tempTestDir);

        testDataSource = new LocalFileSystemDataSource(
            null,
            dataSourceName(),
            "0 0 * * *",
            LocalDateTime.now(),
            null,
            DataSourceStatus.IDLE,
            tempTestDir.toString(),
            null
        );

        testDataSource = (LocalFileSystemDataSource) dataSourceDao.create(testDataSource);
        configureInstanceBeforeTreatments();
        for (Treatments treatment : treatmentsToEnable()) {
            treatmentDao.addTreatment(testDataSource.id(), treatment, null);
        }
        scanner.scan(testDataSource);
    }

    @AfterEach
    void tearDownFaceFixture() throws IOException {
        if (tempTestDir != null && Files.exists(tempTestDir)) {
            TestFileUtils.deleteDirectoryRecursively(tempTestDir);
        }
    }

    /** Find a scanned item by file name, failing the test if it is missing. */
    protected Item findItem(String name) {
        return itemDao.findItemsByDatasource(testDataSource.id()).stream()
            .filter(item -> item.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Scanned items should include " + name));
    }

    /** Drain the treatment processor until no work remains, returning the total processed. */
    protected int processAllTreatments(TreatmentProcessor treatmentProcessor) {
        int totalProcessed = 0;
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
            totalProcessed += processed;
        } while (processed > 0);
        return totalProcessed;
    }

    protected static void copyFaceTestImages(Path destDir) throws IOException {
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
