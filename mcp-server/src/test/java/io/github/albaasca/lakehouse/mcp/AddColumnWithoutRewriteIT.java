package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * Demo of "schema change without reprocessing": adding a column through the repair instance leaves the data files
 * exactly as they were; old rows read the column as NULL, new rows carry a value, and time travel to the snapshot
 * before the change still sees the old schema. See docs/schema-evolution-demo.md.
 */
class AddColumnWithoutRewriteIT {

    private static final String NS = "evolve_it";

    @Test
    void addingAColumnKeepsEveryDataFileAndOldRowsReadNull() throws Exception {
        LakehouseStack stack = LakehouseStack.shared();
        String t = "lh." + NS + ".events_evolve";
        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:")) {
            stack.attachDuckDb(writer);
            exec(writer, "CREATE SCHEMA IF NOT EXISTS lh." + NS);
            exec(writer, "CREATE TABLE " + t + " (id BIGINT, kind VARCHAR)");
            exec(writer, "INSERT INTO " + t + " VALUES (1, 'view'), (2, 'cart')");
            exec(writer, "INSERT INTO " + t + " VALUES (3, 'view')");
            String before = rows(writer, "SELECT snapshot_id FROM iceberg_snapshots(" + t
                    + ") ORDER BY sequence_number DESC LIMIT 1").get(0);
            List<String> filesBefore = dataFiles(writer, t);
            assertThat(filesBefore).hasSize(2);

            Settings settings = Settings.of(
                    new Catalog(LakehouseStack.CATALOG, stack.polarisUrl(), LakehouseStack.CLIENT_ID, LakehouseStack.CLIENT_SECRET),
                    new Storage(stack.s3Endpoint(), LakehouseStack.S3_ACCESS_KEY, LakehouseStack.S3_SECRET_KEY, LakehouseStack.S3_REGION));
            RepairSession.alongside(settings).apply(RepairSession.ADD_COLUMN,
                    Map.of("table", t, "column", "channel", "type", "VARCHAR"));

            try (Connection reader = DriverManager.getConnection("jdbc:duckdb:")) {
                stack.attachDuckDb(reader);
                // Same files, byte for byte the same paths: nothing was rewritten.
                assertThat(dataFiles(reader, t)).isEqualTo(filesBefore);
                assertThat(rows(reader, "SELECT id, channel FROM " + t + " ORDER BY id"))
                        .containsExactly("1|null", "2|null", "3|null");

                exec(reader, "INSERT INTO " + t + " VALUES (4, 'view', 'web')");
                assertThat(rows(reader, "SELECT id, channel FROM " + t + " ORDER BY id"))
                        .containsExactly("1|null", "2|null", "3|null", "4|web");
                assertThat(dataFiles(reader, t)).containsAll(filesBefore).hasSize(3);

                // Time travel to the snapshot before the change: the old schema, without the column.
                assertThat(rows(reader, "SELECT * FROM " + t + " AT (VERSION => " + before + ") ORDER BY id"))
                        .containsExactly("1|view", "2|cart", "3|view");
                assertThatThrownBy(() -> rows(reader, "SELECT channel FROM " + t + " AT (VERSION => " + before + ")"))
                        .isInstanceOf(SQLException.class);
            }
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
