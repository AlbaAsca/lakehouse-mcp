package io.github.albaasca.lakehouse.producer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.apache.avro.Schema;

/** The Avro schema of the events, loaded from event.avsc. */
public final class EventSchema {

    public static final Schema SCHEMA = load();

    private EventSchema() {
    }

    private static Schema load() {
        try (InputStream in = EventSchema.class.getResourceAsStream("/event.avsc")) {
            if (in == null) {
                throw new IllegalStateException("event.avsc is not on the classpath");
            }
            return new Schema.Parser().parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
