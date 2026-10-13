package enterfind.server.scan;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Cron trigger for automatically running datasource scans.
 * Runs every minute and delegates to {@link ScanRunner} to evaluate cron patterns
 * and trigger scans. Disabled in the 'alpha' profile (local dev / default test profile)
 * so scans don't fire in the background during normal test runs; the underlying
 * {@link ScanRunner} logic remains directly testable regardless of profile.
 */
@Configuration
@EnableScheduling
@Profile("!alpha")  // Enable scheduling for all profiles except 'alpha'
public class ScanScheduler {

    private final ScanRunner scanRunner;

    @Autowired
    public ScanScheduler(ScanRunner scanRunner) {
        this.scanRunner = scanRunner;
    }

    /**
     * Runs every minute to check and execute scheduled scans.
     * Only active in non-alpha profiles (alpha profile disables scheduled execution).
     */
    @Scheduled(cron = "0 * * * * *")  // Every minute at the start of the minute
    public void runScheduledScans() {
        scanRunner.runScheduledScans();
    }
}
