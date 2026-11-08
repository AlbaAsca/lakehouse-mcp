package io.github.albaasca.lakehouse.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryEncoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class CsvEventReaderTest {

    private static final Path SAMPLE = Path.of("../testdata/synthetic-events.csv.gz");
    private static final String HEADER =
            "event_time,event_type,product_id,category_id,category_code,brand,price,user_id,user_session";
    private static final String GOOD_ROW =
            "2024-03-01 00:00:00 UTC,view,900000001,9000000000000000001,synth.home.lamp,synth-quorvo,12.34,900000002,abc";

    // --- Schema ---

    @Test
    void schemaFollowsTheCsvColumnsAndOnlyTheThreeAgreedFieldsAreNullable() {
        List<Schema.Field> fields = EventSchema.SCHEMA.getFields();

        assertThat(fields.stream().map(Schema.Field::name).collect(Collectors.joining(","))).isEqualTo(HEADER);
        assertThat(fields.stream().filter(f -> f.schema().isNullable()).map(Schema.Field::name))
                .containsExactly("category_code", "brand", "user_session");
        assertThat(fields.stream().filter(f -> f.schema().isNullable()).map(Schema.Field::defaultVal))
                .allMatch(v -> v == org.apache.avro.JsonProperties.NULL_VALUE);
        assertThat(EventSchema.SCHEMA.getField("event_time").schema().getLogicalType())
                .isEqualTo(LogicalTypes.timestampMicros());
    }

    // --- The versioned sample, against a reference that shares no code with the reader ---

    @Test
    void everySampleRowBecomesAnEventWithTheSameValues() throws IOException {
        List<String> rows = sampleRows();
        assertThat(rows).hasSize(50_000);

        try (CsvEventReader reader = CsvEventReader.open(SAMPLE)) {
            for (int i = 0; i < rows.size(); i++) {
                String[] expected = rows.get(i).split(",", -1);
                GenericRecord event = reader.next();
                String row = "row " + i + ": " + rows.get(i);

                assertThat(event).as(row).isNotNull();
                assertThat(event.get("event_time")).as(row).isEqualTo(referenceMicros(expected[0]));
                assertThat(event.get("event_type")).as(row).isEqualTo(expected[1]);
                assertThat(event.get("product_id")).as(row).isEqualTo(Long.valueOf(expected[2]));
                assertThat(event.get("category_id")).as(row).isEqualTo(Long.valueOf(expected[3]));
                assertThat(event.get("category_code")).as(row).isEqualTo(emptyToNull(expected[4]));
                assertThat(event.get("brand")).as(row).isEqualTo(emptyToNull(expected[5]));
                assertThat(BigDecimal.valueOf((Double) event.get("price"))).as(row)
                        .isEqualByComparingTo(new BigDecimal(expected[6]));
                assertThat(event.get("user_id")).as(row).isEqualTo(Long.valueOf(expected[7]));
                assertThat(event.get("user_session")).as(row).isEqualTo(emptyToNull(expected[8]));
            }
            assertThat(reader.next()).isNull();
            assertThat(reader.rowsRead()).isEqualTo(50_000);
            assertThat(reader.rowsSkipped()).isZero();
        }
    }

    /** Figures promised by infra/synthetic/README.md. */
    @Test
    void theDirtyCasesOfTheSampleAllSurvive() throws IOException {
        List<GenericRecord> events = readAll(CsvEventReader.open(SAMPLE));

        assertThat(events.stream().filter(e -> e.get("category_code") == null)).hasSize(16_853);
        assertThat(events.stream().filter(e -> e.get("brand") == null)).hasSize(7_261);
        assertThat(events.stream().filter(e -> e.get("user_session") == null)).hasSize(50);
        assertThat(events.stream().filter(e -> (Double) e.get("price") == 0.0)).hasSize(100);
        assertThat(events.stream().filter(e -> (Double) e.get("price") < 0.0)).hasSize(102);
        Set<String> distinct = new HashSet<>();
        events.forEach(e -> distinct.add(e.toString()));
        assertThat(distinct).hasSize(49_750);
    }

    @Test
    void sampleEventsSurviveAvroBinaryEncoding() throws IOException {
        GenericDatumWriter<GenericRecord> writer = new GenericDatumWriter<>(EventSchema.SCHEMA);
        GenericDatumReader<GenericRecord> avroReader = new GenericDatumReader<>(EventSchema.SCHEMA);

        for (GenericRecord event : readAll(CsvEventReader.open(SAMPLE))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            BinaryEncoder encoder = EncoderFactory.get().binaryEncoder(bytes, null);
            writer.write(event, encoder);
            encoder.flush();

            GenericRecord decoded = avroReader.read(null, DecoderFactory.get().binaryDecoder(bytes.toByteArray(), null));
            assertThat(decoded).isEqualTo(event);
        }
    }

    // --- Values worked out by hand ---

    @Test
    void readsARowWithHandCheckedValues() throws IOException {
        GenericRecord event = single(GOOD_ROW);

        assertThat(event.get("event_time")).isEqualTo(1_709_251_200_000_000L);
        assertThat(event.get("event_type")).isEqualTo("view");
        assertThat(event.get("product_id")).isEqualTo(900_000_001L);
        assertThat(event.get("category_id")).isEqualTo(9_000_000_000_000_000_001L);
        assertThat(event.get("category_code")).isEqualTo("synth.home.lamp");
        assertThat(event.get("brand")).isEqualTo("synth-quorvo");
        assertThat(event.get("price")).isEqualTo(12.34);
        assertThat(event.get("user_id")).isEqualTo(900_000_002L);
        assertThat(event.get("user_session")).isEqualTo("abc");
    }

    @Test
    void keepsFractionsOfASecondAndDatesBefore1970() throws IOException {
        assertThat(single(GOOD_ROW.replace("00:00:00", "00:00:00.123456")).get("event_time"))
                .isEqualTo(1_709_251_200_123_456L);
        assertThat(single(GOOD_ROW.replace("2024-03-01 00:00:00", "1969-12-31 23:59:59")).get("event_time"))
                .isEqualTo(-1_000_000L);
    }

    @Test
    void emptyNullableFieldsBecomeNull() throws IOException {
        GenericRecord event = single("2024-03-01 00:00:00 UTC,view,1,2,,,12.34,3,");

        assertThat(event.get("category_code")).isNull();
        assertThat(event.get("brand")).isNull();
        assertThat(event.get("user_session")).isNull();
    }

    @Test
    void keepsZeroAndNegativePrices() throws IOException {
        assertThat(single(GOOD_ROW.replace("12.34", "0.00")).get("price")).isEqualTo(0.0);
        assertThat(single(GOOD_ROW.replace("12.34", "-12.34")).get("price")).isEqualTo(-12.34);
    }

    @Test
    void understandsQuotedFieldsAndLeavesOtherTextAlone() throws IOException {
        GenericRecord event = single(
                "2024-03-01 00:00:00 UTC,view,1,2,\"a,b\",\"say \"\"hi\"\"\",12.34,3, spaced ");

        assertThat(event.get("category_code")).isEqualTo("a,b");
        assertThat(event.get("brand")).isEqualTo("say \"hi\"");
        assertThat(event.get("user_session")).isEqualTo(" spaced ");
    }

    @Test
    void acceptsByteOrderMarkWindowsLineEndsAndBlankLines() throws IOException {
        String csv = "﻿" + HEADER + "\r\n" + GOOD_ROW + "\r\n\r\n" + GOOD_ROW + "\r\n";

        try (CsvEventReader reader = CsvEventReader.open(stream(csv))) {
            assertThat(readAll(reader)).hasSize(2).allMatch(e -> "abc".equals(e.get("user_session")));
            assertThat(reader.rowsSkipped()).isZero();
        }
    }

    // --- Unreadable rows: counted, logged, never fatal ---

    @Test
    void unreadableRowsAreCountedAndTheReadCarriesOn() throws IOException {
        List<String> bad = List.of(
                "not,a,row",
                GOOD_ROW + ",extra",
                GOOD_ROW.replace("2024-03-01", "2024-02-30"),
                GOOD_ROW.replace("2024-03-01 00:00:00 UTC", "yesterday"),
                GOOD_ROW.replace("2024-03-01 00:00:00 UTC", ""),
                GOOD_ROW.replace(",view,", ",,"),
                GOOD_ROW.replace("900000001", ""),
                GOOD_ROW.replace("900000001", "9223372036854775808"),
                GOOD_ROW.replace("12.34", "NaN"),
                GOOD_ROW.replace("12.34", "Infinity"),
                GOOD_ROW.replace("12.34", "1.5f"),
                GOOD_ROW.replace("12.34", "0x1p3"),
                GOOD_ROW.replace("12.34", "1e999"),
                GOOD_ROW.replace("12.34", ""),
                GOOD_ROW.replace("synth-quorvo", "\"cut"));
        StringBuilder csv = new StringBuilder(HEADER).append('\n').append(GOOD_ROW).append('\n');
        bad.forEach(row -> csv.append(row).append('\n'));
        csv.append(GOOD_ROW).append('\n');

        try (CsvEventReader reader = CsvEventReader.open(stream(csv.toString()))) {
            assertThat(readAll(reader)).hasSize(2);
            assertThat(reader.rowsRead()).isEqualTo(2);
            assertThat(reader.rowsSkipped()).isEqualTo(bad.size());
        }
    }

    @Test
    void logsTheLineOfEachUnreadableRowUpToACap() throws IOException {
        Logger logger = (Logger) LoggerFactory.getLogger(CsvEventReader.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String csv = HEADER + "\n" + GOOD_ROW + "\n" + "bad\n".repeat(150);
            try (CsvEventReader reader = CsvEventReader.open(stream(csv))) {
                readAll(reader);
                assertThat(reader.rowsSkipped()).isEqualTo(150);
            }
        } finally {
            logger.detachAppender(appender);
        }

        List<String> rows = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("Skipping")).toList();
        assertThat(rows).hasSize(100);
        assertThat(rows.getFirst()).startsWith("Skipping unreadable row at line 3:");
        assertThat(appender.list).allMatch(e -> e.getLevel() == Level.WARN);
        assertThat(appender.list.getLast().getFormattedMessage())
                .isEqualTo("Skipped 150 unreadable rows, read 1 events");
    }

    // --- Whole-file problems stop the read: they must not pass for a complete file ---

    @Test
    void aWrongOrMissingHeaderStopsTheReadAndClosesTheStream() {
        for (String csv : List.of("", "a,b,c\n", GOOD_ROW + "\n")) {
            CountingStream raw = new CountingStream(stream(csv));
            assertThatThrownBy(() -> CsvEventReader.open(raw)).isInstanceOf(IOException.class);
            assertThat(raw.closed).as(csv).isTrue();
        }
    }

    @Test
    void aTruncatedGzipIsAnErrorNotASilentEndOfFile() throws IOException {
        byte[] sample = Files.readAllBytes(SAMPLE);
        byte[] cut = Arrays.copyOf(sample, sample.length / 2);

        try (CsvEventReader reader = CsvEventReader.open(new ByteArrayInputStream(cut))) {
            assertThatThrownBy(() -> readAll(reader)).isInstanceOf(EOFException.class);
            assertThat(reader.rowsRead()).isPositive();
        }
    }

    // --- File kinds and streaming ---

    @Test
    void readsPlainAndGzipFilesAlikeWhateverTheirName(@TempDir Path dir) throws IOException {
        String csv = HEADER + "\n" + GOOD_ROW + "\n";
        Path plain = Files.writeString(dir.resolve("plain.csv"), csv);
        Path gzip = Files.write(dir.resolve("packed.csv.gz"), gzip(csv));
        Path mislabelled = Files.write(dir.resolve("mislabelled.csv"), gzip(csv));

        for (Path file : List.of(plain, gzip, mislabelled)) {
            assertThat(readAll(CsvEventReader.open(file))).as(file.toString()).hasSize(1);
        }
    }

    @Test
    void plainInputIsConsumedLazily() throws IOException {
        byte[] row = (GOOD_ROW + "\n").getBytes(StandardCharsets.UTF_8);
        InputStream endless = new InputStream() {
            private long position = -HEADER.length() - 1L;

            @Override
            public int read() {
                long p = position++;
                return p < 0
                        ? (HEADER + "\n").charAt((int) (p + HEADER.length() + 1))
                        : row[(int) (p % row.length)];
            }
        };
        CountingStream raw = new CountingStream(endless);

        try (CsvEventReader reader = CsvEventReader.open(raw)) {
            for (int i = 0; i < 1_000; i++) {
                assertThat(reader.next()).isNotNull();
            }
        }
        assertThat(raw.bytes).isLessThan(1_000L * row.length + 64 * 1024);
    }

    @Test
    void gzipInputIsConsumedLazily() throws IOException {
        long fileSize = Files.size(SAMPLE);
        CountingStream raw = new CountingStream(Files.newInputStream(SAMPLE));

        try (CsvEventReader reader = CsvEventReader.open(raw)) {
            assertThat(reader.next()).isNotNull();
        }
        assertThat(raw.bytes).isLessThan(64 * 1024).isLessThan(fileSize);
    }

    // --- Helpers ---

    /** Reads the sample with plain JDK calls only. It holds no quotes: the generator emits none. */
    private static List<String> sampleRows() throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(SAMPLE)), StandardCharsets.UTF_8))) {
            List<String> all = in.lines().toList();
            assertThat(all.getFirst()).isEqualTo(HEADER);
            return all.subList(1, all.size());
        }
    }

    private static long referenceMicros(String text) {
        return Instant.parse(text.replace(" UTC", "Z").replace(' ', 'T')).getEpochSecond() * 1_000_000L;
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private static GenericRecord single(String row) throws IOException {
        try (CsvEventReader reader = CsvEventReader.open(stream(HEADER + "\n" + row + "\n"))) {
            List<GenericRecord> events = readAll(reader);
            assertThat(events).hasSize(1);
            return events.getFirst();
        }
    }

    private static List<GenericRecord> readAll(CsvEventReader reader) throws IOException {
        List<GenericRecord> events = new ArrayList<>();
        for (GenericRecord event = reader.next(); event != null; event = reader.next()) {
            events.add(event);
        }
        return events;
    }

    private static InputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] gzip(String csv) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(csv.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static final class CountingStream extends FilterInputStream {
        long bytes;
        boolean closed;

        CountingStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                bytes++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                bytes += n;
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
