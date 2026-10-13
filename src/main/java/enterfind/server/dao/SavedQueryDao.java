package enterfind.server.dao;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Repository;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import enterfind.server.exception.SystemIssueException;
import enterfind.server.model.SavedQuery;
import enterfind.server.model.SearchCondition;
import enterfind.server.util.Utilities;

/**
 * DAO for saved query operations.
 */
@Repository
public class SavedQueryDao {

    private final DBAccess dbAccess;
    private final ObjectMapper objectMapper;

    public SavedQueryDao(DBAccess dbAccess, ObjectMapper objectMapper) {
        this.dbAccess = dbAccess;
        this.objectMapper = objectMapper;
    }

    /**
     * Create a new saved query for a user.
     * Name is auto-generated from query content.
     */
    public SavedQuery create(String userId, String searchString, List<SearchCondition> queryConditions, SortDirection sortDirection) {
        String id = Utilities.generateHexId();
        LocalDateTime dateCreated = LocalDateTime.now();
        String name = generateQueryName(searchString, queryConditions, sortDirection);

        String sql = """
            INSERT INTO SAVED_QUERIES (id, user_id, name, search_string, query_conditions, sort_direction, date_created)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
            """;

        String conditionsJson;
        try {
            conditionsJson = objectMapper.writeValueAsString(queryConditions);
        } catch (JacksonException e) {
            throw new SystemIssueException("Failed to serialize query conditions: " + e.getMessage());
        }

        dbAccess.update(sql, id, userId, name, searchString, conditionsJson, 
            sortDirection.name(), Timestamp.valueOf(dateCreated));

        return new SavedQuery(id, userId, name, searchString, queryConditions, sortDirection, dateCreated);
    }

    /**
     * Generate a human-readable name from query content.
     */
    private String generateQueryName(String searchString, List<SearchCondition> queryConditions, SortDirection sortDirection) {
        StringBuilder name = new StringBuilder();
        
        // Add search string part
        if (searchString != null && !searchString.trim().isEmpty()) {
            String truncated = searchString.length() > 40 ? searchString.substring(0, 40) + "..." : searchString;
            name.append("Search: ").append(truncated);
        }
        
        // Add conditions part
        if (queryConditions != null && !queryConditions.isEmpty()) {
            if (name.length() > 0) {
                name.append(" + ");
            }
            
            if (queryConditions.size() == 1) {
                SearchCondition c = queryConditions.get(0);
                name.append(formatCondition(c));
            } else if (queryConditions.size() == 2) {
                name.append(formatCondition(queryConditions.get(0)));
                name.append(", ");
                name.append(formatCondition(queryConditions.get(1)));
            } else {
                name.append(queryConditions.size()).append(" filters");
            }
        }
        
        // Add sort direction if not default
        if (sortDirection != SortDirection.DESC) {
            name.append(" [").append(sortDirection.name()).append("]");
        }
        
        // Fallback if empty
        if (name.length() == 0) {
            name.append("Empty query");
        }
        
        // Truncate if too long
        if (name.length() > 200) {
            return name.substring(0, 197) + "...";
        }
        
        return name.toString();
    }

    /**
     * Format a single condition for display.
     */
    private String formatCondition(SearchCondition condition) {
        String value = condition.value();
        if (value.length() > 20) {
            value = value.substring(0, 20) + "...";
        }
        
        return switch (condition.operation()) {
            case EQUAL -> condition.name() + "=" + value;
            case NOT_EQUAL -> condition.name() + "≠" + value;
            case LIKE -> condition.name() + "~" + value.replace("%", "");
            case NOT_LIKE -> condition.name() + "!~" + value.replace("%", "");
            case GREATER -> condition.name() + ">" + value;
            case LESS -> condition.name() + "<" + value;
        };
    }

    /**
     * Find a saved query by ID.
     */
    public Optional<SavedQuery> findById(String id) {
        String sql = """
            SELECT id, user_id, name, search_string, query_conditions::text, sort_direction, date_created
            FROM SAVED_QUERIES
            WHERE id = ?
            """;

        try {
            SavedQuery savedQuery = dbAccess.queryForObject(sql, new SavedQueryRowMapper(), id);
            return Optional.ofNullable(savedQuery);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Find all saved queries for a specific user.
     */
    public List<SavedQuery> findByUserId(String userId) {
        String sql = """
            SELECT id, user_id, name, search_string, query_conditions::text, sort_direction, date_created
            FROM SAVED_QUERIES
            WHERE user_id = ?
            ORDER BY date_created DESC
            """;

        return dbAccess.query(sql, new SavedQueryRowMapper(), userId);
    }

    /**
     * Delete a saved query by ID.
     * Returns true if the query was deleted, false if it didn't exist.
     */
    public boolean delete(String id) {
        String sql = "DELETE FROM SAVED_QUERIES WHERE id = ?";
        int rowsAffected = dbAccess.update(sql, id);
        return rowsAffected > 0;
    }

    /**
     * Delete a saved query by ID, but only if it belongs to the specified user.
     * Returns true if the query was deleted, false if it didn't exist or didn't belong to the user.
     */
    public boolean deleteByIdAndUserId(String id, String userId) {
        String sql = "DELETE FROM SAVED_QUERIES WHERE id = ? AND user_id = ?";
        int rowsAffected = dbAccess.update(sql, id, userId);
        return rowsAffected > 0;
    }

    /**
     * Row mapper for SavedQuery.
     */
    private class SavedQueryRowMapper implements RowMapper<SavedQuery> {
        @Override
        public SavedQuery mapRow(@NonNull ResultSet rs, int rowNum) throws SQLException {
            String id = rs.getString("id");
            String userId = rs.getString("user_id");
            String name = rs.getString("name");
            String searchString = rs.getString("search_string");
            String conditionsJson = rs.getString("query_conditions");
            String sortDirectionStr = rs.getString("sort_direction");
            Timestamp dateCreatedTs = rs.getTimestamp("date_created");
            LocalDateTime dateCreated = dateCreatedTs != null ? dateCreatedTs.toLocalDateTime() : null;

            List<SearchCondition> queryConditions;
            try {
                queryConditions = objectMapper.readValue(conditionsJson, 
                    new TypeReference<List<SearchCondition>>() {});
            } catch (JacksonException e) {
                throw new SystemIssueException("Failed to deserialize query conditions for saved query " + id + ": " + e.getMessage());
            }

            SortDirection sortDirection = SortDirection.valueOf(sortDirectionStr);

            return new SavedQuery(id, userId, name, searchString, queryConditions, sortDirection, dateCreated);
        }
    }
}
