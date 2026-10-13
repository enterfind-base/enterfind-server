package enterfind.server.model.input;

import java.util.List;

import enterfind.server.dao.SortDirection;
import enterfind.server.model.SearchCondition;

/**
 * Input type for creating a saved query.
 * Name is auto-generated from query content.
 */
public record CreateSavedQueryInput(
    String searchString,
    List<SearchCondition> queryConditions,
    SortDirection sortDirection
) {
}
