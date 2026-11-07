package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.albaasca.lakehouse.mcp.CatalogReader.Snapshot;
import io.github.albaasca.lakehouse.mcp.FixProposalStore.Proposal;
import io.github.albaasca.lakehouse.mcp.FixProposalStore.Status;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Catalog;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Settings;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Storage;
import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import io.github.albaasca.lakehouse.testkit.SyntheticLoader;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The whole cycle without a model: chaos injects duplicates, the quality check sees them, a fix is proposed, approved
 * and applied, and the table ends identical, row by row, to what it was before, source duplicates included.
 */
class DeduplicateCycleIT {

    private static final String NS = "dedup_it";
    private static final String TABLE = "lh." + NS + ".events";

    private static LakehouseStack stack;
    private static Settings settings;
    private static LakehouseSession session;
    private static CatalogReader reader;
    private static FixProposals proposals;
    private static FixTools tools;

    @BeforeAll
    static void start() throws Exception {
        stack = LakehouseStack.shared();
        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:")) {
            stack.attachDuckDb(writer);
            SyntheticLoader.load(writer, NS, "events");
        }
        settings = Settings.of(
                new Catalog(LakehouseStack.CATALOG, stack.polarisUrl(), LakehouseStack.CLIENT_ID, LakehouseStack.CLIENT_SECRET),
                new Storage(stack.s3Endpoint(), LakehouseStack.S3_ACCESS_KEY, LakehouseStack.S3_SECRET_KEY, LakehouseStack.S3_REGION));
        session = new LakehouseSession(settings);
        reader = new CatalogReader(session);
        RepairSession repair = RepairSession.alongside(settings);
        FixProposalStore store = new PostgresFixProposalStore(stack.opsJdbcUrl(), stack.dbUser(), stack.dbPassword()).initialize();
        proposals = new FixProposals(store, List.of(new RollbackSnapshot(reader, repair),
                new DeduplicatePartition(reader, repair)), Clock.systemUTC());
        tools = new FixTools(proposals);
    }

    @AfterAll
    static void close() throws Exception {
        if (session != null) {
            session.close();
        }
    }

    private static long latestSnapshot() throws SQLException {
        return reader.snapshots(NS, "events").stream().max(Comparator.comparingLong(Snapshot::sequenceNumber))
                .orElseThrow().snapshotId();
    }

    private static <T> T read(String sql, java.util.function.Function<ResultSet, T> mapper) throws SQLException {
        return session.query(sql, rows -> {
            rows.next();
            return mapper.apply(rows);
        });
    }

    private static long count(String where) throws SQLException {
        return read("SELECT count(*) FROM " + TABLE + " WHERE " + where, rows -> {
            try {
                return rows.getLong(1);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** Every distinct row with how many copies there are, as text: equal strings mean identical tables. */
    private static List<String> fingerprint() throws SQLException {
        return session.query("SELECT CAST(t AS VARCHAR) || ' x' || count(*) FROM " + TABLE + " t GROUP BY t ORDER BY 1",
                rows -> {
                    List<String> out = new java.util.ArrayList<>();
                    while (rows.next()) {
                        out.add(rows.getString(1));
                    }
                    return out;
                });
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @Test
    void removesWhatChaosInjectedAndKeepsTheSourceDuplicates() throws Exception {
        String busiest = read("SELECT CAST(CAST(event_time AS DATE) AS VARCHAR) FROM " + TABLE
                + " GROUP BY 1 ORDER BY count(*) DESC, 1 LIMIT 1", rows -> {
                    try {
                        return rows.getString(1);
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                });
        LocalDate day = LocalDate.parse(busiest);
        String range = "event_time >= TIMESTAMPTZ '" + day + " 00:00:00+00' AND event_time < TIMESTAMPTZ '"
                + day.plusDays(1) + " 00:00:00+00'";

        // The source already has a duplicate: the very row chaos will copy first. It must survive the repair.
        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:"); Statement statement = writer.createStatement()) {
            stack.attachDuckDb(writer);
            statement.execute("SET TimeZone = 'UTC'");
            statement.execute("INSERT INTO " + TABLE + " SELECT * FROM " + TABLE + " WHERE " + range
                    + " ORDER BY user_session, event_time, product_id, event_type LIMIT 1");
        }
        List<String> before = fingerprint();
        long rowsBefore = count("true");
        long baseline = latestSnapshot();

        Map<String, Object> report = new Chaos(settings).duplicates(NS, "events", day, 10);
        long injected = (Long) report.get("rowsDuplicated");
        assertThat(injected).isGreaterThan(1);
        assertThat(count("true")).isEqualTo(rowsBefore + injected);
        assertThat(fingerprint()).isNotEqualTo(before);
        assertThat(report.get("snapshotBefore").toString()).isEqualTo(Long.toString(baseline));

        Proposal proposal = proposals.propose(FixAction.Type.DEDUPLICATE_PARTITION,
                Map.of("namespace", NS, "table", "events", "day", day.toString(), "sinceSnapshotId", baseline),
                injected + " exact copies appeared on " + day);
        assertThat(proposal.status()).isEqualTo(Status.PROPOSED);
        assertThat(proposal.expectedEffect()).contains("Duplicates that already existed");

        CallToolResult refused = tools.applyFix(proposal.id());
        assertThat(refused.isError()).isTrue();
        assertThat(count("true")).isEqualTo(rowsBefore + injected);

        proposals.approve(proposal.id(), "alba");
        CallToolResult applied = tools.applyFix(proposal.id());
        assertThat(applied.isError()).isNotEqualTo(true);
        assertThat(text(applied)).contains("\"status\":\"APPLIED\"");

        assertThat(count("true")).isEqualTo(rowsBefore);
        assertThat(fingerprint()).isEqualTo(before);
    }

    @Test
    void refusesWhatIsNotAProperDeduplication() throws Exception {
        long snapshot = latestSnapshot();
        assertThatThrownBy(() -> proposals.propose(FixAction.Type.DEDUPLICATE_PARTITION,
                Map.of("namespace", NS, "table", "events", "day", "2019-13-45", "sinceSnapshotId", snapshot), "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> proposals.propose(FixAction.Type.DEDUPLICATE_PARTITION,
                Map.of("namespace", NS, "table", "events", "day", "2019-10-01", "sinceSnapshotId", 12345L), "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a snapshot");
        assertThatThrownBy(() -> proposals.propose(FixAction.Type.DEDUPLICATE_PARTITION,
                Map.of("namespace", NS, "table", "events"), "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
