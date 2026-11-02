package io.github.albaasca.lakehouse.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SessionSamplerTest {

    private static final Path SAMPLE = Path.of("../testdata/synthetic-events.csv.gz");

    /** Worked out with a separate Python implementation of the same hash, so these also pin it across JVMs. */
    @Test
    void bucketsMatchAnIndependentImplementation() {
        assertThat(SessionSampler.bucket("a")).isEqualTo(535082);
        assertThat(SessionSampler.bucket("abc")).isEqualTo(212666);
        assertThat(SessionSampler.bucket("ñandú")).isEqualTo(449778);
        assertThat(SessionSampler.bucket("26dd6e6e-4dac-4520-a8b5-1c9d8f0e7a11")).isEqualTo(36810);
    }

    @Test
    void aRowWithoutSessionIsHashedAsAWhole() {
        GenericRecord row = event(null);
        assertThat(SessionSampler.bucket(row)).isEqualTo(350385);

        row.put("price", 12.35);
        assertThat(SessionSampler.bucket(row)).isNotEqualTo(350385);
    }

    @Test
    void aRowWithSessionIsHashedByItsSessionAlone() {
        GenericRecord row = event("abc");
        assertThat(SessionSampler.bucket(row)).isEqualTo(212666);

        row.put("price", 99.0);
        assertThat(SessionSampler.bucket(row)).isEqualTo(212666);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.01, 0.1, 0.5})
    void aSessionIsEntirelyInOrEntirelyOut(double rate) throws IOException {
        SessionSampler sampler = SessionSampler.ofRate(rate);
        Map<String, Boolean> decisions = new HashMap<>();
        for (GenericRecord event : sample()) {
            Object session = event.get("user_session");
            if (session != null) {
                boolean kept = sampler.keep(event);
                assertThat(decisions.computeIfAbsent(session.toString(), s -> kept))
                        .as("session %s", session)
                        .isEqualTo(kept);
            }
        }
        assertThat(decisions).containsValue(true).containsValue(false);
    }

    @Test
    void theSameRateAlwaysKeepsTheSameRows() throws IOException {
        List<GenericRecord> events = sample();
        List<GenericRecord> first = events.stream().filter(SessionSampler.ofRate(0.2)::keep).toList();
        List<GenericRecord> second = events.stream().filter(SessionSampler.ofRate(0.2)::keep).toList();

        assertThat(first).isNotEmpty().isEqualTo(second);
    }

    @Test
    void aSmallerSampleIsContainedInALargerOne() throws IOException {
        SessionSampler small = SessionSampler.ofRate(0.1);
        SessionSampler large = SessionSampler.ofRate(0.3);
        for (GenericRecord event : sample()) {
            if (small.keep(event)) {
                assertThat(large.keep(event)).isTrue();
            }
        }
    }

    @Test
    void theRateIsCloseToTheShareOfSessionsKept() {
        SessionSampler sampler = SessionSampler.ofRate(0.25);
        int kept = 0;
        int sessions = 200_000;
        for (int i = 0; i < sessions; i++) {
            if (sampler.keep(event("session-" + i))) {
                kept++;
            }
        }
        // Binomial: three standard deviations are about 580 sessions.
        assertThat(kept).isBetween(50_000 - 580, 50_000 + 580);
    }

    @Test
    void rateOneKeepsEverything() throws IOException {
        List<GenericRecord> events = sample();
        assertThat(events).allMatch(SessionSampler.ofRate(1.0)::keep).allMatch(SessionSampler.all()::keep);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -0.1, 1.01, Double.NaN, 1e-9})
    void aRateOutOfRangeIsRejected(double rate) {
        assertThatThrownBy(() -> SessionSampler.ofRate(rate)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void aMaxRowsThatIsNotPositiveIsRejected(long maxRows) {
        assertThatThrownBy(() -> SessionSampler.ofMaxRows(maxRows, () -> null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void maxRowsNeverGoesOverAndKeepsSessionsWhole() throws IOException {
        List<GenericRecord> events = sample();
        long maxRows = events.size() / 4;

        SessionSampler sampler = SessionSampler.ofMaxRows(maxRows, sourceOf(events));
        List<GenericRecord> kept = events.stream().filter(sampler::keep).toList();

        // Each bucket holds a handful of rows, so stopping before the next one leaves the sample almost at the limit.
        assertThat((long) kept.size()).isLessThanOrEqualTo(maxRows).isGreaterThan(maxRows * 9 / 10);
        Predicate<GenericRecord> hasSession = event -> event.get("user_session") != null;
        Map<Object, Long> keptPerSession = countBySession(kept.stream().filter(hasSession).toList());
        Map<Object, Long> totalPerSession = countBySession(events.stream().filter(hasSession).toList());
        keptPerSession.forEach((session, rows) -> assertThat(rows).isEqualTo(totalPerSession.get(session)));
    }

    @Test
    void maxRowsCoversTheWholeTimeSpanInsteadOfOnlyTheStart() throws IOException {
        List<GenericRecord> events = sample();
        SessionSampler sampler = SessionSampler.ofMaxRows(events.size() / 4, sourceOf(events));
        long[] span = timeSpan(events);

        long[] keptSpan = timeSpan(events.stream().filter(sampler::keep).toList());

        long tenth = (span[1] - span[0]) / 10;
        assertThat(keptSpan[0]).isLessThan(span[0] + tenth);
        assertThat(keptSpan[1]).isGreaterThan(span[1] - tenth);
    }

    @Test
    void maxRowsAboveTheTotalKeepsEverything() throws IOException {
        List<GenericRecord> events = sample();
        SessionSampler sampler = SessionSampler.ofMaxRows(events.size(), sourceOf(events));
        assertThat(events).allMatch(sampler::keep);
    }

    @Test
    void maxRowsPicksTheSameRowsEveryTime() throws IOException {
        List<GenericRecord> events = sample();
        SessionSampler first = SessionSampler.ofMaxRows(1_000, sourceOf(events));
        SessionSampler second = SessionSampler.ofMaxRows(1_000, sourceOf(events));
        assertThat(events.stream().filter(first::keep).toList()).isEqualTo(events.stream().filter(second::keep).toList());
    }

    private static Map<Object, Long> countBySession(List<GenericRecord> events) {
        Map<Object, Long> counts = new HashMap<>();
        events.forEach(event -> counts.merge(event.get("user_session"), 1L, Long::sum));
        return counts;
    }

    private static long[] timeSpan(List<GenericRecord> events) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (GenericRecord event : events) {
            long time = (Long) event.get("event_time");
            min = Math.min(min, time);
            max = Math.max(max, time);
        }
        return new long[] {min, max};
    }

    private static EventSource sourceOf(List<GenericRecord> events) {
        var remaining = events.iterator();
        return () -> remaining.hasNext() ? remaining.next() : null;
    }

    private static List<GenericRecord> sample() throws IOException {
        List<GenericRecord> events = new ArrayList<>();
        try (CsvEventReader reader = CsvEventReader.open(SAMPLE)) {
            GenericRecord event;
            while ((event = reader.next()) != null) {
                events.add(event);
            }
        }
        return events;
    }

    /** Same values as the row hashed by the Python reference, with the given session. */
    private static GenericRecord event(String session) {
        GenericRecord event = new GenericData.Record(EventSchema.SCHEMA);
        event.put("event_time", 1709251200000000L);
        event.put("event_type", "view");
        event.put("product_id", 900000001L);
        event.put("category_id", 9000000000000000001L);
        event.put("category_code", null);
        event.put("brand", "synth-quorvo");
        event.put("price", 12.34);
        event.put("user_id", 900000002L);
        event.put("user_session", session);
        return event;
    }
}
