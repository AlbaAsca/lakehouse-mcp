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
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;

/** The real worker, registration and catalog: one event goes from Kafka to a row read back through Polaris. */
class IcebergSinkFlowIT {

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);
    /** Waits for the commit interval of connect/iceberg-sink.json (30 s) a few times over. */
    private static final Duration ROW_TIMEOUT = Duration.ofMinutes(3);
    private static final Path ROOT = LakehouseStack.repositoryRoot();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final long EVENT_TIME_MICROS = 1_570_000_000_000_000L;
    private static final String EVENT = "{\"event_time\":" + EVENT_TIME_MICROS + ",\"event_type\":\"view\","
            + "\"product_id\":1,\"category_id\":2,\"category_code\":null,\"brand\":{\"string\":\"acme\"},"
            + "\"price\":9.5,\"user_id\":3,\"user_session\":{\"string\":\"s1\"}}\n";

    private static LakehouseStack stack;
    private static GenericContainer<?> connect;

    @BeforeAll
    static void startWorker() {
        LakehouseStack.shared();
        stack = LakehouseStack.sharedStreaming();
        connect = new GenericContainer<>(LakehouseStack.image("connect"))
                .withNetwork(stack.network())
                .withNetworkAliases("connect")
                .withEnv(Map.of(
                        "CONNECT_BOOTSTRAP_SERVERS", "kafka:9092",
                        "CONNECT_REST_ADVERTISED_HOST_NAME", "connect",
                        "CONNECT_GROUP_ID", "lakehouse-connect",
                        "CONNECT_CONFIG_STORAGE_TOPIC", "_connect-configs",
                        "CONNECT_OFFSET_STORAGE_TOPIC", "_connect-offsets",
                        "CONNECT_STATUS_STORAGE_TOPIC", "_connect-status",
                        "CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR", "1",
                        "CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR", "1",
                        "CONNECT_STATUS_STORAGE_REPLICATION_FACTOR", "1",
                        "CONNECT_KEY_CONVERTER", "org.apache.kafka.connect.storage.StringConverter"))
                .withEnv("CONNECT_OFFSET_FLUSH_INTERVAL_MS", "1000") // same as compose.yaml, which explains why
                .withEnv("CONNECT_VALUE_CONVERTER", "io.confluent.connect.avro.AvroConverter")
                .withEnv("CONNECT_VALUE_CONVERTER_SCHEMA_REGISTRY_URL", "http://schema-registry:8081")
                .withEnv("CONNECT_PLUGIN_PATH", "/usr/share/java,/usr/share/confluent-hub-components,/opt/connectors")
                .withEnv("KAFKA_HEAP_OPTS", "-Xmx1g")
                .withEnv("AWS_REGION", LakehouseStack.S3_REGION)
                .withEnv("AWS_ACCESS_KEY_ID", LakehouseStack.S3_ACCESS_KEY)
                .withEnv("AWS_SECRET_ACCESS_KEY", LakehouseStack.S3_SECRET_KEY)
                .withExposedPorts(8083)
                .waitingFor(Wait.forHttp("/connector-plugins")
                        .forPort(8083)
                        .forStatusCode(200)
                        .withStartupTimeout(STARTUP_TIMEOUT));
        connect.start();
    }

    @AfterAll
    static void stopWorker() {
        connect.stop();
    }

    @Test
    void pluginAnnouncesTheVersionItWasBuiltFrom() throws Exception {
        List<String> versions = JsonPath.read(
                get("/connector-plugins"), "$[?(@.class=='org.apache.iceberg.connect.IcebergSinkConnector')].version");

        assertThat(versions).containsExactly("1.12.0");
    }

    @Test
    void anEventEndsUpAsARowOfTheIcebergTable() throws Exception {
        produceEvent();
        register();

        awaitRunningTask();
        awaitRow();
    }

    private static void produceEvent() throws Exception {
        String command = "kafka-avro-console-producer --bootstrap-server kafka:9092 --topic ecommerce.events"
                + " --property schema.registry.url=http://schema-registry:8081"
                + " --property value.schema.file=/tmp/event.avsc < /tmp/event.json";
        String schema = Files.readString(ROOT.resolve("producer/src/main/avro/event.avsc"));
        runOnce(new GenericContainer<>("confluentinc/cp-schema-registry:8.4.0")
                .withCopyToContainer(Transferable.of(schema), "/tmp/event.avsc")
                .withCopyToContainer(Transferable.of(EVENT), "/tmp/event.json")
                .withCommand("sh", "-c", command));
    }

    private static void register() {
        ImageFromDockerfile image = new ImageFromDockerfile("lakehouse-testkit-connect-init", false)
                .withDockerfile(ROOT.resolve("connect/Dockerfile"));
        runOnce(new GenericContainer<>(image).withEnv(Map.of(
                "S3_ACCESS_KEY", LakehouseStack.S3_ACCESS_KEY,
                "S3_SECRET_KEY", LakehouseStack.S3_SECRET_KEY,
                "S3_REGION", LakehouseStack.S3_REGION,
                "POLARIS_REALM", LakehouseStack.REALM,
                "POLARIS_CLIENT_ID", LakehouseStack.CLIENT_ID,
                "POLARIS_CLIENT_SECRET", LakehouseStack.CLIENT_SECRET)));
    }

    /** Starting throws unless the container exits with code 0. */
    private static void runOnce(GenericContainer<?> container) {
        try (container) {
            container.withNetwork(stack.network())
                    .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(STARTUP_TIMEOUT))
                    .start();
        }
    }

    private static void awaitRunningTask() throws Exception {
        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        String status = "";
        while (Instant.now().isBefore(deadline)) {
            status = get("/connectors/iceberg-sink/status");
            List<String> states = JsonPath.read(status, "$.tasks[*].state");
            assertThat(states).as(status).doesNotContain("FAILED");
            if (states.contains("RUNNING")) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("The task did not reach RUNNING: " + status);
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
        // The sink logs its problems and stays RUNNING, so the status alone would not explain this.
        throw new AssertionError(
                "No row in the table. Status: " + get("/connectors/iceberg-sink/status") + "\nSink log:\n" + sinkLog(),
                lastError);
    }

    private static String sinkLog() {
        List<String> lines = connect.getLogs().lines()
                .filter(line -> line.contains("WARN") || line.contains("ERROR") || line.contains("org.apache.iceberg")
                        || line.contains("Exception"))
                .toList();
        return String.join("\n", lines.subList(Math.max(0, lines.size() - 80), lines.size()));
    }

    private static String get(String path) throws Exception {
        URI uri = URI.create("http://" + connect.getHost() + ":" + connect.getMappedPort(8083) + path);
        HttpResponse<String> response =
                HTTP.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + ": " + response.body()).isEqualTo(200);
        return response.body();
    }
}
