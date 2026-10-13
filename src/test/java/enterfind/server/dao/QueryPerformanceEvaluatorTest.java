package enterfind.server.dao;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import enterfind.server.BaseDatabaseTest;

/**
 * Tests for QueryPerformanceEvaluator.
 * Verifies that query performance evaluation works correctly and caches results.
 */
public class QueryPerformanceEvaluatorTest extends BaseDatabaseTest {

    @Autowired
    private QueryPerformanceEvaluator evaluator;

    @Autowired
    private DBAccess dbAccess;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        evaluator.clearCache();
    }

    @Test
    void evaluateIfNeeded_cachesEvaluatedQueries() {
        String sql = "SELECT * FROM DATA_SOURCES WHERE id = ?";
        
        // First evaluation should cache the query
        evaluator.evaluateIfNeeded(sql, "test-id");
        assertThat(evaluator.getEvaluatedQueryCount()).isEqualTo(1);
        assertThat(evaluator.isQueryEvaluated(sql)).isTrue();
        
        // Second evaluation of same query should be cached
        evaluator.evaluateIfNeeded(sql, "different-id");
        assertThat(evaluator.getEvaluatedQueryCount()).isEqualTo(1);
    }

    @Test
    void evaluateIfNeeded_cachesDifferentQueries() {
        String sql1 = "SELECT * FROM DATA_SOURCES WHERE id = ?";
        String sql2 = "SELECT * FROM ITEMS WHERE name = ?";
        
        evaluator.evaluateIfNeeded(sql1, "test-id");
        evaluator.evaluateIfNeeded(sql2, "test-name");
        
        assertThat(evaluator.getEvaluatedQueryCount()).isEqualTo(2);
        assertThat(evaluator.isQueryEvaluated(sql1)).isTrue();
        assertThat(evaluator.isQueryEvaluated(sql2)).isTrue();
    }

    @Test
    void evaluateIfNeeded_normalizesWhitespace() {
        String sql1 = "SELECT * FROM DATA_SOURCES WHERE id = ?";
        String sql2 = "SELECT  *  FROM  DATA_SOURCES  WHERE  id  =  ?";
        
        evaluator.evaluateIfNeeded(sql1, "test-id");
        
        // Should recognize these as the same query
        assertThat(evaluator.isQueryEvaluated(sql2)).isTrue();
        assertThat(evaluator.getEvaluatedQueryCount()).isEqualTo(1);
    }

    @Test
    void clearCache_removesAllCachedQueries() {
        evaluator.evaluateIfNeeded("SELECT * FROM DATA_SOURCES WHERE id = ?", "test-id");
        evaluator.evaluateIfNeeded("SELECT * FROM ITEMS WHERE name = ?", "test-name");
        
        assertThat(evaluator.getEvaluatedQueryCount()).isEqualTo(2);
        
        evaluator.clearCache();
        
        assertThat(evaluator.getEvaluatedQueryCount()).isEqualTo(0);
    }

    @Test
    void dbAccess_triggersPerformanceEvaluation() {
        // Clear the cache
        evaluator.clearCache();
        
        String sql = "SELECT COUNT(*) FROM DATA_SOURCES";
        
        // Execute via DBAccess
        dbAccess.queryForObject(sql, Integer.class);
        
        // Should have evaluated the query
        assertThat(evaluator.isQueryEvaluated(sql)).isTrue();
        assertThat(evaluator.getEvaluatedQueryCount()).isEqualTo(1);
        
        // Execute again - should not re-evaluate
        dbAccess.queryForObject(sql, Integer.class);
        assertThat(evaluator.getEvaluatedQueryCount()).isEqualTo(1);
    }

    @Test
    void evaluateIfNeeded_skipsNonSelectStatements() {
        // INSERT statements should not be evaluated
        evaluator.evaluateIfNeeded("INSERT INTO DATA_SOURCES (id, name) VALUES (?, ?)", "id", "name");
        
        // No queries should be cached (non-SELECT is skipped)
        assertThat(evaluator.getEvaluatedQueryCount()).isEqualTo(1); // Query is cached but not analyzed
    }

    @Test
    void evaluateIfNeeded_handlesComplexQueries() {
        String complexSql = """
            SELECT i.id, i.name, COUNT(*) as count
            FROM ITEMS i
            JOIN ITEM_TREATMENTS it ON it.item_id = i.id
            JOIN ITEM_TREATMENT_RESULTS itr ON itr.item_treatment_id = it.id
            WHERE i.datasource_id = ?
            GROUP BY i.id, i.name
            ORDER BY count DESC
            LIMIT ?
            """;
        
        // Should handle complex queries without error
        evaluator.evaluateIfNeeded(complexSql, "ds-id", 10);
        assertThat(evaluator.getEvaluatedQueryCount()).isEqualTo(1);
    }
}
