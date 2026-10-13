package enterfind.server.controller;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import enterfind.server.dao.SavedQueryDao;
import enterfind.server.dao.Schema;
import enterfind.server.dao.SortDirection;
import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.exception.NotFoundException;
import enterfind.server.model.HumanUser;
import enterfind.server.model.SavedQuery;
import enterfind.server.model.SearchCondition;
import enterfind.server.model.input.CreateSavedQueryInput;
import enterfind.server.security.AuthContext;

/**
 * GraphQL controller for saved query operations.
 */
@Controller
public class SavedQueryController {

    private final SavedQueryDao savedQueryDao;
    private final AuthContext authContext;
    private final Schema schema;

    public SavedQueryController(SavedQueryDao savedQueryDao, AuthContext authContext, Schema schema) {
        this.savedQueryDao = savedQueryDao;
        this.authContext = authContext;
        this.schema = schema;
    }

    private void checkSchemaReady() {
        if (schema.isSchemaUpdateInProgress()) {
            throw new IllegalStateException("Schema update in progress. Please try again shortly.");
        }
    }

    // ================= Queries =================

    @QueryMapping
    public List<SavedQuery> savedQueries(@Argument String token) {
        checkSchemaReady();
        authContext.requireReader(token);
        HumanUser user = authContext.validateAndGetUser(token)
            .orElseThrow(() -> new InvalidArgumentException("Invalid token"));
        return savedQueryDao.findByUserId(user.id());
    }

    @QueryMapping
    public SavedQuery savedQuery(@Argument String token, @Argument String id) {
        checkSchemaReady();
        authContext.requireReader(token);
        HumanUser user = authContext.validateAndGetUser(token)
            .orElseThrow(() -> new InvalidArgumentException("Invalid token"));
        SavedQuery savedQuery = savedQueryDao.findById(id)
            .orElseThrow(() -> new NotFoundException("Saved query not found with id: " + id));
        
        // Ensure user can only access their own saved queries
        if (!savedQuery.userId().equals(user.id())) {
            throw new InvalidArgumentException("You don't have permission to access this saved query");
        }
        
        return savedQuery;
    }

    // ================= Mutations =================

    @MutationMapping
    public SavedQuery createSavedQuery(@Argument String token, @Argument CreateSavedQueryInput input) {
        checkSchemaReady();
        authContext.requireReader(token);
        HumanUser user = authContext.validateAndGetUser(token)
            .orElseThrow(() -> new InvalidArgumentException("Invalid token"));
        
        // Validate that query has content (either searchString or conditions)
        boolean hasSearchString = input.searchString() != null && !input.searchString().trim().isEmpty();
        boolean hasConditions = input.queryConditions() != null && !input.queryConditions().isEmpty();
        
        if (!hasSearchString && !hasConditions) {
            throw new InvalidArgumentException("Saved query must have at least a search string or conditions");
        }
        
        // Convert SearchConditionInput to SearchCondition
        List<SearchCondition> queryConditions = input.queryConditions() != null ? 
            input.queryConditions().stream()
                .map(c -> new SearchCondition(c.name(), c.operation(), c.value()))
                .collect(Collectors.toList()) :
            List.of();
        
        return savedQueryDao.create(
            user.id(), 
            input.searchString(), 
            queryConditions, 
            input.sortDirection() != null ? input.sortDirection() : SortDirection.DESC
        );
    }

    @MutationMapping
    public boolean deleteSavedQuery(@Argument String token, @Argument String id) {
        checkSchemaReady();
        authContext.requireReader(token);
        HumanUser user = authContext.validateAndGetUser(token)
            .orElseThrow(() -> new InvalidArgumentException("Invalid token"));
        
        // Delete only if the saved query belongs to the current user
        boolean deleted = savedQueryDao.deleteByIdAndUserId(id, user.id());
        
        if (!deleted) {
            throw new NotFoundException("Saved query not found or you don't have permission to delete it");
        }
        
        return true;
    }
}
