package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.albaasca.lakehouse.mcp.SqlGuard.Rejected;
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
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * T-146: named subqueries that read the previous one several times. The validator takes the deepest body, it does not
 * multiply by references, so the work could grow as 3^n for a short text. Each length is run in a JVM of its own until
 * the validator rejects it: the process must end in time, with exit 0, and every answer must be the hand-worked 1.
 */
class SqlGuardFanOutTest {

    private static final int REFERENCES = 3;
    private static final List<TableName> ALLOWED = List.of(TableName.of("memory.main.a"));

    private static Connection connection;

    static String fanOut(int links) {
        StringBuilder sql = new StringBuilder("WITH c0 AS (SELECT 1 x)");
        for (int last = 0; last < links; last++) {
            sql.append(",c").append(last + 1).append(" AS (SELECT 1 x FROM ")
                    .append(String.join(",", java.util.Collections.nCopies(REFERENCES, "c" + last))).append(')');
        }
        return sql.append(" SELECT x FROM c").append(links).toString();
    }

    private static <T> T query(String sql, LakehouseSession.RowReader<T> reader) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            return reader.read(rows);
        }
    }

    /** The JVM of its own: prints one answer per length until the validator rejects one. */
    public static void main(String[] args) throws Exception {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE a AS SELECT 1 AS id");
        }
        SqlGuard guard = new SqlGuard(SqlGuardFanOutTest::query);
        for (int links = 1; links < 200; links++) {
            long start = System.nanoTime();
            try {
                Object rows = guard.run(fanOut(links), ALLOWED, 1).rows();
                System.out.println("ANSWER " + links + " " + rows + " " + (System.nanoTime() - start) / 1_000_000 + "ms");
            } catch (Rejected e) {
                System.out.println("REJECTED " + links);
                break;
            }
            System.out.flush();
        }
        Runtime.getRuntime().halt(0);
    }

    @Test
    void theLongestAcceptedFanOutEndsInTime() throws Exception {
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String java = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Path log = Files.createTempFile("fan-out", ".log");
        Process process = new ProcessBuilder(java, "-Xmx1g", "-cp", classpath, SqlGuardFanOutTest.class.getName())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        boolean ended = process.waitFor(90, TimeUnit.SECONDS);
        if (!ended) {
            process.destroyForcibly().waitFor();
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(log, StandardCharsets.ISO_8859_1));
        Files.delete(log);
        assertThat(ended).as("the JVM ended in time: %s", lines).isTrue();
        assertThat(process.exitValue()).as("exit code: %s", lines).isZero();
        assertThat(lines).anyMatch(line -> line.startsWith("REJECTED"));
        assertThat(lines).filteredOn(line -> line.startsWith("ANSWER")).allMatch(line -> line.contains("[[1]]"));
    }
}
