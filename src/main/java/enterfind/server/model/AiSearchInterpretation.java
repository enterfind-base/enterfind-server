package enterfind.server.model;

import java.util.List;

/**
 * Result of AI interpretation of a natural language search query.
 * 
 * The searchConditions and searchString fields are designed to be directly
 * compatible with the search() query parameters, allowing seamless integration.
 * 
 * When success=false, the explanation contains the error message and
 * searchConditions will be an empty list.
 */
public record AiSearchInterpretation(
    String naturalLanguageQuery,
    boolean success,
    List<SearchCondition> searchConditions,
    String searchString,
    String explanation,
    Double confidence
) {
    /**
     * Create a successful interpretation result.
     */
    public static AiSearchInterpretation success(
            String naturalLanguageQuery,
            List<SearchCondition> searchConditions,
            String searchString,
            String explanation,
            Double confidence) {
        return new AiSearchInterpretation(
            naturalLanguageQuery, true, searchConditions, searchString, explanation, confidence);
    }
    
    /**
     * Create an error result when interpretation fails.
     */
    public static AiSearchInterpretation error(
            String naturalLanguageQuery,
            String errorExplanation) {
        return new AiSearchInterpretation(
            naturalLanguageQuery, false, List.of(), null, errorExplanation, null);
    }
}
