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
import java.util.zip.GZIPInputStream;
import org.apache.avro.generic.GenericRecord;
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

    private static LakehouseStack stack;
    private static int exitCode;
    private static List<ConsumerRecord<String, GenericRecord>> consumed;

    @BeforeAll
    static void seedTheTopic() throws Exception {
        stack = LakehouseStack.sharedStreaming();
        exitCode = SeedCommand.execute(new String[] {
            "--input", SAMPLE.toString(),
            "--speedup", "1e12",
            "--bootstrap-servers", stack.kafkaBootstrapServers(),
            "--schema-registry-url", stack.schemaRegistryUrl()
        });
        consumed = consumeTopic();
    }

    @Test
    void theCommandSucceedsAndTheTopicHoldsEveryRow() {
        assertThat(exitCode).isEqualTo(SeedCommand.OK);
        assertThat(consumed).hasSize(SAMPLE_ROWS);
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
                        stack.schemaRegistryUrl() + "/subjects/" + KafkaEventSink.TOPIC + "-value/versions/latest"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        String schema = JsonPath.read(response.body(), "$.schema");
        assertThat(new org.apache.avro.Schema.Parser().parse(schema)).isEqualTo(EventSchema.SCHEMA);
    }

    @Test
    void preparingTheTopicAgainChangesNothing() throws Exception {
        KafkaEventSink.ensureTopic(stack.kafkaBootstrapServers());

        try (KafkaConsumer<String, GenericRecord> consumer = consumer()) {
            assertThat(consumer.partitionsFor(KafkaEventSink.TOPIC)).hasSize(KafkaEventSink.PARTITIONS);
        }
        assertThat(consumeTopic()).hasSize(SAMPLE_ROWS);
    }

    /** Everything in the topic: reads up to the end offsets, so extra rows would show up as well as missing ones. */
    private static List<ConsumerRecord<String, GenericRecord>> consumeTopic() {
        List<ConsumerRecord<String, GenericRecord>> records = new ArrayList<>();
        try (KafkaConsumer<String, GenericRecord> consumer = consumer()) {
            List<TopicPartition> partitions = consumer.partitionsFor(KafkaEventSink.TOPIC).stream()
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
