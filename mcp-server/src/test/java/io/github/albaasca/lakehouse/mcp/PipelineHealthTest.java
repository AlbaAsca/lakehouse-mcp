package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.albaasca.lakehouse.mcp.PipelineHealth.ConnectorSource;
import io.github.albaasca.lakehouse.mcp.PipelineHealth.ConnectorState;
import io.github.albaasca.lakehouse.mcp.PipelineHealth.LagSource;
import io.github.albaasca.lakehouse.mcp.PipelineHealth.LastCommitSource;
import io.github.albaasca.lakehouse.mcp.PipelineHealth.PartitionLag;
import io.github.albaasca.lakehouse.mcp.PipelineHealth.Report;
import io.github.albaasca.lakehouse.mcp.PipelineHealth.Status;
import io.github.albaasca.lakehouse.mcp.PipelineHealth.TaskState;
import io.github.albaasca.lakehouse.mcp.PipelineHealth.Thresholds;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The rules of each check, with fake sources and a fixed clock. */
class PipelineHealthTest {

    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    private static final Thresholds LIMITS = new Thresholds(100, 1000, Duration.ofMinutes(2), Duration.ofMinutes(10));
    private static final ConnectorSource RUNNING = () -> new ConnectorState(true, "RUNNING",
            List.of(new TaskState(0, "RUNNING", null)));
    private static final LagSource NO_LAG = () -> List.of(new PartitionLag(0, 50, 50, 0));
    private static final LastCommitSource JUST_NOW = () -> Optional.of(NOW.minusSeconds(10));

    private static Report check(LagSource lag, ConnectorSource connector, LastCommitSource lastCommit) {
        return new PipelineHealth("iceberg-sink", "ecommerce.events", "ecommerce.events", LIMITS, lag, connector,
                lastCommit, Clock.fixed(NOW, ZoneOffset.UTC)).check();
    }

    private static LagSource lagOf(long... lags) {
        return () -> {
            PartitionLag[] out = new PartitionLag[lags.length];
            for (int i = 0; i < lags.length; i++) {
                out[i] = new PartitionLag(i, 0, lags[i], lags[i]);
            }
            return List.of(out);
        };
    }

    @Test
    void allWellIsOkWithNoReasons() {
        Report report = check(NO_LAG, RUNNING, JUST_NOW);

        assertThat(report.status()).isEqualTo(Status.OK);
        assertThat(report.reasons()).isEmpty();
        assertThat(report.checkedAt()).isEqualTo(NOW);
        assertThat(report.lag().group()).isEqualTo("connect-iceberg-sink");
        assertThat(report.lag().messages()).isZero();
        assertThat(report.connector().state()).isEqualTo("RUNNING");
        assertThat(report.freshness().ageSeconds()).isEqualTo(10);
        assertThat(report.freshness().lastSnapshotAt()).isEqualTo(NOW.minusSeconds(10));
    }

    @Test
    void lagIsTheSumOfThePartitionsAndCrossesEachLimitAtItsValue() {
        assertThat(check(lagOf(40, 59), RUNNING, JUST_NOW).lag().status()).isEqualTo(Status.OK);
        assertThat(check(lagOf(40, 59), RUNNING, JUST_NOW).lag().messages()).isEqualTo(99);
        assertThat(check(lagOf(40, 60), RUNNING, JUST_NOW).lag().status()).isEqualTo(Status.WARN);
        assertThat(check(lagOf(999), RUNNING, JUST_NOW).lag().status()).isEqualTo(Status.WARN);
        Report fail = check(lagOf(500, 500), RUNNING, JUST_NOW);
        assertThat(fail.lag().status()).isEqualTo(Status.FAIL);
        assertThat(fail.status()).isEqualTo(Status.FAIL);
        assertThat(fail.reasons()).containsExactly(fail.lag().reason());
        assertThat(fail.lag().reason()).contains("1000 messages");
        assertThat(fail.lag().partitions()).extracting(PartitionLag::lag).containsExactly(500L, 500L);
    }

    @Test
    void freshnessComesFromTheLastSnapshotAndCrossesEachLimitAtItsValue() {
        assertThat(check(NO_LAG, RUNNING, () -> Optional.of(NOW.minusSeconds(119))).freshness().status())
                .isEqualTo(Status.OK);
        assertThat(check(NO_LAG, RUNNING, () -> Optional.of(NOW.minusSeconds(120))).freshness().status())
                .isEqualTo(Status.WARN);
        Report stale = check(lagOf(5), RUNNING, () -> Optional.of(NOW.minusSeconds(600)));
        assertThat(stale.freshness().status()).isEqualTo(Status.FAIL);
        assertThat(stale.freshness().ageSeconds()).isEqualTo(600);
        assertThat(stale.status()).isEqualTo(Status.FAIL);
        assertThat(stale.freshness().reason()).contains("600 s ago").doesNotContain("idle");
    }

    @Test
    void staleWithNothingWaitingSaysTheProducerMayBeIdle() {
        Report report = check(NO_LAG, RUNNING, () -> Optional.of(NOW.minus(Duration.ofHours(3))));

        assertThat(report.freshness().status()).isEqualTo(Status.FAIL);
        assertThat(report.freshness().reason()).contains("idle");
    }

    @Test
    void aSnapshotAheadOfTheClockIsFreshNotNegative() {
        Report report = check(NO_LAG, RUNNING, () -> Optional.of(NOW.plusSeconds(30)));

        assertThat(report.freshness().ageSeconds()).isZero();
        assertThat(report.freshness().status()).isEqualTo(Status.OK);
    }

    @Test
    void aTableWithoutSnapshotsOrThatDoesNotExistFails() {
        Report empty = check(NO_LAG, RUNNING, Optional::empty);
        Report missing = check(NO_LAG, RUNNING, () -> {
            throw new NoSuchElementException("no table");
        });

        assertThat(empty.freshness().status()).isEqualTo(Status.FAIL);
        assertThat(empty.freshness().reason()).contains("no snapshot");
        assertThat(missing.freshness().status()).isEqualTo(Status.FAIL);
        assertThat(missing.freshness().reason()).contains("does not exist");
        assertThat(missing.freshness().ageSeconds()).isNull();
    }

    @Test
    void whatTheCatalogSaysWhenItFailsStaysInTheLog() {
        Report report = check(NO_LAG, RUNNING, () -> {
            throw new SQLException("IO Error: s3://lakehouse/secret/path");
        });

        assertThat(report.freshness().status()).isEqualTo(Status.FAIL);
        assertThat(report.freshness().reason()).isEqualTo("The catalog did not answer.");
    }

    @Test
    void aSourceThatDoesNotAnswerIsReportedNotThrown() {
        Report report = check(() -> {
            throw new ExecutionException(new TimeoutException("Timed out waiting for a node assignment."));
        }, () -> {
            throw new java.net.ConnectException("Connection refused");
        }, JUST_NOW);

        assertThat(report.status()).isEqualTo(Status.FAIL);
        assertThat(report.lag().status()).isEqualTo(Status.FAIL);
        assertThat(report.lag().messages()).isNull();
        assertThat(report.lag().reason()).isEqualTo("Kafka did not answer: Timed out waiting for a node assignment.");
        assertThat(report.connector().status()).isEqualTo(Status.FAIL);
        assertThat(report.connector().reason()).isEqualTo("Kafka Connect did not answer: Connection refused");
        assertThat(report.freshness().status()).isEqualTo(Status.OK);
        assertThat(report.reasons()).containsExactly(report.connector().reason(), report.lag().reason());
    }

    @Test
    void theConnectorStatesMapToStatuses() {
        assertThat(connector(new ConnectorState(false, null, List.of())).reason()).contains("not registered");
        assertThat(connector(new ConnectorState(false, null, List.of())).status()).isEqualTo(Status.FAIL);
        assertThat(connector(new ConnectorState(true, "FAILED", List.of())).status()).isEqualTo(Status.FAIL);
        PipelineHealth.ConnectorCheck taskFailed = connector(new ConnectorState(true, "RUNNING",
                List.of(new TaskState(0, "RUNNING", null), new TaskState(1, "FAILED", "org.x.Boom: no catalog"))));
        assertThat(taskFailed.status()).isEqualTo(Status.FAIL);
        assertThat(taskFailed.reason()).isEqualTo("Task 1 of iceberg-sink has FAILED: org.x.Boom: no catalog");
        assertThat(connector(new ConnectorState(true, "PAUSED", List.of(new TaskState(0, "PAUSED", null)))).status())
                .isEqualTo(Status.WARN);
        assertThat(connector(new ConnectorState(true, "RUNNING", List.of())).status()).isEqualTo(Status.WARN);
        assertThat(connector(new ConnectorState(true, "RUNNING", List.of(new TaskState(0, "UNASSIGNED", null))))
                .status()).isEqualTo(Status.WARN);
    }

    private static PipelineHealth.ConnectorCheck connector(ConnectorState state) {
        return check(NO_LAG, () -> state, JUST_NOW).connector();
    }

    @Test
    void theWorstStatusWinsAndReasonsGoWorstFirst() {
        Report report = check(lagOf(200), () -> new ConnectorState(true, "FAILED", List.of()),
                () -> Optional.of(NOW.minusSeconds(150)));

        assertThat(report.status()).isEqualTo(Status.FAIL);
        assertThat(report.reasons()).containsExactly(report.connector().reason(), report.lag().reason(),
                report.freshness().reason());
    }

    @Test
    void limitsOutOfOrderAreRejected() {
        assertThatThrownBy(() -> new Thresholds(10, 5, Duration.ofMinutes(1), Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Thresholds(5, 10, Duration.ofMinutes(3), Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theToolAnswersTheReportAsJsonWithItsContractFields() {
        PipelineHealth health = new PipelineHealth("iceberg-sink", "ecommerce.events", "ecommerce.events", LIMITS,
                lagOf(3), RUNNING, JUST_NOW, Clock.fixed(NOW, ZoneOffset.UTC));

        CallToolResult result = new PipelineTools(health).pipelineHealth();

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        JsonNode json = new ObjectMapper().readTree(((TextContent) result.content().getFirst()).text());
        assertThat(new HashSet<>(json.propertyNames()))
                .containsExactlyInAnyOrder("status", "reasons", "checkedAt", "lag", "connector", "freshness");
        assertThat(json.get("status").asString()).isEqualTo("OK");
        assertThat(json.get("lag").get("messages").asLong()).isEqualTo(3);
        assertThat(json.get("freshness").get("ageSeconds").asLong()).isEqualTo(10);
    }
}
