package enterfind.server.model;

import enterfind.server.dao.SearchOperation;

/**
 * Search condition output model for GraphQL response
 */
public record SearchCondition(
    String name,
    SearchOperation operation,
    String value
) {
}
