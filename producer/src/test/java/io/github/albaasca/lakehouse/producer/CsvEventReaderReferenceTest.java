package io.github.albaasca.lakehouse.producer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/** The tester's checks: a reference computed outside Java, and hostile inputs built byte by byte. */
class CsvEventReaderReferenceTest {

    private static final Path SAMPLE = Path.of("../testdata/synthetic-events.csv.gz");
    private static final String HEADER =
            "event_time,event_type,product_id,category_id,category_code,brand,price,user_id,user_session";
    private static final String GOOD_ROW =
            "2024-03-01 00:00:00 UTC,view,900000001,9000000000000000001,synth.home.lamp,synth-quorvo,12.34,900000002,abc";

    /** Output of scripts/analysis/t008_reference.py: Python standard library only, one digest per column. */
    private static final Map<String, String> REFERENCE = Map.of(
            "event_time", "67f34ed6518d4582d3d731560e7942818b4a6ad18e592c806685233620a1b1bc",
            "event_type", "ec92c8c8f0491c6e3ad64e823a0388709ddfef9ceb8a93b909834e64a9a1764d",
            "product_id", "dd49d3397c78d54543f53db48120a5f11b2e4f4db2598cc17fc0dde143d4a0f0",
            "category_id", "02fdd7b299ac4c49d1ffec1d28d086750655bfac96d7bbbabe94713e0c392d3f",
            "category_code", "4be14dce0c1ad803c4d108d377572cf275622012f9fc7247d2536e571a396a5b",
            "brand", "86e05bf71c4d5438238dd87b70d06aef001e732b0b37e91a6586b78a2848f6a5",
            "price", "36b5fa281fea0f9371950123383bbe7d820753399329c70e407da6abe771a10e",
            "user_id", "02667595e3f6d582d337dea5e082fb1ac238ff9aea7e59a29ff2ccac70e6af51",
            "user_session", "d4f2dacde38891e2db5c511aab6a64bb0c5d9cb7b159d91639fd9097d1cf26ba");

    @Test
    void everyColumnOfTheSampleMatchesTheReferenceComputedOutsideJava() throws Exception {
        Map<String, MessageDigest> digests = new LinkedHashMap<>();
        for (String column : REFERENCE.keySet()) {
            digests.put(column, sha256());
        }

        try (CsvEventReader reader = CsvEventReader.open(SAMPLE)) {
            for (GenericRecord event = reader.next(); event != null; event = reader.next()) {
                for (Map.Entry<String, MessageDigest> column : digests.entrySet()) {
                    column.getValue().update((canonical(event, column.getKey()) + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            assertThat(reader.rowsRead()).isEqualTo(50_000);
            assertThat(reader.rowsSkipped()).isZero();
        }

        for (Map.Entry<String, MessageDigest> column : digests.entrySet()) {
            assertThat(HexFormat.of().formatHex(column.getValue().digest())).as(column.getKey())
                    .isEqualTo(REFERENCE.get(column.getKey()));
        }
    }

    @Test
    void aRealByteOrderMarkIsAccepted() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        bytes.write(utf8(HEADER + "\n" + GOOD_ROW + "\n"));

        assertThat(readAll(bytes.toByteArray())).hasSize(1);
        assertThat(readAll(gzip(bytes.toByteArray()))).hasSize(1);
    }

    @Test
    void invalidUtf8KeepsTheRowAndItsOtherValues() throws IOException {
        String tail = GOOD_ROW.substring(GOOD_ROW.indexOf("synth-quorvo") + "synth-quorvo".length());
        String head = HEADER + "\n" + GOOD_ROW.substring(0, GOOD_ROW.indexOf("synth-quorvo"));
        ByteArrayOutputStream dirty = new ByteArrayOutputStream();
        dirty.write(utf8(head));
        dirty.write(new byte[] {'q', (byte) 0xFF, 'u'});
        dirty.write(utf8(tail + "\n"));

        List<GenericRecord> events = readAll(dirty.toByteArray());

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("brand")).isEqualTo("q�u");
        assertThat(events.getFirst().get("user_session")).isEqualTo("abc");
    }

    @Test
    void readsEveryMemberOfAConcatenatedGzip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(gzip(utf8(HEADER + "\n" + GOOD_ROW + "\n")));
        bytes.write(gzip(utf8(GOOD_ROW + "\n" + GOOD_ROW + "\n")));

        assertThat(readAll(bytes.toByteArray())).hasSize(3);
    }

    @Test
    void edgesOfAFile() throws IOException {
        assertThat(readAll(utf8(HEADER + "\n"))).isEmpty();
        assertThat(readAll(utf8(HEADER))).isEmpty();
        assertThat(readAll(utf8(HEADER + "\n" + GOOD_ROW))).as("no final line break").hasSize(1);
    }

    @Test
    void timesWorkedOutByHand() throws IOException {
        assertThat(time("1969-12-31 23:59:59.5 UTC")).as("half a second before the epoch").isEqualTo(-500_000L);
        assertThat(time("1970-01-01 00:00:00 UTC")).isZero();
        assertThat(time("2024-02-29 12:00:00 UTC")).as("leap day").isEqualTo(1_709_208_000_000_000L);
    }

    @Test
    void impossibleTimesAreSkippedWithoutStoppingTheRead() throws IOException {
        List<String> bad = List.of(
                "2023-02-29 00:00:00 UTC",
                "2024-03-01 25:00:00 UTC",
                "2024-03-01 00:00:00",
                "2024-03-01 00:00:00.1234567 UTC",
                "+999999-01-01 00:00:00 UTC");
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        bad.forEach(t -> csv.append(GOOD_ROW.replace("2024-03-01 00:00:00 UTC", t)).append('\n'));
        csv.append(GOOD_ROW).append('\n');

        try (CsvEventReader reader = CsvEventReader.open(new ByteArrayInputStream(utf8(csv.toString())))) {
            assertThat(drain(reader)).hasSize(1);
            assertThat(reader.rowsSkipped()).isEqualTo(bad.size());
        }
    }

    @Test
    void aQuoteInsideAnUnquotedFieldIsJustText() throws IOException {
        List<GenericRecord> events = readAll(utf8(HEADER + "\n" + GOOD_ROW.replace("synth-quorvo", "5\" lamp") + "\n"));

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().get("brand")).isEqualTo("5\" lamp");
    }

    private static long time(String text) throws IOException {
        return (Long) readAll(utf8(HEADER + "\n" + GOOD_ROW.replace("2024-03-01 00:00:00 UTC", text) + "\n"))
                .getFirst().get("event_time");
    }

    private static String canonical(GenericRecord event, String column) {
        Object value = event.get(column);
        if (value == null) {
            return "<null>";
        }
        return column.equals("price")
                ? BigDecimal.valueOf((Double) value).movePointRight(2).toBigIntegerExact().toString()
                : value.toString();
    }

    private static MessageDigest sha256() throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256");
    }

    private static List<GenericRecord> readAll(byte[] file) throws IOException {
        try (CsvEventReader reader = CsvEventReader.open(new ByteArrayInputStream(file))) {
            return drain(reader);
        }
    }

    private static List<GenericRecord> drain(CsvEventReader reader) throws IOException {
        List<GenericRecord> events = new ArrayList<>();
        for (GenericRecord event = reader.next(); event != null; event = reader.next()) {
            events.add(event);
        }
        return events;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] gzip(byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
            out.write(content);
        }
        return bytes.toByteArray();
    }
}
