package enterfind.server.model;

import java.util.List;

import enterfind.server.dao.SortDirection;
import enterfind.server.scan.Item;

/**
 * Search result model containing search metadata and results.
 * Facets are now fetched separately via the getFacets query for better performance.
 */
public record SearchResult(
    List<SearchCondition> searchConditions,
    String name,
    String folder,
    String searchString,
    int offset,
    int howmany,
    int totalHits,
    SortDirection sortDirection,
    List<Item> items
) {
}
