package enterfind.server.model;

/**
 * Represents the result of interpreting a natural language cron expression.
 */
public record CronInterpretation(
        String cronExpression,
        String naturalLanguageInput
) {
}
