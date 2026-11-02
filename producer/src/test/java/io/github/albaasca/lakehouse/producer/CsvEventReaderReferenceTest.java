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
            "event_time", "734e3d100c0b93b717007a0c9693ad3937c3c0b99c1a04d30399b67be6022873",
            "event_type", "23bfc2d0a496296e29209f1fd7d35247a1053e4d1712581f98d866356d6b29ba",
            "product_id", "e1a3b55b215688b07c7bef03120212d82111782cb097df655e256b64623eff53",
            "category_id", "a32ae2a9fa6bb9f424ef6592771414512ad0a0e437c863a222ea4be28d83d6ad",
            "category_code", "f0fdce645c0e318d8af92107fe9d698b6f4179bbafc87b93a0df7e7b49856c8d",
            "brand", "8959462f1f4d0d87f8fd7026ec2c8fa6f108721644a844e2f12b03d33ab13924",
            "price", "d4357082a7c83c56efa109b0c948febadd1d83f2e589e523745d67b127c4f9bf",
            "user_id", "03106a0619302ebd0e405fc1545d8500d697fa11750ed8e6efad2040641ea877",
            "user_session", "6031b108f2892a38aa44e409d8d69d085a12cfa58e778d53216c3279ee621583");

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
