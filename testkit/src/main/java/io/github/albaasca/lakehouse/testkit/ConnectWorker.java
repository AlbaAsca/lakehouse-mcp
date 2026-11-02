package io.github.albaasca.lakehouse.testkit;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;

/** The Kafka Connect worker of compose.yaml on the network of a stack, and the real one-shot job that registers the sink. */
public final class ConnectWorker implements AutoCloseable {

    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);
    private static final Path CONNECT_DIR = LakehouseStack.repositoryRoot().resolve("connect");
    private static final String COMMIT_INTERVAL = "\"iceberg.control.commit.interval-ms\": \"\\d+\"";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final LakehouseStack stack;
    private final GenericContainer<?> container;

    private ConnectWorker(LakehouseStack stack, GenericContainer<?> container) {
        this.stack = stack;
        this.container = container;
    }

    /** Needs the catalog and streaming of the stack already started. */
    public static ConnectWorker start(LakehouseStack stack) {
        GenericContainer<?> container = new GenericContainer<>(LakehouseStack.image("connect"))
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
        container.start();
        return new ConnectWorker(stack, container);
    }

    /** Registers connect/iceberg-sink.json as it is. */
    public void register() {
        stack.runOnce(registration());
    }

    /** Same file and same job, with a shorter commit interval so that a test does not wait for the real one. */
    public void register(Duration commitInterval) throws IOException {
        String config = Files.readString(CONNECT_DIR.resolve("iceberg-sink.json"));
        String changed = config.replaceFirst(
                COMMIT_INTERVAL, "\"iceberg.control.commit.interval-ms\": \"" + commitInterval.toMillis() + "\"");
        if (changed.equals(config)) {
            throw new IllegalStateException("No commit interval to change in iceberg-sink.json");
        }
        stack.runOnce(registration().withCopyToContainer(Transferable.of(changed), "/iceberg-sink.json"));
    }

    private static GenericContainer<?> registration() {
        ImageFromDockerfile image = new ImageFromDockerfile("lakehouse-testkit-connect-init", false)
                .withDockerfile(CONNECT_DIR.resolve("Dockerfile"));
        return new GenericContainer<>(image).withEnv(Map.of(
                "S3_ACCESS_KEY", LakehouseStack.S3_ACCESS_KEY,
                "S3_SECRET_KEY", LakehouseStack.S3_SECRET_KEY,
                "S3_REGION", LakehouseStack.S3_REGION,
                "POLARIS_REALM", LakehouseStack.REALM,
                "POLARIS_CLIENT_ID", LakehouseStack.CLIENT_ID,
                "POLARIS_CLIENT_SECRET", LakehouseStack.CLIENT_SECRET));
    }

    /** Body of a GET to the REST API; anything but 200 fails. */
    public String get(String path) throws IOException, InterruptedException {
        URI uri = URI.create("http://" + container.getHost() + ":" + container.getMappedPort(8083) + path);
        HttpResponse<String> response =
                HTTP.send(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new AssertionError(path + ": " + response.statusCode() + " " + response.body());
        }
        return response.body();
    }

    /** Fails at once if the task is FAILED. */
    public void awaitRunningTask() throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(STARTUP_TIMEOUT);
        String status = "";
        while (Instant.now().isBefore(deadline)) {
            status = status();
            List<String> states = JsonPath.read(status, "$.tasks[*].state");
            if (states.contains("FAILED")) {
                throw new AssertionError("The task failed: " + status);
            }
            if (states.contains("RUNNING")) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("The task did not reach RUNNING: " + status);
    }

    /** The status of the sink and the end of its log: it logs its problems and stays RUNNING, so the status is not enough. */
    public String diagnostics() throws IOException, InterruptedException {
        List<String> lines = container.getLogs().lines()
                .filter(line -> line.contains("WARN") || line.contains("ERROR") || line.contains("org.apache.iceberg")
                        || line.contains("Exception"))
                .toList();
        return "Status: " + status() + "\nSink log:\n"
                + String.join("\n", lines.subList(Math.max(0, lines.size() - 80), lines.size()));
    }

    private String status() throws IOException, InterruptedException {
        return get("/connectors/iceberg-sink/status");
    }

    @Override
    public void close() {
        container.stop();
    }
}
