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
                new Scenario("rate01", rate(0.01), 1, false, WINDOW, 0, 527, 0,
                        "85d5355969bd7e545c4f80df8d349de5c2e093f581fe2ad0ab1a278e10cda3c1"),
                new Scenario("rate10", rate(0.1), 1, false, WINDOW, 0, 4968, 0,
                        "76e886db8442d53368dc050416b17e628dc2dd7ac6520c4de3cca01c75d27db8"),
                new Scenario("rate50", rate(0.5), 1, false, WINDOW, 0, 24809, 0,
                        "4d408b5d92430197fc2f108ff6b8b3a48ba352ab9be78e5ca3a0b44c83c9bdc1"),
                new Scenario("max1", maxRows(1), 1, false, WINDOW, 0, 1, 0,
                        "622ea4c532ef9e125eb2af09ab5d5269616827386f1f3ddfbea9589f68ed2d3e"),
                new Scenario("max500", maxRows(500), 1, false, WINDOW, 0, 499, 0,
                        "7bd1fe2d6da808a85047f5deb5dba96df3d6619a5aa306e2e70b6cd31715f3f4"),
                new Scenario("max12500", maxRows(12_500), 1, false, WINDOW, 0, 12_500, 0,
                        "0aaf023ad92414baa0294e96c8d68933183424a3e85871a14b9d555f1d4b868e"),
                new Scenario("max50000", maxRows(50_000), 1, false, WINDOW, 0, 50_000, 0,
                        "9bc21da9a10809066ec6d41e42e8ff4aa9784880bf51cb95b6b81d2ef42b75c8"),
                new Scenario("allSpeed60", SessionSampler::all, 60, false, WINDOW, 0, 50_000, 0,
                        "9580632a91971599523dd65ddac0efc1af5408a1316bf4e1d9e0218850a9c2bd"),
                new Scenario("allSpeed7", SessionSampler::all, 7, false, WINDOW, 0, 50_000, 0,
                        "5efefc5bdbd64e02a50a350694e5400edaecadd143970528162af714cfc35f4b"),
                new Scenario("allSpeed1234_5", SessionSampler::all, 1234.5, false, WINDOW, 0, 50_000, 0,
                        "a3b855a43b438bc1292fabe8b092a0c96d641782ab6b66e1aa1ea764fbc358d8"),
                new Scenario("allHalfSpeed", SessionSampler::all, 0.5, false, WINDOW, 0, 50_000, 0,
                        "e2934ce6579021a654548dc760c63940367f9ffd251b5625f988bf0b20e6dfbe"),
                new Scenario("allRewrite", SessionSampler::all, 3600, true, WINDOW, 0, 50_000, 0,
                        "9c950633e4e359b0a584e4fc18b3d8d503e50db318bba3835d42022a7fba1123"),
                new Scenario("rate10Rewrite", rate(0.1), 7, true, WINDOW, 0, 4968, 0,
                        "4b445d965f0a9146342e8aace6ac72ed14669affad96174f2640ad4c4721fbd0"),
                new Scenario("allWindow0", SessionSampler::all, 60, false, 0, 0, 50_000, 0,
                        "9580632a91971599523dd65ddac0efc1af5408a1316bf4e1d9e0218850a9c2bd"),
                new Scenario("allWindow10", SessionSampler::all, 60, true, 10, 0, 50_000, 0,
                        "5bfa5cd5089ad6b24a2745058791c72c21da2d91db831976646f40bd913b32a9"),
                new Scenario("rate50Window1000", rate(0.5), 60, true, 1_000, 0, 24809, 0,
                        "ea8cd8bee7f3e40f48254517105f25584e86e79f1cd30b130580cd7c0fb1a8a3"),
                new Scenario("disorderedWindow49", SessionSampler::all, 60, true, 49, 50, 50_000, 0,
                        "609fa16b18607eb8ae757230c1f49c88fd3a6dae1e35adc6767d40a0eae2f81f"),
                new Scenario("disorderedWindow48", SessionSampler::all, 60, true, 48, 50, 50_000, 976,
                        "097d45c9db432d91910ff1a8a7b3336ab3c765297253c1a324a3aee8ef551f2f"),
                new Scenario("disorderedWindow0", SessionSampler::all, 60, true, 0, 50, 50_000, 48_976,
                        "fbb59f7a2ac6d72a2741f77930274406977eee3860bd894492e1b0204d4236b9"),
                new Scenario("disorderedRate50", rate(0.5), 7, false, WINDOW, 50, 24809, 0,
                        "67724a65f75fecd4b5dbed6325b8c33a162e21abcc8ccaa0c6a5b35e4edc46b7"));
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
