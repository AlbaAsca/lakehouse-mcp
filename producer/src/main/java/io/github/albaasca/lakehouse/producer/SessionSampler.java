package io.github.albaasca.lakehouse.producer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.StringJoiner;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

/**
 * Keeps or drops whole sessions, so a sample never breaks a view-cart-purchase path.
 *
 * <p>Each session falls in a fixed bucket given by a hash of its id, and the sample is the buckets below a cutoff. The
 * same settings therefore pick the same rows on every run and JVM, a smaller sample is contained in a larger one, and
 * the choice does not depend on file order. A row without a session is hashed as a whole, so identical rows share their
 * fate.
 */
public final class SessionSampler {

    /** 2^20 buckets: enough for a resolution of about one row in a million. */
    static final int BUCKETS = 1 << 20;

    private static final int BUCKET_SHIFT = Long.SIZE - Integer.numberOfTrailingZeros(BUCKETS);
    private static final String ROW_FIELD_SEPARATOR = "\u0001";

    private final int cutoff;

    private SessionSampler(int cutoff) {
        this.cutoff = cutoff;
    }

    public static SessionSampler all() {
        return new SessionSampler(BUCKETS);
    }

    /** Keeps about this fraction of the sessions, between one in a million and 1. */
    public static SessionSampler ofRate(double rate) {
        if (!(rate >= 1.0 / BUCKETS && rate <= 1.0)) {
            throw new IllegalArgumentException("Sample rate must be between " + 1.0 / BUCKETS + " and 1: " + rate);
        }
        return new SessionSampler((int) Math.round(rate * BUCKETS));
    }

    /**
     * Keeps as many buckets as fit in maxRows, without ever going over it. Needs a full pass over the source first and
     * then a fresh source for the replay. Cutting at the first maxRows rows instead would only cover the start of the
     * month and leave its last sessions unfinished.
     */
    public static SessionSampler ofMaxRows(long maxRows, EventSource source) throws IOException {
        if (maxRows <= 0) {
            throw new IllegalArgumentException("Max rows must be positive: " + maxRows);
        }
        long[] rowsPerBucket = new long[BUCKETS];
        GenericRecord event;
        while ((event = source.next()) != null) {
            rowsPerBucket[bucket(event)]++;
        }
        long rows = 0;
        int cutoff = 0;
        while (cutoff < BUCKETS && rows + rowsPerBucket[cutoff] <= maxRows) {
            rows += rowsPerBucket[cutoff];
            cutoff++;
        }
        return new SessionSampler(cutoff);
    }

    public boolean keep(GenericRecord event) {
        return bucket(event) < cutoff;
    }

    static int bucket(GenericRecord event) {
        Object session = event.get("user_session");
        return bucket(session != null ? session.toString() : rowText(event));
    }

    /** FNV-1a 64 to hash, then the murmur3 finalizer because FNV alone mixes the top bits poorly. */
    static int bucket(String text) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hash = (hash ^ (b & 0xff)) * 0x100000001b3L;
        }
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return (int) (hash >>> BUCKET_SHIFT);
    }

    private static String rowText(GenericRecord event) {
        StringJoiner row = new StringJoiner(ROW_FIELD_SEPARATOR);
        for (Schema.Field field : event.getSchema().getFields()) {
            row.add(String.valueOf(event.get(field.pos())));
        }
        return row.toString();
    }
}
