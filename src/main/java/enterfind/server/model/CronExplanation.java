package enterfind.server.model;

/**
 * Represents the natural language explanation of a cron expression.
 */
public record CronExplanation(
        String cronExpression,
        String naturalLanguageExplanation
) {
}
