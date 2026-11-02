package io.github.albaasca.lakehouse.producer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.apache.avro.generic.GenericRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Command line that replays a CSV into Kafka: exit code 0 only if at least one event went out and all were confirmed. */
public final class SeedCommand {

    static final int OK = 0;
    static final int FAILED = 1;
    static final int BAD_ARGUMENTS = 2;

    private static final int INTERRUPTED = 130;
    private static final String USAGE = """
            Usage: --input <file.csv|file.csv.gz> [--sample-rate <0-1> | --max-rows <n>] [--speedup <x>] \
            [--rewrite-timestamp] [--bootstrap-servers <host:port>] [--schema-registry-url <url>]""";
    private static final Logger log = LoggerFactory.getLogger(SeedCommand.class);

    private SeedCommand() {
    }

    public static void main(String[] args) {
        System.exit(execute(args));
    }

    static int execute(String[] argv) {
        Args args;
        try {
            args = Args.parse(argv);
        } catch (IllegalArgumentException e) {
            log.error("{}\n{}", e.getMessage(), USAGE);
            return BAD_ARGUMENTS;
        }
        try {
            KafkaEventSink.ensureTopic(args.bootstrapServers());
            KafkaEventSink sink = KafkaEventSink.open(args.bootstrapServers(), args.schemaRegistryUrl());
            int exit;
            try (sink) {
                exit = seed(args, sink, Clock.systemUTC());
            }
            log.info("Kafka confirmed {} events", sink.confirmed());
            return exit;
        } catch (IOException | RuntimeException e) {
            log.error("Seed failed", e);
            return FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Seed interrupted");
            return INTERRUPTED;
        }
    }

    /** Samples and replays the input into the sink. Sending nothing counts as a failure: it is never what was asked for. */
    static int seed(Args args, EventReplay.EventSink sink, Clock clock) throws IOException, InterruptedException {
        SessionSampler sampler = args.sampler();
        EventReplay replay = new EventReplay(args.options(sampler), clock);
        Instant start = clock.instant();
        EventReplay.Result result;
        long skipped;
        try (CsvEventReader reader = CsvEventReader.open(args.input())) {
            result = replay.run(reader, new ProgressLog(sink, clock));
            skipped = reader.rowsSkipped();
        }
        log.info("Read {} rows ({} unreadable), sent {} events, {} late, in {}",
                result.read(), skipped, result.delivered(), result.late(), Duration.between(start, clock.instant()));
        if (result.delivered() == 0) {
            log.error("No events sent: the sample is empty. Raise --max-rows or --sample-rate, or check the input file.");
            return FAILED;
        }
        return OK;
    }

    record Args(
            Path input,
            Double sampleRate,
            Long maxRows,
            double speedup,
            boolean rewriteTimestamp,
            String bootstrapServers,
            String schemaRegistryUrl) {

        /** Rejects everything that can be known to be wrong before touching the file or Kafka. */
        static Args parse(String[] argv) {
            Path input = null;
            Double sampleRate = null;
            Long maxRows = null;
            double speedup = 1;
            boolean rewriteTimestamp = false;
            // The names of the services in compose.yaml: the command runs inside the tools container.
            String bootstrapServers = "kafka:9092";
            String schemaRegistryUrl = "http://schema-registry:8081";
            for (int i = 0; i < argv.length; i++) {
                String name = argv[i];
                if (name.equals("--rewrite-timestamp")) {
                    rewriteTimestamp = true;
                    continue;
                }
                if (++i == argv.length) {
                    throw new IllegalArgumentException("Missing value or unknown option: " + name);
                }
                String value = argv[i];
                switch (name) {
                    case "--input" -> input = Path.of(value);
                    case "--sample-rate" -> sampleRate = Double.valueOf(value);
                    case "--max-rows" -> maxRows = Long.valueOf(value);
                    case "--speedup" -> speedup = Double.parseDouble(value);
                    case "--bootstrap-servers" -> bootstrapServers = value;
                    case "--schema-registry-url" -> schemaRegistryUrl = value;
                    default -> throw new IllegalArgumentException("Unknown option: " + name);
                }
            }
            if (input == null) {
                throw new IllegalArgumentException("--input is required");
            }
            if (!Files.isRegularFile(input)) {
                throw new IllegalArgumentException("Input file not found: " + input);
            }
            if (sampleRate != null && maxRows != null) {
                throw new IllegalArgumentException("Use --sample-rate or --max-rows, not both");
            }
            if (sampleRate != null) {
                SessionSampler.ofRate(sampleRate);
            }
            if (maxRows != null && maxRows <= 0) {
                throw new IllegalArgumentException("Max rows must be positive: " + maxRows);
            }
            Args args = new Args(input, sampleRate, maxRows, speedup, rewriteTimestamp, bootstrapServers, schemaRegistryUrl);
            args.options(SessionSampler.all());
            return args;
        }

        /** With max-rows the file is read twice, because working out the sample consumes the source. */
        private SessionSampler sampler() throws IOException {
            if (maxRows != null) {
                try (CsvEventReader reader = CsvEventReader.open(input)) {
                    return SessionSampler.ofMaxRows(maxRows, reader);
                }
            }
            return sampleRate != null ? SessionSampler.ofRate(sampleRate) : SessionSampler.all();
        }

        private EventReplay.Options options(SessionSampler sampler) {
            return new EventReplay.Options(sampler, speedup, rewriteTimestamp, EventReplay.DEFAULT_REORDER_WINDOW);
        }
    }

    /** Logs how far the run is, at most once per interval, as events go through. */
    static final class ProgressLog implements EventReplay.EventSink {

        static final Duration INTERVAL = Duration.ofSeconds(10);

        private final EventReplay.EventSink sink;
        private final Clock clock;
        private final Instant start;
        private Instant nextReport;
        private long sent;

        ProgressLog(EventReplay.EventSink sink, Clock clock) {
            this.sink = sink;
            this.clock = clock;
            start = clock.instant();
            nextReport = start.plus(INTERVAL);
        }

        @Override
        public void accept(GenericRecord event) throws IOException {
            sink.accept(event);
            sent++;
            Instant now = clock.instant();
            if (!now.isBefore(nextReport)) {
                long millis = Duration.between(start, now).toMillis();
                log.info("Sent {} events, {} per second", sent, sent * 1000 / millis);
                nextReport = now.plus(INTERVAL);
            }
        }
    }
}
