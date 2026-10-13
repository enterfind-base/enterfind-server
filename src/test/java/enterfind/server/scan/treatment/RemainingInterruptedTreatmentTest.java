package enterfind.server.scan.treatment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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

class RemainingInterruptedTreatmentTest extends BaseDatabaseTest {

    @Autowired
    private ObjectDetectionTreatmentHandler objectDetectionHandler;

    @Autowired
    private OcrTreatmentHandler ocrHandler;

    @Autowired
    private DataSourceDao dataSourceDao;

    @Autowired
    private TreatmentDao treatmentDao;

    @Autowired
    private TreatmentProcessor treatmentProcessor;

    @Test
    void interruptedObjectDetectionWaitRecordsFailure() throws Exception {
        assertInterruptedWaitRecordsFailure(Treatments.OBJECT_DETECTION,
            "interrupted-object-detection.jpg",
            hook -> objectDetectionHandler.beforeSemaphoreAcquireHook = hook,
            hook -> objectDetectionHandler.semaphoreAcquiredHook = hook,
            () -> {
                objectDetectionHandler.beforeSemaphoreAcquireHook = null;
                objectDetectionHandler.semaphoreAcquiredHook = null;
            },
            () -> objectDetectionHandler.apply(null, null, null, "OBJECT_DETECTION"));
    }

    @Test
    void interruptedOcrWaitRecordsFailure() throws Exception {
        assertInterruptedWaitRecordsFailure(Treatments.OCR, "interrupted-ocr.jpg",
            hook -> ocrHandler.beforeSemaphoreAcquireHook = hook,
            hook -> ocrHandler.semaphoreAcquiredHook = hook,
            () -> {
                ocrHandler.beforeSemaphoreAcquireHook = null;
                ocrHandler.semaphoreAcquiredHook = null;
            },
            () -> ocrHandler.apply(null, null, null, "OCR"));
    }

    private void assertInterruptedWaitRecordsFailure(Treatments treatmentName, String itemName,
            Consumer<Runnable> setBeforeAcquireHook, Consumer<Runnable> setAcquiredHook,
            Runnable clearHooks, Runnable invokeHandler) throws Exception {
        CountDownLatch releasePermitHolders = new CountDownLatch(1);
        CountDownLatch permitsHeld = new CountDownLatch(2);
        CountDownLatch interruptedCallerAboutToWait = new CountDownLatch(1);
        AtomicInteger callers = new AtomicInteger();
        CountDownLatch interruptedCallerFinished = new CountDownLatch(1);
        String itemTreatmentId = createTreatment(treatmentName, itemName);

        setBeforeAcquireHook.accept(() -> {
            if (callers.incrementAndGet() == 3) {
                interruptedCallerAboutToWait.countDown();
            }
        });
        setAcquiredHook.accept(() -> {
            permitsHeld.countDown();
            try {
                releasePermitHolders.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        Thread firstPermitHolder = startHandlerCall("TreatmentPermitHolder-1", invokeHandler);
        Thread secondPermitHolder = startHandlerCall("TreatmentPermitHolder-2", invokeHandler);
        try {
            assertThat(permitsHeld.await(5, TimeUnit.SECONDS)).isTrue();

            Thread interruptedCaller = new Thread(() -> {
                try {
                    treatmentProcessor.processTreatments();
                } finally {
                    interruptedCallerFinished.countDown();
                }
            }, "InterruptedTreatmentCaller");
            interruptedCaller.start();

            assertThat(interruptedCallerAboutToWait.await(5, TimeUnit.SECONDS)).isTrue();
            interruptedCaller.interrupt();
            assertThat(interruptedCallerFinished.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(treatmentDao.getItemTreatmentById(itemTreatmentId))
                .hasValueSatisfying(itemTreatment -> assertThat(itemTreatment.getStatus())
                    .as("An interrupted handler must cause TreatmentProcessor to record FAILED")
                    .isEqualTo(ItemTreatmentStatus.FAILED));
        } finally {
            clearHooks.run();
            releasePermitHolders.countDown();
            firstPermitHolder.join(5_000);
            secondPermitHolder.join(5_000);
        }
    }

    private String createTreatment(Treatments treatmentName, String itemName) {
        LocalDateTime now = LocalDateTime.now();
        LocalFileSystemDataSource dataSource = (LocalFileSystemDataSource) dataSourceDao.create(
            new LocalFileSystemDataSource(null, "Interrupted treatment test", "0 0 * * *", now,
                null, DataSourceStatus.IDLE, "/tmp", null));
        treatmentDao.addTreatment(dataSource.id(), treatmentName, null);
        DataSourceTreatment treatment = treatmentDao.getTreatment(dataSource.id(), treatmentName).orElseThrow();
        String itemId = Utilities.generateHexId();
        String itemTreatmentId = Utilities.generateHexId();

        dbAccess.update("""
                INSERT INTO ITEMS (id, datasource_id, name, path, time_created, time_modified,
                                  size_bytes, content_hash, mime_type, date_scanned)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, itemId, dataSource.id(), itemName, "/tmp", now, now, 1024L,
            "interrupted", "image/jpeg", now);
        dbAccess.update("""
                INSERT INTO ITEM_TREATMENTS (id, datasource_treatment_id, item_id, status, date_created)
                VALUES (?, ?, ?, ?, ?)
                """, itemTreatmentId, treatment.getId(), itemId, ItemTreatmentStatus.NEW.toString(), now);
        return itemTreatmentId;
    }

    private static Thread startHandlerCall(String name, Runnable invokeHandler) {
        Thread thread = new Thread(() -> {
            try {
                invokeHandler.run();
            } catch (Throwable ignored) {
            }
        }, name);
        thread.start();
        return thread;
    }
}