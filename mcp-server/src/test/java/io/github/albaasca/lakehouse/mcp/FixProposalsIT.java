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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The whole flow of propose_fix and apply_fix on a real catalog and a real Postgres: apply without approval changes
 * nothing, after approval it is applied once, and repeating it does not apply it again.
 */
class FixProposalsIT {

    private static final String NS = "fix_it";
    private static final String TABLE = "lh." + NS + ".events";

    private static LakehouseStack stack;
    private static long loaded;
    private static LakehouseSession session;
    private static CatalogReader reader;
    private static FixProposals proposals;
    private static FixTools tools;

    @BeforeAll
    static void start() throws Exception {
        stack = LakehouseStack.shared();
        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:")) {
            stack.attachDuckDb(writer);
            loaded = SyntheticLoader.load(writer, NS, "events");
        }
        Settings settings = Settings.of(
                new Catalog(LakehouseStack.CATALOG, stack.polarisUrl(), LakehouseStack.CLIENT_ID, LakehouseStack.CLIENT_SECRET),
                new Storage(stack.s3Endpoint(), LakehouseStack.S3_ACCESS_KEY, LakehouseStack.S3_SECRET_KEY, LakehouseStack.S3_REGION));
        session = new LakehouseSession(settings);
        reader = new CatalogReader(session);
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

    private static long count() throws SQLException {
        return session.query("SELECT count(*) FROM " + TABLE, rows -> {
            rows.next();
            return rows.getLong(1);
        });
    }

    private static long oldestSnapshot() throws SQLException {
        return reader.snapshots(NS, "events").stream().min(Comparator.comparingLong(Snapshot::sequenceNumber))
                .orElseThrow().snapshotId();
    }

    private static void addRows(int rows) throws SQLException {
        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:"); Statement statement = writer.createStatement()) {
            stack.attachDuckDb(writer);
            statement.execute("INSERT INTO " + TABLE + " SELECT * FROM " + TABLE + " LIMIT " + rows);
        }
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }

    @Test
    void appliesOnlyAfterApprovalAndOnlyOnce() throws Exception {
        addRows(7);
        assertThat(count()).isEqualTo(loaded + 7);
        Map<String, Object> params = Map.of("namespace", NS, "table", "events", "snapshotId", oldestSnapshot());

        Proposal proposal = proposals.propose(FixAction.Type.ROLLBACK_SNAPSHOT, params, "7 duplicated rows in the last load");
        assertThat(proposal.status()).isEqualTo(Status.PROPOSED);
        assertThat(proposal.approvedBy()).isNull();
        assertThat(proposal.expectedEffect()).contains(NS + ".events");

        // Not approved: refused, and the table is as it was.
        CallToolResult refused = tools.applyFix(proposal.id());
        assertThat(refused.isError()).isTrue();
        assertThat(text(refused)).contains("not been approved");
        assertThat(count()).isEqualTo(loaded + 7);
        assertThat(proposals.get(proposal.id()).status()).isEqualTo(Status.PROPOSED);

        Proposal approved = proposals.approve(proposal.id(), "alba");
        assertThat(approved.status()).isEqualTo(Status.APPROVED);
        assertThat(approved.approvedBy()).isEqualTo("alba");
        assertThat(approved.approvedAt()).isNotNull();
        assertThatThrownBy(() -> proposals.approve(proposal.id(), "otra")).isInstanceOf(IllegalStateException.class);

        CallToolResult applied = tools.applyFix(proposal.id());
        assertThat(applied.isError()).isNotEqualTo(true);
        assertThat(text(applied)).contains("\"status\":\"APPLIED\"").contains("\"appliedNow\":true");
        assertThat(count()).isEqualTo(loaded);

        // Written again after the repair: a second application would erase these rows.
        addRows(3);
        CallToolResult again = tools.applyFix(proposal.id());
        assertThat(again.isError()).isNotEqualTo(true);
        assertThat(text(again)).contains("\"status\":\"APPLIED\"").contains("\"appliedNow\":false");
        assertThat(count()).isEqualTo(loaded + 3);
        assertThat(proposals.get(proposal.id()).appliedAt()).isNotNull();
    }

    @Test
    void refusesWhatIsNotAProperProposal() throws Exception {
        long oldest = oldestSnapshot();
        assertThatThrownBy(() -> proposals.propose(FixAction.Type.ROLLBACK_SNAPSHOT,
                Map.of("namespace", NS, "table", "events", "snapshotId", 12345L), "x"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a snapshot");
        assertThatThrownBy(() -> proposals.propose(FixAction.Type.ROLLBACK_SNAPSHOT,
                Map.of("namespace", NS, "table", "events; DROP TABLE x", "snapshotId", oldest), "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> proposals.propose(FixAction.Type.ROLLBACK_SNAPSHOT, Map.of("namespace", NS), "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> proposals.propose(FixAction.Type.ROLLBACK_SNAPSHOT,
                Map.of("namespace", NS, "table", "events", "snapshotId", oldest), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tools.applyFix(987654321L).isError()).isTrue();
    }

    @Test
    void restResourceOffersApproveOnlyWhileProposed() throws Exception {
        Proposal proposal = proposals.propose(FixAction.Type.ROLLBACK_SNAPSHOT,
                Map.of("namespace", NS, "table", "events", "snapshotId", oldestSnapshot()), "links");
        FixProposalController controller = new FixProposalController(proposals, ApprovalGate.OPEN);

        @SuppressWarnings("unchecked")
        Map<String, Object> links = (Map<String, Object>) controller.one(proposal.id()).get("_links");
        assertThat(links).containsKeys("self", "collection", "approve");

        var created = controller.approve(proposal.id(), new FixProposalController.Approval("alba"), null);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        @SuppressWarnings("unchecked")
        Map<String, Object> after = (Map<String, Object>) created.getBody().get("_links");
        assertThat(after).doesNotContainKey("approve");
        assertThat(controller.list().get("proposals").toString()).contains("id=" + proposal.id());

        ApprovalGate closed = (request, by) -> {
            throw new SecurityException("no");
        };
        Proposal other = proposals.propose(FixAction.Type.ROLLBACK_SNAPSHOT,
                Map.of("namespace", NS, "table", "events", "snapshotId", oldestSnapshot()), "gate");
        assertThatThrownBy(() -> new FixProposalController(proposals, closed)
                .approve(other.id(), new FixProposalController.Approval("alba"), null))
                .isInstanceOf(SecurityException.class);
        assertThat(proposals.get(other.id()).status()).isEqualTo(Status.PROPOSED);
    }
}
