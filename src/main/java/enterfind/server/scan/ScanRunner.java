package enterfind.server.scan;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import enterfind.server.config.ApplianceMode;
import enterfind.server.dao.DataSourceDao;
import enterfind.server.dao.Schema;
import enterfind.server.model.DataSourceInterface;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.util.CronScheduleCalculator;

/**
 * Evaluates all datasources with cron patterns configured and triggers scans for those
 * whose next scheduled execution time has arrived.
 *
 * Registered unconditionally (unlike {@link ScanScheduler}, which is disabled in the
 * 'alpha' profile) so this logic can be invoked directly in tests without needing the
 * cron trigger itself to be active.
 */
@Component
public class ScanRunner {
    private static final Logger logger = LoggerFactory.getLogger(ScanRunner.class);

    private final DataSourceDao dataSourceDao;
    private final Scanner scanner;
    private final CronScheduleCalculator cronCalculator;
    private final Schema schema;
    private final ApplianceMode applianceMode;

    @Autowired
    public ScanRunner(DataSourceDao dataSourceDao, Scanner scanner, CronScheduleCalculator cronCalculator, Schema schema, ApplianceMode applianceMode) {
        this.dataSourceDao = dataSourceDao;
        this.scanner = scanner;
        this.cronCalculator = cronCalculator;
        this.schema = schema;
        this.applianceMode = applianceMode;
    }

    /**
     * For each datasource with a cron pattern configured, checks if the next
     * scheduled execution time has arrived and triggers an async scan if so.
     */
    public void runScheduledScans() {
        logger.info("Running scheduled scan check");

        if (applianceMode.isEnabled()) {
            logger.warn("Appliance mode: skipping scheduled datasource scan");
            return;
        }

        // Check if a schema update is in progress and skip scanning if so
        if (schema.isSchemaUpdateInProgress()) {
            logger.info("Skipping scheduled scan: schema update in progress");
            return;
        }

        try {
            LocalDateTime currentTime = LocalDateTime.now();
            List<DataSourceInterface> allDataSources = dataSourceDao.findAll();

            for (DataSourceInterface dataSource : allDataSources) {
                String cronPattern = dataSource.getCronPattern();

                // Skip datasources without a cron pattern
                if (cronPattern == null || cronPattern.trim().isEmpty()) {
                    continue;
                }

                try {
                    // Skip datasources already scanning — dateLastScanned is only updated on completion,
                    // so without this guard the scheduler would keep triggering redundant scans each minute.
                    if (dataSource.getStatus() == DataSourceStatus.SCANNING) {
                        logger.debug("Skipping scan trigger for datasource '{}': already scanning", dataSource.getName());
                        continue;
                    }

                    // Check if this datasource should be scanned now
                    if (cronCalculator.shouldRunNow(cronPattern, dataSource.getDateLastScanned(), currentTime)) {
                        logger.info("Triggering scheduled scan for datasource: {} (cron: {})",
                            dataSource.getName(), cronPattern);
                        scanner.scanAsync(dataSource);
                    }
                } catch (Exception e) {
                    logger.warn("Error checking scan schedule for datasource '{}' with cron pattern '{}': {}",
                        dataSource.getName(), cronPattern, e.getMessage());
                    // Continue processing other datasources
                }
            }
        } catch (Exception e) {
            logger.error("Error in scheduled scan check", e);
            // Don't fail the entire scheduling loop
        }
    }
}
