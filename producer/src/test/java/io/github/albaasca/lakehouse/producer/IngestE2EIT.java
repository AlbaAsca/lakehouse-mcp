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
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The whole ingestion path: CSV, seed command, Kafka, sink, Polaris and S3, read back with DuckDB.
 *
 * <p>The sink is registered before any event exists, the order of {@code ./mk up} and then {@code ./mk seed}. The expected
 * rows come from DuckDB's own CSV reader, not from the producer's.
 */
class IngestE2EIT {

    private static final Path SAMPLE = Path.of("../testdata/synthetic-events.csv.gz").toAbsolutePath().normalize();
    private static final int SAMPLE_ROWS = 50_000;
    private static final Duration COMMIT_INTERVAL = Duration.ofSeconds(5);
    private static final Duration ROWS_TIMEOUT = Duration.ofMinutes(5);
    private static final String TABLE = "lh.ecommerce.events";
    private static final String COLUMNS =
            "event_time, event_type, product_id, category_id, category_code, brand, price, user_id, user_session";
    /** Timestamps as microseconds in both views: the comparison cannot depend on a time zone. */
    private static final String TABLE_ROWS = "SELECT epoch_us(event_time) AS event_time, event_type, product_id,"
            + " category_id, category_code, brand, price, user_id, user_session FROM " + TABLE;
    private static final String CSV_ROWS = "SELECT epoch_us(strptime(event_time, '%Y-%m-%d %H:%M:%S UTC')) AS event_time,"
            + " event_type, product_id, category_id, category_code, brand, price, user_id, user_session"
            + " FROM read_csv('" + SAMPLE + "', header = true, auto_detect = false, delim = ',', nullstr = '', columns = {"
            + "'event_time': 'VARCHAR', 'event_type': 'VARCHAR', 'product_id': 'BIGINT', 'category_id': 'BIGINT',"
            + "'category_code': 'VARCHAR', 'brand': 'VARCHAR', 'price': 'DOUBLE', 'user_id': 'BIGINT',"
            + "'user_session': 'VARCHAR'})";

    private static LakehouseStack stack;
    private static ConnectWorker connect;
    private static Connection duckDb;

    @BeforeAll
    static void sendTheSampleThroughThePipeline() throws Exception {
        connect = ConnectWorker.shared();
        stack = LakehouseStack.sharedStreaming();
        // What kafka-init does in compose.yaml: the topic exists, with its partitions, before the connector subscribes.
        KafkaEventSink.ensureTopic(stack.kafkaBootstrapServers(), "ecommerce.events");
        connect.register(COMMIT_INTERVAL);
        connect.awaitRunningTask();

        int exit = SeedCommand.execute(new String[] {
            "--input", SAMPLE.toString(),
            "--speedup", "1e12",
            "--bootstrap-servers", stack.kafkaBootstrapServers(),
            "--schema-registry-url", stack.schemaRegistryUrl()
        });
        assertThat(exit).isEqualTo(SeedCommand.OK);

        awaitEveryRow();
        // A commit that repeats files would come after the count first adds up.
        Thread.sleep(COMMIT_INTERVAL.multipliedBy(2).toMillis());
        duckDb = openDuckDb();
    }

    @AfterAll
    static void stop() throws Exception {
        if (duckDb != null) {
            duckDb.close();
        }
        if (connect != null) {
            connect.deleteConnector("iceberg-sink");
        }
    }

    /** EXCEPT ALL keeps duplicates and treats nulls as equal: a row missing, extra or repeated once too often shows up. */
    @Test
    void theTableHoldsExactlyTheRowsOfTheCsv() throws Exception {
        assertThat(single("SELECT count(*) FROM (" + CSV_ROWS + ")")).isEqualTo(SAMPLE_ROWS);
        assertThat(single("SELECT count(*) FROM " + TABLE)).isEqualTo(SAMPLE_ROWS);
        assertThat(rows("(" + CSV_ROWS + ") EXCEPT ALL (" + TABLE_ROWS + ") LIMIT 20")).as("only in the CSV").isEmpty();
        assertThat(rows("(" + TABLE_ROWS + ") EXCEPT ALL (" + CSV_ROWS + ") LIMIT 20")).as("only in the table").isEmpty();
    }

    /** The sink's subscription must not have auto-created the topic with the broker default of one partition (T-114). */
    @Test
    void theEventsTopicKeepsItsPartitions() throws Exception {
        try (org.apache.kafka.clients.admin.Admin admin = org.apache.kafka.clients.admin.Admin.create(java.util.Map.<String, Object>of(
                "bootstrap.servers", stack.kafkaBootstrapServers()))) {
            assertThat(admin.describeTopics(List.of("ecommerce.events")).allTopicNames().get()
                    .get("ecommerce.events").partitions()).hasSize(KafkaEventSink.PARTITIONS);
        }
    }

    /** The amounts documented in infra/synthetic/README.md: nothing upstream cleaned the sample on the way. */
    @Test
    void theDirtyRowsAndDuplicatesOfTheOriginKeepTheirNumbers() throws Exception {
        assertThat(single("SELECT count(*) FILTER (WHERE category_code IS NULL) FROM " + TABLE)).isEqualTo(11_952);
        assertThat(single("SELECT count(*) FILTER (WHERE brand IS NULL) FROM " + TABLE)).isEqualTo(5_029);
        assertThat(single("SELECT count(*) FILTER (WHERE user_session IS NULL) FROM " + TABLE)).isEqualTo(50);
        assertThat(single("SELECT count(*) FILTER (WHERE price = 0) FROM " + TABLE)).isEqualTo(100);
        assertThat(single("SELECT count(*) FILTER (WHERE price < 0) FROM " + TABLE)).isEqualTo(102);
        assertThat(single("SELECT count(*) FROM (SELECT DISTINCT " + COLUMNS + " FROM " + TABLE + ")")).isEqualTo(49_750);
    }

    /** Checks the committed files, not the declared spec: each row sits under the folder of its own UTC day and type. */
    @Test
    void theDataFilesArePartitionedByDayAndEventType() throws Exception {
        String files = rows("SELECT file_path FROM iceberg_metadata('" + TABLE + "') WHERE content = 'DATA'").stream()
                .map(path -> "'" + path + "'")
                .collect(Collectors.joining(",", "[", "]"));
        String located = "(SELECT regexp_extract(filename, '/event_time_day=([^/]+)/', 1) AS folder_day,"
                + " regexp_extract(filename, '/event_type=([^/]+)/', 1) AS folder_type,"
                + " strftime(make_timestamp(epoch_us(event_time)), '%Y-%m-%d') AS day, event_type"
                + " FROM read_parquet(" + files + ", filename = true))";

        assertThat(single("SELECT count(*) FROM " + located)).isEqualTo(SAMPLE_ROWS);
        assertThat(rows("SELECT DISTINCT folder_day, folder_type, day, event_type FROM " + located
                        + " WHERE folder_day <> day OR folder_type <> event_type LIMIT 20"))
                .as("rows in the folder of another partition")
                .isEmpty();
        assertThat(rows("SELECT DISTINCT folder_day, folder_type FROM " + located + " ORDER BY 1, 2"))
                .isEqualTo(rows("SELECT DISTINCT strftime(make_timestamp(event_time), '%Y-%m-%d'), event_type FROM ("
                        + CSV_ROWS + ") ORDER BY 1, 2"));
    }

    private static void awaitEveryRow() throws Exception {
        try {
            await("all " + SAMPLE_ROWS + " rows in the table")
                    .atMost(ROWS_TIMEOUT)
                    .pollInterval(Duration.ofSeconds(5))
                    // The table does not exist until the first commit.
                    .ignoreExceptionsInstanceOf(SQLException.class)
                    .until(IngestE2EIT::rowsInTheTable, rows -> rows >= SAMPLE_ROWS);
        } catch (ConditionTimeoutException e) {
            throw new AssertionError(e.getMessage() + "\n" + connect.diagnostics(), e);
        }
    }

    private static long rowsInTheTable() throws SQLException {
        try (Connection connection = openDuckDb();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + TABLE)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static Connection openDuckDb() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:duckdb:");
        stack.attachDuckDb(connection);
        return connection;
    }

    private static long single(String sql) throws SQLException {
        try (Statement statement = duckDb.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /** Each row as text, with its columns joined. */
    private static List<String> rows(String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (Statement statement = duckDb.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    row.add(rows.getString(i));
                }
                result.add(String.join(" | ", row));
            }
        }
        return result;
    }
}
