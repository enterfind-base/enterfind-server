package enterfind.server.scan.treatment;

import static enterfind.server.model.FieldNameConstants.FIELD_DETECTED_FACE;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import enterfind.server.model.FaceEmbeddingMode;
import enterfind.server.scan.AbstractFaceTreatmentTest;
import enterfind.server.scan.Item;
import enterfind.server.scan.LocalFilesDataProvider;
import enterfind.server.scan.TreatmentOutput;
import enterfind.server.scan.TreatmentProcessor;
import enterfind.server.scan.Treatments;
import enterfind.server.util.NamedLockRegistry;

/**
 * The embedding-model half of {@link FaceDetectionConcurrentCloseTest}: closing the model while a
 * {@code predict()} is in flight must not free native DJL resources out from under it.
 *
 * <p>This matters more for recognition than it ever did for detection. Detection's model is fixed,
 * so a close mid-inference only happened at shutdown or idle-unload. The picker turns it into a
 * <b>routine</b> operation — an operator changing models triggers a reload while a drain is running,
 * which is exactly this race, on purpose, in production.
 *
 * <p>Built on {@link AbstractFaceTreatmentTest} rather than copying the detection test's fixture,
 * because recognition needs more setup than detection does — a selected model and <em>persisted
 * detection rows</em> to read landmarks from — and that is precisely what the shared fixture plus one
 * drain already produces. It lives in this package regardless, because the {@code inferenceStartHook}
 * seam it drives is package-private.
 */
class FaceRecognitionConcurrentCloseTest extends AbstractFaceTreatmentTest {

    @Autowired
    private TreatmentProcessor treatmentProcessor;

    @Autowired
    private FaceRecognitionTreatmentHandler faceRecognitionHandler;

    @Autowired
    private FaceEmbeddingModelService faceEmbeddingModelService;

    @Autowired
    private NamedLockRegistry namedLockRegistry;

    @Override
    protected List<Treatments> treatmentsToEnable() {
        return List.of(Treatments.FACE_RECOGNITION);
    }

    @Override
    protected void configureInstanceBeforeTreatments() {
        faceEmbeddingModelService.setModel(FaceEmbeddingMode.BUILTIN, null, true);
    }

    @AfterEach
    void clearHookAndUnloadModel() {
        faceRecognitionHandler.inferenceStartHook = null;
        faceRecognitionHandler.closeAllModels();
    }

    @Test
    void closingTheEmbeddingModelDuringInferenceDoesNotCorruptTheResult() throws Exception {
        // One full drain first: this persists the detection geometry recognition reads, and loads
        // the embedding model, so the race below is about closing it rather than about loading it.
        processAllTreatments(treatmentProcessor);

        Item image = findItem(TEST_IMAGE_1);
        LocalFilesDataProvider dataProvider = new LocalFilesDataProvider(testDataSource);

        // Latch 1: the inference thread signals it holds the model and the read lock, and is paused
        // in the hook — the window in which a concurrent close would free the native handle.
        CountDownLatch modelAcquired = new CountDownLatch(1);
        // Latch 2: the test releases it to proceed into newPredictor() + predict().
        CountDownLatch allowProceed = new CountDownLatch(1);

        faceRecognitionHandler.inferenceStartHook = () -> {
            modelAcquired.countDown();
            try {
                allowProceed.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        CompletableFuture<TreatmentOutput> inference = CompletableFuture.supplyAsync(() -> {
            TreatmentOutput result =
                    Treatments.FACE_RECOGNITION.applyTreatment(image, dataProvider, testDataSource);
            // Released here, on the thread that acquired it, because the grouping lock is a
            // ReentrantLock and so is owner-bound — releasing from the test thread throws
            // IllegalMonitorStateException. TreatmentProcessor satisfies the same constraint by
            // running apply, save and release on one processor thread; this stands in for that.
            result.locks().forEach(namedLockRegistry::unlock);
            return result;
        });

        assertThat(modelAcquired.await(120, TimeUnit.SECONDS))
                .as("the inference thread should reach the hook holding the embedding model")
                .isTrue();

        // Close concurrently. Without the read/write lock discipline this returns immediately and
        // frees the model; with it, closeEmbeddingModel() blocks on the write lock until the
        // inference thread releases its read lock.
        Thread closeThread = new Thread(faceRecognitionHandler::closeAllModels, "TestCloseThread");
        closeThread.start();
        closeThread.join(200);
        allowProceed.countDown();

        TreatmentOutput output = inference.get(120, TimeUnit.SECONDS);
        closeThread.join(30_000);

        assertThat(output.results())
                .as("embedding must survive a concurrent close: an empty result here means the "
                        + "DJL native handle was freed while predict() was in flight")
                .anyMatch(result -> FIELD_DETECTED_FACE.equals(result.getKey()));
        assertThat(output.faceEmbeddings())
                .as("and the vectors it produced come back with it")
                .isNotEmpty();
    }
}
