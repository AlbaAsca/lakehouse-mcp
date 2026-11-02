package io.github.albaasca.lakehouse.testkit;

import com.github.dockerjava.api.command.InspectContainerResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;

/**
 * SeaweedFS, Postgres and Polaris with the images and configuration of compose.yaml, ready for Iceberg tables.
 *
 * <p>One stack per JVM (so one per Maven module): it starts on the first {@link #shared()} and Testcontainers removes
 * it when the JVM exits. Every test class in the module sees the same catalog, so use your own namespace.
 *
 * <p>Kafka and Schema Registry start only with {@link #sharedStreaming()}, on the same network: a test can ask for the
 * catalog, for streaming or for both, and pays only for what it asks.
 */
public final class LakehouseStack {

    public static final String REALM = "POLARIS";
    public static final String CATALOG = "lakehouse";
    public static final String CLIENT_ID = "root";
    public static final String CLIENT_SECRET = "testkit-secret";
    public static final String S3_ACCESS_KEY = "lakehouse";
    public static final String S3_SECRET_KEY = "testkit-s3";
    public static final String S3_REGION = "us-east-1";
    public static final String S3_BUCKET = "lakehouse";
    /** Name of the catalog in DuckDB after {@link #attachDuckDb(Connection)}. */
    public static final String DUCKDB_CATALOG = "lh";

    private static final String DB_PASSWORD = "testkit-db";
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);
    private static final Map<String, String> PERSISTENCE = Map.of(
            "polaris.persistence.type", "relational-jdbc",
            "quarkus.datasource.username", "polaris",
            "quarkus.datasource.password", DB_PASSWORD,
            "quarkus.datasource.jdbc.url", "jdbc:postgresql://postgres:5432/polaris");
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static LakehouseStack shared;

    private final Network network = Network.newNetwork();
    private final GenericContainer<?> postgres = new GenericContainer<>(image("postgres"))
            .withNetwork(network)
            .withNetworkAliases("postgres")
            .withEnv(Map.of("POSTGRES_DB", "polaris", "POSTGRES_USER", "polaris", "POSTGRES_PASSWORD", DB_PASSWORD))
            // Twice: the temporary server used for initdb logs the same line first.
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));
    private final GenericContainer<?> seaweedfs = new GenericContainer<>(image("seaweedfs"))
            .withNetwork(network)
            .withNetworkAliases("seaweedfs")
            .withEnv(Map.of("S3_ACCESS_KEY", S3_ACCESS_KEY, "S3_SECRET_KEY", S3_SECRET_KEY))
            .withExposedPorts(8333, 9333)
            .waitingFor(Wait.forHttp("/cluster/healthz").forPort(9333).forStatusCode(200));
    private final GenericContainer<?> polaris = new GenericContainer<>("apache/polaris:1.8.0")
            .withNetwork(network)
            .withNetworkAliases("polaris")
            .withEnv(PERSISTENCE)
            .withEnv("polaris.realm-context.realms", REALM)
            .withEnv("AWS_REGION", S3_REGION)
            .withEnv("AWS_ACCESS_KEY_ID", S3_ACCESS_KEY)
            .withEnv("AWS_SECRET_ACCESS_KEY", S3_SECRET_KEY)
            .withEnv("JAVA_OPTS_APPEND", "-Xmx512m")
            .withExposedPorts(8181, 8182)
            .waitingFor(Wait.forHttp("/q/health").forPort(8182).forStatusCode(200).withStartupTimeout(STARTUP_TIMEOUT));
    private final KafkaBroker kafka = new KafkaBroker(network);
    private final GenericContainer<?> schemaRegistry = new GenericContainer<>("confluentinc/cp-schema-registry:8.4.0")
            .withNetwork(network)
            .withEnv(Map.of(
                    "SCHEMA_REGISTRY_HOST_NAME", "schema-registry",
                    "SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "kafka:9092",
                    "SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081",
                    "SCHEMA_REGISTRY_HEAP_OPTS", "-Xmx256m"))
            .withNetworkAliases("schema-registry")
            .withExposedPorts(8081)
            .waitingFor(Wait.forHttp("/subjects").forPort(8081).forStatusCode(200).withStartupTimeout(STARTUP_TIMEOUT));
    private boolean catalogStarted;
    private boolean streamingStarted;

    private LakehouseStack() {}

    /** The stack of this JVM with its catalog, started on the first call (about a minute once the images are built). */
    public static synchronized LakehouseStack shared() {
        LakehouseStack stack = instance();
        if (!stack.catalogStarted) {
            stack.startCatalog();
            stack.catalogStarted = true;
        }
        return stack;
    }

    /** The stack of this JVM with Kafka and Schema Registry, started on the first call. */
    public static synchronized LakehouseStack sharedStreaming() {
        LakehouseStack stack = instance();
        if (!stack.streamingStarted) {
            stack.kafka.start();
            stack.schemaRegistry.start();
            stack.streamingStarted = true;
        }
        return stack;
    }

    private static LakehouseStack instance() {
        if (shared == null) {
            shared = new LakehouseStack();
        }
        return shared;
    }

    private void startCatalog() {
        postgres.start();
        seaweedfs.start();
        bootstrap();
        polaris.start();
        // The real init script: bucket, catalog and grants.
        runOnce(new GenericContainer<>(image("polaris")).withEnv(Map.of(
                "S3_ACCESS_KEY", S3_ACCESS_KEY,
                "S3_SECRET_KEY", S3_SECRET_KEY,
                "S3_REGION", S3_REGION,
                "S3_BUCKET", S3_BUCKET,
                "POLARIS_REALM", REALM,
                "POLARIS_CLIENT_ID", CLIENT_ID,
                "POLARIS_CLIENT_SECRET", CLIENT_SECRET)));
    }

    /** Runs the admin tool as `make up` does; throws unless it exits with code 0. */
    public void bootstrap() {
        runOnce(new GenericContainer<>("apache/polaris-admin-tool:1.8.0")
                .withEnv(PERSISTENCE)
                .withCommand("bootstrap", "-r", REALM, "-c", REALM + "," + CLIENT_ID + "," + CLIENT_SECRET));
    }

    /** New Polaris process on the same metastore. Its port changes: DuckDB connections must attach again. */
    public void restartPolaris() {
        polaris.stop();
        polaris.start();
    }

    /** The network of the stack, for a test that adds its own container (aliases: kafka, polaris, seaweedfs...). */
    public Network network() {
        return network;
    }

    /** Base URL of Polaris from the test JVM, without a trailing slash. */
    public String polarisUrl() {
        return "http://" + polaris.getHost() + ":" + polaris.getMappedPort(8181);
    }

    /** {@code host:port} of the S3 API from the test JVM. */
    public String s3Endpoint() {
        return seaweedfs.getHost() + ":" + seaweedfs.getMappedPort(8333);
    }

    /** {@code host:port} of Kafka from the test JVM. Containers on the network use {@code kafka:9092}. */
    public String kafkaBootstrapServers() {
        return kafka.bootstrapServers();
    }

    /** Base URL of Schema Registry from the test JVM. Containers on the network use {@code http://schema-registry:8081}. */
    public String schemaRegistryUrl() {
        return "http://" + schemaRegistry.getHost() + ":" + schemaRegistry.getMappedPort(8081);
    }

    /** Bearer token of the root principal. */
    public String polarisToken() throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create(polarisUrl() + "/api/catalog/v1/oauth/tokens"))
                        .header("Polaris-Realm", REALM)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&client_id=" + CLIENT_ID
                                + "&client_secret=" + CLIENT_SECRET + "&scope=PRINCIPAL_ROLE:ALL"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        Matcher matcher = ACCESS_TOKEN.matcher(response.body());
        if (response.statusCode() != 200 || !matcher.find()) {
            throw new IllegalStateException("No token from Polaris: " + response.statusCode() + " " + response.body());
        }
        return matcher.group(1);
    }

    /**
     * Attaches the catalog as {@value #DUCKDB_CATALOG}. No credential vending: the table locations use the bucket name
     * and this connection reaches them with its own S3 secret on the mapped port, as infra/smoke.sh does inside compose.
     */
    public void attachDuckDb(Connection connection) throws SQLException {
        List<String> statements = List.of(
                "INSTALL iceberg", "LOAD iceberg", "INSTALL httpfs", "LOAD httpfs",
                "CREATE OR REPLACE SECRET lakehouse_s3 (TYPE s3, KEY_ID '" + S3_ACCESS_KEY + "', SECRET '" + S3_SECRET_KEY
                        + "', REGION '" + S3_REGION + "', ENDPOINT '" + s3Endpoint() + "', URL_STYLE 'path', USE_SSL false)",
                "CREATE OR REPLACE SECRET lakehouse_polaris (TYPE iceberg, CLIENT_ID '" + CLIENT_ID + "', CLIENT_SECRET '"
                        + CLIENT_SECRET + "', OAUTH2_SERVER_URI '" + polarisUrl() + "/api/catalog/v1/oauth/tokens', "
                        + "OAUTH2_SCOPE 'PRINCIPAL_ROLE:ALL')",
                "ATTACH '" + CATALOG + "' AS " + DUCKDB_CATALOG + " (TYPE iceberg, URI '" + polarisUrl()
                        + "/api/catalog', SECRET lakehouse_polaris, ACCESS_DELEGATION_MODE 'none')");
        try (Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    /** Starting throws unless the container exits with code 0. */
    private void runOnce(GenericContainer<?> container) {
        try (container) {
            container.withNetwork(network)
                    .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(STARTUP_TIMEOUT))
                    .start();
        }
    }

    /**
     * A broker that must advertise its mapped port, which is only known once the container exists: it waits for a
     * script with that address before starting. Same listeners as compose.yaml.
     */
    private static final class KafkaBroker extends GenericContainer<KafkaBroker> {

        private static final int EXTERNAL_PORT = 19092;
        private static final String STARTER = "/tmp/start-broker.sh";

        KafkaBroker(Network network) {
            super("apache/kafka:4.4.0");
            withNetwork(network);
            withNetworkAliases("kafka");
            withEnv(Map.of(
                    "KAFKA_NODE_ID", "1",
                    "KAFKA_PROCESS_ROLES", "broker,controller",
                    "KAFKA_LISTENERS", "INTERNAL://:9092,EXTERNAL://:" + EXTERNAL_PORT + ",CONTROLLER://:9093",
                    "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT",
                    "KAFKA_INTER_BROKER_LISTENER_NAME", "INTERNAL",
                    "KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER",
                    "KAFKA_CONTROLLER_QUORUM_VOTERS", "1@kafka:9093",
                    "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1",
                    "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1",
                    "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1"));
            withEnv("KAFKA_HEAP_OPTS", "-Xmx512m");
            withExposedPorts(EXTERNAL_PORT);
            withCommand("sh", "-c", "while [ ! -f " + STARTER + " ]; do sleep 0.1; done; exec " + STARTER);
            waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1).withStartupTimeout(STARTUP_TIMEOUT));
        }

        String bootstrapServers() {
            return getHost() + ":" + getMappedPort(EXTERNAL_PORT);
        }

        @Override
        protected void containerIsStarting(InspectContainerResponse container) {
            String script = "#!/bin/sh\n"
                    + "export KAFKA_ADVERTISED_LISTENERS=INTERNAL://kafka:9092,EXTERNAL://" + bootstrapServers() + "\n"
                    + "exec /etc/kafka/docker/run\n";
            copyFileToContainer(Transferable.of(script, 0777), STARTER);
        }
    }

    static ImageFromDockerfile image(String infraDir) {
        return new ImageFromDockerfile("lakehouse-testkit-" + infraDir, false)
                .withDockerfile(repositoryRoot().resolve("infra").resolve(infraDir).resolve("Dockerfile"));
    }

    /** Tests run from their module directory, so the root is the closest parent that holds infra/. */
    public static Path repositoryRoot() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("infra"))) {
                return dir;
            }
        }
        throw new IllegalStateException("No infra/ directory above " + Path.of("").toAbsolutePath());
    }
}
