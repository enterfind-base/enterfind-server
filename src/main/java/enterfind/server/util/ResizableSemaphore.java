package enterfind.server.util;

import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.Condition;

/**
 * A thread-safe semaphore that can be resized dynamically without race conditions.
 * Prevents the issue where replacing a semaphore causes threads holding permits
 * from the old semaphore to lose coordination.
 */
public class ResizableSemaphore {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition condition = lock.newCondition();
    private int permits;
    private int available;

    public ResizableSemaphore(int permits) {
        this.permits = permits;
        this.available = permits;
    }

    public void acquire() throws InterruptedException {
        lock.lock();
        try {
            while (available <= 0) {
                condition.await();
            }
            available--;
        } finally {
            lock.unlock();
        }
    }

    public void release() {
        lock.lock();
        try {
            available++;
            condition.signal();
        } finally {
            lock.unlock();
        }
    }

    public void resize(int newPermits) {
        lock.lock();
        try {
            int oldPermits = this.permits;
            this.permits = Math.max(0, newPermits);

            // If increasing permits, add the difference to available
            if (newPermits > oldPermits) {
                available += (newPermits - oldPermits);
                condition.signalAll();
            }
            // If decreasing permits, don't reduce available below 0
            // This prevents race conditions where threads holding permits
            // from the old semaphore lose coordination
        } finally {
            lock.unlock();
        }
    }

    public int availablePermits() {
        lock.lock();
        try {
            return available;
        } finally {
            lock.unlock();
        }
    }

    public int getPermits() {
        lock.lock();
        try {
            return permits;
        } finally {
            lock.unlock();
        }
    }
}