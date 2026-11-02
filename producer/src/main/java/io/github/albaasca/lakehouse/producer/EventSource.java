package io.github.albaasca.lakehouse.producer;

import java.io.IOException;
import org.apache.avro.generic.GenericRecord;

/** Events pulled one at a time, so the source can be larger than memory. */
public interface EventSource {

    /** The next event, or null at the end. */
    GenericRecord next() throws IOException;
}
