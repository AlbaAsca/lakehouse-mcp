package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.albaasca.lakehouse.mcp.LakehouseSession.Catalog;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Settings;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Storage;
import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Compaction through the repair instance: fewer data files, the very same rows, and the past still reachable.
 * The reference rows are written by hand, not read back from the table. Compacting while the sink commits is not
 * covered here (see docs/wiki: it is expected to fail one of the two commits, untested).
 */
class MaintenanceIT {

    private static final String NS = "compact_it";

    private static RepairSession repair(LakehouseStack stack) {
        return RepairSession.alongside(Settings.of(
                new Catalog(LakehouseStack.CATALOG, stack.polarisUrl(), LakehouseStack.CLIENT_ID, LakehouseStack.CLIENT_SECRET),
                new Storage(stack.s3Endpoint(), LakehouseStack.S3_ACCESS_KEY, LakehouseStack.S3_SECRET_KEY, LakehouseStack.S3_REGION)));
    }

    @Test
    void compactionMergesFilesAppliesDeletesAndKeepsEveryRow() throws Exception {
        LakehouseStack stack = LakehouseStack.shared();
        String t = "lh." + NS + ".events";
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            stack.attachDuckDb(c);
            exec(c, "CREATE SCHEMA IF NOT EXISTS lh." + NS);
            exec(c, "CREATE TABLE " + t + " (id BIGINT, kind VARCHAR, amount BIGINT)");
            for (int id = 1; id <= 6; id++) {
                exec(c, "INSERT INTO " + t + " VALUES (" + id + ", 'k" + id + "', " + id * 10 + ")");
            }
            exec(c, "DELETE FROM " + t + " WHERE id = 3");
            String select = "SELECT id, kind, amount FROM " + t + " ORDER BY id";
            List<String> expected = List.of("1|k1|10", "2|k2|20", "4|k4|40", "5|k5|50", "6|k6|60");
            assertThat(rows(c, select)).containsExactlyElementsOf(expected);
            assertThat(dataFiles(c, t)).hasSize(6);
            String pastId = rows(c, "SELECT snapshot_id FROM iceberg_snapshots(" + t
                    + ") ORDER BY sequence_number LIMIT 1 OFFSET 2").get(0);
            List<String> past = rows(c, "SELECT id FROM " + t + " AT (VERSION => " + pastId + ") ORDER BY id");
            assertThat(past).containsExactly("1", "2", "3");

            // Default minimum (5) would also act here, but the contract is: always explicit.
            String result = repair(stack).applyForResult(RepairSession.COMPACT, Map.of("table", t, "min_input_files", 2L));

            assertThat(result.split("\\|")[0]).isEqualTo("6");
            assertThat(dataFiles(c, t)).hasSize(1);
            assertThat(rows(c, select)).containsExactlyElementsOf(expected);
            assertThat(rows(c, "SELECT id FROM " + t + " AT (VERSION => " + pastId + ") ORDER BY id"))
                    .containsExactlyElementsOf(past);
            // The delete file stays registered after compacting.
            assertThat(rows(c, "SELECT file_path FROM iceberg_metadata(" + t + ") WHERE content = 'POSITION_DELETES'"))
                    .hasSize(1);
        }
    }

    @Test
    void withTooFewFilesNothingIsDoneAndItIsReportedAsSuch() throws Exception {
        LakehouseStack stack = LakehouseStack.shared();
        String t = "lh." + NS + ".few";
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            stack.attachDuckDb(c);
            exec(c, "CREATE SCHEMA IF NOT EXISTS lh." + NS);
            exec(c, "CREATE TABLE " + t + " (id BIGINT)");
            exec(c, "INSERT INTO " + t + " VALUES (1)");
            exec(c, "INSERT INTO " + t + " VALUES (2)");
            List<String> files = dataFiles(c, t);

            String result = repair(stack).applyForResult(RepairSession.COMPACT, Map.of("table", t, "min_input_files", 5L));

            assertThat(result).isEqualTo("0|0|0");
            assertThat(CompactCommand.report(new CompactCommand.Parsed(NS, "few", 5), result)).contains("nothing to compact");
            assertThat(dataFiles(c, t)).isEqualTo(files);
        }
    }

    private static List<String> dataFiles(Connection c, String t) throws SQLException {
        return rows(c, "SELECT DISTINCT file_path FROM iceberg_metadata(" + t
                + ") WHERE content = 'DATA' AND status != 'DELETED' ORDER BY file_path");
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static List<String> rows(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    row.append(i > 1 ? "|" : "").append(rs.getString(i));
                }
                out.add(row.toString());
            }
        }
        return out;
    }
}
