package enterfind.server.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Statistics for a data source including total item count and treatment status counts.
 */
public record DataSourceStats(
    int totalItems,
    List<TreatmentStatusCount> treatmentStatusCounts,
    DataSourceStatus dataSourceStatus,
    LocalDateTime dateLastScanned,
    int previewsToDo,
    int foldersWithProblems
) {}
