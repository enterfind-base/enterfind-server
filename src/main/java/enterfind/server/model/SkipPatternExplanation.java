package enterfind.server.model;

/**
 * Represents the natural language explanation of a skip pattern expression.
 */
public record SkipPatternExplanation(
        String skipPattern,
        String naturalLanguageExplanation
) {
}
