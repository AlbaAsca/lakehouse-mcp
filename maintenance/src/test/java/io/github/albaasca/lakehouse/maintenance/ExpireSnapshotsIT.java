package io.github.albaasca.lakehouse.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * Against the real catalog and store. What is left is read with DuckDB and with a listing of the bucket, never with
 * Iceberg's own view: it would not notice files it failed to delete.
 */
class ExpireSnapshotsIT {

    private static final String NS = "maintenance_it";
    private static final String DAY = "TIMESTAMP '2026-01-01 10:00:00'";
    /** What the root .dockerignore lets through: Testcontainers does not read it. */
    private static final List<String> CONTEXT = List.of("pom.xml", "producer/pom.xml", "mcp-server/pom.xml", "agent/pom.xml",
            "semantic/pom.xml", "evals/pom.xml","testkit/pom.xml", "maintenance/pom.xml",
            "maintenance/src/main", "testkit/src/main", "infra/maintenance");

    private static LakehouseStack stack;
    private static Connection duckdb;
    private static RESTCatalog catalog;

    @BeforeAll
    static void connect() throws Exception {
        stack = LakehouseStack.shared();
        duckdb = DriverManager.getConnection("jdbc:duckdb:");
        stack.attachDuckDb(duckdb);
        exec("CREATE SCHEMA IF NOT EXISTS lh." + NS);
        catalog = new RESTCatalog();
        catalog.initialize("lakehouse", ExpireSnapshotsCommand.catalogProperties(Map.of(
                "POLARIS_URL", stack.polarisUrl(),
                "POLARIS_CLIENT_ID", LakehouseStack.CLIENT_ID,
                "POLARIS_CLIENT_SECRET", LakehouseStack.CLIENT_SECRET,
                "POLARIS_REALM", LakehouseStack.REALM,
                "S3_ENDPOINT", "http://" + stack.s3Endpoint(),
                "S3_ACCESS_KEY", LakehouseStack.S3_ACCESS_KEY,
                "S3_SECRET_KEY", LakehouseStack.S3_SECRET_KEY,
                "S3_REGION", LakehouseStack.S3_REGION)));
    }

    @AfterAll
    static void close() throws Exception {
        if (catalog != null) {
            catalog.close();
        }
        if (duckdb != null) {
            duckdb.close();
        }
    }

    @Test
    void keepsExactlyTheSnapshotsInsideTheRetention() throws Exception {
        String t = events("retention");
        for (int id = 1; id <= 4; id++) {
            exec("INSERT INTO " + t + " VALUES (" + id + ", " + DAY + ", 'view', 100)");
        }
        List<Snapshot> history = history("retention");
        assertThat(history).hasSize(4);
        List<String> rows = rows(t);
        List<String> dataFiles = referenced(t, "DATA");
        assertThat(dataFiles).hasSize(4);
        List<String> filesBefore = filesInStore("retention");
        String expiredList = manifestList(history.get(0));

        String logs = runImage("retention", "--older-than",
                Instant.ofEpochMilli(history.get(2).timestampMillis()).toString());

        assertThat(logs).contains("snapshots: 4 -> 2", "files that could not be deleted: 0");
        assertThat(snapshotIds(t)).containsExactly(
                String.valueOf(history.get(2).snapshotId()), String.valueOf(history.get(3).snapshotId()));
        assertThat(rows(t)).isEqualTo(rows);
        assertThat(rowsAt(t, history.get(2))).containsExactly("1", "2", "3");
        assertThatThrownBy(() -> rowsAt(t, history.get(1))).hasMessageContaining("Could not find snapshot");
        // Only appends: the current snapshot still needs every data file.
        assertThat(filesInStore("retention")).containsAll(dataFiles).doesNotContain(expiredList);
        assertThat(filesBefore).contains(expiredList);
    }

    @Test
    void theRetentionCannotRemoveTheCurrentSnapshot() throws Exception {
        String t = events("current");
        exec("INSERT INTO " + t + " VALUES (1, " + DAY + ", 'view', 100)");
        exec("INSERT INTO " + t + " VALUES (2, " + DAY + ", 'view', 100)");
        long current = catalog.loadTable(id("current")).currentSnapshot().snapshotId();

        runImage("current", "--retention", "PT0S");

        assertThat(snapshotIds(t)).containsExactly(String.valueOf(current));
        assertThat(rows(t)).containsExactly("1", "2");
    }

    /**
     * This JVM cannot resolve the store: Polaris gives the table the address seen from inside the stack, and it overrides
     * ours. Without the check, Iceberg would commit and leave every file behind with nothing but warnings.
     */
    @Test
    void withoutAccessToTheStoreNothingIsExpired() throws Exception {
        String t = events("unreachable");
        exec("INSERT INTO " + t + " VALUES (1, " + DAY + ", 'view', 100)");
        exec("INSERT INTO " + t + " VALUES (2, " + DAY + ", 'view', 100)");
        List<String> before = snapshotIds(t);

        assertThatThrownBy(() -> SnapshotExpirer.expire(catalog, id("unreachable"), Instant.now()))
                .isInstanceOf(RuntimeException.class);

        assertThat(snapshotIds(t)).isEqualTo(before).hasSize(2);
    }

    /** The mandatory case: delete files written by DuckDB and a compaction by DuckDB, with the real image. */
    @Test
    void afterACompactionTheImageRemovesTheReplacedFilesFromTheStore() throws Exception {
        String t = events("compacted");
        exec("INSERT INTO " + t + " VALUES (1, " + DAY + ", 'view', 100)");
        exec("INSERT INTO " + t + " VALUES (2, " + DAY + ", 'view', 100)");
        exec("INSERT INTO " + t + " VALUES (3, " + DAY + ", 'view', 100)");
        exec("DELETE FROM " + t + " WHERE event_id = 2");
        List<String> replaced = referenced(t, "DATA");
        assertThat(replaced).hasSize(3);
        assertThat(referenced(t, "POSITION_DELETES")).hasSize(1);
        assertThat(query("CALL iceberg_rewrite_data_files('" + t + "', min_input_files => 2)").get(0)).startsWith("3|1|");
        assertThat(rows(t)).containsExactly("1", "3");

        // Iceberg deletes a replaced file when the snapshot that replaced it expires, and the current one never does.
        String first = runImage("compacted", "--retention", "PT0S");

        assertThat(first).contains("snapshots: 5 -> 1", "files that could not be deleted: 0");
        assertThat(rows(t)).containsExactly("1", "3");
        assertThat(filesInStore("compacted")).containsAll(replaced);

        exec("INSERT INTO " + t + " VALUES (4, " + DAY + ", 'view', 100)");
        String second = runImage("compacted", "--retention", "PT0S");

        assertThat(second).contains("snapshots: 2 -> 1", "files that could not be deleted: 0");
        assertThat(rows(t)).containsExactly("1", "3", "4");
        assertThat(snapshotIds(t)).hasSize(1);
        assertThat(filesInStore("compacted")).doesNotContainAnyElementsOf(replaced)
                .filteredOn(file -> file.contains("/snap-")).hasSize(1);
        // Nothing left that nobody uses. The delete file stays: DuckDB's compaction leaves it registered.
        assertThat(filesInStore("compacted")).filteredOn(file -> !file.contains("/metadata/"))
                .containsExactlyInAnyOrderElementsOf(query(
                        "SELECT file_path FROM iceberg_metadata(" + t + ") WHERE status != 'DELETED'"));
        assertThat(referenced(t, "POSITION_DELETES")).hasSize(1);
    }

    /** A delete file nobody references any more: its commit was rolled back. */
    @Test
    void aDeleteFileWithoutReferencesLeavesTheStore() throws Exception {
        String t = events("rolled_back");
        exec("INSERT INTO " + t + " VALUES (1, " + DAY + ", 'view', 100), (2, " + DAY + ", 'view', 100)");
        long before = catalog.loadTable(id("rolled_back")).currentSnapshot().snapshotId();
        exec("DELETE FROM " + t + " WHERE event_id = 2");
        List<String> deleteFiles = referenced(t, "POSITION_DELETES");
        assertThat(deleteFiles).hasSize(1);
        assertThat(filesInStore("rolled_back")).containsAll(deleteFiles);
        exec("CALL iceberg_rollback_to_snapshot('" + t + "', " + before + ")");
        assertThat(rows(t)).containsExactly("1", "2");

        String logs = runImage("rolled_back", "--retention", "PT0S");

        assertThat(logs).contains("snapshots: 2 -> 1", "files that could not be deleted: 0");
        assertThat(rows(t)).containsExactly("1", "2");
        assertThat(filesInStore("rolled_back")).doesNotContainAnyElementsOf(deleteFiles);
    }

    /**
     * Every column of every row, against a reference written by hand (not read from the table): two partitions, a
     * delete, a compaction and two expirations must not change a single value.
     */
    @Test
    void theCurrentRowsKeepEveryColumnAfterExpiring() throws Exception {
        String t = events("columns");
        exec("INSERT INTO " + t + " VALUES (1, TIMESTAMP '2026-01-01 10:00:00', 'view', 100),"
                + " (2, TIMESTAMP '2026-01-01 11:30:00', 'cart', 200)");
        exec("INSERT INTO " + t + " VALUES (3, TIMESTAMP '2026-01-02 09:15:00', 'purchase', 300),"
                + " (4, TIMESTAMP '2026-01-02 23:59:59', 'view', NULL)");
        exec("DELETE FROM " + t + " WHERE event_id = 2");
        List<String> expected = List.of(
                "1|2026-01-01 10:00:00|view|100",
                "3|2026-01-02 09:15:00|purchase|300",
                "4|2026-01-02 23:59:59|view|null");
        String select = "SELECT event_id, strftime(ts, '%Y-%m-%d %H:%M:%S'), event_type, user_id FROM " + t
                + " ORDER BY event_id";
        assertThat(query(select)).containsExactlyElementsOf(expected);
        query("CALL iceberg_rewrite_data_files('" + t + "', min_input_files => 2)");

        String first = runImage("columns", "--retention", "PT0S");
        exec("INSERT INTO " + t + " VALUES (5, TIMESTAMP '2026-01-03 00:00:00', 'cart', 500)");
        String second = runImage("columns", "--retention", "PT0S");

        assertThat(first).contains("-> 1", "files that could not be deleted: 0");
        assertThat(second).contains("snapshots: 2 -> 1", "files that could not be deleted: 0");
        assertThat(query(select)).containsExactlyElementsOf(List.of(
                expected.get(0), expected.get(1), expected.get(2), "5|2026-01-03 00:00:00|cart|500"));
        assertThat(filesInStore("columns")).filteredOn(file -> !file.contains("/metadata/"))
                .containsExactlyInAnyOrderElementsOf(query(
                        "SELECT file_path FROM iceberg_metadata(" + t + ") WHERE status != 'DELETED'"));
    }

    private static String runImage(String table, String cutoffOption, String cutoff) {
        Path root = LakehouseStack.repositoryRoot();
        ImageFromDockerfile image = new ImageFromDockerfile("lakehouse-testkit-maintenance", false)
                .withDockerfilePath("infra/maintenance/Dockerfile");
        CONTEXT.forEach(path -> image.withFileFromPath(path, root.resolve(path)));
        try (GenericContainer<?> container = new GenericContainer<>(image)
                .withNetwork(stack.network())
                .withEnv(Map.of(
                        "POLARIS_CLIENT_ID", LakehouseStack.CLIENT_ID,
                        "POLARIS_CLIENT_SECRET", LakehouseStack.CLIENT_SECRET,
                        "POLARIS_REALM", LakehouseStack.REALM,
                        "S3_ACCESS_KEY", LakehouseStack.S3_ACCESS_KEY,
                        "S3_SECRET_KEY", LakehouseStack.S3_SECRET_KEY,
                        "S3_REGION", LakehouseStack.S3_REGION))
                .withCommand("--table", NS + "." + table, cutoffOption, cutoff)
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(3)))) {
            container.start();
            return container.getLogs();
        }
    }

    private static String events(String name) throws SQLException {
        String table = "lh." + NS + "." + name;
        exec("CREATE TABLE " + table + " (event_id BIGINT, ts TIMESTAMP, event_type VARCHAR, user_id BIGINT)"
                + " PARTITIONED BY (day(ts), event_type)");
        return table;
    }

    private static TableIdentifier id(String table) {
        return TableIdentifier.of(NS, table);
    }

    /** Oldest first. */
    private static List<Snapshot> history(String table) {
        List<Snapshot> history = new ArrayList<>();
        catalog.loadTable(id(table)).snapshots().forEach(history::add);
        history.sort((a, b) -> Long.compare(a.sequenceNumber(), b.sequenceNumber()));
        return history;
    }

    private static List<String> rows(String table) throws SQLException {
        return query("SELECT event_id FROM " + table + " ORDER BY event_id");
    }

    private static List<String> rowsAt(String table, Snapshot snapshot) throws SQLException {
        return query("SELECT event_id FROM " + table + " AT (VERSION => " + snapshot.snapshotId() + ") ORDER BY event_id");
    }

    private static List<String> snapshotIds(String table) throws SQLException {
        return query("SELECT snapshot_id FROM iceberg_snapshots(" + table + ") ORDER BY sequence_number");
    }

    private static List<String> referenced(String table, String content) throws SQLException {
        return query("SELECT file_path FROM iceberg_metadata(" + table + ") WHERE content = '" + content + "'");
    }

    /** Everything under the table's location, from a listing of the bucket. */
    private static List<String> filesInStore(String table) throws SQLException {
        Table loaded = catalog.loadTable(id(table));
        return query("SELECT file FROM glob('" + loaded.location() + "/**') ORDER BY file");
    }

    private static String manifestList(Snapshot snapshot) {
        return snapshot.manifestListLocation();
    }

    private static void exec(String sql) throws SQLException {
        try (Statement statement = duckdb.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Each row as its columns joined with |. A new statement each time: one that fails is closed by the driver. */
    private static List<String> query(String sql) throws SQLException {
        try (Statement statement = duckdb.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            List<String> rows = new ArrayList<>();
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                StringBuilder row = new StringBuilder(String.valueOf(result.getString(1)));
                for (int i = 2; i <= columns; i++) {
                    row.append('|').append(result.getString(i));
                }
                rows.add(row.toString());
            }
            return rows;
        }
    }
}
