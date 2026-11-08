package io.github.albaasca.lakehouse.producer;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chaos scenario {@code schema-drift}: from now on the producer sends a v2 of the event schema with one more optional
 * field (nullable, default null, so Schema Registry accepts it as compatible). The sink has schema evolution off, so
 * Iceberg drops the field without an error until the column is added to the table.
 *
 * <p>Options: {@code --field <name>} (default {@value #DEFAULT_FIELD}), {@code --count <n>} (default 20),
 * {@code --bootstrap-servers}, {@code --schema-registry-url}, {@code --topic}. Events are synthetic and stamped now.
 */
public final class SchemaDriftCommand {

    static final String DEFAULT_FIELD = "coupon_code";
    /** Value every drifted event carries in the new field, so a test can tell filled rows from old ones. */
    static final String FIELD_VALUE = "DRIFT10";

    private static final Logger log = LoggerFactory.getLogger(SchemaDriftCommand.class);

    private SchemaDriftCommand() {
    }

    public static void main(String[] argv) {
        System.exit(execute(argv));
    }

    static int execute(String[] argv) {
        String field = DEFAULT_FIELD;
        int count = 20;
        String bootstrap = "kafka:9092";
        String registry = "http://schema-registry:8081";
        String topic = KafkaEventSink.TOPIC;
        try {
            for (int i = 0; i < argv.length; i += 2) {
                if (i + 1 == argv.length) {
                    throw new IllegalArgumentException("Missing value or unknown option: " + argv[i]);
                }
                switch (argv[i]) {
                    case "--field" -> field = argv[i + 1];
                    case "--count" -> count = Integer.parseInt(argv[i + 1]);
                    case "--bootstrap-servers" -> bootstrap = argv[i + 1];
                    case "--schema-registry-url" -> registry = argv[i + 1];
                    case "--topic" -> topic = argv[i + 1];
                    default -> throw new IllegalArgumentException("Unknown option: " + argv[i]);
                }
            }
            if (count <= 0) {
                throw new IllegalArgumentException("--count must be positive: " + count);
            }
            schemaV2(field);
        } catch (IllegalArgumentException e) {
            log.error("{}\nUsage: [--field <name>] [--count <n>] [--bootstrap-servers <host:port>] "
                    + "[--schema-registry-url <url>] [--topic <name>]", e.getMessage());
            return SeedCommand.BAD_ARGUMENTS;
        }
        try {
            KafkaEventSink.ensureTopic(bootstrap, topic);
            KafkaEventSink sink = KafkaEventSink.open(bootstrap, registry, topic);
            try (sink) {
                send(sink, field, count, Clock.systemUTC());
            }
            log.info("Kafka confirmed {} events with the new field {}", sink.confirmed(), field);
            return SeedCommand.OK;
        } catch (IOException | RuntimeException e) {
            log.error("Schema drift failed", e);
            return SeedCommand.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 130;
        }
    }

    /** The event schema plus one nullable string field with default null (a compatible change). */
    static Schema schemaV2(String field) {
        if (field == null || !field.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("--field must be a plain name: " + field);
        }
        if (EventSchema.SCHEMA.getField(field) != null) {
            throw new IllegalArgumentException("The field " + field + " already exists in the event schema");
        }
        List<Schema.Field> fields = new ArrayList<>();
        for (Schema.Field f : EventSchema.SCHEMA.getFields()) {
            fields.add(new Schema.Field(f.name(), f.schema(), f.doc(), f.defaultVal()));
        }
        Schema optionalString = Schema.createUnion(Schema.create(Schema.Type.NULL), Schema.create(Schema.Type.STRING));
        fields.add(new Schema.Field(field, optionalString, "Added by the schema-drift chaos scenario.", Schema.Field.NULL_DEFAULT_VALUE));
        Schema v2 = Schema.createRecord(EventSchema.SCHEMA.getName(), EventSchema.SCHEMA.getDoc(),
                EventSchema.SCHEMA.getNamespace(), false);
        v2.setFields(fields);
        return v2;
    }

    /** Sends {@code count} synthetic events that carry the new field. */
    static void send(EventReplay.EventSink sink, String field, int count, Clock clock) throws IOException {
        Schema v2 = schemaV2(field);
        long nowMicros = clock.instant().getEpochSecond() * 1_000_000L + clock.instant().getNano() / 1_000;
        for (int i = 0; i < count; i++) {
            GenericRecord event = new GenericData.Record(v2);
            event.put("event_time", nowMicros + i);
            event.put("event_type", "view");
            event.put("product_id", 1000L + i);
            event.put("category_id", 1L);
            event.put("category_code", null);
            event.put("brand", null);
            event.put("price", 9.99);
            event.put("user_id", 42L);
            event.put("user_session", "drift-session-" + (i % 5));
            event.put(field, FIELD_VALUE);
            sink.accept(event);
        }
    }
}
