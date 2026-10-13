package enterfind.server.scan.treatment;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Serializes the one-time initialization of DJL's model-zoo classes.
 *
 * <p>{@code DefaultModelZoo} extends {@code ModelZoo}, and {@code ModelZoo.<clinit>} constructs a
 * {@code DefaultModelZoo} while registering the default zoo provider. When two threads touch DJL
 * for the first time concurrently, each can end up owning one of the two class-initialization
 * monitors and waiting for the other:
 *
 * <pre>
 *   Thread A: ModelZoo.&lt;clinit&gt; -&gt; new DefaultModelZoo() -&gt; waits for DefaultModelZoo init
 *   Thread B: DefaultModelZoo init -&gt; needs superclass ModelZoo -&gt; waits for ModelZoo init
 * </pre>
 *
 * <p>The result is a permanent deadlock. It is not reported by the JVM's deadlock detector (that
 * only finds monitor cycles, not class-init cycles), and threads waiting on a class-init monitor
 * are not interruptible, so no watchdog can recover them. The process must be restarted.
 *
 * <p>Per-handler locks cannot prevent this: the model instances are per-handler, but DJL's zoo
 * bootstrap is global. This class provides the one global gate. Initializing {@code ModelZoo} from
 * a single thread is safe because the recursive superclass initialization that follows happens on
 * that same thread, which the JVM permits (JLS 12.4.2).
 */
@Component
public class DjlBootstrap {

    private static final Logger logger = LoggerFactory.getLogger(DjlBootstrap.class);

    private static final Object INIT_LOCK = new Object();
    private static volatile boolean initialized = false;

    /**
     * Warm DJL at startup, on the single-threaded Spring initialization path, so the first
     * concurrent treatment threads always find the classes already initialized.
     */
    @PostConstruct
    public void warmUp() {
        ensureInitialized();
    }

    /**
     * Force DJL's model-zoo classes to initialize, at most once per JVM. Callers must invoke this
     * before any {@code ModelZoo} access, and must not hold a handler's model lock while doing so.
     * Cheap after the first call: a volatile read.
     */
    public static void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (INIT_LOCK) {
            if (initialized) {
                return;
            }
            try {
                ClassLoader loader = DjlBootstrap.class.getClassLoader();
                logger.info("Initializing DJL model zoo classes (one-time, single-threaded)...");
                Class.forName("ai.djl.repository.zoo.ModelZoo", true, loader);
                Class.forName("ai.djl.repository.zoo.DefaultModelZoo", true, loader);
                logger.info("DJL model zoo classes initialized");
            } catch (ClassNotFoundException | LinkageError e) {
                // Leave initialized=false so a later call can retry. Handlers will surface the
                // real failure when they attempt to load a model.
                logger.error("Failed to initialize DJL model zoo classes", e);
                return;
            }
            initialized = true;
        }
    }
}
