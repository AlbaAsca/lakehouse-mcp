package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.albaasca.lakehouse.mcp.SqlGuard.Rejected;
import io.github.albaasca.lakehouse.mcp.SqlGuard.Rule;
import io.github.albaasca.lakehouse.mcp.SqlGuard.TableName;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The deepest queries the validator lets through, run for real and not only parsed, on half the JVM's default stack. They
 * run in a JVM of their own: too little stack does not fail, it kills the process, and in this module's test JVM that
 * stopped the whole build before the integration tests (T-127). Hand-worked results: nesting must not change the answer.
 */
class SqlGuardStackTest {

    private static final int STACK_KB = 512;
    private static final List<TableName> ALLOWED = List.of(TableName.of("memory.main.a"));

    private static Connection connection;

    private static String chain(int joins, String join) {
        return "SELECT 1 FROM a" + join.repeat(joins);
    }

    /** One level past the depth-limited ones is rejected: see {@link #oneLevelMoreIsRejected}. */
    private static Map<String, String> deepestAllowed() {
        Map<String, String> shapes = new LinkedHashMap<>();
        shapes.put("SELECT * FROM (".repeat(23) + "SELECT 1" + ")".repeat(23), "[[1]]");
        shapes.put("SELECT " + "abs(".repeat(23) + "1" + ")".repeat(23), "[[1]]");
        shapes.put("SELECT 1" + "+1".repeat(23), "[[24]]");
        shapes.put(chain(70, ",a"), "[[1]]");
        shapes.put(chain(70, " JOIN a ON 1=1"), "[[1]]");
        shapes.put("SELECT 1 WHERE EXISTS (".repeat(23) + "SELECT 1" + ")".repeat(23), "[[1]]");
        // Long, but flat in the tree: only the length limit holds these two back.
        shapes.put("SELECT 1" + " UNION ALL SELECT 1".repeat(210), "[[1]]");
        shapes.put("SELECT 1" + " UNION SELECT 1".repeat(266), "[[1]]");
        // The ones that need the most stack per level, the reason for the limit's value.
        shapes.put("SELECT 1" + " EXCEPT SELECT 2".repeat(35), "[[1]]");
        shapes.put("SELECT 1" + " INTERSECT SELECT 1".repeat(35), "[[1]]");
        shapes.put(cteChain(13, ""), "[[1]]");
        shapes.put(cteChain(9, " EXCEPT SELECT 2"), "[[1]]");
        return shapes;
    }

    /** Each CTE reads the one before it: the tree stays shallow, but binding it does not. */
    private static String cteChain(int links, String suffix) {
        StringBuilder sql = new StringBuilder("WITH c0 AS (SELECT 1 x)");
        for (int last = 0; last < links; last++) {
            sql.append(",c").append(last + 1).append(" AS (SELECT x FROM c").append(last).append(suffix).append(')');
        }
        return sql.append(" SELECT x FROM c").append(links).toString();
    }

    /** Every CTE is shallow enough alone: only counting each reference adds them up. */
    private static String tablesSpreadOverCtes() {
        StringBuilder sql = new StringBuilder("WITH c0 AS (SELECT 1 x FROM a" + ",a".repeat(50) + ")");
        for (int last = 0; last < 7; last++) {
            sql.append(",c").append(last + 1).append(" AS (SELECT 1 x FROM c").append(last).append(",a".repeat(50)).append(')');
        }
        return sql.append(" SELECT x FROM c7").toString();
    }

    private static <T> T query(String sql, LakehouseSession.RowReader<T> reader) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            return reader.read(rows);
        }
    }

    private static void createTable() throws SQLException {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE a AS SELECT 1 AS id");
        }
    }

    /** The JVM of its own: prints one answer per shape, in order, until it ends or dies. */
    public static void main(String[] args) throws Exception {
        createTable();
        SqlGuard guard = new SqlGuard(SqlGuardStackTest::query);
        for (String sql : deepestAllowed().keySet()) {
            Object[] answer = {null};
            Thread thread = new Thread(null, () -> {
                try {
                    answer[0] = guard.run(sql, ALLOWED, 1).rows();
                } catch (Rejected | SQLException e) {
                    answer[0] = e.getMessage();
                }
            }, "half-the-default-stack", STACK_KB * 1024L);
            thread.start();
            thread.join();
            System.out.println("ANSWER " + String.valueOf(answer[0]).replace('\n', ' '));
            System.out.flush();
        }
        Runtime.getRuntime().halt(0);
    }

    @Test
    void theDeepestAllowedQueriesRunOnHalfTheDefaultStack() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Path log = Files.createTempFile("stack-margin", ".log");
        Process process = new ProcessBuilder(java, "-cp", classpath, SqlGuardStackTest.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        boolean ended = process.waitFor(3, TimeUnit.MINUTES);
        if (!ended) {
            process.destroyForcibly().waitFor();
        }
        List<String> answers = new ArrayList<>();
        for (String line : Files.readAllLines(log, StandardCharsets.ISO_8859_1)) {
            if (line.startsWith("ANSWER ")) {
                answers.add(line.substring(7));
            }
        }
        Files.delete(log);
        assertThat(ended).as("the JVM ended in time: %s", answers).isTrue();
        assertThat(process.exitValue()).as("exit code after %s answers", answers.size()).isZero();
        assertThat(answers).containsExactlyElementsOf(deepestAllowed().values());
    }

    @Test
    void theShapesFitTheLengthLimit() {
        assertThat(deepestAllowed().keySet()).allSatisfy(sql -> assertThat(sql.length()).isLessThanOrEqualTo(SqlGuard.MAX_SQL_LENGTH));
    }

    /** Parsed only, so these are safe here: the validator stops them before they run. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
    void oneLevelMoreIsRejected(int shape) throws SQLException {
        String sql = List.of(
                        "SELECT * FROM (".repeat(24) + "SELECT 1" + ")".repeat(24),
                        "SELECT " + "abs(".repeat(24) + "1" + ")".repeat(24),
                        "SELECT 1" + "+1".repeat(24),
                        chain(71, ",a"),
                        chain(71, " JOIN a ON 1=1"),
                        "SELECT 1 WHERE EXISTS (".repeat(24) + "SELECT 1" + ")".repeat(24),
                        "SELECT 1" + " EXCEPT SELECT 2".repeat(36),
                        cteChain(14, ""),
                        cteChain(10, " EXCEPT SELECT 2"),
                        tablesSpreadOverCtes())
                .get(shape);
        createTable();
        SqlGuard guard = new SqlGuard(SqlGuardStackTest::query);
        try {
            assertThatThrownBy(() -> guard.guarded(sql, ALLOWED, 1))
                    .isInstanceOfSatisfying(Rejected.class, e -> assertThat(e.rule()).isEqualTo(Rule.SYNTAX))
                    .hasMessage("The query is too complex to validate");
        } finally {
            connection.close();
        }
    }

    /** Measured before the limit: this one was accepted, and running it killed a JVM with its default stack of 1 MB. */
    @Test
    void whatKilledTheProcessIsRejected() throws SQLException {
        createTable();
        SqlGuard guard = new SqlGuard(SqlGuardStackTest::query);
        try {
            assertThatThrownBy(() -> guard.guarded(chain(400, ",a"), ALLOWED, 1)).isInstanceOf(Rejected.class);
        } finally {
            connection.close();
        }
    }
}
