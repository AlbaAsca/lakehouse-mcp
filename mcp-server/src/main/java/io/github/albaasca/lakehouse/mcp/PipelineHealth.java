package io.github.albaasca.lakehouse.mcp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the pipeline is alive: how far the sink lags behind Kafka, what Connect says of it, and when the table was last
 * written. A source that does not answer is part of the report, never an exception: "Kafka is down" is what the agent
 * needs to read.
 *
 * <p>Freshness comes from the time of the last snapshot, not from {@code event_time}: the events of 2019 would look old
 * forever, and with rewrite-timestamp and a speed-up above 1 they run ahead of the clock (decision 19 of
 * docs/architecture.md).
 */
final class PipelineHealth {

    private static final Logger LOG = LoggerFactory.getLogger(PipelineHealth.class);

    enum Status { OK, WARN, FAIL }

    /** Lag in messages, freshness in time; each check is WARN from its first limit and FAIL from its second. */
    record Thresholds(long lagWarn, long lagFail, Duration freshnessWarn, Duration freshnessFail) {

        Thresholds {
            if (lagWarn < 0 || lagFail < lagWarn) {
                throw new IllegalArgumentException("Lag limits must be 0 <= warn <= fail: " + lagWarn + ", " + lagFail);
            }
            if (freshnessWarn.isNegative() || freshnessFail.compareTo(freshnessWarn) < 0) {
                throw new IllegalArgumentException(
                        "Freshness limits must be 0 <= warn <= fail: " + freshnessWarn + ", " + freshnessFail);
            }
        }
    }

    /** One partition of the topic: {@code committed} is where the sink's group is, {@code end} where the topic is. */
    record PartitionLag(int partition, long committed, long end, long lag) {}

    record TaskState(int id, String state, String error) {}

    /** What Connect says. {@code registered} is false when it does not know the connector. */
    record ConnectorState(boolean registered, String state, List<TaskState> tasks) {}

    @FunctionalInterface
    interface LagSource {
        List<PartitionLag> read() throws Exception;
    }

    @FunctionalInterface
    interface ConnectorSource {
        ConnectorState read() throws Exception;
    }

    @FunctionalInterface
    interface LastCommitSource {
        /** @return empty if the table has no snapshot yet */
        Optional<Instant> read() throws Exception;
    }

    /** {@code messages} is null when Kafka did not answer. */
    record LagCheck(Status status, String reason, String group, String topic, Long messages, List<PartitionLag> partitions) {}

    record ConnectorCheck(Status status, String reason, String name, String state, List<TaskState> tasks) {}

    /** {@code ageSeconds} is never negative: a clock slightly behind the catalog's still means "just written". */
    record FreshnessCheck(Status status, String reason, String table, Instant lastSnapshotAt, Long ageSeconds) {}

    /** The answer of the tool. {@code reasons} lists what is not OK, worst first; empty when all is well. */
    record Report(Status status, List<String> reasons, Instant checkedAt, LagCheck lag, ConnectorCheck connector,
            FreshnessCheck freshness) {}

    private final String group;
    private final String topic;
    private final String connector;
    private final String table;
    private final Thresholds thresholds;
    private final LagSource lagSource;
    private final ConnectorSource connectorSource;
    private final LastCommitSource lastCommitSource;
    private final Clock clock;

    PipelineHealth(String connector, String topic, String table, Thresholds thresholds, LagSource lagSource,
            ConnectorSource connectorSource, LastCommitSource lastCommitSource, Clock clock) {
        this.group = groupOf(connector);
        this.topic = topic;
        this.connector = connector;
        this.table = table;
        this.thresholds = thresholds;
        this.lagSource = lagSource;
        this.connectorSource = connectorSource;
        this.lastCommitSource = lastCommitSource;
        this.clock = clock;
    }

    /** The consumer group Kafka Connect gives a sink connector, where the Iceberg sink commits its offsets. */
    static String groupOf(String connector) {
        return "connect-" + connector;
    }

    Report check() {
        Instant now = clock.instant();
        LagCheck lag = lag();
        ConnectorCheck connectorCheck = connector();
        FreshnessCheck freshness = freshness(now, lag);
        List<Status> statuses = List.of(lag.status(), connectorCheck.status(), freshness.status());
        Status worst = statuses.stream().max(Enum::compareTo).orElseThrow();
        List<String> reasons = new ArrayList<>();
        for (Status level : List.of(Status.FAIL, Status.WARN)) {
            if (connectorCheck.status() == level) {
                reasons.add(connectorCheck.reason());
            }
            if (lag.status() == level) {
                reasons.add(lag.reason());
            }
            if (freshness.status() == level) {
                reasons.add(freshness.reason());
            }
        }
        return new Report(worst, List.copyOf(reasons), now, lag, connectorCheck, freshness);
    }

    private LagCheck lag() {
        List<PartitionLag> partitions;
        try {
            partitions = List.copyOf(lagSource.read());
        } catch (NoSuchElementException e) {
            return new LagCheck(Status.FAIL, e.getMessage(), group, topic, null, List.of());
        } catch (Exception e) {
            LOG.warn("Kafka did not answer for the lag of {}", group, e);
            return new LagCheck(Status.FAIL, "Kafka did not answer: " + message(e), group, topic, null, List.of());
        }
        long messages = partitions.stream().mapToLong(PartitionLag::lag).sum();
        Status status = messages >= thresholds.lagFail() ? Status.FAIL
                : messages >= thresholds.lagWarn() ? Status.WARN : Status.OK;
        String reason = messages == 0
                ? "The sink has read everything in " + topic + "."
                : messages + " messages in " + topic + " are waiting for the sink (WARN from " + thresholds.lagWarn()
                        + ", FAIL from " + thresholds.lagFail() + ").";
        return new LagCheck(status, reason, group, topic, messages, partitions);
    }

    private ConnectorCheck connector() {
        ConnectorState state;
        try {
            state = connectorSource.read();
        } catch (Exception e) {
            LOG.warn("Kafka Connect did not answer for {}", connector, e);
            return new ConnectorCheck(Status.FAIL, "Kafka Connect did not answer: " + message(e), connector, null,
                    List.of());
        }
        if (!state.registered()) {
            return new ConnectorCheck(Status.FAIL, "Connector " + connector + " is not registered in Kafka Connect.",
                    connector, null, List.of());
        }
        List<TaskState> tasks = List.copyOf(state.tasks());
        List<TaskState> failed = tasks.stream().filter(task -> "FAILED".equals(task.state())).toList();
        Status status;
        String reason;
        if ("FAILED".equals(state.state())) {
            status = Status.FAIL;
            reason = "Connector " + connector + " has FAILED.";
        } else if (!failed.isEmpty()) {
            status = Status.FAIL;
            TaskState first = failed.getFirst();
            reason = "Task " + first.id() + " of " + connector + " has FAILED"
                    + (first.error() == null ? "." : ": " + first.error());
        } else if (!"RUNNING".equals(state.state())) {
            status = Status.WARN;
            reason = "Connector " + connector + " is " + state.state() + ", so nothing is being read.";
        } else if (tasks.isEmpty() || tasks.stream().anyMatch(task -> !"RUNNING".equals(task.state()))) {
            status = Status.WARN;
            reason = "Connector " + connector + " is RUNNING but not all of its tasks are.";
        } else {
            status = Status.OK;
            reason = "Connector " + connector + " and its " + tasks.size() + " task(s) are RUNNING.";
        }
        return new ConnectorCheck(status, reason, connector, state.state(), tasks);
    }

    private FreshnessCheck freshness(Instant now, LagCheck lag) {
        Optional<Instant> last;
        try {
            last = lastCommitSource.read();
        } catch (NoSuchElementException e) {
            return new FreshnessCheck(Status.FAIL, "Table " + table + " does not exist yet: the sink creates it on its "
                    + "first commit.", table, null, null);
        } catch (Exception e) {
            // Like ToolResults: what the catalog says can carry paths of the store, so it goes to the log only.
            LOG.error("The catalog did not answer for the freshness of {}", table, e);
            return new FreshnessCheck(Status.FAIL, "The catalog did not answer.", table, null, null);
        }
        if (last.isEmpty()) {
            return new FreshnessCheck(Status.FAIL, "Table " + table + " has no snapshot: nothing has been written.",
                    table, null, null);
        }
        long age = Math.max(0, Duration.between(last.get(), now).toSeconds());
        Status status = age >= thresholds.freshnessFail().toSeconds() ? Status.FAIL
                : age >= thresholds.freshnessWarn().toSeconds() ? Status.WARN : Status.OK;
        String reason = "Last write to " + table + " " + age + " s ago (WARN from " + thresholds.freshnessWarn().toSeconds()
                + " s, FAIL from " + thresholds.freshnessFail().toSeconds() + " s).";
        if (status != Status.OK && Long.valueOf(0).equals(lag.messages())) {
            reason += " No messages are waiting either: the producer may just be idle.";
        }
        return new FreshnessCheck(status, reason, table, last.get(), age);
    }

    /** The message of the cause a future wraps; the name of the exception when it has none. */
    private static String message(Exception e) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
