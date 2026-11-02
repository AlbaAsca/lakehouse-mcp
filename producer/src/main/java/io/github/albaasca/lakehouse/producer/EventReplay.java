package io.github.albaasca.lakehouse.producer;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import org.apache.avro.generic.GenericRecord;

/**
 * Replays events in time order at a chosen speed: samples them, restores their order and hands each one over when due.
 *
 * <p>Every due time is worked out from the first event, never from the previous one, so rounding and a slow sink do not
 * add up over a month. Order is restored within a bounded window because the month does not fit in memory: an event
 * that arrives later than the window allows is still delivered, right away, and counted in {@link Result#late()}.
 */
public final class EventReplay {

    /** Sampled events held back to restore order. Check it against the disorder measured in docs/data-profile.md. */
    public static final int DEFAULT_REORDER_WINDOW = 100_000;

    private static final String EVENT_TIME = "event_time";
    private static final Comparator<Buffered> BY_TIME_THEN_ARRIVAL =
            Comparator.comparingLong(Buffered::time).thenComparingLong(Buffered::arrival);

    private final Options options;
    private final Clock clock;
    private final Sleeper sleeper;

    public EventReplay(Options options, Clock clock) {
        this(options, clock, duration -> TimeUnit.NANOSECONDS.sleep(duration.toNanos()));
    }

    EventReplay(Options options, Clock clock, Sleeper sleeper) {
        this.options = options;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** Runs the replay to the end of the source. Events are changed in place when timestamps are rewritten. */
    public Result run(EventSource source, EventSink sink) throws IOException, InterruptedException {
        PriorityQueue<Buffered> window = new PriorityQueue<>(BY_TIME_THEN_ARRIVAL);
        Timeline timeline = new Timeline();
        long arrival = 0;
        GenericRecord event;
        while ((event = source.next()) != null) {
            arrival++;
            if (options.sampler().keep(event)) {
                window.add(new Buffered(event, (Long) event.get(EVENT_TIME), arrival));
                if (window.size() > options.reorderWindow()) {
                    timeline.deliver(window.remove(), sink);
                }
            }
        }
        while (!window.isEmpty()) {
            timeline.deliver(window.remove(), sink);
        }
        return new Result(arrival, timeline.delivered, timeline.late);
    }

    /** The clock of one run: fixed by its first delivered event. */
    private final class Timeline {
        private Instant start;
        private long firstEventTime;
        private long shift;
        private long latestEventTime;
        private long delivered;
        private long late;

        void deliver(Buffered next, EventSink sink) throws IOException, InterruptedException {
            if (start == null) {
                start = clock.instant();
                firstEventTime = next.time();
                latestEventTime = next.time();
                shift = ChronoUnit.MICROS.between(Instant.EPOCH, start) - firstEventTime;
            }
            if (next.time() < latestEventTime) {
                late++;
            }
            latestEventTime = Math.max(latestEventTime, next.time());
            waitUntil(start.plusNanos(nanosAfterStart(next.time())));
            if (options.rewriteTimestamp()) {
                next.event().put(EVENT_TIME, next.time() + shift);
            }
            sink.accept(next.event());
            delivered++;
        }

        /** A double holds every whole number of nanoseconds up to 104 days, well over a month. */
        private long nanosAfterStart(long eventTime) {
            return Math.round((eventTime - firstEventTime) * 1_000.0 / options.speedup());
        }

        private void waitUntil(Instant due) throws InterruptedException {
            Duration delay = Duration.between(clock.instant(), due);
            if (delay.isPositive()) {
                sleeper.sleep(delay);
            }
        }
    }

    /**
     * @param speedup how many times faster than the original the events are delivered
     * @param rewriteTimestamp shifts every event_time by the same amount so that the first one is now. The original
     *     spacing stays, so days remain days, but with a speedup above 1 later events carry times ahead of the clock.
     * @param reorderWindow events held back to restore order; 0 keeps the order of the source
     */
    public record Options(SessionSampler sampler, double speedup, boolean rewriteTimestamp, int reorderWindow) {

        public Options {
            if (!(speedup > 0) || Double.isInfinite(speedup)) {
                throw new IllegalArgumentException("Speedup must be a positive number: " + speedup);
            }
            if (reorderWindow < 0) {
                throw new IllegalArgumentException("Reorder window cannot be negative: " + reorderWindow);
            }
        }
    }

    /**
     * @param read events taken from the source, before sampling
     * @param delivered events handed to the sink
     * @param late delivered events older than one delivered before them
     */
    public record Result(long read, long delivered, long late) {
    }

    /** Receives each event when it is due. It owns the event from then on. */
    @FunctionalInterface
    public interface EventSink {
        void accept(GenericRecord event) throws IOException;
    }

    /** Waits for a duration. Separate from the clock so tests can run a month without waiting. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private record Buffered(GenericRecord event, long time, long arrival) {
    }
}
