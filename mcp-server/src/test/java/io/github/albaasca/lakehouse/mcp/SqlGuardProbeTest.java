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
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The tester's probes, independent of {@link SqlGuardTest}: expected values worked out by hand, the rebuilt SQL compared
 * with the original row by row, and attacks that are shown to work on the bare engine before the guard is asked.
 */
class SqlGuardProbeTest {

    private static final List<TableName> ALLOWED =
            List.of(TableName.of("lh.ns.events"), TableName.of("memory.main.views_only"));

    private static Connection connection;
    private static SqlGuard guard;

    @BeforeAll
    static void createTables() throws SQLException {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        guard = new SqlGuard(SqlGuardProbeTest::query);
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

    /** Worked out by hand, without running them: the rebuilt SQL must not move a parenthesis or lose a quote. */
    private static Stream<Arguments> handWorkedResults() {
        return Stream.of(
                arguments("SELECT -(1+2)*3", "[[-9]]"),
                arguments("SELECT 2-(3-4)", "[[3]]"),
                arguments("SELECT 1-1-1", "[[-1]]"),
                arguments("SELECT 2*(3+4)", "[[14]]"),
                arguments("SELECT (2+3)*4", "[[20]]"),
                arguments("SELECT 7%(4%3)", "[[0]]"),
                arguments("SELECT 10/(5/2)", "[[4.0]]"),
                arguments("SELECT (2^3)^2", "[[64.0]]"),
                arguments("SELECT (true OR false) AND false", "[[false]]"),
                arguments("SELECT true OR false AND false", "[[true]]"),
                arguments("SELECT NOT (true AND false)", "[[true]]"),
                arguments("SELECT NOT true AND false", "[[false]]"),
                arguments("SELECT (1 = 2) IS NULL", "[[false]]"),
                arguments("SELECT 1 + CASE WHEN false THEN 1 ELSE 2 END", "[[3]]"),
                arguments("SELECT (1+1)::VARCHAR", "[[2]]"),
                arguments("SELECT 'a''b'", "[[a'b]]"),
                arguments("SELECT 'ñ€😀'", "[[ñ€😀]]"),
                arguments("SELECT '/* not a comment */'", "[[/* not a comment */]]"),
                arguments("SELECT '-- not a comment'", "[[-- not a comment]]"),
                arguments("SELECT $x$;DROP TABLE y$x$", "[[;DROP TABLE y]]"),
                arguments("SELECT regexp_extract('a.b', '\\.b')", "[[.b]]"),
                arguments("SELECT 1 UNION ALL (SELECT 2 EXCEPT SELECT 2)", "[[1]]"),
                arguments("(SELECT 1 UNION ALL SELECT 2) INTERSECT SELECT 2", "[[2]]"),
                arguments("SELECT sum(id) FROM lh.ns.events", "[[55]]"),
                arguments("SELECT id FROM lh.ns.events WHERE id > 8.5 ORDER BY id", "[[9], [10]]"),
                arguments("SELECT kind, count(*) c FROM lh.ns.events GROUP BY 1 HAVING c > 1 ORDER BY 1", "[[0, 5], [1, 5]]"),
                arguments("SELECT string_agg(name, ',' ORDER BY id DESC) FROM lh.ns.events WHERE id < 4", "[[p3,p2,p1]]"),
                arguments("SELECT id FROM lh.ns.events ORDER BY id LIMIT 2 OFFSET 7", "[[8], [9]]"),
                arguments("SELECT id FROM lh.ns.events ORDER BY kind DESC, id DESC LIMIT 2", "[[9], [7]]"),
                arguments("SELECT id, sum(id) OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING AND CURRENT ROW) s"
                        + " FROM lh.ns.events ORDER BY id LIMIT 3", "[[1, 1], [2, 3], [3, 5]]"),
                arguments("SELECT a.id, v.id AS other FROM lh.ns.events a LEFT JOIN views_only v ON a.id = v.id"
                        + " ORDER BY a.id LIMIT 2", "[[1, 1], [2, null]]"));
    }

    @ParameterizedTest
    @MethodSource("handWorkedResults")
    void theRebuiltSqlGivesTheHandWorkedResult(String sql, String expected) throws SQLException {
        assertThat(guard.run(sql, ALLOWED, 50).rows()).hasToString(expected);
    }

    /** The original, on the bare engine, is the reference: same column names, same rows in the same order. */
    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT 2^3^2", "SELECT -2^2", "SELECT - 2 ** 2", "SELECT -1::INT", "SELECT 'a' || 'b' LIKE 'a%'",
        "SELECT 1 = (2 IS NULL)", "SELECT 1 BETWEEN 0 AND 2 AND true", "SELECT (1 BETWEEN 0 AND 2) = false",
        "SELECT 1 AS \"a b\"", "SELECT 1 AS \"a\"\"b\"", "SELECT 1 AS \"ñandú\"", "SELECT 1.50", "SELECT 1e-3",
        "SELECT 12345678901234567890", "SELECT CAST('1.5' AS DECIMAL(3,1)) * 2", "SELECT 'line\nbreak'",
        "SELECT count(*) FROM lh.ns.events", "SELECT upper(name) FROM lh.ns.events ORDER BY 1",
        "SELECT id+1 FROM lh.ns.events ORDER BY 1", "SELECT id::DOUBLE FROM lh.ns.events ORDER BY 1",
        "SELECT NULL::INT AS x UNION ALL SELECT 1 ORDER BY x NULLS FIRST",
        "SELECT NULL::INT AS x UNION ALL SELECT 1 ORDER BY x NULLS LAST",
        "SELECT NULL::INT AS x UNION ALL SELECT 1 ORDER BY x DESC",
        "SELECT DISTINCT ON (kind) kind, id FROM lh.ns.events ORDER BY kind, id",
        "(SELECT id FROM lh.ns.events ORDER BY id LIMIT 1) UNION ALL (SELECT id FROM lh.ns.events ORDER BY id DESC LIMIT 1)",
        "SELECT sum(id) OVER w, id FROM lh.ns.events WINDOW w AS (ORDER BY id) ORDER BY id",
        "SELECT lag(id, 2, 0) OVER (ORDER BY id) FROM lh.ns.events ORDER BY 1",
        "SELECT first_value(id IGNORE NULLS) OVER (ORDER BY id) FROM lh.ns.events ORDER BY 1",
        "SELECT kind, count(*) FROM lh.ns.events GROUP BY CUBE (kind) ORDER BY 1 NULLS LAST",
        "SELECT id = ANY (SELECT id FROM views_only) FROM lh.ns.events ORDER BY id",
        "SELECT id > ALL (SELECT id FROM views_only) FROM lh.ns.events ORDER BY id",
        "SELECT 1 IS DISTINCT FROM NULL", "SELECT 1 IS NOT DISTINCT FROM NULL",
        "SELECT a.id FROM lh.ns.events a ANTI JOIN views_only v ON a.id = v.id ORDER BY 1",
        "SELECT a.id, l.x FROM views_only a, LATERAL (SELECT a.id + 1 AS x) l",
        "SELECT * FROM (VALUES (1, 'a'), (2, 'b')) v(x, y) ORDER BY x",
        "SELECT e.id FROM lh.ns.events AS e(id, k) ORDER BY 1",
        "SELECT date_trunc('month', ts)::DATE, date_diff('day', DATE '2019-10-01', ts::DATE) FROM lh.ns.events ORDER BY ts",
        "SELECT INTERVAL '2' MONTH + DATE '2019-10-01'", "SELECT DATE '2019-10-31' - INTERVAL (1+1) DAY",
        "WITH c(a, b) AS (SELECT 1, 2) SELECT b, a FROM c", "SELECT (WITH c AS (SELECT 1 x) SELECT x FROM c)",
        "SELECT * EXCLUDE (ts) REPLACE (id + 1 AS id) FROM lh.ns.events ORDER BY id",
        "SELECT COLUMNS('^id$') FROM lh.ns.events ORDER BY 1",
        "SELECT count(*) FROM lh.ns.events WHERE ts >= '2019-10-05'",
    })
    void theRebuiltSqlMatchesTheOriginalColumnByColumnAndRowByRow(String sql) throws SQLException {
        QueryResult original = query(sql, SqlGuard.limitedTo(50));

        QueryResult guarded = guard.run(sql, ALLOWED, 50);

        assertThat(guarded.columns()).isEqualTo(original.columns());
        assertThat(guarded.rows()).hasToString(original.rows().toString());
    }

    /** Today's behaviour, fixed so that a change is noticed: the row limit wrapper renames repeated columns. */
    @Test
    void theWrapperRenamesRepeatedColumnsAndKeepsTheValues() throws SQLException {
        QueryResult result = guard.run("SELECT id, id + 1 AS id FROM lh.ns.events ORDER BY 1 LIMIT 1", ALLOWED, 5);

        assertThat(result.columns()).containsExactly("id", "id_1");
        assertThat(result.rows()).hasToString("[[1, 2]]");
    }

    /** Each one reads the secret on the bare engine, so the attack is real and the rejection is not vacuous. */
    @ParameterizedTest
    @ValueSource(strings = {
        "TABLE secret",
        "FROM secret",
        "VALUES ((SELECT s FROM secret))",
        "SELECT * FROM (FROM secret)",
        "SELECT * FROM secret, LATERAL (SELECT 1)",
        "SELECT s FROM lh.ns.events POSITIONAL JOIN secret",
        "(WITH secret AS (SELECT 1 AS s) SELECT s::VARCHAR AS s FROM secret WHERE false) UNION ALL SELECT * FROM secret",
    })
    void whatLeaksOnTheBareEngineIsRejected(String sql) throws SQLException {
        assertThat(query(sql, SqlGuardProbeTest::text)).contains("hidden");

        assertThatThrownBy(() -> guard.guarded(sql, ALLOWED, 10))
                .isInstanceOfSatisfying(Rejected.class, e -> assertThat(e.rule()).isEqualTo(Rule.TABLE));
    }

    private static Stream<Arguments> moreAttacks() {
        return Stream.of(
                arguments("TABLE lh.ns.secret", Rule.TABLE),
                arguments("SELECT 1 WHERE 'hidden' IN (TABLE secret)", Rule.TABLE),
                arguments("SELECT * FROM lh.ns.events ASOF JOIN secret ON true", Rule.TABLE),
                arguments("SELECT * FROM memory.views_only", Rule.TABLE),
                arguments("UNPIVOT secret ON s INTO NAME n VALUE v", Rule.NODE),
                arguments("PIVOT secret ON s", Rule.NOT_A_SELECT),
                arguments("SELECT * FROM range(10)", Rule.NODE),
                arguments("SELECT 'a' COLLATE \"x\"", Rule.NODE),
                arguments("SELECT [getenv('HOME')]", Rule.FUNCTION),
                arguments("SELECT {'a': getenv('HOME')}", Rule.FUNCTION),
                arguments("SELECT [getenv('HOME') for x in [1]]", Rule.FUNCTION),
                arguments("SELECT MAP {'a': getenv('HOME')}", Rule.FUNCTION),
                arguments("SELECT COLUMNS(getenv('HOME'))", Rule.FUNCTION),
                arguments("SELECT * LIKE getenv('HOME') FROM lh.ns.events", Rule.FUNCTION),
                arguments("SELECT id FROM lh.ns.events OFFSET getenv('X')", Rule.FUNCTION),
                arguments("SELECT U&\"getenv\"('HOME')", Rule.FUNCTION),
                arguments("SELECT nextval('s')", Rule.FUNCTION),
                arguments("SELECT read_text('/etc/passwd')", Rule.FUNCTION),
                arguments("/* unterminated SELECT 1", Rule.SYNTAX),
                arguments("SELECT 'unterminated", Rule.SYNTAX),
                arguments("SELECT " + "(".repeat(20000) + "1" + ")".repeat(20000), Rule.SYNTAX),
                arguments("SELECT " + "NOT ".repeat(20000) + "true", Rule.SYNTAX),
                arguments("SELECT 1" + "+1".repeat(20000), Rule.SYNTAX),
                arguments("SELECT * FROM (".repeat(2000) + "SELECT 1" + ")".repeat(2000), Rule.SYNTAX));
    }

    @ParameterizedTest
    @MethodSource("moreAttacks")
    void rejectsTheseToo(String sql, Rule rule) {
        assertThatThrownBy(() -> guard.guarded(sql, ALLOWED, 10))
                .isInstanceOfSatisfying(Rejected.class, e -> assertThat(e.rule()).isEqualTo(rule));
    }

    /** A column name with no column behind it does not fall back to the function of the same name. */
    @ParameterizedTest
    @ValueSource(strings = {"getenv", "getvariable", "current_setting", "version", "which_secret", "duckdb_settings"})
    void aBareNameDoesNotCallTheFunction(String name) {
        assertThatThrownBy(() -> guard.run("SELECT " + name, ALLOWED, 1))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("was not found");
    }

    /** Unguarded, this does not fail: it kills the JVM (segmentation fault, 42 KB of SQL). */
    @Test
    void deepNestingIsRejectedBeforeItReachesTheNativeParser() {
        String sql = "FROM (".repeat(6000) + "SELECT 1" + ")".repeat(6000);

        assertThatThrownBy(() -> guard.guarded(sql, ALLOWED, 10)).isInstanceOf(Rejected.class);
    }

    /** Run for real, a single cell of 2 GB gets the process killed despite the memory limit. */
    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT lpad('a', 2000000000, 'x')",
        "SELECT rpad('a', 2000000000, 'x')",
        "SELECT length(replace(replace('xxxx', 'x', 'xxxx'), 'x', 'xxxx'))",
        "SELECT length(regexp_replace(regexp_replace('xxxx', 'x', 'xxxx', 'g'), 'x', 'xxxx', 'g'))",
    })
    void aSingleCellCannotTakeTheProcessDown(String sql) {
        assertThatThrownBy(() -> guard.guarded(sql, ALLOWED, 10)).isInstanceOf(Rejected.class);
    }

    private static List<String> text(ResultSet rows) throws SQLException {
        List<String> values = new ArrayList<>();
        int width = rows.getMetaData().getColumnCount();
        while (rows.next()) {
            for (int i = 1; i <= width; i++) {
                values.add(String.valueOf(rows.getObject(i)));
            }
        }
        return values;
    }
}
