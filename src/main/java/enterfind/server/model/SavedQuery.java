package enterfind.server.model;

import java.time.LocalDateTime;
import java.util.List;

import enterfind.server.dao.SortDirection;

/**
 * Represents a saved search query for a user.
 * Stores complete search state that can be recalled and reapplied.
 * Name is auto-generated from query content.
 */
public record SavedQuery(
    String id,
    String userId,
    String name,
    String searchString,
    List<SearchCondition> queryConditions,
    SortDirection sortDirection,
    LocalDateTime dateCreated
) {
}
