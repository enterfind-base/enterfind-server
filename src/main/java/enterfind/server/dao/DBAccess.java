package enterfind.server.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.sql.DataSource;

import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import enterfind.server.exception.SystemIssueException;


/**
 * Wrapper bean for JdbcTemplate that provides database access throughout the application.
 * This centralizes database access and allows for easier testing and monitoring.
 * Includes automatic query performance evaluation that logs warnings for inefficient queries.
 * All queries have a configured timeout and will cleanly disconnect and throw an exception when exceeded.
 */
@Component
public class DBAccess {

    /**
     * Default query timeout in seconds. Queries exceeding this time will be cancelled
     * and throw a QueryTimeoutException with clean connection cleanup.
     */
    private static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;

    /**
     * No timeout value for administrative operations like schema migrations and maintenance.
     * Setting to 0 means no timeout (unlimited).
     */
    private static final int NO_TIMEOUT = 0;

    private final JdbcTemplate jdbcTemplate;
    private final QueryPerformanceEvaluator performanceEvaluator;

    public DBAccess(JdbcTemplate jdbcTemplate, QueryPerformanceEvaluator performanceEvaluator) {
        this.jdbcTemplate = jdbcTemplate;
        this.performanceEvaluator = performanceEvaluator;

        // Configure query timeout for all queries
        // Spring JDBC automatically handles connection cleanup when timeout occurs
        this.jdbcTemplate.setQueryTimeout(DEFAULT_QUERY_TIMEOUT_SECONDS);
    }

    /**
     * Executes an admin SQL statement on a dedicated connection with no query timeout.
     * Bypasses the shared JdbcTemplate entirely to avoid mutating its global timeout state.
     * Uses a direct DataSource connection so VACUUM and DDL can run outside any transaction.
     * @return the number of rows affected (0 for DDL/maintenance statements)
     */
    private int executeWithoutTimeout(String sql, Object... args) {
        DataSource dataSource = Objects.requireNonNull(jdbcTemplate.getDataSource());
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setQueryTimeout(NO_TIMEOUT);
            for (int i = 0; i < args.length; i++) {
                stmt.setObject(i + 1, args[i]);
            }
            return stmt.execute() ? 0 : stmt.getUpdateCount();
        } catch (SQLException e) {
            throw new SystemIssueException("Admin SQL execution failed", e);
        }
    }

    /**
     * Wraps a database operation with timeout exception handling.
     * Converts Spring's QueryTimeoutException to SystemIssueException.
     */
    private <T> T executeWithTimeout(java.util.function.Supplier<T> operation) {
        try {
            return operation.get();
        } catch (QueryTimeoutException e) {
            throw new SystemIssueException(
                "Query exceeded timeout of " + DEFAULT_QUERY_TIMEOUT_SECONDS + " seconds", e);
        }
    }

    /**
     * Wraps a void database operation with timeout exception handling.
     * Converts Spring's QueryTimeoutException to SystemIssueException.
     */
    private void executeWithTimeout(Runnable operation) {
        try {
            operation.run();
        } catch (QueryTimeoutException e) {
            throw new SystemIssueException(
                "Query exceeded timeout of " + DEFAULT_QUERY_TIMEOUT_SECONDS + " seconds", e);
        }
    }

    /**
     * Execute a SQL statement (DDL or non-query statement).
     * @throws SystemIssueException if execution exceeds timeout
     */
    public void execute(@NonNull String sql) {
        executeWithTimeout(() -> jdbcTemplate.execute(sql));
    }

    /**
     * Execute a SQL statement without any timeout for administrative operations.
     * Use this for schema migrations, VACUUM, ANALYZE, index creation, etc.
     * that are expected to take longer than the default 30-second timeout.
     * For parameterized DML (INSERT, UPDATE, DELETE), pass args to get the row count back.
     *
     * WARNING: Only use this for administrative operations that genuinely need
     * unlimited time. Normal application queries should use execute() / update() with timeout.
     * @return the number of rows affected (0 for DDL/maintenance statements)
     */
    public int executeAdmin(@NonNull String sql, Object... args) {
        return executeWithoutTimeout(sql, args);
    }

    /**
     * Execute an update statement (INSERT, UPDATE, DELETE).
     * @return the number of rows affected
     * @throws SystemIssueException if update exceeds timeout
     */
    public int update(@NonNull String sql, Object... args) {
        return executeWithTimeout(() -> jdbcTemplate.update(sql, args));
    }

    /**
     * Execute a batch update statement.
     * @return an array of the number of rows affected by each statement
     * @throws SystemIssueException if batch update exceeds timeout
     */
    public int[] batchUpdate(@NonNull String sql, @NonNull List<Object[]> batchArgs) {
        return executeWithTimeout(() -> jdbcTemplate.batchUpdate(sql, batchArgs));
    }

    /**
     * Query for a list of objects using a RowMapper.
     * Automatically evaluates query performance on first execution.
     * @throws SystemIssueException if query exceeds timeout
     */
    @NonNull
    public <T> List<T> query(@NonNull String sql, @NonNull RowMapper<T> rowMapper, Object... args) {
        performanceEvaluator.evaluateIfNeeded(sql, args);
        return Objects.requireNonNull(executeWithTimeout(() -> jdbcTemplate.query(sql, rowMapper, args)));
    }

    /**
     * Query for a single object using a RowMapper.
     * Automatically evaluates query performance on first execution.
     * @throws SystemIssueException if query exceeds timeout
     */
    @Nullable
    public <T> T queryForObject(@NonNull String sql, @NonNull RowMapper<T> rowMapper, Object... args) {
        performanceEvaluator.evaluateIfNeeded(sql, args);
        return executeWithTimeout(() -> jdbcTemplate.queryForObject(sql, rowMapper, args));
    }

    /**
     * Query for a single object of a specific type.
     * Automatically evaluates query performance on first execution.
     * @throws SystemIssueException if query exceeds timeout
     */
    @Nullable
    public <T> T queryForObject(@NonNull String sql, @NonNull Class<T> requiredType, Object... args) {
        performanceEvaluator.evaluateIfNeeded(sql, args);
        return executeWithTimeout(() -> jdbcTemplate.queryForObject(sql, requiredType, args));
    }

    /**
     * Query for a list of objects of a specific type.
     * Automatically evaluates query performance on first execution.
     * @throws SystemIssueException if query exceeds timeout
     */
    @NonNull
    public <T> List<T> queryForList(@NonNull String sql, @NonNull Class<T> elementType, Object... args) {
        performanceEvaluator.evaluateIfNeeded(sql, args);
        return Objects.requireNonNull(executeWithTimeout(() -> jdbcTemplate.queryForList(sql, elementType, args)));
    }

    /**
     * Query for a list of maps (for dynamic column access).
     * Automatically evaluates query performance on first execution.
     * @throws SystemIssueException if query exceeds timeout
     */
    @NonNull
    public List<Map<String, Object>> queryForList(@NonNull String sql, Object... args) {
        performanceEvaluator.evaluateIfNeeded(sql, args);
        return Objects.requireNonNull(executeWithTimeout(() -> jdbcTemplate.queryForList(sql, args)));
    }

    /**
     * Query for a single row as a Map.
     * Automatically evaluates query performance on first execution.
     * @throws SystemIssueException if query exceeds timeout
     */
    @NonNull
    public Map<String, Object> queryForMap(@NonNull String sql, Object... args) {
        performanceEvaluator.evaluateIfNeeded(sql, args);
        return Objects.requireNonNull(executeWithTimeout(() -> jdbcTemplate.queryForMap(sql, args)));
    }


    /**
     * Query for a list of objects using a custom PreparedStatementCreator (e.g., for array parameters).
     * Use this for advanced cases like PostgreSQL array queries.
     * Suppresses null type safety warnings for advanced usage.
     * @throws SystemIssueException if query exceeds timeout
     */
    @NonNull
    public <T> List<T> queryWithPreparedStatement(
            PreparedStatementCreator psc,
            RowMapper<T> rowMapper) {
        // No performance evaluation here; caller should ensure this is needed
        return executeWithTimeout(() -> jdbcTemplate.query(psc, rowMapper));
    }

    /**
     * Execute an update using a custom PreparedStatementCreator (e.g., for array parameters).
     * Use this for advanced cases like PostgreSQL array parameters in DELETE/UPDATE statements.
     * @return the number of rows affected
     * @throws SystemIssueException if update exceeds timeout
     */
    public int updateWithPreparedStatement(PreparedStatementCreator psc) {
        // No performance evaluation here; caller should ensure this is needed
        return executeWithTimeout(() -> jdbcTemplate.update(psc));
    }

    /**
     * Get the performance evaluator for testing or manual evaluation.
     */

    public QueryPerformanceEvaluator getPerformanceEvaluator() {
        return performanceEvaluator;
    }
}
