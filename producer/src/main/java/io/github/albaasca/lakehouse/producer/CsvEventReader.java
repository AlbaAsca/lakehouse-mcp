package io.github.albaasca.lakehouse.producer;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streams events out of a .csv or .csv.gz file, one line at a time, so the file size does not matter.
 *
 * <p>Dirty data stays: empty nullable fields become null and odd prices or duplicates pass through untouched. A row
 * that cannot become a valid event is counted, logged and skipped. A wrong header or an I/O error (such as a truncated
 * gzip) does stop the read, because carrying on would present a broken file as a complete one. Bytes that are not valid
 * UTF-8 become U+FFFD instead of losing the row.
 */
public final class CsvEventReader implements EventSource, Closeable {

    private static final Logger log = LoggerFactory.getLogger(CsvEventReader.class);
    private static final int MAX_LOGGED_ROWS = 100;
    private static final String EXPECTED_HEADER = EventSchema.SCHEMA.getFields().stream()
            .map(Schema.Field::name)
            .collect(Collectors.joining(","));
    private static final int COLUMNS = EventSchema.SCHEMA.getFields().size();
    // STRICT: the default resolver would silently turn February 30 into February 29.
    private static final DateTimeFormatter EVENT_TIME = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 6, true)
            .optionalEnd()
            .appendLiteral(" UTC")
            .toFormatter()
            .withResolverStyle(ResolverStyle.STRICT);

    private final BufferedReader lines;
    private long lineNumber;
    private long rowsRead;
    private long rowsSkipped;

    public static CsvEventReader open(Path path) throws IOException {
        return open(Files.newInputStream(path));
    }

    /** Takes ownership of the stream: it is closed with the reader, or right here if the file cannot be read. */
    static CsvEventReader open(InputStream raw) throws IOException {
        try {
            return new CsvEventReader(raw);
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }

    private CsvEventReader(InputStream raw) throws IOException {
        lines = new BufferedReader(new InputStreamReader(decompressIfGzip(raw), StandardCharsets.UTF_8));
        String header = lines.readLine();
        lineNumber = 1;
        if (header == null) {
            throw new IOException("Empty CSV: no header");
        }
        String clean = header.startsWith("﻿") ? header.substring(1) : header;
        if (!clean.equals(EXPECTED_HEADER)) {
            throw new IOException("Unexpected CSV header: " + clean);
        }
    }

    /** Goes by content rather than by file name, so a mislabelled file still reads correctly. */
    private static InputStream decompressIfGzip(InputStream raw) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw);
        in.mark(2);
        int first = in.read();
        int second = in.read();
        in.reset();
        return first == 0x1f && second == 0x8b ? new GZIPInputStream(in) : in;
    }

    /** The next event, or null at the end of the file. */
    @Override
    public GenericRecord next() throws IOException {
        String line;
        while ((line = lines.readLine()) != null) {
            lineNumber++;
            if (line.isBlank()) {
                continue;
            }
            try {
                GenericRecord event = parse(line);
                rowsRead++;
                return event;
            } catch (BadRow e) {
                skip(e);
            }
        }
        return null;
    }

    public long rowsRead() {
        return rowsRead;
    }

    public long rowsSkipped() {
        return rowsSkipped;
    }

    @Override
    public void close() throws IOException {
        lines.close();
        if (rowsSkipped > 0) {
            log.warn("Skipped {} unreadable rows, read {} events", rowsSkipped, rowsRead);
        }
    }

    private void skip(BadRow e) {
        rowsSkipped++;
        if (rowsSkipped <= MAX_LOGGED_ROWS) {
            log.warn("Skipping unreadable row at line {}: {}", lineNumber, e.getMessage());
        }
        if (rowsSkipped == MAX_LOGGED_ROWS) {
            log.warn("Reached {} unreadable rows: further ones are only counted", MAX_LOGGED_ROWS);
        }
    }

    private static GenericRecord parse(String line) throws BadRow {
        List<String> fields = split(line);
        if (fields.size() != COLUMNS) {
            throw new BadRow("expected " + COLUMNS + " fields, found " + fields.size());
        }
        GenericRecord event = new GenericData.Record(EventSchema.SCHEMA);
        event.put("event_time", required("event_time", fields.get(0), CsvEventReader::parseEventTime));
        if (fields.get(1).isBlank()) {
            throw new BadRow("event_type is empty");
        }
        event.put("event_type", fields.get(1));
        event.put("product_id", required("product_id", fields.get(2), Long::valueOf));
        event.put("category_id", required("category_id", fields.get(3), Long::valueOf));
        event.put("category_code", nullIfEmpty(fields.get(4)));
        event.put("brand", nullIfEmpty(fields.get(5)));
        event.put("price", required("price", fields.get(6), CsvEventReader::parsePrice));
        event.put("user_id", required("user_id", fields.get(7), Long::valueOf));
        event.put("user_session", nullIfEmpty(fields.get(8)));
        return event;
    }

    private static <T> T required(String column, String raw, Function<String, T> parser) throws BadRow {
        String text = raw.strip();
        if (text.isEmpty()) {
            throw new BadRow(column + " is empty");
        }
        try {
            return parser.apply(text);
        } catch (RuntimeException e) {
            throw new BadRow(column + " is unreadable: " + raw);
        }
    }

    private static String nullIfEmpty(String value) {
        return value.isEmpty() ? null : value;
    }

    private static Long parseEventTime(String text) {
        LocalDateTime time = LocalDateTime.parse(text, EVENT_TIME);
        return Math.addExact(Math.multiplyExact(time.toEpochSecond(ZoneOffset.UTC), 1_000_000L), time.getNano() / 1_000L);
    }

    /** Through BigDecimal because Double.parseDouble also accepts NaN, Infinity, "1.5f" and hexadecimal. */
    private static Double parsePrice(String text) {
        double price = new BigDecimal(text).doubleValue();
        if (Double.isInfinite(price)) {
            throw new NumberFormatException("out of range");
        }
        return price;
    }

    /** RFC 4180 fields on a single line. A quoted line break cannot be told apart from a cut row: it counts as bad. */
    private static List<String> split(String line) throws BadRow {
        List<String> fields = new ArrayList<>(COLUMNS);
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c != '"') {
                    field.append(c);
                } else if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = false;
                }
            } else if (c == '"' && field.isEmpty()) {
                quoted = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        if (quoted) {
            throw new BadRow("unbalanced quote");
        }
        fields.add(field.toString());
        return fields;
    }

    private static final class BadRow extends Exception {
        BadRow(String reason) {
            super(reason, null, false, false);
        }
    }
}
