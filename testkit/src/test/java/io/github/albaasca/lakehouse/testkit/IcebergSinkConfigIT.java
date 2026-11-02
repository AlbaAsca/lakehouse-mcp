package io.github.albaasca.lakehouse.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * connect/iceberg-sink.json against a real worker built from infra/connect. Kafka and Connect only: the task cannot
 * reach a catalog here, so RUNNING and the data path are for the end-to-end test.
 */
class IcebergSinkConfigIT {

    private static final String VALIDATE = "/connector-plugins/IcebergSinkConnector/config/validate";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);
    private static final Path DIR = LakehouseStack.repositoryRoot().resolve("connect");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Map<String, String> ENV = Map.of(
            "S3_ACCESS_KEY", "test-access",
            "S3_SECRET_KEY", "test-secret",
            "S3_REGION", "us-east-1",
            "POLARIS_REALM", "POLARIS",
            "POLARIS_CLIENT_ID", "root",
            "POLARIS_CLIENT_SECRET", "test-polaris");

    private static final Network NETWORK = Network.newNetwork();
    private static final GenericContainer<?> KAFKA = new GenericContainer<>("apache/kafka:4.4.0")
            .withNetwork(NETWORK)
            .withNetworkAliases("kafka")
            .withEnv(Map.of(
                    "KAFKA_NODE_ID", "1",
                    "KAFKA_PROCESS_ROLES", "broker,controller",
                    "KAFKA_LISTENERS", "INTERNAL://:9092,CONTROLLER://:9093",
                    "KAFKA_ADVERTISED_LISTENERS", "INTERNAL://kafka:9092",
                    "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT",
                    "KAFKA_INTER_BROKER_LISTENER_NAME", "INTERNAL",
                    "KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER",
                    "KAFKA_CONTROLLER_QUORUM_VOTERS", "1@kafka:9093",
                    "KAFKA_HEAP_OPTS", "-Xmx256m"))
            .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
            .waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1).withStartupTimeout(STARTUP_TIMEOUT));
    private static final GenericContainer<?> CONNECT = new GenericContainer<>(LakehouseStack.image("connect"))
            .withNetwork(NETWORK)
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
            .withEnv("CONNECT_VALUE_CONVERTER", "io.confluent.connect.avro.AvroConverter")
            .withEnv("CONNECT_VALUE_CONVERTER_SCHEMA_REGISTRY_URL", "http://schema-registry:8081")
            .withEnv("CONNECT_PLUGIN_PATH", "/usr/share/java,/usr/share/confluent-hub-components,/opt/connectors")
            .withEnv("KAFKA_HEAP_OPTS", "-Xmx1g")
            .withExposedPorts(8083)
            .waitingFor(Wait.forHttp(VALIDATE.replace("/config/validate", "") + "/config")
                    .forPort(8083)
                    .forStatusCode(200)
                    .withStartupTimeout(STARTUP_TIMEOUT));

    @BeforeAll
    static void startWorker() {
        KAFKA.start();
        CONNECT.start();
    }

    @AfterAll
    static void stopWorker() {
        CONNECT.stop();
        KAFKA.stop();
    }

    @Test
    void configIsValidForTheBuiltPlugin() throws Exception {
        HttpResponse<String> response = put(VALIDATE, config());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(response.body(), "$.error_count")).as(response.body()).isZero();
    }

    /** Without this, a validate that ignored the connector or the transforms would look like a pass. */
    @Test
    void validationRejectsBadConnectorAndTransformValues() throws Exception {
        String badInterval = config().replace("\"30000\"", "\"soon\"");
        String badPrecision = config().replace("\"microseconds\"", "\"parsecs\"");

        assertThat(badInterval).isNotEqualTo(config());
        assertThat(badPrecision).isNotEqualTo(config());
        assertThat((Integer) JsonPath.read(put(VALIDATE, badInterval).body(), "$.error_count")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(put(VALIDATE, badPrecision).body(), "$.error_count")).isEqualTo(1);
    }

    /** What docs/architecture.md decision 3 and the partitioning of the brief require. */
    @Test
    void configKeepsTheDecisionsOfTheDesign() throws Exception {
        String config = config();

        assertThat(value(config, "topics")).isEqualTo("ecommerce.events");
        assertThat(value(config, "iceberg.tables")).isEqualTo("ecommerce.events");
        assertThat(value(config, "iceberg.tables.default-partition-by")).isEqualTo("day(event_time),event_type");
        assertThat(value(config, "iceberg.tables.evolve-schema-enabled")).isEqualTo("false");
        assertThat(value(config, "iceberg.control.commit.interval-ms")).isEqualTo("30000");
        assertThat(value(config, "errors.tolerance")).isEqualTo("all");
        assertThat(value(config, "errors.deadletterqueue.topic.name")).isEqualTo("ecommerce.events.dlq");
    }

    @Test
    void registrationReplacesSecretsAndCanRunTwice() throws Exception {
        runRegister(ENV);
        runRegister(ENV);

        String registered = get("/connectors/iceberg-sink/config").body();
        assertThat(registered).doesNotContain("${");
        assertThat(value(registered, "iceberg.catalog.credential")).isEqualTo("root:test-polaris");
        assertThat(value(registered, "iceberg.catalog.s3.access-key-id")).isEqualTo("test-access");
        assertThat(value(registered, "iceberg.catalog.s3.secret-access-key")).isEqualTo("test-secret");
        assertThat(value(registered, "iceberg.catalog.client.region")).isEqualTo("us-east-1");
        assertThat(value(registered, "iceberg.catalog.header.Polaris-Realm")).isEqualTo("POLARIS");
    }

    @Test
    void registrationStopsWhenASecretIsMissing() {
        Map<String, String> incomplete = Map.of("S3_ACCESS_KEY", "only-this-one");

        assertThatThrownBy(() -> runRegister(incomplete)).isInstanceOf(RuntimeException.class);
    }

    private static void runRegister(Map<String, String> env) {
        ImageFromDockerfile image = new ImageFromDockerfile("lakehouse-testkit-connect-init", false)
                .withDockerfile(DIR.resolve("Dockerfile"));
        try (GenericContainer<?> register = new GenericContainer<>(image)) {
            register.withNetwork(NETWORK)
                    .withEnv(env)
                    .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(STARTUP_TIMEOUT))
                    .start();
        }
    }

    /** The file with its placeholders replaced, as register.sh does. */
    private static String config() throws Exception {
        String text = Files.readString(DIR.resolve("iceberg-sink.json"));
        for (Map.Entry<String, String> entry : ENV.entrySet()) {
            text = text.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        assertThat(text).doesNotContain("${");
        return text;
    }

    private static String value(String json, String key) {
        return JsonPath.read(json, "$['" + key + "']");
    }

    private static HttpResponse<String> put(String path, String body) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(url(path))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(url(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI url(String path) {
        return URI.create("http://" + CONNECT.getHost() + ":" + CONNECT.getMappedPort(8083) + path);
    }
}
