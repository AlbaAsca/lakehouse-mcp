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
class StackDiagTest {

    private static final int STACK_KB = 512;
    private static final int DEEPEST_CTE_CHAIN = 17;
    private static final int DEEPEST_EXCEPT_CHAIN = 45;
    private static final List<TableName> ALLOWED = List.of(TableName.of("memory.main.a"));

    private static Connection connection;

    private static String chain(int joins, String join) {
        return "SELECT 1 FROM a" + join.repeat(joins);
    }

    /** One level past each of these is rejected: see {@link #oneLevelMoreIsRejected}. */
    private static Map<String, String> deepestAllowed() {
        Map<String, String> shapes = new LinkedHashMap<>();
        shapes.put("SELECT * FROM (".repeat(30) + "SELECT 1" + ")".repeat(30), "[[1]]");
        shapes.put("SELECT " + "abs(".repeat(30) + "1" + ")".repeat(30), "[[1]]");
        shapes.put("SELECT 1" + "+1".repeat(30), "[[31]]");
        shapes.put(chain(90, ",a"), "[[1]]");
        shapes.put(chain(90, " JOIN a ON 1=1"), "[[1]]");
        // Long, but flat in the tree: only the length limit holds these two back.
        shapes.put("SELECT 1" + " UNION ALL SELECT 1".repeat(209), "[[1]]");
        shapes.put("SELECT 1" + " UNION SELECT 1".repeat(265), "[[1]]");
        shapes.put("SELECT 1" + " EXCEPT SELECT 2".repeat(DEEPEST_EXCEPT_CHAIN), "[[1]]");
        shapes.put(cteChain(DEEPEST_CTE_CHAIN), "[[1]]");
        return shapes;
    }

    /** Each CTE reads the one before it: the tree stays shallow, but binding it does not. */
    private static String cteChain(int links) {
        StringBuilder sql = new StringBuilder("WITH c0 AS (SELECT 1 x)");
        for (int last = 0; last < links; last++) {
            sql.append(",c").append(last + 1).append(" AS (SELECT x FROM c").append(last).append(')');
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

    // TEMP-BEGIN
    private static String family(String name, int n) {
        return switch (name) {
            case "except" -> "SELECT 1" + " EXCEPT SELECT 2".repeat(n);
            case "intersect" -> "SELECT 1" + " INTERSECT SELECT 1".repeat(n);
            case "exceptall" -> "SELECT 1" + " EXCEPT ALL SELECT 2".repeat(n);
            case "unionparen" -> "(".repeat(n) + "SELECT 1" + " UNION SELECT 1)".repeat(n);
            case "case" -> "SELECT " + "CASE WHEN true THEN ".repeat(n) + "1" + " END".repeat(n);
            case "scalar" -> "SELECT " + "(SELECT ".repeat(n) + "1" + ")".repeat(n);
            case "in" -> "SELECT 1 WHERE 1 IN (SELECT 1 WHERE 1 IN (".repeat(n) + "SELECT 1" + "))".repeat(n);
            case "exists" -> "SELECT 1 WHERE EXISTS (".repeat(n) + "SELECT 1" + ")".repeat(n);
            case "joinparen" -> "SELECT 1 FROM " + "a JOIN (".repeat(n) + "a" + ") ON 1=1".repeat(n);
            case "joinsub" -> "SELECT 1 FROM a" + " JOIN (SELECT 1 x) ON 1=1".repeat(n);
            case "cast" -> "SELECT 1" + "::INT".repeat(n);
            case "neg" -> "SELECT " + "-(".repeat(n) + "1" + ")".repeat(n);
            case "coalesce" -> "SELECT " + "coalesce(".repeat(n) + "1" + ")".repeat(n);
            case "window" -> "SELECT " + "sum(".repeat(n) + "1" + ") OVER ()".repeat(n);
            case "lateral" -> "SELECT 1 FROM a" + ", LATERAL (SELECT 1)".repeat(n);
            case "cte" -> cteChain(n);
            case "ctesetop" -> {
                StringBuilder sql = new StringBuilder("WITH c0 AS (SELECT 1 x)");
                for (int i = 0; i < n; i++) {
                    sql.append(",c").append(i + 1).append(" AS (SELECT x FROM c").append(i).append(" EXCEPT SELECT 2)");
                }
                yield sql.append(" SELECT x FROM c").append(n).toString();
            }
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static void measureChild(String[] args) throws Exception {
        createTable();
        SqlGuard guard = new SqlGuard(StackDiagTest::query);
        String sql = family(args[0], Integer.parseInt(args[1]));
        String[] answer = {"none"};
        Thread thread = new Thread(null, () -> {
            try {
                guard.run(sql, ALLOWED, 1);
                answer[0] = "ran";
            } catch (Throwable e) {
                answer[0] = e.getClass().getSimpleName();
            }
        }, "m", Integer.parseInt(args[2]) * 1024L);
        thread.start();
        thread.join();
        System.out.println("ANSWER " + answer[0]);
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    private static void sequenceChild(int stackKb, boolean sameThread) throws Exception {
        createTable();
        SqlGuard guard = new SqlGuard(StackDiagTest::query);
        List<String> shapes = new ArrayList<>(deepestAllowed().keySet());
        Runnable all = () -> {
            for (int i = 0; i < shapes.size(); i++) {
                System.out.println("START " + i);
                System.out.flush();
                try {
                    guard.run(shapes.get(i), ALLOWED, 1);
                } catch (Exception e) {
                    System.out.println("ERR " + e.getMessage());
                }
            }
        };
        if (sameThread) {
            Thread thread = new Thread(null, all, "seq", stackKb * 1024L);
            thread.start();
            thread.join();
        } else {
            for (int i = 0; i < shapes.size(); i++) {
                int index = i;
                Thread thread = new Thread(null, () -> {
                    System.out.println("START " + index);
                    System.out.flush();
                    try {
                        guard.run(shapes.get(index), ALLOWED, 1);
                    } catch (Exception e) {
                        System.out.println("ERR " + e.getMessage());
                    }
                }, "seq", stackKb * 1024L);
                thread.start();
                thread.join();
            }
        }
        System.out.println("DONE");
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    @Test
    void tempSequence() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String javaBin = System.getProperty("java.home") + "/bin/java";
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        Map<String, java.util.concurrent.Future<String>> futures = new java.util.TreeMap<>();
        for (int stack : List.of(512, 1024, 2048, 8192)) {
            for (String mode : List.of("fresh", "same")) {
                for (int rep = 0; rep < 10; rep++) {
                    futures.put(String.format("%04d %s %02d", stack, mode, rep), pool.submit(() -> {
                        Path log = Files.createTempFile("s", ".log");
                        String command = "ulimit -c 0; exec '" + javaBin + "' -cp '" + classpath + "' "
                                + StackDiagTest.class.getName() + " " + stack + " " + mode;
                        Process process = new ProcessBuilder("sh", "-c", command)
                                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                        if (!process.waitFor(90, TimeUnit.SECONDS)) {
                            process.destroyForcibly().waitFor();
                            return "TIMEOUT";
                        }
                        List<String> lines = Files.readAllLines(log, StandardCharsets.ISO_8859_1);
                        String lastStart = lines.stream().filter(l -> l.startsWith("START")).reduce((a, b) -> b).orElse("-");
                        String extra = process.exitValue() == 0 ? ""
                                : " TAIL=" + String.join(" | ", lines.subList(Math.max(0, lines.size() - 3), lines.size()));
                        return "exit=" + process.exitValue() + " last=" + lastStart + extra;
                    }));
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, java.util.concurrent.Future<String>> entry : futures.entrySet()) {
            out.add(entry.getKey() + " " + entry.getValue().get());
            Files.write(Path.of("target", "stack-sequence.txt"), out);
        }
        pool.shutdown();
    }

    @Test
    void tempMeasure() throws Exception {
        createTable();
        SqlGuard guard = new SqlGuard(StackDiagTest::query);
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String javaBin = System.getProperty("java.home") + "/bin/java";
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        Map<String, java.util.concurrent.Future<String>> futures = new java.util.TreeMap<>();
        for (String name : List.of("except", "intersect", "exceptall", "unionparen", "case", "scalar", "in", "exists",
                "joinparen", "joinsub", "cast", "neg", "coalesce", "window", "lateral", "cte", "ctesetop")) {
            int deepest = 0;
            for (int n = 1; n <= 300; n++) {
                String sql = family(name, n);
                if (sql.length() > SqlGuard.MAX_SQL_LENGTH) {
                    break;
                }
                try {
                    guard.guarded(sql, ALLOWED, 1);
                    deepest = n;
                } catch (Rejected | SQLException e) {
                    if (deepest > 0) {
                        break;
                    }
                }
            }
            int levels = deepest;
            for (int stack : List.of(256, 384, 512, 768, 1024, 2048)) {
                futures.put(String.format("%s n=%d stack=%04d", name, levels, stack), pool.submit(() -> {
                    Path log = Files.createTempFile("m", ".log");
                    String command = "ulimit -c 0; exec '" + javaBin + "' -cp '" + classpath + "' "
                            + StackDiagTest.class.getName() + " " + name + " " + levels + " " + stack;
                    Process process = new ProcessBuilder("sh", "-c", command)
                            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                    if (!process.waitFor(90, TimeUnit.SECONDS)) {
                        process.destroyForcibly().waitFor();
                        return "TIMEOUT";
                    }
                    if (process.exitValue() != 0) {
                        return "CRASH" + process.exitValue();
                    }
                    return Files.readAllLines(log, StandardCharsets.ISO_8859_1).stream()
                            .filter(l -> l.startsWith("ANSWER ")).findFirst().orElse("?");
                }));
            }
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, java.util.concurrent.Future<String>> entry : futures.entrySet()) {
            lines.add(entry.getKey() + " " + entry.getValue().get());
            Files.write(Path.of("target", "stack-measure.txt"), lines);
        }
        pool.shutdown();
        connection.close();
    }
    // TEMP-END

    /** The JVM of its own: prints one answer per shape, in order, until it ends or dies. */
    public static void main(String[] args) throws Exception {
        if (args.length == 3) {
            measureChild(args);
            return;
        }
        if (args.length == 2) {
            sequenceChild(Integer.parseInt(args[0]), args[1].equals("same"));
            return;
        }
        createTable();
        SqlGuard guard = new SqlGuard(StackDiagTest::query);
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

        Process process = new ProcessBuilder(java, "-cp", classpath, StackDiagTest.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        boolean ended = process.waitFor(3, TimeUnit.MINUTES);
        if (!ended) {
            process.destroyForcibly().waitFor();
        }

        List<String> lines = Files.readAllLines(log, StandardCharsets.ISO_8859_1);
        List<String> answers = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("ANSWER ")) {
                answers.add(line.substring(7));
            }
        }
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
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void oneLevelMoreIsRejected(int shape) throws SQLException {
        String sql = List.of(
                        "SELECT * FROM (".repeat(31) + "SELECT 1" + ")".repeat(31),
                        "SELECT " + "abs(".repeat(31) + "1" + ")".repeat(31),
                        "SELECT 1" + "+1".repeat(31),
                        chain(91, ",a"),
                        chain(91, " JOIN a ON 1=1"),
                        cteChain(DEEPEST_CTE_CHAIN + 1),
                        "SELECT 1" + " EXCEPT SELECT 2".repeat(DEEPEST_EXCEPT_CHAIN + 1),
                        tablesSpreadOverCtes())
                .get(shape);
        createTable();
        SqlGuard guard = new SqlGuard(StackDiagTest::query);
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
        SqlGuard guard = new SqlGuard(StackDiagTest::query);
        try {
            assertThatThrownBy(() -> guard.guarded(chain(400, ",a"), ALLOWED, 1)).isInstanceOf(Rejected.class);
        } finally {
            connection.close();
        }
    }
}
