package io.github.albaasca.lakehouse.producer;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPInputStream;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The seed command against a real broker and registry: what is read back from the topic, row by row, against the CSV.
 * The expected rows are parsed here from the raw file, without the producer's reader. The sample has no quoted fields.
 */
class ProducerIT {

    private static final Path SAMPLE = Path.of("../testdata/synthetic-events.csv.gz");
    private static final int SAMPLE_ROWS = 50_000;
    private static final Duration CONSUME_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration JAR_TIMEOUT = Duration.ofMinutes(3);
    /** Its own topic, because IngestE2EIT sends the sample through the real one on the same broker. */
    private static final String TOPIC = "producer_it.events";

    private static LakehouseStack stack;
    private static JarRun seedRun;
    private static List<ConsumerRecord<String, GenericRecord>> consumed;
    private static long realTopicEndBeforeTheSeed;

    /** Through the packaged jar, as `make seed` does: on the test classpath a dependency missing from it would not show. */
    @BeforeAll
    static void seedTheTopic() throws Exception {
        stack = LakehouseStack.sharedStreaming();
        realTopicEndBeforeTheSeed = realTopicEnd();
        seedRun = runJar(
                "--input", SAMPLE.toString(),
                "--speedup", "1e12",
                "--topic", TOPIC,
                "--bootstrap-servers", stack.kafkaBootstrapServers(),
                "--schema-registry-url", stack.schemaRegistryUrl());
        consumed = consumeTopic();
    }

    @Test
    void theCommandSucceedsAndTheTopicHoldsEveryRow() {
        assertThat(seedRun.exitCode()).as(seedRun.output()).isEqualTo(SeedCommand.OK);
        assertThat(consumed).hasSize(SAMPLE_ROWS);
    }

    @Test
    void theJarReportsWhatKafkaConfirmed() {
        assertThat(seedRun.output()).contains("Kafka confirmed " + SAMPLE_ROWS + " events");
    }

    @Test
    void theJarRejectsWrongArgumentsWithItsOwnExitCode() throws Exception {
        JarRun run = runJar("--input", "does-not-exist.csv");

        assertThat(run.exitCode()).as(run.output()).isEqualTo(SeedCommand.BAD_ARGUMENTS);
        assertThat(run.output()).contains("Input file not found").contains("Usage:");
    }

    @Test
    void anUnreachableRegistryFailsTheRunAndAddsNothingToTheTopic() throws Exception {
        JarRun run = runJar(
                "--input", SAMPLE.toString(),
                "--speedup", "1e12",
                "--topic", TOPIC,
                "--bootstrap-servers", stack.kafkaBootstrapServers(),
                "--schema-registry-url", "http://localhost:1");

        assertThat(run.exitCode()).as(run.output()).isEqualTo(SeedCommand.FAILED);
        assertThat(run.output()).contains("Seed failed");
        assertThat(consumeTopic()).hasSize(SAMPLE_ROWS);
    }

    private record JarRun(int exitCode, String output) {}

    private static JarRun runJar(String... args) throws IOException, InterruptedException {
        List<Path> jars;
        // The same pattern as `make seed`: more than one match would break its `java -jar`.
        try (DirectoryStream<Path> matches = Files.newDirectoryStream(Path.of("target"), "producer-*.jar")) {
            jars = StreamSupport.stream(matches.spliterator(), false).toList();
        }
        assertThat(jars).hasSize(1);
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jars.getFirst().toString()));
        command.addAll(List.of(args));
        Path log = Files.createTempFile("seed", ".log");
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            if (!process.waitFor(JAR_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("The jar did not finish in " + JAR_TIMEOUT + ":\n" + Files.readString(log));
            }
            return new JarRun(process.exitValue(), Files.readString(log));
        } finally {
            Files.delete(log);
        }
    }

    @Test
    void everySessionArrivesCompleteAndInTimeOrderInOnePartition() throws IOException {
        Map<String, List<List<Object>>> expected = new LinkedHashMap<>();
        expectedRowsInTimeOrder().forEach(row -> expected
                .computeIfAbsent((String) row.get(8), session -> new ArrayList<>())
                .add(row));
        List<List<Object>> expectedWithoutSession = expected.remove(null);

        Map<String, List<List<Object>>> actual = new HashMap<>();
        Map<String, Integer> partitionOfSession = new HashMap<>();
        List<List<Object>> actualWithoutSession = new ArrayList<>();
        for (ConsumerRecord<String, GenericRecord> record : consumed) {
            List<Object> row = row(record.value());
            if (record.key() == null) {
                actualWithoutSession.add(row);
                continue;
            }
            assertThat(record.key()).isEqualTo(row.get(8));
            actual.computeIfAbsent(record.key(), session -> new ArrayList<>()).add(row);
            assertThat(partitionOfSession.computeIfAbsent(record.key(), session -> record.partition()))
                    .as("partition of session %s", record.key())
                    .isEqualTo(record.partition());
        }

        assertThat(expected).isNotEmpty();
        assertThat(actual.keySet()).isEqualTo(expected.keySet());
        expected.forEach((session, rows) -> assertThat(actual.get(session)).as(session).isEqualTo(rows));
        assertThat(expectedWithoutSession).as("the sample includes rows without a session").isNotEmpty();
        // No key means no fixed partition, so these rows have no order to check.
        assertThat(sorted(actualWithoutSession)).isEqualTo(sorted(expectedWithoutSession));
    }

    @Test
    void theTopicIsSpreadOverItsPartitions() {
        assertThat(consumed.stream().map(ConsumerRecord::partition).distinct().count())
                .isEqualTo(KafkaEventSink.PARTITIONS);
    }

    @Test
    void theSchemaIsRegisteredUnderTheTopicValueSubject() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        stack.schemaRegistryUrl() + "/subjects/" + TOPIC + "-value/versions/latest"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        String schema = JsonPath.read(response.body(), "$.schema");
        assertThat(new org.apache.avro.Schema.Parser().parse(schema)).isEqualTo(EventSchema.SCHEMA);
    }

    @Test
    void preparingTheTopicAgainChangesNothing() throws Exception {
        KafkaEventSink.ensureTopic(stack.kafkaBootstrapServers(), TOPIC);

        try (KafkaConsumer<String, GenericRecord> consumer = consumer()) {
            assertThat(consumer.partitionsFor(TOPIC)).hasSize(KafkaEventSink.PARTITIONS);
        }
        assertThat(consumeTopic()).hasSize(SAMPLE_ROWS);
    }

    /** The isolation that lets this class share a broker with IngestE2EIT, in whichever order they run. */
    @Test
    void aSeedWithItsOwnTopicLeavesTheRealTopicAlone() throws Exception {
        assertThat(realTopicEnd()).isEqualTo(realTopicEndBeforeTheSeed);
    }

    /** Sum of the end offsets of the real topic, 0 if it does not exist. Through Admin, which never creates it. */
    private static long realTopicEnd() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, stack.kafkaBootstrapServers()))) {
            String real = KafkaEventSink.TOPIC;
            if (!admin.listTopics().names().get().contains(real)) {
                return 0;
            }
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            admin.describeTopics(List.of(real)).allTopicNames().get().get(real).partitions()
                    .forEach(info -> latest.put(new TopicPartition(real, info.partition()), OffsetSpec.latest()));
            return admin.listOffsets(latest).all().get().values().stream()
                    .mapToLong(ListOffsetsResult.ListOffsetsResultInfo::offset)
                    .sum();
        }
    }

    /** Everything in the topic: reads up to the end offsets, so extra rows would show up as well as missing ones. */
    private static List<ConsumerRecord<String, GenericRecord>> consumeTopic() {
        List<ConsumerRecord<String, GenericRecord>> records = new ArrayList<>();
        try (KafkaConsumer<String, GenericRecord> consumer = consumer()) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
                    .map(info -> new TopicPartition(info.topic(), info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            long deadline = System.nanoTime() + CONSUME_TIMEOUT.toNanos();
            while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
                assertThat(System.nanoTime()).as("reading the topic in time").isLessThan(deadline);
                consumer.poll(Duration.ofSeconds(1)).forEach(records::add);
            }
        }
        return records;
    }

    private static KafkaConsumer<String, GenericRecord> consumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, stack.kafkaBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class,
                AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, stack.schemaRegistryUrl()));
    }

    /** Stable, so rows with the same time keep the order of the file, as the replay promises. */
    private static List<List<Object>> expectedRowsInTimeOrder() throws IOException {
        List<List<Object>> rows = new ArrayList<>();
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(SAMPLE)), StandardCharsets.UTF_8))) {
            lines.readLine();
            for (String line = lines.readLine(); line != null; line = lines.readLine()) {
                assertThat(line).doesNotContain("\"");
                rows.add(csvRow(line.split(",", -1)));
            }
        }
        assertThat(rows).hasSize(SAMPLE_ROWS);
        rows.sort(Comparator.comparingLong(row -> (Long) row.getFirst()));
        return rows;
    }

    private static List<Object> csvRow(String[] fields) {
        assertThat(fields).hasSize(9);
        LocalDateTime time = LocalDateTime.parse(fields[0].replace(" UTC", "").replace(' ', 'T'));
        return Arrays.asList(
                ChronoUnit.MICROS.between(LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC), time),
                fields[1],
                Long.valueOf(fields[2]),
                Long.valueOf(fields[3]),
                fields[4].isEmpty() ? null : fields[4],
                fields[5].isEmpty() ? null : fields[5],
                new BigDecimal(fields[6]).doubleValue(),
                Long.valueOf(fields[7]),
                fields[8].isEmpty() ? null : fields[8]);
    }

    private static List<Object> row(GenericRecord event) {
        return Arrays.asList(
                event.get("event_time"),
                text(event.get("event_type")),
                event.get("product_id"),
                event.get("category_id"),
                text(event.get("category_code")),
                text(event.get("brand")),
                event.get("price"),
                event.get("user_id"),
                text(event.get("user_session")));
    }

    /** Avro decodes strings to its own text type. */
    private static String text(Object value) {
        return value != null ? value.toString() : null;
    }

    private static List<List<Object>> sorted(List<List<Object>> rows) {
        return rows.stream().sorted(Comparator.comparing(List::toString)).toList();
    }
}
