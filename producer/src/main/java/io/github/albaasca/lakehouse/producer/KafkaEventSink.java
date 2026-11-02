package io.github.albaasca.lakehouse.producer;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sends events to the topic as Avro, keyed by session so that one session stays in one partition and keeps its order.
 *
 * <p>Sends are asynchronous, so a failure surfaces on the next {@link #accept} or at the latest on {@link #close}: a run
 * that closes cleanly had every event confirmed by the broker. The record timestamp is left to the send time, because
 * the original 2019 times would put the events past the retention of the topic.
 */
public final class KafkaEventSink implements EventReplay.EventSink, Closeable {

    public static final String TOPIC = "ecommerce.events";
    static final int PARTITIONS = 6;

    private static final Logger log = LoggerFactory.getLogger(KafkaEventSink.class);

    private final Producer<String, GenericRecord> producer;
    private final String topic;
    private final AtomicReference<Exception> failure = new AtomicReference<>();
    private final AtomicLong confirmed = new AtomicLong();

    KafkaEventSink(Producer<String, GenericRecord> producer) {
        this(producer, TOPIC);
    }

    KafkaEventSink(Producer<String, GenericRecord> producer, String topic) {
        this.producer = producer;
        this.topic = topic;
    }

    public static KafkaEventSink open(String bootstrapServers, String schemaRegistryUrl, String topic) {
        return new KafkaEventSink(
                new KafkaProducer<>(Map.of(
                        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                        ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class,
                        AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl)),
                topic);
    }

    /** Creates the topic if it is missing. An existing topic is left as it is, whatever its number of partitions. */
    public static void ensureTopic(String bootstrapServers, String name) throws IOException, InterruptedException {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            NewTopic topic = new NewTopic(name, Optional.of(PARTITIONS), Optional.empty());
            try {
                admin.createTopics(List.of(topic)).all().get();
                log.info("Created topic {} with {} partitions", name, PARTITIONS);
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof TopicExistsException)) {
                    throw e;
                }
                int partitions = admin.describeTopics(List.of(name)).allTopicNames().get().get(name).partitions().size();
                log.info("Topic {} already exists with {} partitions", name, partitions);
            }
        } catch (ExecutionException e) {
            throw new IOException("Cannot prepare topic " + name + " on " + bootstrapServers, e.getCause());
        }
    }

    @Override
    public void accept(GenericRecord event) throws IOException {
        throwIfFailed();
        Object session = event.get("user_session");
        String key = session != null ? session.toString() : null;
        producer.send(new ProducerRecord<>(topic, key, event), (metadata, exception) -> {
            if (exception != null) {
                failure.compareAndSet(null, exception);
            } else {
                confirmed.incrementAndGet();
            }
        });
    }

    /** Events the broker has confirmed so far. */
    public long confirmed() {
        return confirmed.get();
    }

    /** Waits for every pending event. */
    @Override
    public void close() throws IOException {
        producer.close();
        throwIfFailed();
    }

    private void throwIfFailed() throws IOException {
        Exception first = failure.get();
        if (first != null) {
            throw new IOException("Kafka rejected an event after " + confirmed.get() + " confirmed", first);
        }
    }
}
