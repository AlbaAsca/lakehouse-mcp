package io.github.albaasca.lakehouse.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;

/** The real worker, registration and catalog: one event goes from Kafka to a row read back through Polaris. */
class IcebergSinkFlowIT {

    /** Waits for the commit interval of connect/iceberg-sink.json (30 s) a few times over. */
    private static final Duration ROW_TIMEOUT = Duration.ofMinutes(3);
    private static final String KAFKA_IMAGE = "apache/kafka:4.4.0";
    private static final Path ROOT = LakehouseStack.repositoryRoot();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final long EVENT_TIME_MICROS = 1_570_000_000_000_000L;
    private static final String EVENT = "{\"event_time\":" + EVENT_TIME_MICROS + ",\"event_type\":\"view\","
            + "\"product_id\":1,\"category_id\":2,\"category_code\":null,\"brand\":{\"string\":\"acme\"},"
            + "\"price\":9.5,\"user_id\":3,\"user_session\":{\"string\":\"s1\"}}\n";

    private static LakehouseStack stack;
    private static ConnectWorker connect;

    @BeforeAll
    static void startWorker() {
        LakehouseStack.shared();
        stack = LakehouseStack.sharedStreaming();
        connect = ConnectWorker.start(stack);
    }

    @AfterAll
    static void stopWorker() {
        connect.close();
    }

    @Test
    void pluginAnnouncesTheVersionItWasBuiltFrom() throws Exception {
        List<String> versions = JsonPath.read(
                connect.get("/connector-plugins"),
                "$[?(@.class=='org.apache.iceberg.connect.IcebergSinkConnector')].version");

        assertThat(versions).containsExactly("1.12.0");
    }

    @Test
    void anEventEndsUpAsARowOfTheIcebergTable() throws Exception {
        produceEvent();
        connect.register();

        connect.awaitRunningTask();
        awaitRow();
        assertTableIsPartitionedByDayAndEventType();
    }

    /** The promise of T-012: a message that cannot be read goes aside, with its reason, and the sink carries on. */
    @Test
    void anUnreadableMessageGoesToTheDeadLetterTopicAndTheTaskKeepsRunning() throws Exception {
        String garbage = "not-an-avro-message";
        stack.runOnce(new GenericContainer<>(KAFKA_IMAGE)
                .withCopyToContainer(Transferable.of(garbage + "\n"), "/tmp/garbage.txt")
                .withCommand("sh", "-c", "/opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092"
                        + " --topic ecommerce.events < /tmp/garbage.txt"));
        connect.register();

        String deadLetters = stack.runOnce(new GenericContainer<>(KAFKA_IMAGE)
                .withCommand("sh", "-c", "/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092"
                        + " --topic ecommerce.events.dlq --from-beginning --max-messages 1 --timeout-ms 150000"
                        + " --formatter-property print.headers=true"));

        assertThat(deadLetters).contains(garbage).contains("__connect.errors.exception.class.name");
        List<String> states = JsonPath.read(connect.get("/connectors/iceberg-sink/status"), "$.tasks[*].state");
        assertThat(states).containsExactly("RUNNING");
    }

    /** Compared with the brief, not with connect/iceberg-sink.json: the auto-created table is what has to obey. */
    private static void assertTableIsPartitionedByDayAndEventType() throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(
                                stack.polarisUrl() + "/api/catalog/v1/lakehouse/namespaces/ecommerce/tables/events"))
                        .header("Authorization", "Bearer " + stack.polarisToken())
                        .header("Polaris-Realm", LakehouseStack.REALM)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        String metadata = response.body();
        String fields = "$.metadata['partition-specs'][0].fields";
        List<Integer> sourceIds = JsonPath.read(metadata, fields + "[*]['source-id']");
        List<String> sources = sourceIds.stream()
                .map(id -> JsonPath.<List<String>>read(metadata, "$.metadata.schemas[0].fields[?(@.id==" + id + ")].name").get(0))
                .toList();
        List<String> transforms = JsonPath.read(metadata, fields + "[*].transform");

        assertThat(sources).containsExactly("event_time", "event_type");
        assertThat(transforms).containsExactly("day", "identity");
    }

    private static void produceEvent() throws Exception {
        String command = "kafka-avro-console-producer --bootstrap-server kafka:9092 --topic ecommerce.events"
                + " --property schema.registry.url=http://schema-registry:8081"
                + " --property value.schema.file=/tmp/event.avsc < /tmp/event.json";
        String schema = Files.readString(ROOT.resolve("producer/src/main/avro/event.avsc"));
        stack.runOnce(new GenericContainer<>("confluentinc/cp-schema-registry:8.4.0")
                .withCopyToContainer(Transferable.of(schema), "/tmp/event.avsc")
                .withCopyToContainer(Transferable.of(EVENT), "/tmp/event.json")
                .withCommand("sh", "-c", command));
    }

    /** The field values prove it is this event and that event_time kept its meaning through the transform. */
    private static void awaitRow() throws Exception {
        String sql = "SELECT epoch_us(event_time) AS micros, event_type, product_id, category_id, category_code, brand,"
                + " price, user_id, user_session FROM lh.ecommerce.events";
        Instant deadline = Instant.now().plus(ROW_TIMEOUT);
        SQLException lastError = null;
        while (Instant.now().isBefore(deadline)) {
            try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
                    Statement statement = connection.createStatement()) {
                stack.attachDuckDb(connection);
                try (ResultSet rows = statement.executeQuery(sql)) {
                    if (rows.next()) {
                        assertThat(rows.getLong("micros")).isEqualTo(EVENT_TIME_MICROS);
                        assertThat(rows.getString("event_type")).isEqualTo("view");
                        assertThat(rows.getLong("product_id")).isEqualTo(1);
                        assertThat(rows.getLong("category_id")).isEqualTo(2);
                        assertThat(rows.getString("category_code")).isNull();
                        assertThat(rows.getString("brand")).isEqualTo("acme");
                        assertThat(rows.getDouble("price")).isEqualTo(9.5);
                        assertThat(rows.getLong("user_id")).isEqualTo(3);
                        assertThat(rows.getString("user_session")).isEqualTo("s1");
                        assertThat(rows.next()).as("exactly one row").isFalse();
                        return;
                    }
                }
            } catch (SQLException tableNotThereYet) {
                lastError = tableNotThereYet;
            }
            Thread.sleep(5000);
        }
        throw new AssertionError("No row in the table. " + connect.diagnostics(), lastError);
    }
}
