package enterfind.server.scan.treatment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.TreatmentDao;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.DataSourceTreatment;
import enterfind.server.model.ItemTreatmentStatus;
import enterfind.server.model.LocalFileSystemDataSource;
import enterfind.server.scan.TreatmentProcessor;
import enterfind.server.scan.Treatments;
import enterfind.server.util.Utilities;

class FaceDetectionInterruptedTreatmentTest extends BaseDatabaseTest {

    @Autowired
    private FaceDetectionTreatmentHandler handler;

    @Autowired
    private DataSourceDao dataSourceDao;

    @Autowired
    private TreatmentDao treatmentDao;

    @Autowired
    private TreatmentProcessor treatmentProcessor;

    private final CountDownLatch releasePermitHolders = new CountDownLatch(1);

    @AfterEach
    void releasePermitHolders() {
        handler.beforeSemaphoreAcquireHook = null;
        handler.semaphoreAcquiredHook = null;
        releasePermitHolders.countDown();
    }

    @Test
    void interruptedWaitForConcurrencySlotPropagatesFailure() throws Exception {
        CountDownLatch permitsHeld = new CountDownLatch(2);
        CountDownLatch interruptedCallerAboutToWait = new CountDownLatch(1);
        AtomicInteger callers = new AtomicInteger();
        CountDownLatch interruptedCallerFinished = new CountDownLatch(1);
        String itemTreatmentId = createFaceDetectionTreatment();

        handler.beforeSemaphoreAcquireHook = () -> {
            if (callers.incrementAndGet() == 3) {
                interruptedCallerAboutToWait.countDown();
            }
        };
        handler.semaphoreAcquiredHook = () -> {
            permitsHeld.countDown();
            try {
                releasePermitHolders.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        };

        Thread firstPermitHolder = startHandlerCall("FaceDetectionPermitHolder-1");
        Thread secondPermitHolder = startHandlerCall("FaceDetectionPermitHolder-2");
        assertThat(permitsHeld.await(5, TimeUnit.SECONDS)).isTrue();

        Thread interruptedCaller = new Thread(() -> {
            try {
                treatmentProcessor.processTreatments();
            } finally {
                interruptedCallerFinished.countDown();
            }
        }, "FaceDetectionInterruptedCaller");
        interruptedCaller.start();

        assertThat(interruptedCallerAboutToWait.await(5, TimeUnit.SECONDS)).isTrue();
        interruptedCaller.interrupt();
        assertThat(interruptedCallerFinished.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(treatmentDao.getItemTreatmentById(itemTreatmentId))
            .hasValueSatisfying(itemTreatment -> assertThat(itemTreatment.getStatus())
                .as("An interrupted handler must cause TreatmentProcessor to record FAILED")
                .isEqualTo(ItemTreatmentStatus.FAILED));

        releasePermitHolders.countDown();
        firstPermitHolder.join(5_000);
        secondPermitHolder.join(5_000);
    }

    private String createFaceDetectionTreatment() {
        LocalDateTime now = LocalDateTime.now();
        LocalFileSystemDataSource dataSource = (LocalFileSystemDataSource) dataSourceDao.create(
            new LocalFileSystemDataSource(null, "Interrupted treatment test", "0 0 * * *", now,
                null, DataSourceStatus.IDLE, "/tmp", null));
        treatmentDao.addTreatment(dataSource.id(), Treatments.FACE_DETECTION, null);
        DataSourceTreatment treatment = treatmentDao.getTreatment(dataSource.id(), Treatments.FACE_DETECTION)
            .orElseThrow();
        String itemId = Utilities.generateHexId();
        String itemTreatmentId = Utilities.generateHexId();

        dbAccess.update("""
                INSERT INTO ITEMS (id, datasource_id, name, path, time_created, time_modified,
                                  size_bytes, content_hash, mime_type, date_scanned)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, itemId, dataSource.id(), "interrupted.jpg", "/tmp", now, now, 1024L,
            "interrupted", "image/jpeg", now);
        dbAccess.update("""
                INSERT INTO ITEM_TREATMENTS (id, datasource_treatment_id, item_id, status, date_created)
                VALUES (?, ?, ?, ?, ?)
                """, itemTreatmentId, treatment.getId(), itemId, ItemTreatmentStatus.NEW.toString(), now);
        return itemTreatmentId;
    }

    private Thread startHandlerCall(String name) {
        Thread thread = new Thread(() -> {
            try {
                handler.apply(null, null, null, "FACE_DETECTION");
            } catch (Throwable ignored) {
            }
        }, name);
        thread.start();
        return thread;
    }
}