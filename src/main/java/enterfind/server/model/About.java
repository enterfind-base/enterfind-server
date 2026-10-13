package enterfind.server.model;

/**
 * About information model for GraphQL response
 */
public record About(
    String name,
    String version,
    String description,
    String environment,
    String timezone,
    String banner
) {
}