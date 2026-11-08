package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.albaasca.lakehouse.mcp.FixProposalStore.Proposal;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Catalog;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Settings;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Storage;
import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import io.github.albaasca.lakehouse.testkit.SyntheticLoader;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Chaos empties one day; the repair is a rollback to the snapshot before it. Without later writes the day gets back its
 * exact rows; with later writes the proposal says, before approval, how many rows would be lost.
 */
class EmptyPartitionCycleIT {

    private static final String NS = "empty_it";
    private static final String TABLE = "lh." + NS + ".events";

    private static LakehouseStack stack;
    private static Settings settings;
    private static LakehouseSession session;
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
        CatalogReader reader = new CatalogReader(session);
        FixProposalStore store = new PostgresFixProposalStore(stack.opsJdbcUrl(), stack.dbUser(), stack.dbPassword()).initialize();
        proposals = new FixProposals(store, List.of(new RollbackSnapshot(reader, RepairSession.alongside(settings))),
                Clock.systemUTC());
        tools = new FixTools(proposals);
    }

    @AfterAll
    static void close() throws Exception {
        if (session != null) {
            session.close();
        }
    }

    private static long count(String where) throws SQLException {
        return session.query("SELECT count(*) FROM " + TABLE + " WHERE " + where, rows -> {
            rows.next();
            return rows.getLong(1);
        });
    }

    private static String busiestDay() throws SQLException {
        return session.query("SELECT CAST(CAST(event_time AS DATE) AS VARCHAR) FROM " + TABLE
                + " GROUP BY 1 ORDER BY count(*) DESC, 1 LIMIT 1", rows -> {
                    rows.next();
                    return rows.getString(1);
                });
    }

    private static String range(LocalDate day) {
        return "event_time >= TIMESTAMPTZ '" + day + " 00:00:00+00' AND event_time < TIMESTAMPTZ '" + day.plusDays(1)
                + " 00:00:00+00'";
    }

    private Proposal emptyAndPropose(LocalDate day, Runnable laterWrites) throws Exception {
        Map<String, Object> report = new Chaos(settings).emptyPartition(NS, "events", day);
        long deleted = ((Number) report.get("rowsDeleted")).longValue();
        assertThat(deleted).isGreaterThan(0);
        assertThat(count(range(day))).isZero();
        laterWrites.run();
        return proposals.propose(FixAction.Type.ROLLBACK_SNAPSHOT,
                Map.of("namespace", NS, "table", "events", "snapshotId", Long.parseLong(report.get("snapshotBefore").toString())),
                "day " + day + " has no rows");
    }

    @Test
    void restoresTheExactRowsOfTheDay() throws Exception {
        LocalDate day = LocalDate.parse(busiestDay());
        long dayRows = count(range(day));
        long total = count("true");

        Proposal proposal = emptyAndPropose(day, () -> {});
        assertThat(proposal.expectedEffect()).contains("Snapshots discarded").doesNotContain("WARNING");

        assertThat(tools.applyFix(proposal.id()).isError()).isTrue();
        assertThat(count(range(day))).isZero();

        proposals.approve(proposal.id(), "alba");
        CallToolResult applied = tools.applyFix(proposal.id());
        assertThat(applied.isError()).isNotEqualTo(true);
        assertThat(count(range(day))).isEqualTo(dayRows);
        assertThat(count("true")).isEqualTo(total);
    }

    @Test
    void warnsAboutWritesAfterTheFailure() throws Exception {
        LocalDate day = LocalDate.parse(busiestDay());
        Proposal proposal = emptyAndPropose(day, () -> {
            try (Connection writer = DriverManager.getConnection("jdbc:duckdb:"); Statement statement = writer.createStatement()) {
                stack.attachDuckDb(writer);
                statement.execute("INSERT INTO " + TABLE + " SELECT * FROM " + TABLE + " LIMIT 5");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(proposal.expectedEffect()).contains("WARNING: 5 rows were added by writes after that snapshot");
    }
}
