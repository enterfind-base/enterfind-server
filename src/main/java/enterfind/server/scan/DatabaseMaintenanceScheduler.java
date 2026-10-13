package enterfind.server.scan;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import enterfind.server.config.ApplianceMode;
import enterfind.server.dao.DBAccess;
import enterfind.server.dao.Schema;

/**
 * Scheduler for database maintenance tasks.
 * Runs periodic VACUUM ANALYZE to update statistics and clean up dead rows,
 * which helps PostgreSQL query planner make better decisions.
 */
@Configuration
@EnableScheduling
@Profile("!alpha")  // Enable for all profiles except 'alpha'
public class DatabaseMaintenanceScheduler {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseMaintenanceScheduler.class);

    private final DBAccess dbAccess;
    private final Schema schema;
    private final ApplianceMode applianceMode;

    @Autowired
    public DatabaseMaintenanceScheduler(DBAccess dbAccess, Schema schema, ApplianceMode applianceMode) {
        this.dbAccess = dbAccess;
        this.schema = schema;
        this.applianceMode = applianceMode;
    }

    /**
     * Runs VACUUM ANALYZE every 6 hours to:
     * - Update table statistics for query planner optimization
     * - Clean up dead rows from UPDATE/DELETE operations
     * - Reclaim disk space from bloated tables
     * 
     * Only active in non-alpha profiles (alpha profile is for testing).
     * 
     * VACUUM ANALYZE is safe to run while the database is in use - it does not
     * lock tables exclusively like VACUUM FULL would.
     */
    @Scheduled(cron = "0 0 */6 * * *")  // Every 6 hours at minute 0
    public void runVacuumAnalyze() {
        logger.info("Starting scheduled VACUUM ANALYZE");
        
        if (applianceMode.isEnabled()) {
            logger.warn("Appliance mode: skipping scheduled VACUUM ANALYZE");
            return;
        }
        
        // Check if a schema update is in progress, and skip VACUUM ANALYZE if so
        if (schema.isSchemaUpdateInProgress()) {
            logger.info("Skipping scheduled VACUUM ANALYZE: schema update in progress");
            return;
        }
        
        try {
            long startTime = System.currentTimeMillis();
            dbAccess.executeAdmin("VACUUM ANALYZE");
            long duration = System.currentTimeMillis() - startTime;
            
            logger.info("Completed VACUUM ANALYZE in {} ms", duration);
        } catch (Exception e) {
            logger.error("Error running VACUUM ANALYZE: {}", e.getMessage(), e);
            // Don't fail - this is maintenance, not critical
        }
    }

    /**
     * Runs ANALYZE on high-churn tables more frequently (every hour).
     * These tables (ITEM_TREATMENTS, ITEM_TREATMENT_RESULTS) change frequently
     * during scanning and need more up-to-date statistics.
     * 
     * ANALYZE is lightweight and only updates statistics without reclaiming space.
     */
    @Scheduled(cron = "0 30 * * * *")  // Every hour at minute 30
    public void runAnalyzeHighChurnTables() {
        logger.info("Starting scheduled ANALYZE on high-churn tables");
        
        // Check if a schema update is in progress, and skip ANALYZE if so
        if (schema.isSchemaUpdateInProgress()) {
            logger.info("Skipping scheduled ANALYZE: schema update in progress");
            return;
        }
        
        try {
            long startTime = System.currentTimeMillis();
            
            // Analyze tables that change frequently during scanning
            dbAccess.executeAdmin("ANALYZE ITEM_TREATMENTS");
            dbAccess.executeAdmin("ANALYZE ITEM_TREATMENT_RESULTS");
            dbAccess.executeAdmin("ANALYZE ITEMS");
            
            long duration = System.currentTimeMillis() - startTime;
            logger.info("Completed ANALYZE on high-churn tables in {} ms", duration);
        } catch (Exception e) {
            logger.error("Error running ANALYZE: {}", e.getMessage(), e);
            // Don't fail - this is maintenance, not critical
        }
    }
}
