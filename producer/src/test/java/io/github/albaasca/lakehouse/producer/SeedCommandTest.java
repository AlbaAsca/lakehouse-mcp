package io.github.albaasca.lakehouse.producer;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class SeedCommandTest {

    private static final Path SAMPLE = Path.of("../testdata/synthetic-events.csv.gz");
    private static final String TOO_FAST = "1e12";
    private static final String TWO_ROWS_OF_ONE_SESSION = """
            event_time,event_type,product_id,category_id,category_code,brand,price,user_id,user_session
            2024-03-01 00:00:00 UTC,view,1,2,,,1.50,3,only-session
            2024-03-01 00:00:01 UTC,cart,1,2,,,1.50,3,only-session
            """;

    private final List<GenericRecord> sent = new ArrayList<>();

    @Test
    void sendsTheWholeFileByDefault() throws Exception {
        int exit = seed("--input", SAMPLE.toString(), "--speedup", TOO_FAST);

        assertThat(exit).isEqualTo(SeedCommand.OK);
        assertThat(sent).hasSize(50_000);
    }

    @Test
    void theTopicIsTheOneOfTheSinkUnlessAnotherIsGiven() {
        String input = SAMPLE.toString();

        assertThat(SeedCommand.Args.parse(new String[] {"--input", input}).topic()).isEqualTo("ecommerce.events");
        assertThat(SeedCommand.Args.parse(new String[] {"--input", input, "--topic", "other"}).topic()).isEqualTo("other");
    }

    @Test
    void theSampleRateSelectsTheSameRowsAsTheSampler() throws Exception {
        int exit = seed("--input", SAMPLE.toString(), "--speedup", TOO_FAST, "--sample-rate", "0.1");

        assertThat(exit).isEqualTo(SeedCommand.OK);
        // Row count from scripts/analysis/t011_reference.py, scenario rate10.
        assertThat(sent).hasSize(4857);
    }

    @Test
    void maxRowsReadsTheFileTwiceAndStaysWithinTheLimit() throws Exception {
        int exit = seed("--input", SAMPLE.toString(), "--speedup", TOO_FAST, "--max-rows", "500");

        assertThat(exit).isEqualTo(SeedCommand.OK);
        // Row count from scripts/analysis/t011_reference.py, scenario max500.
        assertThat(sent).hasSize(497);
    }

    @Test
    void rewriteTimestampMovesTheFirstEventToNow() throws Exception {
        seed("--input", SAMPLE.toString(), "--speedup", TOO_FAST, "--max-rows", "1", "--rewrite-timestamp");

        long nowMicros = Instant.now().toEpochMilli() * 1000;
        assertThat((Long) sent.getFirst().get("event_time")).isBetween(nowMicros - 60_000_000, nowMicros + 60_000_000);
    }

    @Test
    void anEmptySampleFailsInsteadOfLookingLikeASuccess(@TempDir Path dir) throws Exception {
        Path csv = Files.writeString(dir.resolve("two.csv"), TWO_ROWS_OF_ONE_SESSION);

        int exit = seed("--input", csv.toString(), "--max-rows", "1");

        assertThat(exit).isEqualTo(SeedCommand.FAILED);
        assertThat(sent).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "--input",
        "--input does-not-exist.csv",
        "--input ../testdata/synthetic-events.csv.gz --unknown 1",
        "--input ../testdata/synthetic-events.csv.gz --sample-rate 0.5 --max-rows 10",
        "--input ../testdata/synthetic-events.csv.gz --sample-rate 2",
        "--input ../testdata/synthetic-events.csv.gz --sample-rate abc",
        "--input ../testdata/synthetic-events.csv.gz --max-rows 0",
        "--input ../testdata/synthetic-events.csv.gz --speedup 0",
    })
    void wrongArgumentsStopBeforeTouchingKafka(String line) {
        String[] argv = line.isEmpty() ? new String[0] : line.split(" ");

        // The default broker does not resolve here: reaching it would take a minute and exit with another code.
        assertThat(SeedCommand.execute(argv)).isEqualTo(SeedCommand.BAD_ARGUMENTS);
    }

    @Test
    void progressIsLoggedOncePerInterval() throws IOException {
        Logger logger = (Logger) LoggerFactory.getLogger(SeedCommand.class);
        ListAppender<ILoggingEvent> logged = new ListAppender<>();
        logged.start();
        logger.addAppender(logged);
        MutableClock clock = new MutableClock();
        SeedCommand.ProgressLog progress = new SeedCommand.ProgressLog(sent::add, clock);
        GenericRecord event = new GenericData.Record(EventSchema.SCHEMA);
        try {
            progress.accept(event);
            clock.advance(SeedCommand.ProgressLog.INTERVAL.minusMillis(1));
            progress.accept(event);
            assertThat(logged.list).isEmpty();

            clock.advance(Duration.ofMillis(1));
            progress.accept(event);
            progress.accept(event);
        } finally {
            logger.detachAppender(logged);
        }

        assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly("Sent 3 events, 0 per second");
        assertThat(sent).hasSize(4);
    }

    private int seed(String... argv) throws IOException, InterruptedException {
        return SeedCommand.seed(SeedCommand.Args.parse(argv), sent::add, Clock.systemUTC());
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-02T12:00:00Z");

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
