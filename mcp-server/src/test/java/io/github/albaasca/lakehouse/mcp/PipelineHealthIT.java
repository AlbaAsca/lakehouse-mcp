package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.albaasca.lakehouse.mcp.PipelineHealth.ConnectorState;
import io.github.albaasca.lakehouse.mcp.PipelineHealth.PartitionLag;
import io.github.albaasca.lakehouse.mcp.PipelineHealth.Report;
import io.github.albaasca.lakehouse.mcp.PipelineHealth.Status;
import io.github.albaasca.lakehouse.mcp.PipelineHealth.TaskState;
import io.github.albaasca.lakehouse.mcp.PipelineHealth.Thresholds;
import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Lag against a real broker, with the sink's group created by hand, and the state of a simulated Kafka Connect. The
 * number of messages each partition gets is the reference: the lag reported has to match it partition by partition.
 */
class PipelineHealthIT {

    private static final String CONNECTOR = "health-it-sink";
    private static final String GROUP = PipelineHealth.groupOf(CONNECTOR);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final Thresholds LIMITS = new Thresholds(5, 20, Duration.ofMinutes(2), Duration.ofMinutes(10));

    private static LakehouseStack stack;
    private static Admin admin;
    private static HttpServer connect;
    private static final AtomicReference<Answer> CONNECT_ANSWER = new AtomicReference<>();

    private record Answer(int code, String body) {}

    @BeforeAll
    static void start() throws IOException {
        stack = LakehouseStack.sharedStreaming();
        Properties config = new Properties();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, stack.kafkaBootstrapServers());
        admin = Admin.create(config);
        connect = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        connect.createContext("/connectors/" + CONNECTOR + "/status", exchange -> {
            Answer answer = CONNECT_ANSWER.get();
            byte[] body = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.code(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        connect.start();
    }

    @AfterAll
    static void stop() {
        if (connect != null) {
            connect.stop(0);
        }
        if (admin != null) {
            admin.close();
        }
    }

    private static String connectUrl() {
        return "http://127.0.0.1:" + connect.getAddress().getPort();
    }

    private static void createTopic(String topic, int partitions) throws Exception {
        admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get();
    }

    /** Sends {@code counts[p]} messages to partition {@code p}. */
    private static void send(String topic, int... counts) {
        Properties config = new Properties();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, stack.kafkaBootstrapServers());
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            for (int partition = 0; partition < counts.length; partition++) {
                for (int i = 0; i < counts[partition]; i++) {
                    producer.send(new ProducerRecord<>(topic, partition, null, "event-" + i));
                }
            }
            producer.flush();
        }
    }

    private static void commit(String topic, Map<Integer, Long> offsets) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> out = new java.util.HashMap<>();
        offsets.forEach((partition, offset) -> out.put(new TopicPartition(topic, partition), new OffsetAndMetadata(offset)));
        admin.alterConsumerGroupOffsets(GROUP, out).all().get();
    }

    private static Report check(String bootstrapServers, String topic, String connectUrl, Instant lastCommit) {
        return new PipelineHealth(CONNECTOR, topic, "ecommerce.events", LIMITS,
                new KafkaLag(bootstrapServers, GROUP, topic, TIMEOUT),
                new ConnectStatus(connectUrl, CONNECTOR, TIMEOUT),
                () -> Optional.ofNullable(lastCommit), Clock.fixed(NOW, ZoneOffset.UTC)).check();
    }

    private static String running() {
        return "{\"name\":\"" + CONNECTOR + "\",\"connector\":{\"state\":\"RUNNING\",\"worker_id\":\"connect:8083\"},"
                + "\"tasks\":[{\"id\":0,\"state\":\"RUNNING\",\"worker_id\":\"connect:8083\"}],\"type\":\"sink\"}";
    }

    @Test
    void theLagIsWhatWasSentMinusWhatTheGroupCommittedPartitionByPartition() throws Exception {
        String topic = "health-it.lag";
        createTopic(topic, 2);
        send(topic, 7, 4);
        CONNECT_ANSWER.set(new Answer(200, running()));

        commit(topic, Map.of(0, 0L, 1, 0L));
        Report unread = check(stack.kafkaBootstrapServers(), topic, connectUrl(), NOW.minusSeconds(5));
        assertThat(unread.lag().messages()).isEqualTo(11);
        assertThat(unread.lag().partitions()).containsExactly(new PartitionLag(0, 0, 7, 7), new PartitionLag(1, 0, 4, 4));
        assertThat(unread.lag().status()).isEqualTo(Status.WARN);
        assertThat(unread.lag().group()).isEqualTo(GROUP);

        commit(topic, Map.of(0, 7L, 1, 1L));
        Report almost = check(stack.kafkaBootstrapServers(), topic, connectUrl(), NOW.minusSeconds(5));
        assertThat(almost.lag().partitions()).containsExactly(new PartitionLag(0, 7, 7, 0), new PartitionLag(1, 1, 4, 3));
        assertThat(almost.lag().messages()).isEqualTo(3);
        assertThat(almost.status()).isEqualTo(Status.OK);
        assertThat(almost.connector().tasks()).containsExactly(new TaskState(0, "RUNNING", null));
    }

    @Test
    void aPartitionTheGroupNeverCommittedCountsEverythingInIt() throws Exception {
        String topic = "health-it.never";
        createTopic(topic, 1);
        send(topic, 25);
        CONNECT_ANSWER.set(new Answer(200, running()));

        Report report = check(stack.kafkaBootstrapServers(), topic, connectUrl(), NOW.minusSeconds(5));

        assertThat(report.lag().partitions()).containsExactly(new PartitionLag(0, 0, 25, 25));
        assertThat(report.lag().status()).isEqualTo(Status.FAIL);
    }

    @Test
    void withoutRecentWritesTheFreshnessIsStale() throws Exception {
        String topic = "health-it.stale";
        createTopic(topic, 1);
        CONNECT_ANSWER.set(new Answer(200, running()));

        Report report = check(stack.kafkaBootstrapServers(), topic, connectUrl(), NOW.minus(Duration.ofMinutes(30)));

        assertThat(report.freshness().status()).isEqualTo(Status.FAIL);
        assertThat(report.freshness().ageSeconds()).isEqualTo(1800);
        assertThat(report.status()).isEqualTo(Status.FAIL);
    }

    @Test
    void aFailedTaskOfConnectIsAFailWithTheFirstLineOfItsTrace() throws Exception {
        String topic = "health-it.failed";
        createTopic(topic, 1);
        CONNECT_ANSWER.set(new Answer(200, "{\"name\":\"" + CONNECTOR + "\",\"connector\":{\"state\":\"RUNNING\"},"
                + "\"tasks\":[{\"id\":0,\"state\":\"FAILED\",\"trace\":\"org.apache.kafka.connect.errors.ConnectException:"
                + " Tolerance exceeded\\n\\tat org.apache.kafka.Foo.bar(Foo.java:1)\\n\"}]}"));

        Report report = check(stack.kafkaBootstrapServers(), topic, connectUrl(), NOW.minusSeconds(5));

        assertThat(report.connector().status()).isEqualTo(Status.FAIL);
        assertThat(report.connector().tasks()).containsExactly(new TaskState(0, "FAILED",
                "org.apache.kafka.connect.errors.ConnectException: Tolerance exceeded"));
        assertThat(report.reasons()).containsExactly(
                "Task 0 of " + CONNECTOR + " has FAILED: org.apache.kafka.connect.errors.ConnectException: Tolerance exceeded");
    }

    @Test
    void aConnectorConnectDoesNotKnowIsAFail() throws Exception {
        String topic = "health-it.unknown";
        createTopic(topic, 1);
        CONNECT_ANSWER.set(new Answer(404, "{\"error_code\":404,\"message\":\"No status found for connector\"}"));

        ConnectorState state = new ConnectStatus(connectUrl(), CONNECTOR, TIMEOUT).read();
        Report report = check(stack.kafkaBootstrapServers(), topic, connectUrl(), NOW.minusSeconds(5));

        assertThat(state.registered()).isFalse();
        assertThat(report.connector().status()).isEqualTo(Status.FAIL);
        assertThat(report.connector().reason()).contains("not registered");
    }

    @Test
    void connectAnsweringAnErrorIsReportedWithItsCode() throws Exception {
        String topic = "health-it.error";
        createTopic(topic, 1);
        CONNECT_ANSWER.set(new Answer(500, "{}"));

        Report report = check(stack.kafkaBootstrapServers(), topic, connectUrl(), NOW.minusSeconds(5));

        assertThat(report.connector().reason()).isEqualTo("Kafka Connect did not answer: it answered HTTP 500");
    }

    @Test
    void kafkaAndConnectDownAreReportedAsDataNotAsExceptions() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        String nowhere = "127.0.0.1:" + closed;
        PipelineHealth health = new PipelineHealth(CONNECTOR, "health-it.down", "ecommerce.events", LIMITS,
                new KafkaLag(nowhere, GROUP, "health-it.down", Duration.ofSeconds(2)),
                new ConnectStatus("http://" + nowhere, CONNECTOR, Duration.ofSeconds(2)),
                () -> Optional.of(NOW.minusSeconds(5)), Clock.fixed(NOW, ZoneOffset.UTC));

        long started = System.nanoTime();
        Report report = health.check();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(report.status()).isEqualTo(Status.FAIL);
        assertThat(report.lag().status()).isEqualTo(Status.FAIL);
        assertThat(report.lag().reason()).startsWith("Kafka did not answer: ");
        assertThat(report.lag().messages()).isNull();
        assertThat(report.connector().status()).isEqualTo(Status.FAIL);
        assertThat(report.connector().reason()).startsWith("Kafka Connect did not answer");
        assertThat(report.freshness().status()).isEqualTo(Status.OK);
        assertThat(took).as("the tool must not hang on a dead source").isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void aTopicThatDoesNotExistIsReportedNotThrown() {
        CONNECT_ANSWER.set(new Answer(200, running()));

        Report report = check(stack.kafkaBootstrapServers(), "health-it.missing", connectUrl(), NOW.minusSeconds(5));

        assertThat(report.lag().status()).isEqualTo(Status.FAIL);
        assertThat(report.lag().reason()).isEqualTo("Topic health-it.missing does not exist in Kafka.");
    }
}
