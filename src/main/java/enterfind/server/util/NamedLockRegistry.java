package enterfind.server.util;

import java.util.concurrent.locks.Lock;

import org.springframework.stereotype.Component;

import com.google.common.util.concurrent.Striped;

/**
 * A registry of reentrant locks keyed by an arbitrary string, for serializing critical sections that
 * contend on a shared logical resource named by that string. General-purpose: any code needing "at
 * most one thread in the section identified by key K" can use it.
 *
 * <p>Backed by Guava {@link Striped}, which is the vetted primitive for exactly this — no hand-rolled
 * get-or-create/reference-count/removal logic to get subtly wrong. {@code get(key)} always returns
 * the same {@link Lock} for a given key; distinct keys are spread across a fixed pool of stripes, so
 * two unrelated keys may occasionally share a stripe. That only ever costs a little concurrency,
 * never correctness (they serialize when they needn't), and the pool is large enough that it is rare.
 * The locks are reentrant, so {@link #unlock} must run on the thread that called {@link #lock}.
 *
 * <p><b>Same-thread contract.</b> The one cross-method use here — a treatment handler acquiring a
 * lock in {@code apply()} and the {@code TreatmentProcessor} releasing it in the {@code finally}
 * around the apply→save lifecycle — satisfies this because both run on one processor thread per item.
 */
@Component
public class NamedLockRegistry {

    // 256 stripes: with string keys' hash spread, collisions between distinct live keys are rare, and
    // a collision is a concurrency hit, not a correctness one. Eager (strong-referenced) locks, so a
    // key always maps to the same lock instance for the life of this bean.
    private static final int STRIPE_COUNT = 256;

    private final Striped<Lock> striped = Striped.lock(STRIPE_COUNT);

    /** Acquire the lock for {@code key}, blocking until available. Pair with one {@link #unlock} on the same thread. */
    public void lock(String key) {
        striped.get(key).lock();
    }

    /** Release the lock for {@code key}. Must run on the thread that acquired it. */
    public void unlock(String key) {
        striped.get(key).unlock();
    }
}
