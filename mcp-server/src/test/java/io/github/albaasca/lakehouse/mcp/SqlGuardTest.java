package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.github.albaasca.lakehouse.mcp.SqlGuard.QueryResult;
import io.github.albaasca.lakehouse.mcp.SqlGuard.Rejected;
import io.github.albaasca.lakehouse.mcp.SqlGuard.Rule;
import io.github.albaasca.lakehouse.mcp.SqlGuard.TableName;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Against the real DuckDB parser, in memory: no containers. {@code lh} stands in for the Iceberg catalog. */
class SqlGuardTest {

    private static final List<TableName> ALLOWED =
            List.of(TableName.of("lh.ns.events"), TableName.of("memory.main.views_only"));

    private static Connection connection;
    private static SqlGuard guard;

    @BeforeAll
    static void createTables() throws SQLException {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        guard = new SqlGuard(SqlGuardTest::query);
        try (Statement statement = connection.createStatement()) {
            statement.execute("ATTACH ':memory:' AS lh");
            statement.execute("CREATE SCHEMA lh.ns");
            statement.execute("CREATE TABLE lh.ns.events AS SELECT i AS id, i % 2 AS kind,"
                    + " TIMESTAMP '2019-10-01' + INTERVAL (i) DAY AS ts, 'p' || i AS name FROM range(1, 11) t(i)");
            statement.execute("CREATE TABLE lh.ns.secret AS SELECT 'hidden' AS s");
            statement.execute("CREATE TABLE memory.main.views_only AS SELECT 1 AS id");
            statement.execute("CREATE TABLE memory.main.secret AS SELECT 'hidden' AS s");
        }
    }

    @AfterAll
    static void close() throws SQLException {
        connection.close();
    }

    private static <T> T query(String sql, LakehouseSession.RowReader<T> reader) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            return reader.read(rows);
        }
    }

    private static Stream<Arguments> attacks() {
        return Stream.of(
                // Rule 1: one statement. The second one must not hide behind a comment or a semicolon.
                arguments("SELECT 1; SELECT 2", Rule.NOT_ONE_STATEMENT),
                arguments("SELECT 1;;SELECT 2", Rule.NOT_ONE_STATEMENT),
                arguments("", Rule.NOT_ONE_STATEMENT),
                arguments("-- only a comment", Rule.NOT_ONE_STATEMENT),
                arguments("SELECT 1; DROP TABLE lh.ns.events", Rule.NOT_A_SELECT),
                arguments("SELECT 1; -- x\nDROP TABLE lh.ns.events", Rule.NOT_A_SELECT),
                arguments("SELECT 1 /* ; */ ; /* ; */ DELETE FROM lh.ns.events", Rule.NOT_A_SELECT),
                // Not a read at all.
                arguments("DROP TABLE lh.ns.events", Rule.NOT_A_SELECT),
                arguments("INSERT INTO lh.ns.events VALUES (1)", Rule.NOT_A_SELECT),
                arguments("DELETE FROM lh.ns.events", Rule.NOT_A_SELECT),
                arguments("UPDATE lh.ns.events SET id = 1", Rule.NOT_A_SELECT),
                arguments("CREATE TABLE x AS SELECT 1", Rule.NOT_A_SELECT),
                arguments("COPY lh.ns.events TO '/tmp/x.csv'", Rule.NOT_A_SELECT),
                arguments("ATTACH '/tmp/x.db'", Rule.NOT_A_SELECT),
                arguments("SET enable_external_access = true", Rule.NOT_A_SELECT),
                arguments("PRAGMA version", Rule.NOT_A_SELECT),
                arguments("CALL iceberg_rollback_to_snapshot('t', 1)", Rule.NOT_A_SELECT),
                arguments("INSTALL httpfs", Rule.NOT_A_SELECT),
                arguments("LOAD httpfs", Rule.NOT_A_SELECT),
                arguments("EXPLAIN SELECT 1", Rule.NOT_A_SELECT),
                arguments("sElEcT FROM", Rule.SYNTAX),
                arguments("SELECT 1\0; DROP TABLE lh.ns.events", Rule.SYNTAX),
                // Rule 2 and 3: table functions, wherever they hide in the tree.
                arguments("SELECT * FROM read_csv('/etc/passwd')", Rule.NODE),
                arguments("FROM read_csv('/etc/passwd')", Rule.NODE),
                arguments("sElEcT * fRoM ReAd_CsV('/etc/passwd')", Rule.NODE),
                arguments("SELECT/**/*/**/FROM/**/read_text('/etc/passwd')", Rule.NODE),
                arguments("SELECT * FROM glob('/*')", Rule.NODE),
                arguments("SELECT * FROM query('SELECT 1')", Rule.NODE),
                arguments("SELECT * FROM query_table('secret')", Rule.NODE),
                arguments("SELECT * FROM iceberg_rollback_to_snapshot('t', 1)", Rule.NODE),
                arguments("SELECT * FROM duckdb_secrets()", Rule.NODE),
                arguments("SELECT * FROM (SELECT * FROM read_text('/etc/passwd')) t", Rule.NODE),
                arguments("SELECT (SELECT content FROM read_text('/etc/passwd'))", Rule.NODE),
                arguments("SELECT 1 WHERE EXISTS (SELECT 1 FROM read_text('/etc/passwd'))", Rule.NODE),
                arguments("SELECT 1 WHERE 1 IN (SELECT 1 FROM glob('/*'))", Rule.NODE),
                arguments("SELECT 1 UNION ALL SELECT * FROM read_csv('a')", Rule.NODE),
                arguments("SELECT 1 ORDER BY (SELECT count(*) FROM read_text('/etc/passwd'))", Rule.NODE),
                arguments("SELECT 1 LIMIT (SELECT count(*) FROM read_text('/etc/passwd'))", Rule.NODE),
                arguments("SELECT * FROM lh.ns.events e, LATERAL read_csv(e.name)", Rule.NODE),
                arguments("SELECT * FROM lh.ns.events JOIN glob('/*') ON true", Rule.NODE),
                arguments("WITH c AS (SELECT * FROM read_text('/etc/passwd')) SELECT * FROM c", Rule.NODE),
                arguments("WITH c AS (SELECT 1) SELECT * FROM (WITH d AS (FROM glob('/*')) FROM d)", Rule.NODE),
                arguments("DESCRIBE secret", Rule.NODE),
                arguments("SUMMARIZE secret", Rule.NODE),
                arguments("SHOW TABLES", Rule.NODE),
                arguments("SELECT * FROM (DESCRIBE secret)", Rule.NODE),
                arguments("SELECT * FROM lh.ns.events PIVOT (sum(id) FOR kind IN (1))", Rule.NODE),
                arguments("SELECT $1", Rule.NODE),
                arguments("SELECT upper(x -> 1)", Rule.NODE),
                arguments("SELECT list_transform([1], x -> getenv('HOME'))", Rule.FUNCTION),
                // Rule 3: only known tables. Paths, system catalogs and names that merely look alike.
                arguments("SELECT * FROM '/etc/passwd'", Rule.TABLE),
                arguments("SELECT * FROM \"/etc/passwd\"", Rule.TABLE),
                arguments("FROM '/etc/passwd'", Rule.TABLE),
                arguments("SELECT * FROM 's3://bucket/x.parquet'", Rule.TABLE),
                arguments("SELECT * FROM secret", Rule.TABLE),
                arguments("SELECT * FROM lh.ns.secret", Rule.TABLE),
                arguments("SELECT * FROM duckdb_views", Rule.TABLE),
                arguments("SELECT * FROM sqlite_master", Rule.TABLE),
                arguments("SELECT * FROM information_schema.tables", Rule.TABLE),
                arguments("SELECT * FROM system.main.duckdb_settings", Rule.TABLE),
                arguments("SELECT * FROM pg_catalog.pg_settings", Rule.TABLE),
                arguments("SELECT * FROM events", Rule.TABLE),
                arguments("SELECT * FROM ns.events", Rule.TABLE),
                arguments("SELECT * FROM other.ns.events", Rule.TABLE),
                arguments("SELECT * FROM lh.other.events", Rule.TABLE),
                arguments("SELECT * FROM \"lh.ns.events\"", Rule.TABLE),
                arguments("SELECT * FROM temp.main.views_only", Rule.TABLE),
                arguments("SELECT * FROM lh.ns.events JOIN secret ON true", Rule.TABLE),
                arguments("SELECT * FROM lh.ns.events UNION ALL SELECT * FROM secret", Rule.TABLE),
                arguments("SELECT (SELECT s FROM secret)", Rule.TABLE),
                // Rule 4: a CTE name only counts inside its scope. Outside it, DuckDB reads the real table or file.
                arguments("WITH \"/etc/passwd\" AS (SELECT * FROM \"/etc/passwd\") SELECT * FROM \"/etc/passwd\"", Rule.TABLE),
                arguments("WITH secret AS (SELECT * FROM secret) SELECT * FROM secret", Rule.TABLE),
                arguments("WITH a AS (SELECT * FROM secret), secret AS (SELECT 1) SELECT * FROM a", Rule.TABLE),
                arguments("SELECT * FROM (WITH secret AS (SELECT 1) SELECT * FROM secret) x, secret", Rule.TABLE),
                arguments("WITH c AS (WITH secret AS (SELECT 1) SELECT * FROM secret) SELECT * FROM secret", Rule.TABLE),
                arguments("WITH \"SECRET\" AS (SELECT 1) SELECT * FROM secret", Rule.TABLE),
                arguments("WITH secret AS (SELECT 1) SELECT * FROM main.secret", Rule.TABLE),
                arguments("WITH RECURSIVE \"/etc/passwd\" AS (SELECT * FROM \"/etc/passwd\" UNION ALL"
                        + " SELECT * FROM \"/etc/passwd\") SELECT * FROM \"/etc/passwd\"", Rule.NODE),
                arguments("WITH RECURSIVE r AS (SELECT 1 x UNION ALL SELECT x FROM r) SELECT * FROM r", Rule.NODE),
                // Rule 5: functions, also when written so that a grep would not find them.
                arguments("SELECT getenv('HOME')", Rule.FUNCTION),
                arguments("SELECT GeTeNv('HOME')", Rule.FUNCTION),
                arguments("SELECT \"getenv\"('HOME')", Rule.FUNCTION),
                arguments("SELECT 'HOME'.getenv()", Rule.FUNCTION),
                arguments("SELECT system.main.getenv('HOME')", Rule.FUNCTION),
                arguments("SELECT system.main.upper('a')", Rule.FUNCTION),
                arguments("SELECT getvariable('x')", Rule.FUNCTION),
                arguments("SELECT current_setting('s3_secret_access_key')", Rule.FUNCTION),
                arguments("SELECT which_secret('s3://b/x', 's3')", Rule.FUNCTION),
                arguments("SELECT 1 FROM lh.ns.events WHERE getenv('HOME') IS NULL", Rule.FUNCTION),
                arguments("SELECT id FROM lh.ns.events ORDER BY getenv('HOME')", Rule.FUNCTION),
                arguments("SELECT kind FROM lh.ns.events GROUP BY kind HAVING getenv('HOME') = ''", Rule.FUNCTION),
                arguments("SELECT CASE WHEN true THEN getenv('HOME') END", Rule.FUNCTION),
                arguments("SELECT CAST(getenv('HOME') AS VARCHAR)", Rule.FUNCTION),
                arguments("SELECT sum(id) FILTER (WHERE getenv('HOME') = '') FROM lh.ns.events", Rule.FUNCTION),
                arguments("SELECT row_number() OVER (ORDER BY getenv('HOME')) FROM lh.ns.events", Rule.FUNCTION),
                arguments("SELECT * REPLACE (getenv('HOME') AS name) FROM lh.ns.events", Rule.FUNCTION),
                arguments("SELECT 1 LIMIT getenv('HOME')", Rule.FUNCTION),
                arguments("SELECT * FROM (VALUES (getenv('HOME'))) v(x)", Rule.FUNCTION),
                // Rule 6: time travel belongs to the server, even on an allowed table.
                arguments("SELECT * FROM lh.ns.events AT (VERSION => 1)", Rule.TIME_TRAVEL),
                arguments("SELECT * FROM lh.ns.events AT (TIMESTAMP => TIMESTAMP '2019-10-01')", Rule.TIME_TRAVEL),
                arguments("SELECT * FROM (SELECT * FROM lh.ns.events AT (VERSION => 1))", Rule.TIME_TRAVEL));
    }

    @ParameterizedTest
    @MethodSource("attacks")
    void rejectsEachAttackForTheRightReason(String sql, Rule rule) {
        assertThatThrownBy(() -> guard.guarded(sql, ALLOWED, 10))
                .isInstanceOfSatisfying(Rejected.class, rejected -> assertThat(rejected.rule()).isEqualTo(rule));
    }

    /** Each one really runs, with the row limit around it: it has to be a read the engine accepts, not just the guard. */
    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT 1",
        "SELECT 1;",
        "select 1 /* a comment */ ; -- and another",
        "SELECT id FROM lh.ns.events",
        "SELECT id FROM LH.NS.EVENTS",
        "SELECT id FROM \"lh\".\"ns\".\"events\"",
        "FROM lh.ns.events",
        "SELECT * FROM views_only",
        "SELECT * FROM main.views_only",
        "SELECT * FROM memory.main.views_only",
        "SELECT DISTINCT kind FROM lh.ns.events ORDER BY kind DESC NULLS LAST",
        "SELECT kind, count(*), count(DISTINCT id), sum(id), avg(id) FROM lh.ns.events GROUP BY kind HAVING count(*) > 1",
        "SELECT kind, sum(id) FILTER (WHERE id > 2) FROM lh.ns.events GROUP BY ALL ORDER BY ALL",
        "SELECT id, row_number() OVER (PARTITION BY kind ORDER BY ts) AS n, lag(id) OVER (ORDER BY ts) FROM lh.ns.events",
        "SELECT id FROM lh.ns.events QUALIFY row_number() OVER (PARTITION BY kind ORDER BY id) = 1",
        "SELECT date_trunc('day', ts), extract(year FROM ts), ts + INTERVAL 1 DAY, strftime(ts, '%Y') FROM lh.ns.events",
        "SELECT CASE WHEN id IS NULL THEN 0 ELSE id END, CAST(id AS VARCHAR), id::DOUBLE, COALESCE(id, 0) FROM lh.ns.events",
        "SELECT upper(name), name || 'x', -id FROM lh.ns.events WHERE name LIKE 'p%' OR name ILIKE 'P%' AND NOT id = 3",
        "SELECT id FROM lh.ns.events WHERE id IN (1, 2) AND id BETWEEN 1 AND 5 AND id IN (SELECT id FROM lh.ns.events)",
        "SELECT id FROM lh.ns.events e WHERE EXISTS (SELECT 1 FROM lh.ns.events x WHERE x.id = e.id)",
        "SELECT a.id FROM lh.ns.events a JOIN lh.ns.events b ON a.id = b.id LEFT JOIN views_only v ON v.id = a.id",
        "SELECT * FROM (SELECT id FROM lh.ns.events) t WHERE id > 1",
        "SELECT * FROM (VALUES (1, 'a')) v(x, y)",
        "SELECT id FROM lh.ns.events UNION ALL SELECT id FROM views_only ORDER BY id",
        "WITH c AS (SELECT id FROM lh.ns.events) SELECT * FROM c",
        "WITH c AS (SELECT id FROM lh.ns.events), d AS (SELECT id FROM c) SELECT * FROM d UNION ALL SELECT * FROM c",
        "WITH c AS (SELECT 1 AS x) SELECT * FROM (SELECT * FROM c) s WHERE x IN (SELECT x FROM c)",
        "WITH events AS (SELECT 1 AS x) SELECT * FROM events",
        "SELECT sum(if(id > 1, 1, 0)), count_if(id > 1), countif(id > 2) FROM lh.ns.events",
        "SELECT name FROM lh.ns.events WHERE name LIKE 'p$%' ESCAPE '$'",
        "SELECT id FROM lh.ns.events LIMIT 3 OFFSET 1",
        "SELECT id FROM lh.ns.events LIMIT 50%",
        "SELECT 'it''s; DROP TABLE x -- not sql' AS text, '/etc/passwd' AS path, 'getenv' AS f",
    })
    void letsLegitimateReadsThrough(String sql) throws SQLException {
        assertThat(guard.run(sql, ALLOWED, 100).columns()).isNotEmpty();
    }

    @Test
    void returnsTheSameRowsAsTheOriginalQuery() throws SQLException {
        String sql = "WITH c AS (SELECT id, 'it''s' AS text FROM lh.ns.events WHERE id < 4) SELECT * FROM c ORDER BY id DESC;";

        QueryResult result = guard.run(sql, ALLOWED, 10);

        assertThat(result.columns()).containsExactly("id", "text");
        assertThat(result.rows()).containsExactly(
                List.of(3L, "it's"), List.of(2L, "it's"), List.of(1L, "it's"));
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void cutsAtTheLimitAndSaysSo() throws SQLException {
        QueryResult result = guard.run("SELECT id FROM lh.ns.events ORDER BY id", ALLOWED, 3);

        assertThat(result.rows()).containsExactly(List.of(1L), List.of(2L), List.of(3L));
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void exactlyAsManyRowsAsTheLimitIsNotTruncated() throws SQLException {
        QueryResult result = guard.run("SELECT id FROM lh.ns.events", ALLOWED, 10);

        assertThat(result.rows()).hasSize(10);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void theLimitAlsoAppliesWhenTheQueryAsksForMore() throws SQLException {
        QueryResult result = guard.run("SELECT id FROM lh.ns.events ORDER BY id LIMIT 8", ALLOWED, 2);

        assertThat(result.rows()).containsExactly(List.of(1L), List.of(2L));
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void aTrailingCommentCannotSwallowTheLimit() throws SQLException {
        assertThat(guard.run("SELECT id FROM lh.ns.events -- comment", ALLOWED, 2).rows()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void aLimitBelowOneIsAProgrammingError(int maxRows) {
        assertThatThrownBy(() -> guard.guarded("SELECT 1", ALLOWED, maxRows))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLimitDoesNotOverflow() throws SQLException {
        assertThat(guard.guarded("SELECT 1", ALLOWED, Integer.MAX_VALUE)).endsWith("LIMIT 2147483648");
    }

    /** Without this, rule 4 would only be our reading of the engine: it shows the scope hole is real. */
    @Test
    void theEngineReallyReadsTheTableBehindASelfReferencingCte() throws SQLException {
        String sql = "WITH secret AS (SELECT * FROM secret) SELECT * FROM secret";

        List<String> leaked = query(sql, SqlGuardTest::firstColumn);

        assertThat(leaked).containsExactly("hidden");
        assertThatThrownBy(() -> guard.guarded(sql, ALLOWED, 10)).isInstanceOf(Rejected.class);
    }

    /** Rule 4 lets these through because the inner name is the outer CTE: this checks that the engine binds it the same way. */
    @ParameterizedTest
    @ValueSource(strings = {
        "WITH secret AS (SELECT 'cte' AS s) SELECT * FROM (WITH secret AS (SELECT * FROM secret) SELECT * FROM secret)",
        "WITH secret AS (SELECT 'cte' AS s) SELECT * FROM (WITH SECRET AS (SELECT * FROM secret) SELECT * FROM SECRET)",
        "WITH secret AS (SELECT 'cte' AS s), b AS (WITH secret AS (SELECT * FROM secret) SELECT * FROM secret) SELECT * FROM b",
        "WITH secret AS (SELECT 'cte' AS s) SELECT (WITH secret AS (SELECT * FROM secret) SELECT s FROM secret)",
        "WITH secret AS (SELECT 'cte' AS s) SELECT * FROM (WITH secret AS MATERIALIZED (SELECT * FROM secret) FROM secret)",
    })
    void aNestedCteWithAnOuterNameNeverReachesTheRealTable(String sql) throws SQLException {
        assertThat(guard.run(sql, ALLOWED, 10).rows()).containsExactly(List.of("cte"));
    }

    @Test
    void theTableListComesFromTheCaller() throws SQLException {
        List<TableName> onlyTheView = List.of(TableName.of("at_snapshot"));
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TEMP VIEW at_snapshot AS SELECT 1 AS x");
        }

        assertThat(guard.run("SELECT x FROM at_snapshot", onlyTheView, 1).rows()).containsExactly(List.of(1));
        assertThatThrownBy(() -> guard.guarded("SELECT id FROM lh.ns.events", onlyTheView, 1))
                .isInstanceOfSatisfying(Rejected.class, e -> assertThat(e.rule()).isEqualTo(Rule.TABLE));
        assertThatThrownBy(() -> guard.guarded("SELECT x FROM temp.main.at_snapshot", onlyTheView, 1))
                .isInstanceOfSatisfying(Rejected.class, e -> assertThat(e.rule()).isEqualTo(Rule.TABLE));
    }

    @Test
    void anUnknownFunctionIsNamedInTheError() {
        assertThatThrownBy(() -> guard.guarded("SELECT getenv('HOME')", ALLOWED, 1))
                .isInstanceOf(Rejected.class)
                .hasMessage("Function not allowed: getenv");
    }

    @Test
    void anUnknownTableIsNamedInTheError() {
        assertThatThrownBy(() -> guard.guarded("SELECT * FROM '/etc/passwd'", ALLOWED, 1))
                .isInstanceOf(Rejected.class)
                .hasMessage("Table not allowed: /etc/passwd");
    }

    @Test
    void aTableFunctionIsNamedInTheError() {
        assertThatThrownBy(() -> guard.guarded("SELECT * FROM read_csv('x')", ALLOWED, 1))
                .isInstanceOf(Rejected.class)
                .hasMessage("Table function read_csv not allowed");
    }

    @Test
    void aMalformedTableNameIsAProgrammingError() {
        assertThatThrownBy(() -> TableName.of("ns.t")).isInstanceOf(IllegalArgumentException.class);
    }

    /** Too long must be settled in Java: in the native parser too much nesting is a crash, not an error. */
    @Test
    void sqlOverTheLimitNeverReachesTheEngine() {
        SqlGuard noEngine = new SqlGuard(new SqlGuard.Engine() {
            @Override
            public <T> T query(String ignored, LakehouseSession.RowReader<T> reader) {
                throw new AssertionError("Reached the engine");
            }
        });
        String sql = "SELECT 1 -- ";

        assertThatThrownBy(() -> noEngine.guarded(sql + "x".repeat(SqlGuard.MAX_SQL_LENGTH - sql.length() + 1), ALLOWED, 1))
                .isInstanceOfSatisfying(Rejected.class, e -> assertThat(e.rule()).isEqualTo(Rule.SYNTAX))
                .hasMessage("The SQL has 4001 characters; the limit is 4000");
    }

    @Test
    void sqlRightAtTheLimitIsJudgedAsUsual() throws SQLException {
        String sql = "SELECT 1 -- ";

        QueryResult result = guard.run(sql + "x".repeat(SqlGuard.MAX_SQL_LENGTH - sql.length()), ALLOWED, 1);

        assertThat(result.rows()).hasToString("[[1]]");
    }

    /**
     * The worst SQL the limit lets through, on a stack much smaller than the JVM's default. If the margin were wrong this
     * would not fail: it would kill the test JVM. Whether it is refused does not matter, only that it comes back.
     */
    @ParameterizedTest
    @MethodSource("worstAllowed")
    void theWorstSqlAllowedSurvivesASmallStack(String sql) throws InterruptedException {
        assertThat(sql.length()).isBetween(SqlGuard.MAX_SQL_LENGTH - 20, SqlGuard.MAX_SQL_LENGTH);
        boolean[] cameBack = {false};
        Thread thread = new Thread(null, () -> {
            try {
                guard.guarded(sql, ALLOWED, 1);
            } catch (Rejected | SQLException e) {
                // Either is an answer.
            }
            cameBack[0] = true;
        }, "small-stack", 256 * 1024);

        thread.start();
        thread.join();

        assertThat(cameBack[0]).isTrue();
    }

    private static Stream<String> worstAllowed() {
        int limit = SqlGuard.MAX_SQL_LENGTH;
        return Stream.of(
                nested("FROM(", "SELECT 1", ")", limit),
                nested("SELECT * FROM(", "SELECT 1", ")", limit),
                nested("(", "SELECT 1", ")", limit),
                nested("SELECT abs(", "1", ")", limit),
                "SELECT 1 FROM a" + ",a".repeat((limit - 15) / 2),
                "SELECT 1 FROM a" + " JOIN a ON true".repeat((limit - 15) / 15),
                "SELECT 1" + " UNION ALL SELECT 1".repeat((limit - 8) / 19),
                "SELECT " + "NOT ".repeat((limit - 11) / 4) + "true");
    }

    private static String nested(String open, String core, String close, int limit) {
        int levels = (limit - core.length()) / (open.length() + close.length());
        return open.repeat(levels) + core + close.repeat(levels);
    }

    /** Against the engine's own catalogue: a typo in the list would pass unnoticed, and a table function must not be in. */
    @Test
    void everyAllowedFunctionExistsAndIsNotATableFunction() throws SQLException {
        Set<String> known = new HashSet<>(query(
                "SELECT DISTINCT function_name FROM duckdb_functions()"
                        + " WHERE function_type NOT IN ('table', 'table_macro', 'pragma')",
                SqlGuardTest::firstColumn));

        assertThat(SqlGuard.FUNCTIONS).isSubsetOf(known);
    }

    private static List<String> firstColumn(ResultSet rows) throws SQLException {
        List<String> values = new ArrayList<>();
        while (rows.next()) {
            values.add(rows.getString(1));
        }
        return values;
    }
}
