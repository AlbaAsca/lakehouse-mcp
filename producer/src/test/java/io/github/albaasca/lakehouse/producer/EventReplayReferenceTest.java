package io.github.albaasca.lakehouse.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The tester's check: every row the replay delivers, its order, its timestamp and its moment, against
 * scripts/analysis/t011_reference.py (Python standard library and exact arithmetic, no code shared with the producer).
 * The sample is in time order, so the disordered scenarios cut it in blocks of 50 rows and turn each one back to front.
 */
class EventReplayReferenceTest {

    private static final Path SAMPLE = Path.of("../testdata/synthetic-events.csv.gz");
    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00.123456789Z");
    private static final int WINDOW = EventReplay.DEFAULT_REORDER_WINDOW;

    @FunctionalInterface
    private interface SamplerFactory {
        SessionSampler create() throws IOException;
    }

    private record Scenario(
            String name,
            SamplerFactory sampler,
            double speedup,
            boolean rewrite,
            int window,
            int disorderedBlock,
            long count,
            long late,
            String sha256) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Scenario> scenarios() {
        return Stream.of(
                new Scenario("rate01", rate(0.01), 1, false, WINDOW, 0, 552, 0,
                        "50ea046666d52241e941ece06117630a60f86f87ed26e29a8640b7a2740977fd"),
                new Scenario("rate10", rate(0.1), 1, false, WINDOW, 0, 4857, 0,
                        "e726f589000bdbcddafd39f462d8d79459b2537542b640cd0fa6737e2648f7f4"),
                new Scenario("rate50", rate(0.5), 1, false, WINDOW, 0, 24855, 0,
                        "2e25be2dc3f2f3bd7fbc991cabb1650c0ba219e8987c77e2d39cb98edc1e0e04"),
                new Scenario("max1", maxRows(1), 1, false, WINDOW, 0, 1, 0,
                        "c41f4a34c0c9f5e29e3fb40cb5304563284f969200b74dc827127dc5972b4d36"),
                new Scenario("max500", maxRows(500), 1, false, WINDOW, 0, 497, 0,
                        "836aea00d798db3451005dda1fab24dfa1e199a1e9f0b69470eeb6412035c0bf"),
                new Scenario("max12500", maxRows(12_500), 1, false, WINDOW, 0, 12_500, 0,
                        "ae8973cdb425facfa220e9406fbc9aeb7e723b8ab50f7e4acf022e66642449ec"),
                new Scenario("max50000", maxRows(50_000), 1, false, WINDOW, 0, 50_000, 0,
                        "939dcfacc8295782900dc25a8e993e3823a9182043b174397117dd385c852810"),
                new Scenario("allSpeed60", SessionSampler::all, 60, false, WINDOW, 0, 50_000, 0,
                        "93467dd30a627864413a36426a640c0e1295ca517f8f39caedd2c3416d54ca2d"),
                new Scenario("allSpeed7", SessionSampler::all, 7, false, WINDOW, 0, 50_000, 0,
                        "c250ee90f0585b18bc06ec970346fbf4d48469ddb50caef61858e697fbb50462"),
                new Scenario("allSpeed1234_5", SessionSampler::all, 1234.5, false, WINDOW, 0, 50_000, 0,
                        "eaf944229138715e3664e71a1566d0bb402044a035465ade27d1ed443400a3a2"),
                new Scenario("allHalfSpeed", SessionSampler::all, 0.5, false, WINDOW, 0, 50_000, 0,
                        "3618ebfe2076c43e7977e377bbfb70ab7946dac507b1fc7b802418d884e85ab3"),
                new Scenario("allRewrite", SessionSampler::all, 3600, true, WINDOW, 0, 50_000, 0,
                        "422f54aa75a72fff1209e98120313d6e2218053abddeb3859f4bcd608569329b"),
                new Scenario("rate10Rewrite", rate(0.1), 7, true, WINDOW, 0, 4857, 0,
                        "8def7300cb5f7e7cd5e52842c789807b891afe9b3e94215bda179bb048bd23ba"),
                new Scenario("allWindow0", SessionSampler::all, 60, false, 0, 0, 50_000, 0,
                        "93467dd30a627864413a36426a640c0e1295ca517f8f39caedd2c3416d54ca2d"),
                new Scenario("allWindow10", SessionSampler::all, 60, true, 10, 0, 50_000, 0,
                        "ff850035c360d3481a82359691786978f04a2ff015c69c54584f8d331b88685c"),
                new Scenario("rate50Window1000", rate(0.5), 60, true, 1_000, 0, 24855, 0,
                        "7b54aee18926af8608cce2442666107d1f84e85d9347f135f81cccb79b1ce290"),
                new Scenario("disorderedWindow49", SessionSampler::all, 60, true, 49, 50, 50_000, 0,
                        "dae88c08bd6260d5c865396a8c1d6c0dd6e9b246dfbfad2136e1641c9e0f58b6"),
                new Scenario("disorderedWindow48", SessionSampler::all, 60, true, 48, 50, 50_000, 972,
                        "5f69c4ad05c717d7508f02bf6ac976e2ec926750b4490052553a6ebc0d6783c2"),
                new Scenario("disorderedWindow0", SessionSampler::all, 60, true, 0, 50, 50_000, 48_976,
                        "0041a123075231e92f036645e3eb64031bdc38b76fd8cf823c987ae800d30200"),
                new Scenario("disorderedRate50", rate(0.5), 7, false, WINDOW, 50, 24855, 0,
                        "c7ee235beb886fd43d3d8195f0115b2c8413342f14cf85586f0440e609387bba"));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    void everyDeliveredRowMatchesTheReference(Scenario scenario) throws Exception {
        List<GenericRecord> rows = readSample();
        assertThat(rows).hasSize(50_000);
        Map<GenericRecord, Integer> indexOf = new IdentityHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            indexOf.put(rows.get(i), i);
        }
        SimulatedClock clock = new SimulatedClock(NOW);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        EventReplay.Options options = new EventReplay.Options(
                scenario.sampler().create(), scenario.speedup(), scenario.rewrite(), scenario.window());

        EventReplay.Result result = new EventReplay(options, clock, clock::advance)
                .run(sourceOf(feed(rows, scenario.disorderedBlock())), event -> {
                    long nanos = Duration.between(NOW, clock.instant()).toNanos();
                    String line = indexOf.get(event) + "," + event.get("event_time") + "," + nanos + "\n";
                    digest.update(line.getBytes(StandardCharsets.UTF_8));
                });

        assertThat(result.read()).isEqualTo(50_000);
        assertThat(result.delivered()).isEqualTo(scenario.count());
        assertThat(result.late()).isEqualTo(scenario.late());
        assertThat(HexFormat.of().formatHex(digest.digest())).isEqualTo(scenario.sha256());
    }

    /** The waiting used outside the tests. A thread already interrupted leaves it at once, so nothing really waits. */
    @Test
    void theRealWaitIsReachedAndAnInterruptionStopsIt() throws IOException {
        List<GenericRecord> rows = readSample().subList(0, 2);
        long gap = (Long) rows.get(1).get("event_time") - (Long) rows.get(0).get("event_time");
        assertThat(gap).isPositive();
        List<GenericRecord> delivered = new ArrayList<>();
        EventReplay replay = new EventReplay(
                new EventReplay.Options(SessionSampler.all(), 1, false, 0), Clock.fixed(NOW, ZoneOffset.UTC));

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> replay.run(sourceOf(rows), delivered::add))
                    .isInstanceOf(InterruptedException.class);
        } finally {
            Thread.interrupted();
        }
        assertThat(delivered).hasSize(1);
    }

    private static SamplerFactory rate(double rate) {
        return () -> SessionSampler.ofRate(rate);
    }

    private static SamplerFactory maxRows(long maxRows) {
        return () -> SessionSampler.ofMaxRows(maxRows, sourceOf(readSample()));
    }

    private static List<GenericRecord> feed(List<GenericRecord> rows, int block) {
        if (block == 0) {
            return rows;
        }
        List<GenericRecord> disordered = new ArrayList<>(rows.size());
        for (int start = 0; start < rows.size(); start += block) {
            List<GenericRecord> part = new ArrayList<>(rows.subList(start, Math.min(start + block, rows.size())));
            Collections.reverse(part);
            disordered.addAll(part);
        }
        return disordered;
    }

    private static EventSource sourceOf(List<GenericRecord> events) {
        Iterator<GenericRecord> remaining = events.iterator();
        return () -> remaining.hasNext() ? remaining.next() : null;
    }

    private static List<GenericRecord> readSample() throws IOException {
        List<GenericRecord> events = new ArrayList<>();
        try (CsvEventReader reader = CsvEventReader.open(SAMPLE)) {
            for (GenericRecord event = reader.next(); event != null; event = reader.next()) {
                events.add(event);
            }
        }
        return events;
    }

    private static final class SimulatedClock extends Clock {
        private Instant now;

        SimulatedClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
