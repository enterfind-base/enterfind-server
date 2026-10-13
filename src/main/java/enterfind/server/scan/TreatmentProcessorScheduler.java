package enterfind.server.scan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import enterfind.server.config.ApplianceMode;
import enterfind.server.dao.Schema;
import enterfind.server.dao.UserSettingsDao;
import enterfind.server.model.UserSettingName;

@Configuration
@EnableScheduling
@Profile("!alpha")  // Enable scheduling for all profiles except 'alpha'
public class TreatmentProcessorScheduler {
    private static final Logger logger = LoggerFactory.getLogger(TreatmentProcessorScheduler.class);

    // Maximum time a thread can run before being forcibly interrupted (in seconds)
    private static final int THREAD_TIMEOUT_SECONDS = 600; // 10 minutes

    private final TreatmentProcessor treatmentProcessor;
    private final Schema schema;
    private final UserSettingsDao userSettingsDao;
    private final ApplianceMode applianceMode;
    private final List<Thread> activeThreads = new ArrayList<>();
    private final Map<Thread, Instant> threadStartTimes = new ConcurrentHashMap<>();

    @Autowired
    public TreatmentProcessorScheduler(TreatmentProcessor treatmentProcessor, Schema schema, UserSettingsDao userSettingsDao, ApplianceMode applianceMode) {
        this.treatmentProcessor = treatmentProcessor;
        this.schema = schema;
        this.userSettingsDao = userSettingsDao;
        this.applianceMode = applianceMode;
    }

    /**
     * Clean up dead threads and return the count of threads still alive.
     */
    private synchronized int cleanupAndCountAliveThreads() {
        Iterator<Thread> iterator = activeThreads.iterator();
        while (iterator.hasNext()) {
            Thread thread = iterator.next();
            if (!thread.isAlive()) {
                logger.info("Removing finished thread: {}", thread.getName());
                iterator.remove();
                threadStartTimes.remove(thread);
            }
        }
        return activeThreads.size();
    }

    /**
     * Thread governor: checks for stuck threads and forcibly interrupts them.
     * Runs every 30 seconds to monitor thread health.
     */
    @Scheduled(fixedDelay = 30000)
    public synchronized void monitorAndKillStuckThreads() {
        Instant now = Instant.now();
        Iterator<Map.Entry<Thread, Instant>> iterator = threadStartTimes.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<Thread, Instant> entry = iterator.next();
            Thread thread = entry.getKey();
            Instant startTime = entry.getValue();

            long runningSeconds = now.getEpochSecond() - startTime.getEpochSecond();

            if (runningSeconds > THREAD_TIMEOUT_SECONDS) {
                logger.error("Thread {} has been running for {} seconds (timeout: {} seconds). Forcibly interrupting!",
                            thread.getName(), runningSeconds, THREAD_TIMEOUT_SECONDS);

                // Interrupt the thread
                thread.interrupt();

                // If it's still alive after interruption, log and abandon it
                if (thread.isAlive()) {
                    logger.error("Thread {} did not respond to interrupt. Removing from active tracking to prevent thread leak.",
                                thread.getName());
                    // Remove from activeThreads so it's no longer counted and a replacement can be spawned
                    activeThreads.remove(thread);
                }

                // Remove from tracking
                iterator.remove();
            }
        }
    }

    // Runs every 10 seconds after previous run completes
    @Scheduled(fixedDelay = 10000)
    public synchronized void runProcessTreatmentsPeriodically() {
        if (applianceMode.isEnabled()) {
            logger.warn("Appliance mode: skipping scheduled treatment processing");
            return;
        }
        if (schema.isSchemaUpdateInProgress()) {
            logger.info("Skipping scheduled treatment processing: schema update in progress");
            return;
        }

        // Clean up finished threads and count how many are still alive
        int aliveThreads = cleanupAndCountAliveThreads();

        // Determine desired thread count
        int desiredThreads = Integer.parseInt(
            userSettingsDao.getSetting(UserSettingName.CONCURRENT_TREATMENT_PROCESSORS)
                .orElse("3")
        );
        desiredThreads = Math.max(1, desiredThreads); // Ensure at least 1 thread

        // Calculate how many new threads to start (less conservative approach)
        int threadsToStart = desiredThreads - aliveThreads;

        if (threadsToStart <= 0) {
            logger.info("All {} treatment processor threads are still running. No new threads to start.", aliveThreads);
            return;
        }

        logger.info("Starting {} new TreatmentProcessor thread(s). ({} already running, {} desired)",
                    threadsToStart, aliveThreads, desiredThreads);

        for (int i = 0; i < threadsToStart; i++) {
            final int threadNum = activeThreads.size() + i;
            Thread thread = new Thread(() -> {
                logger.info("Thread {} started.", threadNum);
                try {
                    int maxIterations = 100; // Limit iterations per cycle to prevent infinite loops
                    int iterations = 0;
                    int processed;
                    do {
                        processed = treatmentProcessor.processTreatments();
                        logger.info("Thread {} processed {} treatments in this iteration.", threadNum, processed);
                        iterations++;
                    } while (processed > 0 && iterations < maxIterations);

                    if (iterations >= maxIterations) {
                        logger.info("Thread {} reached max iterations limit ({}). Will restart in next cycle.", threadNum, maxIterations);
                    }
                } catch (Exception e) {
                    logger.error("Exception in TreatmentProcessor thread {}: ", threadNum, e);
                }
                logger.info("Thread {} finished.", threadNum);
            }, "TreatmentProcessorThread-" + threadNum);

            // Record thread start time for governor monitoring
            threadStartTimes.put(thread, Instant.now());

            thread.setDaemon(true); // Don't prevent JVM shutdown if stuck
            thread.start();
            activeThreads.add(thread);
        }
    }
}
