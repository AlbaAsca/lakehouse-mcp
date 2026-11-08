package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.albaasca.lakehouse.mcp.CatalogReader.Snapshot;
import io.github.albaasca.lakehouse.mcp.CatalogReader.TableDescription;
import io.github.albaasca.lakehouse.mcp.CatalogReader.TableRef;
import io.github.albaasca.lakehouse.mcp.CatalogTools.TableDetails;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class CatalogToolsTest {

    private static final TableDescription DESCRIPTION = new TableDescription(new TableRef("ns", "t"), List.of(), List.of());

    private static List<Snapshot> snapshots(long count) {
        return LongStream.rangeClosed(1, count)
                .mapToObj(n -> new Snapshot(n, n * 10, null, Instant.EPOCH, "append", Map.of()))
                .toList();
    }

    @Test
    void aLongHistoryKeepsTheMostRecentSnapshotsInOrderAndSaysHowManyThereAre() {
        TableDetails details = TableDetails.of(DESCRIPTION, snapshots(CatalogTools.MAX_SNAPSHOTS + 5L));

        assertThat(details.snapshotCount()).isEqualTo(CatalogTools.MAX_SNAPSHOTS + 5);
        assertThat(details.snapshots()).hasSize(CatalogTools.MAX_SNAPSHOTS);
        assertThat(details.snapshots().getFirst().sequenceNumber()).isEqualTo(6L);
        assertThat(details.snapshots().getLast().sequenceNumber()).isEqualTo(CatalogTools.MAX_SNAPSHOTS + 5L);
    }

    @Test
    void aShortHistoryIsComplete() {
        assertThat(TableDetails.of(DESCRIPTION, snapshots(CatalogTools.MAX_SNAPSHOTS)).snapshots())
                .hasSize(CatalogTools.MAX_SNAPSHOTS);
        assertThat(TableDetails.of(DESCRIPTION, snapshots(0)).snapshots()).isEmpty();
    }
}
