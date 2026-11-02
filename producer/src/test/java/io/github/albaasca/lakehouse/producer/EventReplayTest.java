package io.github.albaasca.lakehouse.producer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EventReplayTest {

    private static final long SECOND = 1_000_000L;
    private static final long BASE = Instant.parse("2019-10-01T00:00:00Z").getEpochSecond() * SECOND;
    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00.123456789Z");

    private final SimulatedClock clock = new SimulatedClock(NOW);
    private final List<Duration> sleeps = new ArrayList<>();
    private final List<Instant> deliveredAt = new ArrayList<>();
    private final List<Long> deliveredTimes = new ArrayList<>();

    @Test
    void waitsAreTheOriginalGapsDividedByTheSpeedup() throws Exception {
        replay(options(60, false, 0), times(0, 60, 90, 90, 150));

        assertThat(sleeps).containsExactly(seconds(1), millis(500), seconds(1));
        assertThat(deliveredAt)
                .containsExactly(NOW, NOW.plusSeconds(1), NOW.plusMillis(1500), NOW.plusMillis(1500), NOW.plusMillis(2500));
    }

    @Test
    void aSpeedupOfOneKeepsTheOriginalGaps() throws Exception {
        replay(options(1, false, 0), times(0, 7, 10));

        assertThat(sleeps).containsExactly(seconds(7), seconds(3));
    }

    @Test
    void aSpeedupBelowOneSlowsDown() throws Exception {
        replay(options(0.5, false, 0), times(0, 3));

        assertThat(sleeps).containsExactly(seconds(6));
    }

    @Test
    void aGapThatDoesNotDivideEvenlyIsRoundedToTheNearestNanosecond() throws Exception {
        replay(options(3, false, 0), times(0, 10));

        assertThat(sleeps).containsExactly(Duration.ofNanos(3_333_333_333L));
    }

    @Test
    void aFullMonthAtHighSpeedDoesNotDrift() throws Exception {
        long month = 31L * 24 * 3600;
        replay(options(3600, false, 0), times(0, month / 2, month));

        assertThat(Duration.between(NOW, deliveredAt.getLast())).isEqualTo(Duration.ofHours(31 * 24).dividedBy(3600));
    }

    @Test
    void timeSpentInTheSinkComesOffTheNextWaitSoNothingDrifts() throws Exception {
        EventReplay replay = new EventReplay(options(1, false, 0), clock, sleeper());
        replay.run(sourceOf(times(0, 10, 20)), event -> {
            deliveredAt.add(clock.instant());
            clock.advance(seconds(4));
        });

        assertThat(sleeps).containsExactly(seconds(6), seconds(6));
        assertThat(deliveredAt).containsExactly(NOW, NOW.plusSeconds(10), NOW.plusSeconds(20));
    }

    @Test
    void aSinkSlowerThanTheScheduleGetsTheNextEventWithoutWaiting() throws Exception {
        EventReplay replay = new EventReplay(options(1, false, 0), clock, sleeper());
        replay.run(sourceOf(times(0, 1)), event -> clock.advance(seconds(5)));

        assertThat(sleeps).isEmpty();
    }

    @Test
    void withoutRewriteTheTimestampsStayAsTheyWere() throws Exception {
        replay(options(60, false, 0), times(0, 60));

        assertThat(deliveredTimes).containsExactly(BASE, BASE + 60 * SECOND);
    }

    @Test
    void rewriteShiftsEveryEventSoTheFirstIsNow() throws Exception {
        replay(options(60, true, 0), times(0, 60, 86_400));

        long now = ChronoUnit.MICROS.between(Instant.EPOCH, NOW);
        assertThat(deliveredTimes).containsExactly(now, now + 60 * SECOND, now + 86_400 * SECOND);
    }

    @Test
    void rewriteKeepsMicroseconds() throws Exception {
        replay(options(1, true, 0), List.of(event(BASE + 1, "a"), event(BASE + 3, "a")));

        long now = ChronoUnit.MICROS.between(Instant.EPOCH, NOW);
        assertThat(deliveredTimes).containsExactly(now, now + 2);
    }

    @Test
    void anOutOfOrderSourceComesOutInOrderWithinTheWindow() throws Exception {
        EventReplay.Result result = replay(options(1, false, 2), times(30, 10, 20, 50, 40));

        assertThat(deliveredTimes)
                .containsExactly(BASE + 10 * SECOND, BASE + 20 * SECOND, BASE + 30 * SECOND, BASE + 40 * SECOND, BASE + 50 * SECOND);
        assertThat(sleeps).containsExactly(seconds(10), seconds(10), seconds(10), seconds(10));
        assertThat(result.late()).isZero();
    }

    @Test
    void eventsWithTheSameTimeKeepTheirFileOrder() throws Exception {
        List<String> sessions = new ArrayList<>();
        EventReplay replay = new EventReplay(options(1, false, 10), clock, sleeper());
        replay.run(
                sourceOf(List.of(event(BASE, "first"), event(BASE, "second"), event(BASE, "third"))),
                event -> sessions.add((String) event.get("user_session")));

        assertThat(sessions).containsExactly("first", "second", "third");
    }

    @Test
    void anEventLaterThanTheWindowIsDeliveredWithoutWaitingAndCounted() throws Exception {
        EventReplay.Result result = replay(options(1, false, 1), times(10, 20, 30, 5, 40));

        assertThat(deliveredTimes)
                .containsExactly(BASE + 10 * SECOND, BASE + 20 * SECOND, BASE + 5 * SECOND, BASE + 30 * SECOND, BASE + 40 * SECOND);
        assertThat(sleeps).containsExactly(seconds(10), seconds(10), seconds(10));
        assertThat(result.delivered()).isEqualTo(5);
        assertThat(result.late()).isEqualTo(1);
    }

    @Test
    void aWindowOfZeroKeepsTheOrderOfTheSource() throws Exception {
        EventReplay.Result result = replay(options(1, false, 0), times(10, 5));

        assertThat(deliveredTimes).containsExactly(BASE + 10 * SECOND, BASE + 5 * SECOND);
        assertThat(result.late()).isEqualTo(1);
    }

    @Test
    void theClockStartsAtTheFirstEventThatPassesTheSample() throws Exception {
        String keptSession = sessionWithBucketIn(true);
        String droppedSession = sessionWithBucketIn(false);
        List<GenericRecord> events = List.of(
                event(BASE, droppedSession),
                event(BASE + 100 * SECOND, keptSession),
                event(BASE + 110 * SECOND, droppedSession),
                event(BASE + 120 * SECOND, keptSession));

        EventReplay.Result result =
                replay(new EventReplay.Options(HALF, 1, true, 0), events);

        long now = ChronoUnit.MICROS.between(Instant.EPOCH, NOW);
        assertThat(deliveredTimes).containsExactly(now, now + 20 * SECOND);
        assertThat(sleeps).containsExactly(seconds(20));
        assertThat(result.read()).isEqualTo(4);
        assertThat(result.delivered()).isEqualTo(2);
    }

    @Test
    void anEmptySourceDeliversNothing() throws Exception {
        EventReplay.Result result = replay(options(1, true, 10), List.of());

        assertThat(result).isEqualTo(new EventReplay.Result(0, 0, 0));
        assertThat(deliveredTimes).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(doubles = {0, -1, Double.NaN, Double.POSITIVE_INFINITY})
    void aSpeedupThatIsNotPositiveAndFiniteIsRejected(double speedup) {
        assertThatThrownBy(() -> options(speedup, false, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNegativeWindowIsRejected() {
        assertThatThrownBy(() -> options(1, false, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anInterruptionDuringAWaitStopsTheReplay() {
        EventReplay replay = new EventReplay(options(1, false, 0), clock, duration -> {
            throw new InterruptedException();
        });

        assertThatThrownBy(() -> replay.run(sourceOf(times(0, 1)), event -> deliveredTimes.add(0L)))
                .isInstanceOf(InterruptedException.class);
        assertThat(deliveredTimes).hasSize(1);
    }

    private static final SessionSampler HALF = SessionSampler.ofRate(0.5);

    private static String sessionWithBucketIn(boolean kept) {
        for (int i = 0; ; i++) {
            String session = "session-" + i;
            if (HALF.keep(event(BASE, session)) == kept) {
                return session;
            }
        }
    }

    private EventReplay.Result replay(EventReplay.Options options, List<GenericRecord> events)
            throws IOException, InterruptedException {
        return new EventReplay(options, clock, sleeper()).run(sourceOf(events), event -> {
            deliveredAt.add(clock.instant());
            deliveredTimes.add((Long) event.get("event_time"));
        });
    }

    /** Never really waits: moves the simulated clock forward instead. */
    private EventReplay.Sleeper sleeper() {
        return duration -> {
            sleeps.add(duration);
            clock.advance(duration);
        };
    }

    private static EventReplay.Options options(double speedup, boolean rewrite, int window) {
        return new EventReplay.Options(SessionSampler.all(), speedup, rewrite, window);
    }

    private static Duration seconds(long seconds) {
        return Duration.ofSeconds(seconds);
    }

    private static Duration millis(long millis) {
        return Duration.ofMillis(millis);
    }

    /** Events at these seconds after the base time. */
    private static List<GenericRecord> times(long... secondsAfterBase) {
        return Arrays.stream(secondsAfterBase).mapToObj(s -> event(BASE + s * SECOND, "session")).toList();
    }

    private static GenericRecord event(long time, String session) {
        GenericRecord event = new GenericData.Record(EventSchema.SCHEMA);
        event.put("event_time", time);
        event.put("event_type", "view");
        event.put("product_id", 1L);
        event.put("category_id", 2L);
        event.put("price", 1.0);
        event.put("user_id", 3L);
        event.put("user_session", session);
        return event;
    }

    private static EventSource sourceOf(List<GenericRecord> events) {
        Iterator<GenericRecord> remaining = events.iterator();
        return () -> remaining.hasNext() ? remaining.next() : null;
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
