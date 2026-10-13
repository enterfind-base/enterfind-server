package enterfind.server.dao;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Evaluates query performance by running EXPLAIN ANALYZE and detecting potential issues.
 * Caches evaluated queries to avoid re-analyzing the same query patterns.
 */
@Component
public class QueryPerformanceEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(QueryPerformanceEvaluator.class);

    // Cache of query patterns that have been evaluated (normalized SQL -> evaluated flag)
    private final Set<String> evaluatedQueries = ConcurrentHashMap.newKeySet();

    // Thresholds for detecting bad plans
    private static final long SEQ_SCAN_ROW_THRESHOLD = 1000; // Warn if seq scan on table with more rows
    private static final double COST_THRESHOLD = 10000.0; // Warn if total cost exceeds this
    private static final long ACTUAL_TIME_THRESHOLD_MS = 100; // Warn if actual execution time exceeds this

    // Patterns to detect issues in EXPLAIN output
    private static final Pattern SEQ_SCAN_PATTERN = Pattern.compile("Seq Scan on (\\w+).*rows=(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COST_PATTERN = Pattern.compile("cost=([\\d.]+)\\.\\.([\\d.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTUAL_TIME_PATTERN = Pattern.compile("actual time=([\\d.]+)\\.\\.([\\d.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROWS_REMOVED_PATTERN = Pattern.compile("Rows Removed by Filter: (\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NESTED_LOOP_PATTERN = Pattern.compile("Nested Loop", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbcTemplate;

    public QueryPerformanceEvaluator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Evaluates a query's execution plan if it hasn't been evaluated before.
     * Logs warnings if the plan indicates potential performance issues.
     * 
     * @param sql The SQL query to evaluate
     * @param args The query parameters
     */
    public void evaluateIfNeeded(String sql, Object... args) {
        String normalizedSql = normalizeQuery(sql);
        
        // Skip if already evaluated
        if (evaluatedQueries.contains(normalizedSql)) {
            return;
        }
        
        // Mark as evaluated to prevent re-evaluation
        evaluatedQueries.add(normalizedSql);
        
        try {
            evaluateQuery(sql, normalizedSql, args);
        } catch (Exception e) {
            // Don't let evaluation failures affect normal query execution
            logger.info("Failed to evaluate query performance: {}", e.getMessage());
        }
    }

    /**
     * Normalizes a query by replacing parameter values with placeholders.
     * This allows us to cache evaluation of the same query pattern with different values.
     */
    private String normalizeQuery(String sql) {
        // Remove extra whitespace and normalize
        String normalized = sql.trim().replaceAll("\\s+", " ");
        // The query already has ? placeholders, so we just normalize whitespace
        return normalized;
    }

    /**
     * Runs EXPLAIN ANALYZE and analyzes the results for potential issues.
     */
    private void evaluateQuery(String sql, String normalizedSql, Object[] args) {
        // Skip DDL and other non-SELECT statements
        String upperSql = sql.trim().toUpperCase();
        if (!upperSql.startsWith("SELECT")) {
            return;
        }
        
        // Build EXPLAIN ANALYZE query
        String explainSql = "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + sql;
        
        List<String> planLines;
        try {
            planLines = jdbcTemplate.queryForList(explainSql, String.class, args);
        } catch (Exception e) {
            // Some queries may not be explainable (e.g., syntax issues with EXPLAIN)
            logger.info("Could not run EXPLAIN on query: {}", e.getMessage());
            return;
        }
        
        String fullPlan = String.join("\n", planLines);
        
        // Analyze the plan for issues
        QueryPlanAnalysis analysis = analyzePlan(fullPlan, sql);
        
        if (analysis.hasIssues()) {
            logPerformanceWarning(sql, fullPlan, analysis);
        }
    }

    /**
     * Analyzes an EXPLAIN output for potential performance issues.
     */
    private QueryPlanAnalysis analyzePlan(String plan, String sql) {
        QueryPlanAnalysis analysis = new QueryPlanAnalysis();
        
        // Check for sequential scans on large tables
        Matcher seqScanMatcher = SEQ_SCAN_PATTERN.matcher(plan);
        while (seqScanMatcher.find()) {
            String tableName = seqScanMatcher.group(1);
            long rows = Long.parseLong(seqScanMatcher.group(2));
            if (rows > SEQ_SCAN_ROW_THRESHOLD) {
                analysis.addIssue(QueryIssue.SEQUENTIAL_SCAN, 
                    String.format("Sequential scan on table '%s' with %d rows", tableName, rows),
                    String.format("Consider adding an index on table '%s' for the columns used in WHERE/JOIN clauses", tableName));
            }
        }
        
        // Check for high cost
        Matcher costMatcher = COST_PATTERN.matcher(plan);
        if (costMatcher.find()) {
            double totalCost = Double.parseDouble(costMatcher.group(2));
            if (totalCost > COST_THRESHOLD) {
                analysis.addIssue(QueryIssue.HIGH_COST,
                    String.format("High query cost: %.2f", totalCost),
                    "Review query structure and consider adding indexes or restructuring the query");
            }
        }
        
        // Check for high actual execution time
        Matcher timeMatcher = ACTUAL_TIME_PATTERN.matcher(plan);
        if (timeMatcher.find()) {
            double actualTime = Double.parseDouble(timeMatcher.group(2));
            if (actualTime > ACTUAL_TIME_THRESHOLD_MS) {
                analysis.addIssue(QueryIssue.SLOW_EXECUTION,
                    String.format("Slow query execution: %.2f ms", actualTime),
                    "Query took longer than " + ACTUAL_TIME_THRESHOLD_MS + "ms. Consider optimizing.");
            }
        }
        
        // Check for high filter ratio (many rows removed by filter)
        Matcher rowsRemovedMatcher = ROWS_REMOVED_PATTERN.matcher(plan);
        while (rowsRemovedMatcher.find()) {
            long rowsRemoved = Long.parseLong(rowsRemovedMatcher.group(1));
            if (rowsRemoved > SEQ_SCAN_ROW_THRESHOLD) {
                analysis.addIssue(QueryIssue.HIGH_FILTER_RATIO,
                    String.format("High number of rows removed by filter: %d", rowsRemoved),
                    "Consider adding an index on the filtered column(s) to reduce rows scanned");
            }
        }
        
        // Check for nested loops without indexes (potential N+1 issue)
        if (NESTED_LOOP_PATTERN.matcher(plan).find() && !plan.contains("Index")) {
            // Only warn if there's a nested loop without any index usage
            Matcher nestedLoopMatcher = NESTED_LOOP_PATTERN.matcher(plan);
            int nestedLoopCount = 0;
            while (nestedLoopMatcher.find()) {
                nestedLoopCount++;
            }
            if (nestedLoopCount > 0 && !plan.contains("Index Scan") && !plan.contains("Index Only Scan")) {
                analysis.addIssue(QueryIssue.NESTED_LOOP_WITHOUT_INDEX,
                    String.format("Found %d nested loop(s) without index usage", nestedLoopCount),
                    "Nested loops without index support can be slow. Consider adding indexes on join columns.");
            }
        }
        
        return analysis;
    }

    /**
     * Logs a performance warning with the analysis results.
     */
    private void logPerformanceWarning(String sql, String plan, QueryPlanAnalysis analysis) {
        StringBuilder message = new StringBuilder();
        message.append("\n========== QUERY PERFORMANCE WARNING ==========\n");
        message.append("Query: ").append(sql.length() > 500 ? sql.substring(0, 500) + "..." : sql).append("\n\n");
        message.append("Issues Found:\n");
        
        for (QueryPlanAnalysis.Issue issue : analysis.getIssues()) {
            message.append("  - [").append(issue.type()).append("] ").append(issue.description()).append("\n");
            message.append("    Suggested Fix: ").append(issue.suggestedFix()).append("\n");
        }
        
        message.append("\nExecution Plan:\n");
        message.append(plan);
        message.append("\n===============================================\n");
        
        logger.warn(message.toString());
    }

    /**
     * Clears the cache of evaluated queries.
     * Useful for testing or when schema changes.
     */
    public void clearCache() {
        evaluatedQueries.clear();
    }

    /**
     * Returns the number of queries that have been evaluated.
     */
    public int getEvaluatedQueryCount() {
        return evaluatedQueries.size();
    }

    /**
     * Checks if a query pattern has been evaluated.
     */
    public boolean isQueryEvaluated(String sql) {
        return evaluatedQueries.contains(normalizeQuery(sql));
    }

    /**
     * Types of query performance issues.
     */
    public enum QueryIssue {
        SEQUENTIAL_SCAN,
        HIGH_COST,
        SLOW_EXECUTION,
        HIGH_FILTER_RATIO,
        NESTED_LOOP_WITHOUT_INDEX,
        MISSING_INDEX
    }

    /**
     * Holds the analysis results for a query plan.
     */
    public static class QueryPlanAnalysis {
        private final java.util.List<Issue> issues = new java.util.ArrayList<>();

        public void addIssue(QueryIssue type, String description, String suggestedFix) {
            issues.add(new Issue(type, description, suggestedFix));
        }

        public boolean hasIssues() {
            return !issues.isEmpty();
        }

        public java.util.List<Issue> getIssues() {
            return issues;
        }

        public record Issue(QueryIssue type, String description, String suggestedFix) {}
    }
}
