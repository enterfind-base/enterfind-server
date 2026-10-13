package enterfind.server.dao;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.model.FacetValue;
import enterfind.server.model.FieldNameConstants;
import enterfind.server.model.input.SearchConditionInput;
import enterfind.server.scan.Item;

@Component

public class SearchDAO {

    private static final int MAX_RESULTS_LIMIT = 1000;
    private static final int MAX_SEARCH_CONDITIONS = 50;
    private static final int MAX_STRING_LENGTH = 1000;
    /**
     * Shortest pattern pg_trgm can extract a trigram from. Below this the trigram index returns
     * every row in the table and rechecks all of them against the heap — measured at 13 s for a
     * two-character term on the beta corpus — so such a term is not sent to the metadata table.
     */
    private static final int MIN_TRIGRAM_PATTERN_LENGTH = 3;

    private final DBAccess dbAccess;

    public SearchDAO(DBAccess dbAccess) {
        this.dbAccess = dbAccess;
    }


    /**
     * Search for items without specifying sort direction (defaults to DESC - newest first).
     * This is a convenience overload for backward compatibility.
     */
    public List<Item> search(List<SearchConditionInput> searchConditions, String name, String folder, String searchString,
            int skip, int howmany) {
        return search(searchConditions, name, folder, searchString, skip, howmany, null);
    }

    public List<Item> search(List<SearchConditionInput> searchConditions, String name, String folder, String searchString,
            int skip, int howmany, SortDirection sortDirection) {
        validateSearchString(searchString);

        // Validate pagination parameters
        if (skip < 0) {
            throw new InvalidArgumentException("Skip parameter must be non-negative, got: " + skip);
        }
        if (howmany <= 0) {
            throw new InvalidArgumentException("Howmany parameter must be positive, got: " + howmany);
        }
        if (howmany > MAX_RESULTS_LIMIT) {
            throw new InvalidArgumentException(
                    "Howmany parameter exceeds maximum limit of " + MAX_RESULTS_LIMIT + ", got: " + howmany);
        }

        // Validate string parameters
        if (name != null && name.length() > MAX_STRING_LENGTH) {
            throw new InvalidArgumentException(
                    "Name parameter exceeds maximum length of " + MAX_STRING_LENGTH + " characters");
        }
        if (folder != null && folder.length() > MAX_STRING_LENGTH) {
            throw new InvalidArgumentException(
                    "Folder parameter exceeds maximum length of " + MAX_STRING_LENGTH + " characters");
        }
        // Validate search conditions list
        List<SearchConditionInput> conds = (searchConditions == null) ? List.of() : searchConditions;
        if (conds.size() > MAX_SEARCH_CONDITIONS) {
            throw new InvalidArgumentException("Number of search conditions exceeds maximum limit of "
                    + MAX_SEARCH_CONDITIONS + ", got: " + conds.size());
        }

        // Validate each search condition
        for (SearchConditionInput cond : conds) {
            if (cond.getName() == null || cond.getName().isEmpty()) {
                throw new InvalidArgumentException("Search condition name cannot be null or empty");
            }
            if (cond.getName().length() > MAX_STRING_LENGTH) {
                throw new InvalidArgumentException(
                        "Search condition name exceeds maximum length of " + MAX_STRING_LENGTH + " characters");
            }
            if (cond.getValue() == null) {
                throw new InvalidArgumentException("Search condition value cannot be null");
            }
            if (cond.getValue().length() > MAX_STRING_LENGTH) {
                throw new InvalidArgumentException(
                        "Search condition value exceeds maximum length of " + MAX_STRING_LENGTH + " characters");
            }
            if (cond.getOperation() == null) {
                throw new InvalidArgumentException("Search condition operation cannot be null");
            }
        }

        // Build SQL based on whether FTS is needed
        SqlWithParams sqlWithParams;
        if (hasFTSSearch(conds, searchString)) {
            sqlWithParams = buildSearchSqlWithFTS(conds, name, folder, searchString, skip, howmany, sortDirection);
        } else {
            sqlWithParams = buildSearchSqlWithoutFTS(conds, name, folder, skip, howmany, sortDirection);
        }

        // Execute query
        List<Item> results = dbAccess.query(Objects.requireNonNull(sqlWithParams.getSql()), new ItemRowMapper(), sqlWithParams.getParametersArray());
       
        return results;
    }

    /**
     * Build search SQL for queries without FTS optimization.
     */
    private SqlWithParams buildSearchSqlWithoutFTS(List<SearchConditionInput> searchConditions, String name,
            String folder, int skip, int howmany, SortDirection sortDirection) {

        // Build WHERE clause and parameters
        WhereClauseBuilder whereBuilder = buildWhereClause(searchConditions, name, folder);

        // Build the complete SQL query - join with DATA_SOURCES to get datasource name and type
        StringBuilder sql = new StringBuilder(
            "SELECT i.id, i.datasource_id, i.name, i.path, i.time_created, i.time_modified, i.size_bytes, i.content_hash, i.mime_type, i.date_scanned, "
                + "ds.name as datasource_name, ds.type as datasource_type "
                + " FROM ITEMS i "
                + " JOIN DATA_SOURCES ds ON ds.id = i.datasource_id ");
        sql.append(whereBuilder.getWhereClause());

        // Order by time_modified with specified direction (default to DESC for newest first)
        SortDirection direction = (sortDirection == null) ? SortDirection.DESC : sortDirection;
        // i.id breaks ties: time_modified has second-level granularity on many sources, so items
        // scanned together share it and an unbroken ORDER BY leaves their relative order up to the
        // plan — which makes pagination drop and repeat rows across pages.
        sql.append(" ORDER BY i.time_modified " + direction.name() + ", i.id " + direction.name());
        sql.append(" OFFSET ? LIMIT ?");

        // Add pagination parameters
        List<Object> params = new ArrayList<>(whereBuilder.getParameters());
        params.add(skip);
        params.add(howmany);

        return new SqlWithParams(sql.toString(), params);
    }

    /**
     * Build search SQL with FTS (full-text search) optimization.
     * Uses CTE to force FTS index usage first, preventing PostgreSQL from choosing
     * idx_items_time_modified and scanning items chronologically.
     *
     * FTS is used when:
     * - searchString parameter is provided, OR
     * - Any search condition uses LIKE operation (all LIKE operations use FTS for performance)
     *
     * Without this optimization, queries with selective FTS terms can scan hundreds of
     * thousands of items in time_modified order before finding LIMIT matches, taking
     * 60-100+ seconds. With CTE, these complete in under 1 second.
     */
    private SqlWithParams buildSearchSqlWithFTS(List<SearchConditionInput> searchConditions, String name,
            String folder, String searchString, int skip, int howmany, SortDirection sortDirection) {

        // Separate FTS and non-FTS conditions
        FTSConditionSeparator separator = separateFTSConditions(searchConditions, searchString);
        List<Object> params = new ArrayList<>(separator.getParameters());

        // Build CTE with all FTS conditions combined
        StringBuilder sql = new StringBuilder("WITH fts_matches AS (");
        sql.append(String.join(" INTERSECT ", separator.getFtsSubqueries()));
        sql.append(") ");

        // Build main query joining with FTS matches
        sql.append(
            "SELECT i.id, i.datasource_id, i.name, i.path, i.time_created, i.time_modified, " +
            "i.size_bytes, i.content_hash, i.mime_type, i.date_scanned, " +
            "ds.name as datasource_name, ds.type as datasource_type " +
            "FROM ITEMS i " +
            "JOIN DATA_SOURCES ds ON ds.id = i.datasource_id " +
            "JOIN fts_matches fm ON fm.item_id = i.id ");

        // Add additional WHERE conditions (name, folder, non-FTS searchConditions)
        List<String> whereClauses = buildAdditionalWhereClauses(name, folder, separator.getNonFTSConditions(), params);
        if (!whereClauses.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereClauses));
        }

        // Order by time_modified with specified direction (default to DESC for newest first)
        SortDirection direction = (sortDirection == null) ? SortDirection.DESC : sortDirection;
        sql.append(" ORDER BY i.time_modified ").append(direction.name()).append(", i.id ").append(direction.name());
        sql.append(" OFFSET ? LIMIT ?");

        params.add(skip);
        params.add(howmany);

        return new SqlWithParams(sql.toString(), params);
    }

    public int countSearchResults(List<SearchConditionInput> searchConditions, String name, String folder, String searchString) {
        validateSearchString(searchString);

        List<SearchConditionInput> conds = (searchConditions == null) ? List.of() : searchConditions;

        // Build SQL based on whether FTS is needed
        SqlWithParams sqlWithParams;
        if (hasFTSSearch(conds, searchString)) {
            sqlWithParams = buildCountSqlWithFTS(conds, name, folder, searchString);
        } else {
            sqlWithParams = buildCountSqlWithoutFTS(conds, name, folder);
        }

        // Execute query (ensure SQL string is non-null for null-safety)
        String sql = Objects.requireNonNull(sqlWithParams.getSql());
        Integer count = dbAccess.queryForObject(sql, Integer.class, sqlWithParams.getParametersArray());
        return (count != null) ? count : 0;
    }

    /**
     * Build count SQL for queries without FTS optimization.
     */
    private SqlWithParams buildCountSqlWithoutFTS(List<SearchConditionInput> searchConditions, String name, String folder) {
        // Build WHERE clause and parameters using shared logic
        WhereClauseBuilder whereBuilder = buildWhereClause(searchConditions, name, folder);

        // Build the count query
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM ITEMS i ");
        sql.append(whereBuilder.getWhereClause());

        return new SqlWithParams(sql.toString(), whereBuilder.getParameters());
    }

    /**
     * Build count SQL with FTS optimization.
     */
    private SqlWithParams buildCountSqlWithFTS(List<SearchConditionInput> searchConditions, String name,
            String folder, String searchString) {

        // Separate FTS and non-FTS conditions
        FTSConditionSeparator separator = separateFTSConditions(searchConditions, searchString);
        List<Object> params = new ArrayList<>(separator.getParameters());

        // Build CTE with all FTS conditions
        StringBuilder sql = new StringBuilder("WITH fts_matches AS (");
        sql.append(String.join(" INTERSECT ", separator.getFtsSubqueries()));
        sql.append(") ");

        // Build count query
        sql.append("SELECT COUNT(*) FROM ITEMS i JOIN fts_matches fm ON fm.item_id = i.id ");

        // Add additional WHERE conditions
        List<String> whereClauses = buildAdditionalWhereClauses(name, folder, separator.getNonFTSConditions(), params);
        if (!whereClauses.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", whereClauses));
        }

        return new SqlWithParams(sql.toString(), params);
    }

    /**
     * Get facet values for a single key, optionally filtered by search criteria.
     * This is more efficient than extracting all facets at once because:
     * 1. We only aggregate one key at a time (simpler GROUP BY)
     * 2. We can handle high-cardinality fields without performance issues
     * 3. User only loads facet values they want to see
     * 
     * @param key The attribute key to get facet values for
     * @param searchConditions Optional search conditions to filter items
     * @param name Optional name filter
     * @param folder Optional folder filter  
     * @param searchString Optional full-text search string
     * @return List of FacetValue objects with value and count, sorted by count descending
     */
    public List<FacetValue> getFacetValues(String key, List<SearchConditionInput> searchConditions,
                                           String name, String folder, String searchString) {
        validateSearchString(searchString);

        if (key == null || key.isBlank()) {
            throw new InvalidArgumentException("Key parameter is required for getFacets");
        }

        // Normalize key to lowercase for consistent matching
        String normalizedKey = key.toLowerCase();

        List<SearchConditionInput> conds = (searchConditions == null) ? List.of() : searchConditions;

        // Determine which table to query based on field type
        boolean isContentField = isKnownLargeField(normalizedKey);
        String tableName = isContentField ? "ITEM_TREATMENT_RESULTS_CONTENT" : "ITEM_TREATMENT_RESULTS";

        // Check if any filters are present
        boolean hasFilters = !conds.isEmpty()
                          || (name != null && !name.isEmpty())
                          || (folder != null && !folder.isEmpty())
                          || contributesFTSSubquery(searchString);

        // Optimization: If no filters, use simple direct query (13x faster).
        // The length predicate matches idx_item_treatment_results_key_value, enabling Index Only Scan.
        // Values over 2000 bytes are pathological metadata (XML fragments, ICC profiles, etc.)
        // and are not meaningful as categorical facets.
        if (!hasFilters) {
            String sql = "SELECT value, COUNT(*) as count FROM " + tableName +
                         " WHERE key = ? AND length(value) <= 2000 GROUP BY value ORDER BY count DESC LIMIT 100";
            List<Map<String, Object>> rows = dbAccess.queryForList(sql, normalizedKey);
            return rows.stream()
                    .map(row -> new FacetValue((String) row.get("value"),
                                               ((Number) row.get("count")).intValue()))
                    .collect(Collectors.toList());
        }

        // Build query with appropriate path
        String sql;
        List<Object> params;

        if (hasFTSSearch(conds, searchString)) {
            // Use FTS path with combined CTE
            FTSConditionSeparator separator = separateFTSConditions(conds, searchString);
            params = new ArrayList<>(separator.getParameters());

            // Build combined CTE: fts_matches first, then matched_items using it
            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("WITH fts_matches AS (");
            sqlBuilder.append(String.join(" INTERSECT ", separator.getFtsSubqueries()));
            sqlBuilder.append("), matched_items AS (");
            sqlBuilder.append("SELECT i.id FROM ITEMS i JOIN fts_matches fm ON fm.item_id = i.id");

            // Add non-FTS conditions
            List<String> additionalWhere = buildAdditionalWhereClauses(name, folder, separator.getNonFTSConditions(), params);
            if (!additionalWhere.isEmpty()) {
                sqlBuilder.append(" WHERE ").append(String.join(" AND ", additionalWhere));
            }

            sqlBuilder.append(") ");
            sqlBuilder.append("SELECT itr.value, COUNT(DISTINCT mi.id) as count ");
            sqlBuilder.append("FROM matched_items mi ");
            sqlBuilder.append("JOIN ITEM_TREATMENTS it ON it.item_id = mi.id ");
            sqlBuilder.append("JOIN ").append(tableName).append(" itr ON itr.item_treatment_id = it.id ");
            sqlBuilder.append("WHERE itr.key = ? ");
            sqlBuilder.append("GROUP BY itr.value ");
            sqlBuilder.append("ORDER BY count DESC ");
            sqlBuilder.append("LIMIT 100");

            sql = sqlBuilder.toString();
        } else {
            // Use non-FTS path
            WhereClauseBuilder whereBuilder = buildWhereClause(conds, name, folder);
            params = new ArrayList<>(whereBuilder.getParameters());

            // Simple CTE for non-FTS
            sql = "WITH matched_items AS (" +
                    "  SELECT i.id FROM ITEMS i " +
                    whereBuilder.getWhereClause() +
                    ") " +
                    "SELECT itr.value, COUNT(DISTINCT mi.id) as count " +
                    "FROM matched_items mi " +
                    "JOIN ITEM_TREATMENTS it ON it.item_id = mi.id " +
                    "JOIN " + tableName + " itr ON itr.item_treatment_id = it.id " +
                    "WHERE itr.key = ? " +
                    "GROUP BY itr.value " +
                    "ORDER BY count DESC " +
                    "LIMIT 100";
        }

        params.add(normalizedKey);

        String sqlString = Objects.requireNonNull(sql);
        List<Map<String, Object>> rows = dbAccess.queryForList(sqlString, params.toArray());

        return rows.stream()
                .map(row -> {
                    String value = (String) row.get("value");
                    Integer count = ((Number) row.get("count")).intValue();
                    return new FacetValue(value, count);
                })
                .collect(Collectors.toList());
    }


    /**
     * Helper class to hold SQL query and parameters
     */
    private static class SqlWithParams {
        private final String sql;
        private final List<Object> parameters;

        public SqlWithParams(String sql, List<Object> parameters) {
            this.sql = sql;
            this.parameters = parameters;
        }

        public String getSql() {
            return sql;
        }

        public Object[] getParametersArray() {
            return parameters.toArray();
        }
    }

    /**
     * Helper class to hold WHERE clause and parameters
     */
    private static class WhereClauseBuilder {
        private final String whereClause;
        private final List<Object> parameters;

        public WhereClauseBuilder(String whereClause, List<Object> parameters) {
            this.whereClause = whereClause;
            this.parameters = parameters;
        }

        public String getWhereClause() {
            return whereClause;
        }

        public List<Object> getParameters() {
            return parameters;
        }
    }

    /**
     * Helper class to hold FTS and non-FTS conditions separately
     */
    private static class FTSConditionSeparator {
        private final List<String> ftsSubqueries = new ArrayList<>();
        private final List<SearchConditionInput> nonFTSConditions = new ArrayList<>();
        private final List<Object> parameters = new ArrayList<>();

        public List<String> getFtsSubqueries() {
            return ftsSubqueries;
        }

        public List<SearchConditionInput> getNonFTSConditions() {
            return nonFTSConditions;
        }

        public List<Object> getParameters() {
            return parameters;
        }
    }

    /**
     * Check if any search uses FTS (full-text search).
     * FTS is needed when searchString is provided OR any condition uses LIKE operation.
     * Note: NOT_LIKE alone doesn't trigger FTS path because it can't populate the CTE.
     */
    private boolean hasFTSSearch(List<SearchConditionInput> searchConditions, String searchString) {
        if (contributesFTSSubquery(searchString)) {
            return true;
        }
        for (SearchConditionInput cond : searchConditions) {
            if (cond.getOperation() == SearchOperation.LIKE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Separate search conditions into FTS and non-FTS conditions, building search subqueries.
     * Routes LIKE operations to appropriate table:
     * - Metadata fields use ILIKE with trigram index on ITEM_TREATMENT_RESULTS
     * - Content fields use FTS on ITEM_TREATMENT_RESULTS_CONTENT
     * Returns separator containing subqueries, non-FTS conditions, and parameters.
     */
    private FTSConditionSeparator separateFTSConditions(List<SearchConditionInput> searchConditions, String searchString) {
        FTSConditionSeparator separator = new FTSConditionSeparator();
        List<SearchConditionInput> conds = (searchConditions == null) ? List.of() : searchConditions;

        // Add searchString: one subquery per term, each spanning BOTH tables via UNION, with the
        // caller's INTERSECT ANDing across terms. A term may therefore be satisfied from metadata
        // while its neighbour is satisfied from content — "invoice" in a filename and "2024" in the
        // document text is a match. Folding the whole string into one query, as this used to, made
        // each table satisfy the query alone.
        for (SearchTerm term : parseSearchTerms(searchString)) {
            List<String> halves = new ArrayList<>();

            // Metadata half. Skipped for a pattern too short to yield a trigram: pg_trgm extracts
            // none from 1-2 characters, and the planner still picks the index, which then returns
            // every row in the table for a full heap recheck. Such a term matches document text
            // only; this is stated in the schema.
            if (term.text().length() >= MIN_TRIGRAM_PATTERN_LENGTH) {
                halves.add(
                    "SELECT DISTINCT it.item_id " +
                    "FROM ITEM_TREATMENTS it " +
                    "JOIN ITEM_TREATMENT_RESULTS itr ON itr.item_treatment_id = it.id " +
                    "WHERE itr.value ILIKE ?");
                separator.parameters.add("%" + term.text() + "%");
            }

            // Content half. A quoted term is a phrase, built with phraseto_tsquery rather than a
            // hand-assembled <-> string: it takes plain text and swallows tsquery operators, so it
            // needs no validation of its own. An unquoted term is prefix-matched.
            halves.add(
                "SELECT DISTINCT it.item_id " +
                "FROM ITEM_TREATMENTS it " +
                "JOIN ITEM_TREATMENT_RESULTS_CONTENT itr ON itr.item_treatment_id = it.id " +
                "WHERE itr.value_tsv @@ " +
                (term.phrase() ? "phraseto_tsquery('simple', ?)" : "to_tsquery('simple', ?)"));
            separator.parameters.add(term.phrase() ? term.text() : prefixTsQuery(term.text()));

            // Parenthesised because INTERSECT binds tighter than UNION: joining bare "A UNION B"
            // subqueries with INTERSECT would parse as "A UNION (B INTERSECT C)".
            separator.ftsSubqueries.add("(" + String.join(" UNION ", halves) + ")");
        }

        // Separate LIKE and non-LIKE conditions
        for (SearchConditionInput cond : conds) {
            if (cond.getOperation() == SearchOperation.LIKE) {
                boolean isContentField = isKnownLargeField(cond.getName());
                String table = isContentField
                    ? "ITEM_TREATMENT_RESULTS_CONTENT" : "ITEM_TREATMENT_RESULTS";

                // The key parameter precedes the value parameters in the emitted SQL, so it is
                // added first.
                separator.parameters.add(cond.getName());
                String valuePredicate = buildConditionValuePredicate(
                        cond.getValue(), isContentField, separator.parameters);

                separator.ftsSubqueries.add(
                    "SELECT DISTINCT it.item_id " +
                    "FROM ITEM_TREATMENTS it " +
                    "JOIN " + table + " itr ON itr.item_treatment_id = it.id " +
                    "WHERE itr.key = ? AND " + valuePredicate);
            } else {
                // Non-LIKE condition (EQUAL, NOT_EQUAL, LESS, GREATER, NOT_LIKE)
                // NOT_LIKE is handled in WHERE clause with NOT EXISTS, not in CTE
                separator.nonFTSConditions.add(cond);
            }
        }

        return separator;
    }

    /**
     * Determines if a field is classified as a large content field that should be queried from the content table.
     * Must match the whitelist logic in TreatmentDao.
     *
     * Whitelist includes:
     * - content (main extracted content)
     * - content-N (numbered content chunks: content-0, content-1, etc.)
     * - ocr-content* (all OCR chunks)
     * - icc_red_trc, icc_green_trc, icc_blue_trc (ICC color curves)
     *
     * NOT in whitelist (metadata fields):
     * - content-type, content-encoding, content-language, content-type-hint
     *
     * @param key the field name to classify
     * @return true if field is in content table, false for metadata table
     */
    private boolean isKnownLargeField(String key) {
        if (key == null) {
            return false;
        }
        String lowerKey = key.toLowerCase();
        // Exact match for 'content' or numbered chunks like 'content-0', 'content-1', etc.
        // Must NOT match metadata fields: content-type, content-encoding, content-language, content-type-hint
        if (lowerKey.equals("content") || lowerKey.matches("^content-[0-9]+$")) {
            return true;
        }
        return lowerKey.startsWith("ocr-content") ||
               lowerKey.equals("icc_red_trc") ||
               lowerKey.equals("icc_green_trc") ||
               lowerKey.equals("icc_blue_trc");
    }

    /**
     * Whether a searchString yields an FTS subquery at all.
     *
     * <p>This must agree exactly with the guard in {@link #separateFTSConditions}: if
     * {@link #hasFTSSearch} sends a query down the FTS path but no subquery is produced, the CTE
     * is built as {@code WITH fts_matches AS ( )}, which is not valid SQL. A whitespace-only or
     * {@code ""} searchString is non-empty yet contributes no terms, which is why testing
     * {@code isEmpty()} here is not enough.
     */
    private boolean contributesFTSSubquery(String searchString) {
        return !parseSearchTerms(searchString).isEmpty();
    }

    /**
     * One term of a searchString: the text to match, and whether the caller quoted it.
     *
     * <p>{@code text} is already stripped of its wrapping quotes and of any {@code %}, and is never
     * empty — {@link #parseSearchTerms} drops terms that reduce to nothing.
     */
    private record SearchTerm(String text, boolean phrase) {}

    /**
     * Split a searchString into the terms that will each become one subquery.
     *
     * <p>Splitting is on whitespace only, never punctuation: metadata values are exactly where
     * punctuation-bearing tokens live (mime types, slash-separated paths, hyphenated filenames),
     * and splitting {@code report-2024} into two terms would collapse precision on the fields this
     * is meant to serve.
     *
     * <p>A term that reduces to nothing is dropped rather than emitted. An empty quoted phrase, or
     * a term of {@code %} — which {@link #validateSearchString} does not block — would otherwise
     * build {@code ILIKE '%%'}, satisfied by every row carrying any value.
     */
    private List<SearchTerm> parseSearchTerms(String searchString) {
        if (searchString == null || searchString.isBlank()) {
            return List.of();
        }

        List<SearchTerm> terms = new ArrayList<>();
        for (String part : parseSearchString(searchString)) {
            // parseSearchString returns quoted terms with their quote characters still attached.
            // Stripping them is what lets a quoted phrase match metadata at all: the characters
            // are inert on every tsquery path but match literally on every ILIKE path.
            boolean phrase = part.length() >= 2 && part.startsWith("\"") && part.endsWith("\"");
            String text = phrase ? part.substring(1, part.length() - 1) : part;
            text = text.replace("%", "").trim();
            if (!text.isEmpty()) {
                terms.add(new SearchTerm(text, phrase));
            }
        }
        return terms;
    }

    /**
     * Build the value predicate for a {@code LIKE} or {@code NOT_LIKE} condition.
     *
     * <p>The value is parsed by the same parser as {@code searchString} — unquoted terms match
     * words, quoted terms match phrases — and all of them are ANDed <em>within the same row</em>,
     * because a condition names one key. That row scope is the only difference between the two
     * entry points: {@code name LIKE annual report} means both words in one {@code name} value,
     * not one word in {@code name} and the other in {@code path}.
     *
     * <p>Appends the value parameters to {@code params} in emission order.
     *
     * <p>The short-term guard that {@code searchString} needs does <em>not</em> apply here, and
     * must not be added: a condition always carries {@code key = ?} and ANDs its words within one
     * row, which gives the planner a usable path even when no word yields a trigram.
     */
    private String buildConditionValuePredicate(String value, boolean isContentField,
            List<Object> params) {
        List<SearchTerm> terms = parseSearchTerms(value);

        if (terms.isEmpty()) {
            // A value that reduces to nothing keeps the meaning it has always had rather than
            // acquiring a new one: ILIKE '%%' is satisfied by any row carrying the key, an empty
            // tsquery by none. Conditions of "%" are already stored and rely on the former.
            if (isContentField) {
                params.add("");
                return "itr.value_tsv @@ plainto_tsquery('simple', ?)";
            }
            params.add("%%");
            return "itr.value ILIKE ?";
        }

        if (isContentField) {
            // One index operation, composed with the tsquery && operator rather than several @@
            // tests. plainto_tsquery(a) && plainto_tsquery(b) is byte-for-byte
            // plainto_tsquery("a b"), so an unquoted content value emits exactly what it emitted
            // before this step — "content unquoted is unchanged" is exact, not approximate.
            List<String> factors = new ArrayList<>();
            for (SearchTerm term : terms) {
                factors.add(term.phrase()
                    ? "phraseto_tsquery('simple', ?)" : "plainto_tsquery('simple', ?)");
                params.add(term.text());
            }
            return "itr.value_tsv @@ (" + String.join(" && ", factors) + ")";
        }

        List<String> predicates = new ArrayList<>();
        for (SearchTerm term : terms) {
            predicates.add("itr.value ILIKE ?");
            params.add("%" + term.text() + "%");
        }
        return "(" + String.join(" AND ", predicates) + ")";
    }

    /**
     * Render an unquoted term as a prefix tsquery, so {@code distribut} matches "distributed",
     * "distribution" and so on. The {@code :*} suffix is never present in user input — the
     * validator blocks {@code :} and {@code *} — but guard against it defensively.
     */
    private String prefixTsQuery(String word) {
        return word.endsWith(":*") ? word : word + ":*";
    }

    /**
     * Parse the search string into tokens, respecting double quotes.
     */
    private List<String> parseSearchString(String searchString) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuote = false;

        for (char c : searchString.toCharArray()) {
            if (c == '"') {
                if (inQuote) {
                    // End of quoted string
                    current.append(c);
                    parts.add(current.toString());
                    current.setLength(0);
                    inQuote = false;
                } else {
                    // Start of quoted string
                    if (current.length() > 0) {
                        parts.add(current.toString());
                        current.setLength(0);
                    }
                    current.append(c);
                    inQuote = true;
                }
            } else if (Character.isWhitespace(c) && !inQuote) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            parts.add(current.toString());
        }

        return parts;
    }

    /**
     * Build WHERE clauses and parameters for name, folder, and non-FTS search conditions.
     * Appends parameters to the provided params list and returns list of WHERE clause strings.
     */
    private List<String> buildAdditionalWhereClauses(String name, String folder,
            List<SearchConditionInput> nonFTSConditions, List<Object> params) {

        List<String> whereClauses = new ArrayList<>();

        // Add name filter
        if (name != null && !name.isEmpty()) {
            whereClauses.add("i.name ILIKE ?");
            params.add("%" + name + "%");
        }

        // Add folder filter
        if (folder != null && !folder.isEmpty()) {
            whereClauses.add("i.path ILIKE ?");
            params.add("%" + folder + "%");
        }

        // Add non-LIKE search conditions as EXISTS subqueries
        // Route to appropriate table based on field type
        for (SearchConditionInput cond : nonFTSConditions) {
            boolean isContentField = isKnownLargeField(cond.getName());
            String tableName = isContentField ? "ITEM_TREATMENT_RESULTS_CONTENT" : "ITEM_TREATMENT_RESULTS";

            // For NOT_EQUAL and NOT_LIKE, use NOT EXISTS with positive condition
            boolean isNegation = cond.getOperation() == SearchOperation.NOT_EQUAL ||
                                 cond.getOperation() == SearchOperation.NOT_LIKE;

            StringBuilder existsClause = new StringBuilder();
            if (isNegation) {
                existsClause.append("NOT EXISTS (");
            } else {
                existsClause.append("EXISTS (");
            }
            existsClause.append("SELECT 1 FROM ITEM_TREATMENTS it JOIN ");
            existsClause.append(tableName);
            existsClause.append(" itr ON itr.item_treatment_id = it.id WHERE it.item_id = i.id");
            existsClause.append(" AND itr.key = ?");
            params.add(cond.getName());

            switch (cond.getOperation()) {
                case LESS:
                    existsClause.append(" AND itr.value < ?");
                    params.add(cond.getValue());
                    break;
                case GREATER:
                    existsClause.append(" AND itr.value > ?");
                    params.add(cond.getValue());
                    break;
                case EQUAL:
                    existsClause.append(" AND itr.value = ?");
                    params.add(cond.getValue());
                    break;
                case NOT_EQUAL:
                    // Use NOT EXISTS with equality check (inverted at EXISTS level)
                    existsClause.append(" AND itr.value = ?");
                    params.add(cond.getValue());
                    break;
                case LIKE:
                    // LIKE operations are handled in separateFTSConditions, not here
                    throw new IllegalStateException("LIKE operations should be handled in FTS subqueries");
                case NOT_LIKE:
                    // The exact negation of LIKE: the same value predicate, inside a NOT EXISTS.
                    // Narrowing the predicate widens what satisfies the negation, so a multi-word
                    // metadata NOT_LIKE moves opposite to its LIKE counterpart.
                    existsClause.append(" AND ").append(
                        buildConditionValuePredicate(cond.getValue(), isContentField, params));
                    break;
            }
            existsClause.append(")");
            whereClauses.add(existsClause.toString());
        }

        return whereClauses;
    }

    /**
     * Validate the user-facing search syntax before passing it to PostgreSQL's tsquery parser.
     * Search strings support words and quoted phrases, but not tsquery operators.
     */
    private void validateSearchString(String searchString) {
        if (searchString == null) {
            return;
        }
        if (searchString.length() > MAX_STRING_LENGTH) {
            throw new InvalidArgumentException(
                    "SearchString parameter exceeds maximum length of " + MAX_STRING_LENGTH + " characters");
        }

        boolean inQuote = false;
        for (char c : searchString.toCharArray()) {
            if (c == '"') {
                inQuote = !inQuote;
            } else if ("()&|!:*<>'\\".indexOf(c) >= 0) {
                throw new InvalidArgumentException(
                        "SearchString contains unsupported full-text search syntax character '" + c
                                + "'. Use words or quoted phrases.");
            }
        }
        if (inQuote) {
            throw new InvalidArgumentException(
                    "SearchString contains an unmatched double quote. Quote phrases with a pair of double quotes.");
        }
    }

    /**
     * Builds WHERE clause and parameters for search criteria, for the non-FTS path.
     *
     * <p>Only reachable when {@link #hasFTSSearch} returned false, so no condition here can be
     * {@code LIKE} and no {@code searchString} is present — both of those force the FTS path. The
     * clause building itself is shared with the FTS path via
     * {@link #buildAdditionalWhereClauses}, which is the only copy of the per-condition SQL.
     */
    private WhereClauseBuilder buildWhereClause(List<SearchConditionInput> searchConditions, String name, String folder) {
        List<SearchConditionInput> conds = (searchConditions == null) ? List.of() : searchConditions;
        List<Object> params = new ArrayList<>();
        List<String> whereClauses = buildAdditionalWhereClauses(name, folder, conds, params);

        String whereClauseStr = whereClauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", whereClauses);
        return new WhereClauseBuilder(whereClauseStr, params);
    }

    private static class ItemRowMapper implements RowMapper<Item> {
        @Override
        public Item mapRow(java.sql.@org.jspecify.annotations.NonNull ResultSet rs, int rowNum)
                throws java.sql.SQLException {
            Item item = new Item();
            item.setId(rs.getString("id"));
            item.setDatasourceId(rs.getString("datasource_id"));
            item.setName(rs.getString("name"));
            item.setPath(rs.getString("path"));
            java.sql.Timestamp timeCreated = rs.getTimestamp("time_created");
            if (timeCreated != null) {
                item.setTimeCreated(timeCreated.toLocalDateTime());
            }
            java.sql.Timestamp timeModified = rs.getTimestamp("time_modified");
            if (timeModified != null) {
                item.setTimeModified(timeModified.toLocalDateTime());
            }
            item.setSizeBytes(rs.getLong("size_bytes"));
            if (rs.wasNull()) {
                item.setSizeBytes(null);
            }
            item.setContentHash(rs.getString("content_hash"));
            item.setMimeType(rs.getString("mime_type"));
            java.sql.Timestamp dateScanned = rs.getTimestamp("date_scanned");
            if (dateScanned != null) {
                item.setDateScanned(dateScanned.toLocalDateTime());
            }
            // Datasource info from join
            try {
                item.setDataSourceName(rs.getString("datasource_name"));
                item.setDataSourceType(rs.getString("datasource_type"));
            } catch (SQLException e) {
                // Columns may not be present in all queries
            }
            return item;
        }
    }

    /**
     * Get all distinct keys from both ITEM_TREATMENT_RESULTS and ITEM_TREATMENT_RESULTS_CONTENT tables.
     * This is used to populate the condition field dropdown on the search page.
     * @return List of distinct key names sorted with known fields first, then alphabetically
     */
    public List<String> getAllItemAttributeKeys() {
        String sql = """
                SELECT DISTINCT key FROM ITEM_TREATMENT_RESULTS
                UNION
                SELECT DISTINCT key FROM ITEM_TREATMENT_RESULTS_CONTENT
                ORDER BY key
                """;

        List<String> keys = dbAccess.queryForList(sql, String.class);

        return sortKeysByPriority(keys);
    }

    /**
     * Get distinct keys that are available for items matching the search criteria.
     * This is similar to getAllItemAttributeKeys() but filters based on current search conditions,
     * so only keys that exist for items matching the search are returned.
     *
     * @param searchConditions Optional search conditions to filter items
     * @param name Optional name filter
     * @param folder Optional folder filter
     * @param searchString Optional full-text search string
     * @return List of distinct key names sorted with known fields first, then alphabetically
     */
    public List<String> getAvailableItemAttributeKeys(List<SearchConditionInput> searchConditions,
                                                        String name, String folder, String searchString) {
        validateSearchString(searchString);

        List<SearchConditionInput> conds = (searchConditions == null) ? List.of() : searchConditions;

        // Check if any filters are present
        boolean hasFilters = !conds.isEmpty()
                          || (name != null && !name.isEmpty())
                          || (folder != null && !folder.isEmpty())
                          || contributesFTSSubquery(searchString);

        // Optimization: If no filters, use simple direct query (5x faster)
        if (!hasFilters) {
            return getAllItemAttributeKeys();
        }

        String sql;
        List<Object> params;

        if (hasFTSSearch(conds, searchString)) {
            // Use FTS CTE path — same pattern as getFacetValues() — to avoid correlated subqueries
            // that scan every ITEMS row. This can be orders of magnitude faster when searchString
            // or LIKE conditions are present because the FTS/trigram indexes are used first.
            FTSConditionSeparator separator = separateFTSConditions(conds, searchString);
            params = new ArrayList<>(separator.getParameters());

            StringBuilder sqlBuilder = new StringBuilder();
            sqlBuilder.append("WITH fts_matches AS (");
            sqlBuilder.append(String.join(" INTERSECT ", separator.getFtsSubqueries()));
            sqlBuilder.append("), matched_items AS (");
            sqlBuilder.append("SELECT i.id FROM ITEMS i JOIN fts_matches fm ON fm.item_id = i.id");

            List<String> additionalWhere = buildAdditionalWhereClauses(name, folder, separator.getNonFTSConditions(), params);
            if (!additionalWhere.isEmpty()) {
                sqlBuilder.append(" WHERE ").append(String.join(" AND ", additionalWhere));
            }

            sqlBuilder.append(") ");
            sqlBuilder.append("SELECT DISTINCT itr.key ");
            sqlBuilder.append("FROM matched_items mi ");
            sqlBuilder.append("JOIN ITEM_TREATMENTS it ON it.item_id = mi.id ");
            // Only query the metadata table — content fields are not useful as facet keys
            sqlBuilder.append("JOIN ITEM_TREATMENT_RESULTS itr ON itr.item_treatment_id = it.id ");
            sqlBuilder.append("ORDER BY key");

            sql = sqlBuilder.toString();
        } else {
            // Non-FTS path: build WHERE clause with correlated subqueries (fine when no text search)
            WhereClauseBuilder whereBuilder = buildWhereClause(conds, name, folder);
            params = new ArrayList<>(whereBuilder.getParameters());

            sql = "WITH matched_items AS (" +
                    "  SELECT i.id FROM ITEMS i " +
                    whereBuilder.getWhereClause() +
                    ") " +
                    "SELECT DISTINCT itr.key " +
                    "FROM matched_items mi " +
                    "JOIN ITEM_TREATMENTS it ON it.item_id = mi.id " +
                    // Only query the metadata table — content fields are not useful as facet keys
                    "JOIN ITEM_TREATMENT_RESULTS itr ON itr.item_treatment_id = it.id " +
                    "ORDER BY key";
        }

        List<Map<String, Object>> rows = dbAccess.queryForList(Objects.requireNonNull(sql), params.toArray());

        List<String> keys = rows.stream()
                .map(row -> (String) row.get("key"))
                .collect(Collectors.toList());

        return sortKeysByPriority(keys);
    }

    /**
     * Sort keys with standard fields first (in defined order), then other keys alphabetically.
     * Standard fields are defined in FieldNameConstants.getStandardFieldsInPriorityOrder().
     */
    private List<String> sortKeysByPriority(List<String> keys) {
        // Use centralized standard fields list
        List<String> standardFields = FieldNameConstants.getStandardFieldsInPriorityOrder();

        return keys.stream()
                .sorted((k1, k2) -> {
                    int idx1 = standardFields.indexOf(k1);
                    int idx2 = standardFields.indexOf(k2);

                    // Both are standard fields - sort by their position in the list
                    if (idx1 >= 0 && idx2 >= 0) {
                        return Integer.compare(idx1, idx2);
                    }
                    // Only k1 is a standard field - it comes first
                    if (idx1 >= 0) return -1;
                    // Only k2 is a standard field - it comes first
                    if (idx2 >= 0) return 1;
                    // Neither is a standard field - sort alphabetically
                    return k1.compareTo(k2);
                })
                .collect(Collectors.toList());
    }

    /**
     * Get all treatment results (key-value pairs) for an item by datasource and item name/folder.
     * Returns a list of maps with 'key' and 'value' entries.
     * Note: folder parameter uses forward-slash format (e.g., "level1/level2") which is converted
     * to comma-separated format for database query.
     *
     * @param datasourceId the datasource ID
     * @param name the item name
     * @param folder the folder path (forward-slash format)
     * @param keyNames optional list of key names to filter results. If null or empty, returns all keys.
     * @return list of maps with 'key' and 'value' entries
     */
    public List<Map<String, String>> getItemTreatmentResults(String datasourceId, String name, String folder, List<String> keyNames) {
        // Build base WHERE conditions
        StringBuilder whereConditions = new StringBuilder("WHERE i.datasource_id = ? AND i.name = ?");
        List<Object> params = new ArrayList<>();
        params.add(datasourceId);
        params.add(name);

        if (folder != null && !folder.isEmpty()) {
            whereConditions.append(" AND i.path = ?");
            params.add(folder);
        } else {
            whereConditions.append(" AND (i.path IS NULL OR i.path = '')");
        }

        // Build key filter for both queries
        String keyFilter = "";
        if (keyNames != null && !keyNames.isEmpty()) {
            String placeholders = String.join(",", keyNames.stream().map(k -> "?").toList());
            keyFilter = " AND itr.key IN (" + placeholders + ")";
        }

        // Query both metadata and content tables with UNION
        String sql = "SELECT itr.key, itr.value " +
                "FROM ITEMS i " +
                "JOIN ITEM_TREATMENTS it ON it.item_id = i.id " +
                "JOIN ITEM_TREATMENT_RESULTS itr ON itr.item_treatment_id = it.id " +
                whereConditions + keyFilter +
                " UNION ALL " +
                "SELECT itr.key, itr.value " +
                "FROM ITEMS i " +
                "JOIN ITEM_TREATMENTS it ON it.item_id = i.id " +
                "JOIN ITEM_TREATMENT_RESULTS_CONTENT itr ON itr.item_treatment_id = it.id " +
                whereConditions + keyFilter +
                " ORDER BY key, value";

        // Duplicate parameters for both UNION queries
        List<Object> allParams = new ArrayList<>(params);
        if (keyNames != null && !keyNames.isEmpty()) {
            allParams.addAll(keyNames);
        }
        allParams.addAll(params);  // Second set for UNION query
        if (keyNames != null && !keyNames.isEmpty()) {
            allParams.addAll(keyNames);
        }

        return dbAccess.query(sql, (rs, rowNum) -> {
            Map<String, String> result = new HashMap<>();
            result.put("key", rs.getString("key"));
            result.put("value", rs.getString("value"));
            return result;
        }, allParams.toArray());
    }
}
