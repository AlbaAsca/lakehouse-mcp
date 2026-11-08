package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.albaasca.lakehouse.mcp.CatalogReader.Snapshot;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class QueryToolsTest {

    private static final Instant T = Instant.parse("2026-03-01T10:00:00Z");

    private static Snapshot snapshot(long id, Instant at) {
        return new Snapshot(id, id, null, at, "append", Map.of());
    }

    private static long atOrBefore(List<Snapshot> snapshots, Instant moment) {
        return QueryTools.atOrBefore(snapshots, moment, moment.toString(), "lh.ns.t").snapshotId();
    }

    @Test
    void anInstantPicksTheLastSnapshotAtOrBeforeIt() {
        List<Snapshot> snapshots = List.of(snapshot(1, T), snapshot(2, T.plusSeconds(10)), snapshot(3, T.plusSeconds(20)));

        assertThat(atOrBefore(snapshots, T)).isEqualTo(1);
        assertThat(atOrBefore(snapshots, T.plusSeconds(9))).isEqualTo(1);
        assertThat(atOrBefore(snapshots, T.plusSeconds(10))).isEqualTo(2);
        assertThat(atOrBefore(snapshots, T.plusSeconds(1000))).isEqualTo(3);
    }

    @Test
    void onEqualTimesTheLaterCommitWinsAndAClockGoingBackDoesNotConfuseIt() {
        assertThat(atOrBefore(List.of(snapshot(1, T), snapshot(2, T)), T)).isEqualTo(2);
        assertThat(atOrBefore(List.of(snapshot(1, T.plusSeconds(5)), snapshot(2, T)), T.plusSeconds(9))).isEqualTo(1);
    }

    @Test
    void anInstantBeforeTheFirstSnapshotSaysSo() {
        assertThatThrownBy(() -> atOrBefore(List.of(snapshot(1, T)), T.minusMillis(1)))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("lh.ns.t");
        assertThatThrownBy(() -> atOrBefore(List.of(), T)).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void anInstantKeepsItsZone() {
        assertThat(QueryTools.instant("2026-03-01T10:00:00Z")).isEqualTo(T);
        assertThat(QueryTools.instant("2026-03-01T12:00:00+02:00")).isEqualTo(T);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-03-01T10:00:00", "2026-03-01", "yesterday", "", "1772359200000"})
    void anInstantWithoutZoneIsRefused(String written) {
        assertThatThrownBy(() -> QueryTools.instant(written))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zone");
    }

    @Test
    void theRowLimitHasADefaultAndACeiling() {
        assertThat(QueryTools.rowLimit(null)).isEqualTo(QueryTools.DEFAULT_MAX_ROWS);
        assertThat(QueryTools.rowLimit(1)).isEqualTo(1);
        assertThat(QueryTools.rowLimit(QueryTools.MAX_ROWS)).isEqualTo(QueryTools.MAX_ROWS);
        assertThatThrownBy(() -> QueryTools.rowLimit(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueryTools.rowLimit(QueryTools.MAX_ROWS + 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCellIsTheSameOnAnyMachine() {
        LocalDateTime local = LocalDateTime.parse("2026-03-01T10:00:00");
        BigInteger huge = new BigInteger("170141183460469231731687303715884105727");

        assertThat(QueryTools.cell(null)).isNull();
        assertThat(QueryTools.cell(huge)).isEqualTo(huge);
        assertThat(QueryTools.cell(7L)).isEqualTo(7L);
        assertThat(QueryTools.cell(Timestamp.valueOf(local))).isEqualTo("2026-03-01T10:00");
        assertThat(QueryTools.cell(java.sql.Date.valueOf("2026-03-01"))).isEqualTo("2026-03-01");
        assertThat(QueryTools.cell(List.of(1, 2))).isEqualTo("[1, 2]");
    }
}
