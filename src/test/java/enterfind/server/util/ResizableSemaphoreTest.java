package enterfind.server.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class ResizableSemaphoreTest {

    @Test
    void testBasicAcquireRelease() throws InterruptedException {
        ResizableSemaphore semaphore = new ResizableSemaphore(2);

        // Should be able to acquire 2 permits
        semaphore.acquire();
        semaphore.acquire();

        // Should have 0 available permits
        assertEquals(0, semaphore.availablePermits());

        // Release one permit
        semaphore.release();
        assertEquals(1, semaphore.availablePermits());

        // Release another
        semaphore.release();
        assertEquals(2, semaphore.availablePermits());
    }

    @Test
    void testResize() throws InterruptedException {
        ResizableSemaphore semaphore = new ResizableSemaphore(2);

        // Acquire both permits
        semaphore.acquire();
        semaphore.acquire();
        assertEquals(0, semaphore.availablePermits());

        // Resize to 3 permits
        semaphore.resize(3);
        assertEquals(3, semaphore.getPermits());

        // Should now have 1 available permit (resizing up makes additional permits available)
        assertEquals(1, semaphore.availablePermits());

        // Release permits
        semaphore.release();
        semaphore.release();
        assertEquals(3, semaphore.availablePermits());
    }

    @Test
    void testConcurrentAccess() throws InterruptedException {
        ResizableSemaphore semaphore = new ResizableSemaphore(3);
        AtomicInteger counter = new AtomicInteger(0);

        // Use a thread pool and submit tasks synchronously
        Thread[] threads = new Thread[5];
        for (int i = 0; i < 5; i++) {
            threads[i] = new Thread(() -> {
                try {
                    semaphore.acquire();
                    try {
                        counter.incrementAndGet();
                        Thread.sleep(10); // Simulate work
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads[i].start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join(1000);
        }

        // Counter should be 5 (all tasks completed)
        assertEquals(5, counter.get());
    }

    @Test
    void testResizeDuringConcurrentAccess() throws InterruptedException {
        ResizableSemaphore semaphore = new ResizableSemaphore(2);
        CountDownLatch resizeLatch = new CountDownLatch(1);
        AtomicInteger counter = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(4);

        // Start 2 threads that hold permits
        CountDownLatch acquiredLatch = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    semaphore.acquire();
                    acquiredLatch.countDown();
                    resizeLatch.await(); // Wait for resize
                    semaphore.release();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        // Wait for both threads to acquire permits
        acquiredLatch.await();

        // At this point, 0 permits should be available
        assertEquals(0, semaphore.availablePermits());

        // Resize to 4 permits
        semaphore.resize(4);
        assertEquals(4, semaphore.getPermits());
        assertEquals(2, semaphore.availablePermits()); // Should have gained 2 additional permits

        // Release the waiting threads
        resizeLatch.countDown();

        // Submit 2 more tasks that should be able to acquire permits
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    semaphore.acquire();
                    counter.incrementAndGet();
                    semaphore.release();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));

        // Counter should be 2 (both additional tasks completed)
        assertEquals(2, counter.get());
    }

    @Test
    void testZeroPermits() throws InterruptedException {
        ResizableSemaphore semaphore = new ResizableSemaphore(0);

        // Should not be able to acquire immediately
        assertEquals(0, semaphore.availablePermits());

        // Release should increase available permits
        semaphore.release();
        assertEquals(1, semaphore.availablePermits());

        // Now should be able to acquire
        semaphore.acquire();
        assertEquals(0, semaphore.availablePermits());
    }

    @Test
    void testResizeToZero() throws InterruptedException {
        ResizableSemaphore semaphore = new ResizableSemaphore(2);

        // Acquire one permit
        semaphore.acquire();
        assertEquals(1, semaphore.availablePermits());

        // Resize to 0
        semaphore.resize(0);
        assertEquals(0, semaphore.getPermits());
        assertEquals(1, semaphore.availablePermits()); // Still have the acquired permit

        // Release the permit
        semaphore.release();
        assertEquals(2, semaphore.availablePermits()); // Can release beyond permits limit
    }
}