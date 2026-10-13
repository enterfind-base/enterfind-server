package enterfind.server.util;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;

import enterfind.server.exception.InvalidArgumentException;

/**
 * Utility class for validating cron expressions.
 * Uses the cron-utils library to validate standard Unix 5-field cron format
 * (minute, hour, day, month, day-of-week).
 * 
 * This replaces the custom validation logic with the industry-standard cron-utils library
 * for improved reliability and consistency.
 */
public class CronExpressionValidator {
    
    private static final CronDefinition UNIX_CRON_DEFINITION = 
        CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX);
    
    private static final CronParser PARSER = new CronParser(UNIX_CRON_DEFINITION);
    
    /**
     * Validates a cron expression using the Unix 5-field format.
     * Format: minute hour day month day-of-week
     * 
     * @param cronExpression the cron expression to validate
     * @return true if the expression is valid or null/empty (optional field)
     * @throws InvalidArgumentException if the expression is invalid
     */
    public static boolean isValid(String cronExpression) {
        if (cronExpression == null || cronExpression.trim().isEmpty()) {
            return true; // Cron pattern is optional, null/empty is valid
        }
        
        try {
            PARSER.parse(cronExpression);
            return true;
        } catch (IllegalArgumentException e) {
            throw new InvalidArgumentException(
                String.format("Invalid cron expression '%s': %s", cronExpression, e.getMessage()), e);
        } catch (Exception e) {
            throw new InvalidArgumentException(
                String.format("Error validating cron expression '%s': %s", cronExpression, e.getMessage()), e);
        }
    }
}

