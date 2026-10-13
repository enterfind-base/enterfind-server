package enterfind.server.scan.treatment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import ai.djl.repository.zoo.ModelZoo;

/**
 * Regression test for the DJL model-zoo class-initialization deadlock.
 *
 * <p>Before {@link DjlBootstrap}, the face and object detection handlers could each touch DJL's
 * {@code ModelZoo} for the first time on different threads. Because {@code DefaultModelZoo} extends
 * {@code ModelZoo} and {@code ModelZoo.<clinit>} constructs a {@code DefaultModelZoo}, the two
 * threads could each own one class-init monitor and wait for the other — a permanent, un-interruptible
 * deadlock that wedged the treatment processor until the process was restarted.
 *
 * <p>Note: class initialization happens once per JVM, so this only exercises the real race when it
 * is the first DJL touch in the Surefire fork. It is a cheap guard either way, and it requires no
 * network access — no model is loaded, only classes are initialized.
 */
class DjlBootstrapTest {

    @Test
    @Timeout(60)
    void concurrentFirstTouchOfModelZooDoesNotDeadlock() throws Exception {
        int threadCount = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            // Maximize the chance that every thread races into DJL at the same instant.
            CyclicBarrier startTogether = new CyclicBarrier(threadCount);

            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    startTogether.await(30, TimeUnit.SECONDS);
                    DjlBootstrap.ensureInitialized();
                    // Touch ModelZoo's static state directly: a map lookup, no model download.
                    ModelZoo.hasModelZoo("ai.djl.localmodelzoo");
                    return "ok";
                }));
            }

            for (Future<String> future : futures) {
                // On the deadlock this never completes; the timeout is what fails the test.
                assertThat(future.get(45, TimeUnit.SECONDS))
                    .as("Concurrent DJL model-zoo initialization must complete rather than deadlock")
                    .isEqualTo("ok");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(60)
    void ensureInitializedIsIdempotent() {
        DjlBootstrap.ensureInitialized();
        DjlBootstrap.ensureInitialized();

        assertThat(ModelZoo.listModelZoo())
            .as("The default model zoo should be registered after initialization")
            .isNotEmpty();
    }
}
