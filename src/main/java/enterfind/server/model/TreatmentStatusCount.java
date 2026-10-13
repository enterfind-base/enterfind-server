package enterfind.server.model;

/**
 * Represents a count of item treatments with a specific status.
 * Used for reporting datasource statistics.
 */
public record TreatmentStatusCount(
    ItemTreatmentStatus status,
    int count
) {}
