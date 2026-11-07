package io.github.albaasca.lakehouse.mcp;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lets through only the reads it knows, judged on the tree of DuckDB's own parser (decision 7 of docs/architecture.md).
 *
 * <p>Everything is an allow list, so whatever the parser adds in a future version is rejected until someone looks at it.
 * The SQL that runs is rebuilt from the validated tree and validated again, so a comment or a stray semicolon in the
 * original cannot change what the row limit wraps. The facts behind each rule: docs/spike-duckdb-iceberg.md, question 6.
 */
public final class SqlGuard {

    /** Which rule refused the SQL. */
    public enum Rule {
        /** DuckDB could not parse it, or it is too long or too deep to hand over or to walk. */
        SYNTAX,
        /** Parses, but is not a {@code SELECT}: {@code DROP}, {@code COPY}, {@code SET}... */
        NOT_A_SELECT,
        /** None, or more than one. */
        NOT_ONE_STATEMENT,
        /** A kind of node off the list: table functions, {@code DESCRIBE}, {@code PIVOT}, recursive CTEs, parameters. */
        NODE,
        /** A table off the list. Paths also land here: the parser reads {@code FROM '/etc/passwd'} as a table. */
        TABLE,
        /** A function off the list. Add it to {@link #FUNCTIONS} if it is harmless. */
        FUNCTION,
        /** {@code AT (...)}: only the server travels in time, through its own view (decision 21). */
        TIME_TRAVEL
    }

    /** The SQL was refused; the message is meant for whoever wrote it. */
    public static final class Rejected extends RuntimeException {

        private final Rule rule;

        Rejected(Rule rule, String message) {
            super(message);
            this.rule = rule;
        }

        public Rule rule() {
            return rule;
        }
    }

    /** Where the SQL is parsed and run. {@code LakehouseSession::query} brings its timeout along. */
    @FunctionalInterface
    public interface Engine {
        <T> T query(String sql, LakehouseSession.RowReader<T> reader) throws SQLException;
    }

    /** A table the caller may read. Empty {@code catalog} and {@code schema} mean a bare name such as a temporary view. */
    public record TableName(String catalog, String schema, String table) {

        private static final String DEFAULT_CATALOG = "memory";
        private static final String DEFAULT_SCHEMA = "main";

        /** {@code catalog.schema.table}, or just {@code table}. */
        public static TableName of(String name) {
            String[] parts = name.split("\\.", -1);
            return switch (parts.length) {
                case 1 -> new TableName("", "", parts[0]);
                case 3 -> new TableName(parts[0], parts[1], parts[2]);
                default -> throw new IllegalArgumentException("Expected catalog.schema.table or table: " + name);
            };
        }

        /**
         * Whether a reference reaches this table. A shorter name only counts where DuckDB would resolve it here:
         * {@code ns.t} is not {@code lh.ns.t}, because only the default catalog can be left out.
         */
        boolean isReachedBy(String refCatalog, String refSchema, String refTable) {
            if (!table.equalsIgnoreCase(refTable)) {
                return false;
            }
            boolean inDefaultSchema = DEFAULT_CATALOG.equalsIgnoreCase(catalog) && DEFAULT_SCHEMA.equalsIgnoreCase(schema);
            if (refCatalog.isEmpty() && refSchema.isEmpty()) {
                return catalog.isEmpty() || inDefaultSchema;
            }
            if (refCatalog.isEmpty()) {
                return inDefaultSchema && DEFAULT_SCHEMA.equalsIgnoreCase(refSchema);
            }
            return catalog.equalsIgnoreCase(refCatalog) && schema.equalsIgnoreCase(refSchema);
        }
    }

    /** {@code truncated} means there were more rows than the limit; {@code rows} has exactly the limit. */
    public record QueryResult(List<String> columns, List<List<Object>> rows, boolean truncated) {}

    /** Parser nodes without {@code class}: query nodes, table references, modifiers and sort orders. */
    private static final Set<String> STRUCTURE = Set.of(
            "SELECT_NODE", "SET_OPERATION_NODE",
            "BASE_TABLE", "JOIN", "SUBQUERY", "EMPTY", "EXPRESSION_LIST",
            "ORDER_MODIFIER", "LIMIT_MODIFIER", "LIMIT_PERCENT_MODIFIER", "DISTINCT_MODIFIER",
            "ORDER_DEFAULT", "ASCENDING", "DESCENDING");

    /** Kinds of expression. Lambdas and parameters stay out: nothing here needs them. */
    private static final Set<String> EXPRESSIONS = Set.of(
            "BETWEEN", "CASE", "CAST", "COLUMN_REF", "COMPARISON", "CONJUNCTION", "CONSTANT", "FUNCTION", "OPERATOR",
            "STAR", "SUBQUERY", "TYPE", "WINDOW");

    /** Only meant for {@code OVER (...)}, so DuckDB's function list does not have to know them. */
    static final Set<String> WINDOW_FUNCTIONS = Set.of(
            "row_number", "rank", "dense_rank", "percent_rank", "cume_dist", "ntile", "lag", "lead", "first_value",
            "last_value", "nth_value");

    /** Names in the parser's lower case. Operators count: {@code LIKE} arrives as {@code ~~}, {@code a + 1} as {@code +}. */
    static final Set<String> FUNCTIONS = Set.of(
            // Aggregates.
            "any_value", "approx_count_distinct", "approx_quantile", "arg_max", "arg_min", "avg", "bool_and", "bool_or",
            "corr", "count", "count_star", "first", "last", "max", "median", "min", "mode", "quantile", "quantile_cont",
            "quantile_disc", "stddev", "stddev_pop", "stddev_samp", "string_agg", "sum", "var_pop", "var_samp", "variance",
            // Numbers.
            "abs", "ceil", "ceiling", "exp", "floor", "greatest", "least", "ln", "log", "log10", "log2", "mod", "pow",
            "power", "round", "sign", "sqrt", "trunc",
            // Text.
            // Out: lpad, rpad, replace and regexp_replace. They build one cell of any size past memory_limit and the row
            // limit (T-019: 4.8 GB of memory with a limit of 256 MB, even if only its length comes out). || does not.
            "concat", "concat_ws", "contains", "ends_with", "left", "length", "lower", "ltrim", "nullif",
            "regexp_extract", "regexp_matches", "right", "rtrim", "split_part",
            "starts_with", "strpos", "substr", "substring", "trim", "upper",
            // Dates. INTERVAL 1 DAY arrives as to_days(trunc(...)).
            "date_add", "date_diff", "date_part", "date_sub", "date_trunc", "datediff", "datepart", "datesub", "datetrunc",
            "day", "dayname", "dayofmonth", "dayofweek", "dayofyear", "epoch", "epoch_ms", "get_current_timestamp", "hour",
            "isodow", "last_day", "make_date", "minute", "month", "monthname", "now", "quarter", "second", "strftime",
            "strptime", "to_days", "to_hours", "to_minutes", "to_months", "to_seconds", "to_timestamp", "to_weeks",
            "to_years", "today", "try_strptime", "week", "year",
            // Operators.
            "+", "-", "*", "/", "//", "%", "^", "**", "||", "~~", "!~~", "~~*", "!~~*", "regexp_full_match");

    /**
     * Too much nesting does not fail in DuckDB's parser: it overflows the stack and takes the whole process down. Measured
     * in T-019 with the worst shape, {@code FROM a,a,a...}: a stack of 256 KB survives 6,000 characters and dies at 8,000;
     * one of 1 MB (the JVM's default) survives 16,000 and dies at 32,000. This limit only protects the parser, and it needs
     * no second parser to tell a parenthesis from one inside a string; what runs afterwards is {@link #MAX_DEPTH}'s job.
     */
    static final int MAX_SQL_LENGTH = 4_000;

    /**
     * Levels of the parsed tree, wrapper included. Running a query needs far more stack than parsing it, on the thread that
     * calls DuckDB, whatever DuckDB's own number of threads. Measured in T-127: 400 tables in a {@code FROM} fit the length
     * limit and Jackson's default of 500 levels, and kill the process on a stack of 1 MB, the JVM's default. The hungriest
     * shape is a chain of {@code EXCEPT} or {@code INTERSECT}: at 100 levels (45 links) it dies on 512 KB and runs on 768 KB.
     * At 80 levels every shape measured runs on 384 KB, leaving the default stack more than 2.5 times that. It allows about
     * 70 joined tables, 35 {@code EXCEPT}, 23 nested subqueries, functions or chained operators, or 13 linked CTEs.
     */
    static final int MAX_DEPTH = 80;

    private static final String TOO_COMPLEX = "The query is too complex to validate";

    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(MAX_DEPTH).build())
                    .build())
            .build();

    private final Engine engine;

    public SqlGuard(Engine engine) {
        this.engine = engine;
    }

    /**
     * Validates and runs one read.
     *
     * @param tables what this caller may read: it depends on the role and on the tool, so it is not kept here
     * @throws Rejected if a rule refuses it; nothing has run
     * @throws SQLException if DuckDB fails to run it, timeout included
     */
    public QueryResult run(String sql, Collection<TableName> tables, int maxRows) throws SQLException {
        return engine.query(guarded(sql, tables, maxRows), limitedTo(maxRows));
    }

    /**
     * The SQL to run instead of the original, for a caller that must run it on a connection of its own. It asks for one
     * row more than the limit so that {@link #limitedTo} can tell a full result from a cut one.
     */
    public String guarded(String sql, Collection<TableName> tables, int maxRows) throws SQLException {
        Objects.requireNonNull(sql, "sql");
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be at least 1: " + maxRows);
        }
        if (sql.indexOf('\0') >= 0) {
            // The native driver may end the text there: what gets parsed and what runs must be the same thing.
            throw new Rejected(Rule.SYNTAX, "The SQL contains a NUL character");
        }
        if (sql.length() > MAX_SQL_LENGTH) {
            throw new Rejected(Rule.SYNTAX, "The SQL has " + sql.length() + " characters; the limit is " + MAX_SQL_LENGTH);
        }
        String json = serialize(sql);
        validate(json, tables);
        String rebuilt = firstValue("SELECT json_deserialize_sql(" + quote(json) + ")");
        String wrapped = "SELECT * FROM (\n" + rebuilt + "\n) LIMIT " + (maxRows + 1L);
        validate(serialize(wrapped), tables);
        return wrapped;
    }

    /** Reads the rows of {@link #guarded}: at most {@code maxRows}, and whether there were more. */
    public static LakehouseSession.RowReader<QueryResult> limitedTo(int maxRows) {
        return rows -> {
            ResultSetMetaData meta = rows.getMetaData();
            int width = meta.getColumnCount();
            List<String> columns = new ArrayList<>(width);
            for (int i = 1; i <= width; i++) {
                columns.add(meta.getColumnLabel(i));
            }
            List<List<Object>> values = new ArrayList<>();
            while (values.size() < maxRows && rows.next()) {
                values.add(row(rows, width));
            }
            return new QueryResult(columns, values, rows.next());
        };
    }

    private static List<Object> row(ResultSet rows, int width) throws SQLException {
        List<Object> row = new ArrayList<>(width);
        for (int i = 1; i <= width; i++) {
            row.add(rows.getObject(i));
        }
        return row;
    }

    private String serialize(String sql) throws SQLException {
        return firstValue("SELECT json_serialize_sql(" + quote(sql) + ")");
    }

    private String firstValue(String sql) throws SQLException {
        return engine.query(sql, rows -> {
            rows.next();
            return rows.getString(1);
        });
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static void validate(String json, Collection<TableName> tables) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new Rejected(Rule.SYNTAX, TOO_COMPLEX);
        }
        if (root.path("error").asBoolean(true)) {
            String message = text(root, "error_message");
            throw new Rejected(message.startsWith("Only SELECT") ? Rule.NOT_A_SELECT : Rule.SYNTAX, message);
        }
        JsonNode statements = root.path("statements");
        if (statements.size() != 1) {
            throw new Rejected(Rule.NOT_ONE_STATEMENT, "Expected exactly one statement, got " + statements.size());
        }
        if (walk(statements.get(0), Map.of(), tables) > MAX_DEPTH) {
            throw new Rejected(Rule.SYNTAX, TOO_COMPLEX);
        }
    }

    /**
     * Visits every object of the tree, not just the known places: subqueries can hang from expressions, joins, CTEs...
     *
     * <p>Each CTE body sees only the CTEs before it, and never itself. T-019 ran it: in
     * {@code WITH t AS (SELECT * FROM t) ...} the inner {@code t} is the real table, and it could be a path just as well.
     *
     * @param ctes the CTE names visible here, with their depth. A name outside its scope is not a CTE for DuckDB but a table
     *     or a file.
     * @return the levels this node needs: a reference to a CTE counts its body, which DuckDB binds at that point. T-127: a
     *     chain of CTEs fitting the length limit is shallow in the tree and still killed the process on a stack of 512 KB.
     */
    private static int walk(JsonNode node, Map<String, Integer> ctes, Collection<TableName> tables) {
        int deepest = 0;
        if (node.isArray()) {
            for (JsonNode child : node) {
                deepest = Math.max(deepest, walk(child, ctes, tables));
            }
            return deepest + 1;
        }
        if (!node.isObject()) {
            return 0;
        }
        check(node, ctes.keySet(), tables);
        Map<String, Integer> scope = ctes;
        for (JsonNode entry : node.path("cte_map").path("map")) {
            int body = walk(entry.path("value"), scope, tables);
            deepest = Math.max(deepest, body);
            scope = new HashMap<>(scope);
            scope.put(text(entry, "key"), body);
        }
        for (Map.Entry<String, JsonNode> property : node.properties()) {
            if (!property.getKey().equals("cte_map")) {
                deepest = Math.max(deepest, walk(property.getValue(), scope, tables));
            }
        }
        if (text(node, "type").equals("BASE_TABLE")) {
            deepest += cteDepth(node, ctes);
        }
        return deepest + 1;
    }

    /**
     * T-147: DuckDB resolves CTEs ignoring case ({@code WITH A ... SELECT FROM a}), so the depth is counted that way,
     * conservatively. The allowed-tables check stays exact ({@link #isCte}).
     */
    private static int cteDepth(JsonNode table, Map<String, Integer> ctes) {
        if (!text(table, "catalog_name").isEmpty() || !text(table, "schema_name").isEmpty()) {
            return 0;
        }
        String name = text(table, "table_name");
        int depth = 0;
        for (Map.Entry<String, Integer> cte : ctes.entrySet()) {
            if (cte.getKey().equalsIgnoreCase(name)) {
                depth = Math.max(depth, cte.getValue());
            }
        }
        return depth;
    }

    /** Exact case on purpose: if DuckDB matched CTEs differently, a near miss must not pass as a CTE and read a file. */
    private static boolean isCte(JsonNode table, Set<String> ctes) {
        return text(table, "catalog_name").isEmpty() && text(table, "schema_name").isEmpty()
                && ctes.contains(text(table, "table_name"));
    }

    private static void check(JsonNode node, Set<String> ctes, Collection<TableName> tables) {
        JsonNode at = node.get("at_clause");
        if (at != null && !at.isNull()) {
            throw new Rejected(Rule.TIME_TRAVEL, "AT (...) is not allowed: use the time_travel tool");
        }
        String expression = text(node, "class");
        String type = text(node, "type");
        if (!expression.isEmpty()) {
            if (!EXPRESSIONS.contains(expression)) {
                throw new Rejected(Rule.NODE, "Expression not allowed: " + expression);
            }
        } else if (!type.isEmpty() && !STRUCTURE.contains(type)) {
            throw new Rejected(Rule.NODE, describe(node, type) + " not allowed");
        }
        if (node.has("function_name")) {
            checkFunction(node);
        }
        if (type.equals("BASE_TABLE")) {
            checkTable(node, ctes, tables);
        }
    }

    private static String describe(JsonNode node, String type) {
        String function = text(node.path("function"), "function_name");
        return function.isEmpty() ? "Node " + type : "Table function " + function;
    }

    private static void checkFunction(JsonNode node) {
        String name = text(node, "function_name");
        boolean qualified = !text(node, "catalog").isEmpty() || !text(node, "schema").isEmpty();
        String lower = name.toLowerCase(Locale.ROOT);
        if (qualified || !(FUNCTIONS.contains(lower) || WINDOW_FUNCTIONS.contains(lower))) {
            throw new Rejected(Rule.FUNCTION, "Function not allowed: " + name
                    + (qualified ? " (write it without catalog or schema)" : ""));
        }
    }

    private static void checkTable(JsonNode node, Set<String> ctes, Collection<TableName> tables) {
        String catalog = text(node, "catalog_name");
        String schema = text(node, "schema_name");
        String table = text(node, "table_name");
        if (isCte(node, ctes)) {
            return;
        }
        if (tables.stream().noneMatch(allowed -> allowed.isReachedBy(catalog, schema, table))) {
            String written = (catalog.isEmpty() ? "" : catalog + ".") + (schema.isEmpty() ? "" : schema + ".") + table;
            throw new Rejected(Rule.TABLE, "Table not allowed: " + written);
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && value.isString() ? value.stringValue() : "";
    }
}
