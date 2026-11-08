package io.github.albaasca.lakehouse.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.albaasca.lakehouse.testkit.ConnectWorker;
import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Chaos "schema drift" (T-041), the whole cycle on the real stack. The producer starts sending a compatible v2 with one
 * more field and the sink, with schema evolution off, drops it without an error. The fix is what ADD_COLUMN does: an
 * {@code ALTER TABLE ... ADD COLUMN} through DuckDB, then a restart of the connector and its tasks. After it the new
 * events carry the field, the rows from before keep NULL, and the events sent in between do not get it back.
 */
class SchemaDriftE2EIT {

    private static final Path SAMPLE = Path.of("../testdata/synthetic-events.csv.gz").toAbsolutePath().normalize();
    private static final Duration COMMIT_INTERVAL = Duration.ofSeconds(5);
    private static final String TABLE = "lh.ecommerce.events";
    private static final int DRIFTED = 20;

    private static LakehouseStack stack;
    private static ConnectWorker connect;

    @BeforeAll
    static void start() throws Exception {
        connect = ConnectWorker.shared();
        stack = LakehouseStack.sharedStreaming();
        KafkaEventSink.ensureTopic(stack.kafkaBootstrapServers(), "ecommerce.events");
        connect.register(COMMIT_INTERVAL);
        connect.awaitRunningTask();
    }

    @AfterAll
    static void stop() throws Exception {
        if (connect != null) {
            connect.deleteConnector("iceberg-sink");
        }
    }

    @Test
    void anAddedColumnIsFilledFromTheFixOnAndOldRowsKeepNull() throws Exception {
        // The table before the drift: some rows of the v1 schema.
        long baseline = rowsOrZero();
        int exit = SeedCommand.execute(new String[] {
            "--input", SAMPLE.toString(), "--max-rows", "200", "--speedup", "1e12",
            "--bootstrap-servers", stack.kafkaBootstrapServers(), "--schema-registry-url", stack.schemaRegistryUrl()
        });
        assertThat(exit).isEqualTo(SeedCommand.OK);
        await("the v1 rows in the table").atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(3))
                .until(SchemaDriftE2EIT::rowsOrZero, rows -> rows > baseline);
        Thread.sleep(COMMIT_INTERVAL.multipliedBy(2).toMillis());
        long before = rowsOrZero();

        // The drift: events with coupon_code. The rows arrive, the field does not (no error anywhere).
        sendDrifted();
        awaitRows(before + DRIFTED);
        assertThat(columns()).as("the sink did not add the column on its own").doesNotContain("coupon_code");
        assertThat(connect.get("/connectors/iceberg-sink/status")).as("no failed task").doesNotContain("FAILED");

        // The fix: ADD_COLUMN.
        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:")) {
            stack.attachDuckDb(writer);
            try (Statement statement = writer.createStatement()) {
                statement.execute("ALTER TABLE " + TABLE + " ADD COLUMN \"coupon_code\" VARCHAR");
            }
        }
        var restart = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(connect.url("/connectors/iceberg-sink/restart?includeTasks=true&onlyFailed=false"))
                        .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(restart.statusCode()).as(restart.body()).isBetween(200, 299);
        connect.awaitRunningTask();

        sendDrifted();
        awaitRows(before + 2L * DRIFTED);
        Thread.sleep(COMMIT_INTERVAL.multipliedBy(2).toMillis());

        assertThat(count("coupon_code = 'DRIFT10'")).as("events after the fix carry the field").isEqualTo(DRIFTED);
        assertThat(count("coupon_code IS NULL")).as("old rows and the events in between: NULL").isEqualTo(before + DRIFTED);
        assertThat(count("true")).as("nothing lost, nothing repeated").isEqualTo(before + 2L * DRIFTED);
    }

    private static void sendDrifted() throws Exception {
        KafkaEventSink sink = KafkaEventSink.open(stack.kafkaBootstrapServers(), stack.schemaRegistryUrl(), "ecommerce.events");
        try (sink) {
            SchemaDriftCommand.send(sink, SchemaDriftCommand.DEFAULT_FIELD, DRIFTED, java.time.Clock.systemUTC());
        }
        assertThat(sink.confirmed()).isEqualTo(DRIFTED);
    }

    private static void awaitRows(long expected) throws Exception {
        try {
            await("rows in the table").atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(3))
                    .until(SchemaDriftE2EIT::rowsOrZero, rows -> rows >= expected);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError(e.getMessage() + "\n" + connect.diagnostics(), e);
        }
    }

    private static long rowsOrZero() {
        try {
            return count("true");
        } catch (SQLException e) {
            return 0; // the table does not exist until the first commit
        }
    }

    private static long count(String where) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            stack.attachDuckDb(connection);
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + TABLE + " WHERE " + where)) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static String columns() throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            stack.attachDuckDb(connection);
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT string_agg(column_name, ',') FROM (DESCRIBE " + TABLE + ")")) {
                rows.next();
                return rows.getString(1);
            }
        }
    }
}
