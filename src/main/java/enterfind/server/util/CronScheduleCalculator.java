package enterfind.server.util;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.springframework.stereotype.Component;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

import enterfind.server.exception.InvalidArgumentException;

/**
 * Utility component for calculating cron schedule execution times.
 * Uses cron-utils library to parse cron expressions and determine
 * the next execution time for a given schedule.
 */
@Component
public class CronScheduleCalculator {
    
    /**
     * Determines if a datasource should be scanned now based on its cron pattern
     * and last execution time. Compares the next scheduled execution time with the current time.
     * If lastScannedTime is null (never scanned), schedules from the beginning.
     * 
     * @param cronPattern the cron expression (standard Unix 5-field format)
     * @param lastScannedTime the last time this datasource was scanned, or null if never scanned
     * @param currentTime the current time to use for calculations
     * @return true if the datasource should be scanned now (next execution is now or in the past), false otherwise
     * @throws InvalidArgumentException if the cron pattern is invalid
     */
    public boolean shouldRunNow(String cronPattern, LocalDateTime lastScannedTime, LocalDateTime currentTime) {
        if (cronPattern == null || cronPattern.trim().isEmpty()) {
            return false;
        }
        
        try {
            LocalDateTime nextExecutionTime = getNextExecutionTime(cronPattern, lastScannedTime, currentTime);
            // Run if next execution time is now or in the past
            return nextExecutionTime != null && !nextExecutionTime.isAfter(currentTime);
        } catch (Exception e) {
            throw new InvalidArgumentException("Invalid cron pattern: " + cronPattern, e);
        }
    }
    
    /**
     * Calculates the next execution time for a given cron pattern.
     * If lastScannedTime is null, calculates from the current time.
     * Returns the next time when this cron pattern should execute.
     * 
     * @param cronPattern the cron expression (standard Unix 5-field format: minute hour day month dayOfWeek)
     * @param lastScannedTime the last time this datasource was scanned, or null if never scanned
     * @param currentTime the current time to use for calculations
     * @return the next LocalDateTime when this cron pattern should execute
     * @throws InvalidArgumentException if the cron pattern is invalid
     */
    public LocalDateTime getNextExecutionTime(String cronPattern, LocalDateTime lastScannedTime, LocalDateTime currentTime) {
        if (cronPattern == null || cronPattern.trim().isEmpty()) {
            return null;
        }
        
        try {
            CronDefinition cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
            CronParser parser = new CronParser(cronDefinition);
            Cron cron = parser.parse(cronPattern);
            ExecutionTime executionTime = ExecutionTime.forCron(cron);
            
            // Use lastScannedTime if available, otherwise use currentTime
            LocalDateTime referenceTime = lastScannedTime != null ? lastScannedTime : currentTime;
            ZonedDateTime referenceZoned = referenceTime.atZone(ZoneId.systemDefault());
            
            // Get the next execution time after the reference time
            java.util.Optional<ZonedDateTime> nextExecution = executionTime.nextExecution(referenceZoned);
            
            if (!nextExecution.isPresent()) {
                return null;
            }
            
            return nextExecution.get().toLocalDateTime();
        } catch (IllegalArgumentException e) {
            throw new InvalidArgumentException("Invalid cron pattern: " + cronPattern, e);
        } catch (Exception e) {
            throw new InvalidArgumentException("Error processing cron pattern: " + cronPattern, e);
        }
    }
    
    /**
     * Validates if a cron pattern is valid.
     * 
     * @param cronPattern the cron expression to validate
     * @return true if the pattern is valid
     * @throws InvalidArgumentException if the pattern is invalid
     */
    public boolean isValidCronPattern(String cronPattern) {
        if (cronPattern == null || cronPattern.trim().isEmpty()) {
            return false;
        }
        
        try {
            CronDefinition cronDefinition = CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
            CronParser parser = new CronParser(cronDefinition);
            parser.parse(cronPattern);
            return true;
        } catch (Exception e) {
            throw new InvalidArgumentException("Invalid cron pattern: " + cronPattern, e);
        }
    }
}
