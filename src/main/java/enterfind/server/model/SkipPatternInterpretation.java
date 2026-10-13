package enterfind.server.model;

/**
 * Represents the result of interpreting a natural language skip pattern expression.
 */
public record SkipPatternInterpretation(
        String skipPattern,
        String naturalLanguageInput
) {
}
