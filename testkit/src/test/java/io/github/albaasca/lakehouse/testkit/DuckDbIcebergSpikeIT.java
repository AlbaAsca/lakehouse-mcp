package io.github.albaasca.lakehouse.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.duckdb.DuckDBConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What DuckDB 2.0 really does with Iceberg through Polaris without STS. Each test answers a question of
 * docs/spike-duckdb-iceberg.md, so a DuckDB upgrade that changes an answer breaks here first.
 */
class DuckDbIcebergSpikeIT {

    private static final String DAY_1 = "TIMESTAMP '2026-01-01 10:00:00'";
    private static final String DAY_2 = "TIMESTAMP '2026-01-02 10:00:00'";
    private static final String READ_ONLY_REFUSAL = "read-only mode";

    private static LakehouseStack stack;
    private static Connection writer;

    @BeforeAll
    static void attach() throws Exception {
        stack = LakehouseStack.shared();
        writer = DriverManager.getConnection("jdbc:duckdb:");
        stack.attachDuckDb(writer);
        exec("CREATE SCHEMA IF NOT EXISTS lh.spike");
    }

    @AfterAll
    static void close() throws Exception {
        writer.close();
    }

    // Question 1: INSERT into a table partitioned by day(ts), event_type.

    @Test
    void insertWritesOneFilePerPartition() throws Exception {
        String t = events("q1");

        exec("INSERT INTO " + t + " VALUES (1, " + DAY_1 + ", 'view', 100), (2, " + DAY_1 + ", 'cart', 100), (3, "
                + DAY_2 + ", 'view', 200)");

        assertThat(rows("SELECT event_id, event_type, user_id FROM " + t + " ORDER BY event_id"))
                .containsExactly("1|view|100", "2|cart|100", "3|view|200");
        assertThat(rows("SELECT transform, source_column FROM iceberg_partition_specs(" + t + ") ORDER BY field_id"))
                .containsExactly("day|ts", "identity|event_type");
        List<String> files = rows("SELECT file_path FROM iceberg_metadata(" + t + ") WHERE content = 'DATA'");
        assertThat(files).hasSize(3);
        assertThat(files).filteredOn(f -> f.contains("=20454/") && f.contains("=cart/")).hasSize(1);
        assertThat(files).filteredOn(f -> f.contains("=20454/") && f.contains("=view/")).hasSize(1);
        assertThat(files).filteredOn(f -> f.contains("=20455/") && f.contains("=view/")).hasSize(1);
    }

    // Question 2: time travel.

    @Test
    void timeTravelWorksWithAtClauseAndWithSnapshotFromId() throws Exception {
        String t = events("q2");
        exec("INSERT INTO " + t + " VALUES (1, " + DAY_1 + ", 'view', 100)");
        String old = latestSnapshot(t);
        String oldTime = value("SELECT timestamp_ms FROM iceberg_snapshots(" + t + ") WHERE snapshot_id = " + old);
        exec("INSERT INTO " + t + " VALUES (2, " + DAY_1 + ", 'view', 100)");
        exec("DELETE FROM " + t + " WHERE event_id = 1");

        assertThat(rows("SELECT event_id FROM " + t)).containsExactly("2");
        assertThat(rows("SELECT event_id FROM " + t + " AT (VERSION => " + old + ")")).containsExactly("1");
        assertThat(rows("SELECT event_id FROM " + t + " AT (TIMESTAMP => TIMESTAMP '" + oldTime + "')"))
                .containsExactly("1");
        assertThat(rows("SELECT event_id FROM iceberg_scan(" + t + ", snapshot_from_id = " + old + ")"))
                .containsExactly("1");
        assertThat(rows("SELECT event_id FROM iceberg_scan('" + t + "', snapshot_from_id = " + old + ")"))
                .containsExactly("1");
        assertThatThrownBy(() -> rows("SELECT event_id FROM " + t + " AT (VERSION => 12345)"))
                .hasMessageContaining("Could not find snapshot with id 12345");
    }

    // Question 3: iceberg_snapshots and iceberg_metadata.

    @Test
    void snapshotsAndMetadataDescribeEachCommit() throws Exception {
        String t = events("q3");
        exec("INSERT INTO " + t + " VALUES (1, " + DAY_1 + ", 'view', 100), (2, " + DAY_1 + ", 'view', 100)");
        exec("DELETE FROM " + t + " WHERE event_id = 1");

        assertThat(rows("SELECT sequence_number, parent_id IS NULL, operation FROM iceberg_snapshots(" + t
                + ") ORDER BY sequence_number")).containsExactly("1|true|append", "2|false|delete");
        assertThat(rows("SELECT manifest_content, status, content, record_count FROM iceberg_metadata(" + t
                + ") ORDER BY manifest_sequence_number")).containsExactly(
                "DATA|ADDED|DATA|2", "DELETE|ADDED|POSITION_DELETES|1");
        assertThat(value("SELECT count(*) FROM iceberg_snapshots('" + t + "')")).isEqualTo("2");
    }

    // Question 4: DELETE in a partitioned table, and removing one of two identical rows.

    @Test
    void deleteByPartitionRangeLeavesTheOtherPartitions() throws Exception {
        String t = events("q4_range");
        exec("INSERT INTO " + t + " VALUES (1, " + DAY_1 + ", 'view', 100), (2, " + DAY_2 + ", 'view', 100), (3, "
                + DAY_2 + ", 'cart', 100)");

        assertThat(update("DELETE FROM " + t + " WHERE ts >= " + DAY_2 + " AND ts < TIMESTAMP '2026-01-03'"))
                .isEqualTo(2);

        assertThat(rows("SELECT event_id FROM " + t)).containsExactly("1");
    }

    @Test
    void removesOneOfTwoIdenticalRowsInTheSameFile() throws Exception {
        String t = events("q4_same_file");
        exec("INSERT INTO " + t + " VALUES (9, " + DAY_1 + ", 'view', 900), (9, " + DAY_1 + ", 'view', 900)");
        assertThat(rows("SELECT file_row_number FROM " + t + " ORDER BY 1")).containsExactly("0", "1");

        assertThat(update("DELETE FROM " + t + " WHERE event_id = 9 AND file_row_number = 1")).isEqualTo(1);

        assertThat(rows("SELECT file_row_number, event_id FROM " + t)).containsExactly("0|9");
    }

    @Test
    void removesOneOfTwoIdenticalRowsInDifferentFiles() throws Exception {
        String t = events("q4_two_files");
        exec("INSERT INTO " + t + " VALUES (10, " + DAY_1 + ", 'view', 900)");
        exec("INSERT INTO " + t + " VALUES (10, " + DAY_1 + ", 'view', 900)");
        List<String> files = rows("SELECT filename FROM " + t + " ORDER BY 1");
        assertThat(files).hasSize(2).doesNotHaveDuplicates();

        assertThat(update("DELETE FROM " + t + " WHERE filename = '" + files.get(1) + "' AND file_row_number = 0"))
                .isEqualTo(1);

        assertThat(rows("SELECT filename FROM " + t)).containsExactly(files.get(0));
    }

    /** The statement a repair would use: no file names known in advance, whichever files the copies are in. */
    @Test
    void windowFunctionKeepsOneCopyOfEachDuplicate() throws Exception {
        String t = events("q4_window");
        exec("INSERT INTO " + t + " VALUES (11, " + DAY_1 + ", 'view', 900), (12, " + DAY_1 + ", 'view', 900)");
        exec("INSERT INTO " + t + " VALUES (11, " + DAY_1 + ", 'view', 900), (11, " + DAY_1 + ", 'view', 900)");

        assertThat(update(dedupe(t))).isEqualTo(2);

        assertThat(rows("SELECT event_id FROM " + t + " ORDER BY 1")).containsExactly("11", "12");
    }

    @Test
    void repeatedDeletesOnTheSameFileAccumulate() throws Exception {
        String t = events("q4_twice");
        exec("INSERT INTO " + t + " VALUES (1, " + DAY_1 + ", 'view', 1), (2, " + DAY_1 + ", 'view', 2), (3, " + DAY_1
                + ", 'view', 3)");

        exec("DELETE FROM " + t + " WHERE event_id = 2");
        exec("DELETE FROM " + t + " WHERE event_id = 3");

        assertThat(rows("SELECT event_id FROM " + t)).containsExactly("1");
        assertThat(value("SELECT count(*) FROM iceberg_metadata(" + t + ") WHERE content = 'POSITION_DELETES'"))
                .isEqualTo("2");
    }

    /** The real table comes from the sink, not from DuckDB: spec and properties are the ones the catalog defaults to. */
    @Test
    void deletesInATableCreatedThroughTheCatalogApi() throws Exception {
        String t = "lh.spike.q4_rest";
        HttpResponse<String> created = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(stack.polarisUrl() + "/api/catalog/v1/lakehouse/namespaces/spike/tables"))
                        .header("Authorization", "Bearer " + stack.polarisToken())
                        .header("Polaris-Realm", LakehouseStack.REALM)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"name":"q4_rest","schema":{"type":"struct","schema-id":0,"fields":[
                                 {"id":1,"name":"event_id","required":false,"type":"long"},
                                 {"id":2,"name":"event_time","required":false,"type":"timestamptz"},
                                 {"id":3,"name":"event_type","required":false,"type":"string"}]},
                                 "partition-spec":{"spec-id":0,"fields":[
                                 {"source-id":2,"field-id":1000,"name":"event_time_day","transform":"day"},
                                 {"source-id":3,"field-id":1001,"name":"event_type","transform":"identity"}]}}"""))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        String instant = "TIMESTAMPTZ '2026-01-01 10:00:00+00'";
        exec("INSERT INTO " + t + " VALUES (1, " + instant + ", 'view'), (1, " + instant + ", 'view'), (2, " + instant
                + ", 'cart')");

        assertThat(update("DELETE FROM " + t + " WHERE event_id = 1 AND file_row_number = 1")).isEqualTo(1);
        assertThat(update("DELETE FROM " + t + " WHERE event_type = 'cart'")).isEqualTo(1);

        assertThat(rows("SELECT event_id, file_row_number FROM " + t)).containsExactly("1|0");
    }

    /** The way out if row-level deletes were not an option. Two snapshots: the intermediate one has the partition empty. */
    @Test
    void rewritingAPartitionInATransactionLeavesTwoSnapshots() throws Exception {
        String t = events("q4_rewrite");
        exec("INSERT INTO " + t + " VALUES (12, " + DAY_1 + ", 'view', 900), (12, " + DAY_1 + ", 'view', 900)");
        String where = " WHERE ts >= " + DAY_1 + " AND ts < " + DAY_2;

        exec("BEGIN");
        exec("CREATE TEMP TABLE rewrite_keep AS SELECT DISTINCT * FROM " + t + where);
        exec("DELETE FROM " + t + where);
        exec("INSERT INTO " + t + " SELECT * FROM rewrite_keep");
        exec("COMMIT");

        assertThat(rows("SELECT event_id FROM " + t)).containsExactly("12");
        List<String> snapshots =
                rows("SELECT snapshot_id FROM iceberg_snapshots(" + t + ") ORDER BY sequence_number");
        assertThat(snapshots).hasSize(3);
        assertThat(rows("SELECT event_id FROM " + t + " AT (VERSION => " + snapshots.get(1) + ")")).isEmpty();
    }

    // Question 5: ATTACH ... READ_ONLY.

    @Test
    void readOnlyAttachReadsAndRefusesEveryWrite() throws Exception {
        String t = events("q5");
        exec("INSERT INTO " + t + " VALUES (1, " + DAY_1 + ", 'view', 100)");
        String snapshot = latestSnapshot(t);
        try (Connection reader = readOnlyConnection()) {
            String ro = "ro.spike.q5";
            assertThat(rows(reader, "SELECT readonly FROM duckdb_databases() WHERE database_name = 'ro'"))
                    .containsExactly("true");
            assertThat(rows(reader, "SELECT event_id FROM " + ro + " AT (VERSION => " + snapshot + ")"))
                    .containsExactly("1");

            for (String write : List.of(
                    "INSERT INTO " + ro + " (event_id) VALUES (2)",
                    "DELETE FROM " + ro,
                    "UPDATE " + ro + " SET user_id = 0",
                    "DROP TABLE " + ro,
                    "CREATE TABLE ro.spike.q5_new (a INT)",
                    "ALTER TABLE " + ro + " ADD COLUMN z INT",
                    "CALL set_iceberg_table_properties('" + ro + "', {'a': 'b'})",
                    "CALL iceberg_rollback_to_snapshot('" + ro + "', " + snapshot + ")",
                    "SELECT * FROM iceberg_rollback_to_snapshot('" + ro + "', " + snapshot + ")")) {
                assertThatThrownBy(() -> rows(reader, write)).as(write).hasMessageContaining(READ_ONLY_REFUSAL);
            }
            assertThat(rows(reader, "SELECT event_id FROM " + ro)).containsExactly("1");
        }
    }

    /** The server keeps one reader open while the sink and the repairs commit from elsewhere. */
    @Test
    void readOnlyAttachSeesLaterCommitsFromAnotherInstance() throws Exception {
        String t = events("q5_fresh");
        exec("INSERT INTO " + t + " VALUES (1, " + DAY_1 + ", 'view', 100)");
        try (Connection reader = readOnlyConnection()) {
            String ro = "ro.spike.q5_fresh";
            assertThat(rows(reader, "SELECT event_id FROM " + ro)).containsExactly("1");

            exec("INSERT INTO " + t + " VALUES (2, " + DAY_1 + ", 'view', 100)");

            assertThat(rows(reader, "SELECT event_id FROM " + ro + " ORDER BY 1")).containsExactly("1", "2");
        }
    }

    // Question 6: json_serialize_sql as the validator's parser.

    @ParameterizedTest
    @ValueSource(strings = {
        "DROP TABLE x", "INSERT INTO x VALUES (1)", "DELETE FROM x", "UPDATE x SET a = 1", "CREATE TABLE x (a INT)",
        "CREATE TABLE x AS SELECT 1", "COPY x TO 'y.csv'", "ATTACH 'x.db'", "SET enable_external_access = true",
        "PRAGMA version", "CALL pragma_version()", "INSTALL x", "LOAD x", "EXPLAIN SELECT 1",
        "PIVOT x ON a USING sum(b)"
    })
    void serializerRejectsWhatIsNotASelect(String sql) throws Exception {
        assertThat(serialize(sql)).contains("\"error\":true", "Only SELECT statements can be serialized to json!");
    }

    /** All of these come back as plain selects, so rejecting them is the validator's job: this is the node to look at. */
    @ParameterizedTest
    @CsvSource(delimiter = '#', quoteCharacter = '~', value = {
        "SELECT * FROM read_csv('/etc/passwd') # \"type\":\"TABLE_FUNCTION\"",
        "FROM read_csv('/etc/passwd') # \"type\":\"TABLE_FUNCTION\"",
        "SELECT * FROM glob('*') # \"type\":\"TABLE_FUNCTION\"",
        "SELECT * FROM query('SELECT 1') # \"type\":\"TABLE_FUNCTION\"",
        "SELECT * FROM query_table('x') # \"type\":\"TABLE_FUNCTION\"",
        "SELECT * FROM iceberg_rollback_to_snapshot('t', 1) # \"type\":\"TABLE_FUNCTION\"",
        "SELECT * FROM (SELECT * FROM read_text('/etc/passwd')) t # \"type\":\"TABLE_FUNCTION\"",
        "SELECT (SELECT content FROM read_text('/etc/passwd')) # \"type\":\"TABLE_FUNCTION\"",
        "SELECT 1 UNION ALL SELECT * FROM read_csv('a') # \"type\":\"TABLE_FUNCTION\"",
        "SELECT * FROM x, LATERAL read_csv(a) # \"type\":\"TABLE_FUNCTION\"",
        "WITH c AS (SELECT * FROM read_csv('a')) SELECT * FROM c # \"type\":\"TABLE_FUNCTION\"",
        "SELECT * FROM '/etc/passwd' # \"table_name\":\"/etc/passwd\"",
        "SELECT * FROM 's3://b/x.parquet' # \"table_name\":\"s3://b/x.parquet\"",
        "DESCRIBE x # \"type\":\"SHOW_REF\"",
        "SUMMARIZE x # \"type\":\"SHOW_REF\"",
        "SHOW TABLES # \"type\":\"SHOW_REF\"",
        "SELECT getenv('HOME') # \"function_name\":\"getenv\"",
        "SELECT * FROM t AT (VERSION => 1) # \"at_clause\":{\"unit\":\"VERSION\"",
        "SELECT 1; SELECT 2 # },{\"node\":"
    })
    void serializerAcceptsThemAndTheNodeGivesThemAway(String sql, String node) throws Exception {
        assertThat(serialize(sql)).contains("\"error\":false", node);
    }

    /** The reason for the "no table functions at all" rule: this select changes the table. */
    @Test
    void aSelectCanRollBackATableWhenTheCatalogIsWritable() throws Exception {
        String t = events("q6_rollback");
        exec("INSERT INTO " + t + " VALUES (1, " + DAY_1 + ", 'view', 100)");
        String first = latestSnapshot(t);
        exec("INSERT INTO " + t + " VALUES (2, " + DAY_1 + ", 'view', 100)");

        rows("SELECT * FROM iceberg_rollback_to_snapshot('" + t + "', " + first + ")");

        assertThat(rows("SELECT event_id FROM " + t)).containsExactly("1");
    }

    // Question 7: masking views over Iceberg tables.

    @Test
    void temporaryViewHashesTheUserAndKeepsGroupsOverAReadOnlyCatalog() throws Exception {
        String t = events("q7");
        exec("INSERT INTO " + t + " VALUES (1, " + DAY_1 + ", 'view', 100), (2, " + DAY_1 + ", 'cart', 100), (3, "
                + DAY_2 + ", 'view', 200)");
        try (Connection reader = readOnlyConnection()) {
            exec(reader, "CREATE TEMP VIEW q7_masked AS SELECT event_id, event_type,"
                    + " md5('salt' || user_id::VARCHAR) AS user_id FROM ro.spike.q7");

            assertThat(rows(reader, "SELECT user_id FROM q7_masked ORDER BY event_id")).containsExactly(
                    md5Of(reader, "salt100"), md5Of(reader, "salt100"), md5Of(reader, "salt200"));
            assertThat(rows(reader, "SELECT count(*) AS n FROM q7_masked GROUP BY user_id ORDER BY n"))
                    .containsExactly("1", "2");
            assertThat(rows(reader, "EXPLAIN SELECT event_type FROM q7_masked WHERE event_type = 'view'").toString())
                    .contains("Iceberg Scan", "Filters: event_type = 'view'");
        }
    }

    /** A pool cannot share a temporary view: it needs one per connection, or a view in the local database. */
    @Test
    void temporaryViewBelongsToItsConnectionAndALocalViewToTheInstance() throws Exception {
        String t = events("q7_scope");
        exec("INSERT INTO " + t + " VALUES (1, " + DAY_1 + ", 'view', 100)");
        try (Connection reader = readOnlyConnection();
                Connection sibling = reader.unwrap(DuckDBConnection.class).duplicate()) {
            exec(reader, "CREATE TEMP VIEW q7_temp AS SELECT event_id FROM ro.spike.q7_scope");
            exec(reader, "CREATE VIEW memory.main.q7_local AS SELECT event_id FROM ro.spike.q7_scope");

            assertThatThrownBy(() -> rows(sibling, "SELECT * FROM q7_temp")).hasMessageContaining("q7_temp");
            assertThat(rows(sibling, "SELECT * FROM memory.main.q7_local")).containsExactly("1");
            assertThat(rows(sibling, "SELECT event_id FROM ro.spike.q7_scope")).containsExactly("1");
        }
    }

    /** Time travel does not go through a view, but a view can be pinned to a snapshot. */
    @Test
    void viewsCannotTimeTravelButCanBePinnedToASnapshot() throws Exception {
        String t = events("q7_travel");
        exec("INSERT INTO " + t + " VALUES (1, " + DAY_1 + ", 'view', 100)");
        String old = latestSnapshot(t);
        exec("INSERT INTO " + t + " VALUES (2, " + DAY_1 + ", 'view', 100)");
        exec("CREATE TEMP VIEW q7_now AS SELECT event_id FROM " + t);
        exec("CREATE TEMP VIEW q7_old AS SELECT event_id FROM " + t + " AT (VERSION => " + old + ")");

        assertThatThrownBy(() -> rows("SELECT * FROM q7_now AT (VERSION => " + old + ")"))
                .hasMessageContaining("does not support time travel");
        assertThat(rows("SELECT * FROM q7_old")).containsExactly("1");
    }

    @Test
    void viewInTheCatalogIsAlsoPossible() throws Exception {
        String t = events("q7_catalog");
        exec("INSERT INTO " + t + " VALUES (1, " + DAY_1 + ", 'view', 100)");
        exec("CREATE VIEW lh.spike.q7_catalog_view AS SELECT event_id FROM " + t);

        assertThat(rows("SELECT * FROM lh.spike.q7_catalog_view")).containsExactly("1");
    }

    // Beyond the questions: decisions 5 and 6 of docs/architecture.md give these to other tools.

    @Test
    void rollbackThroughDuckDb() throws Exception {
        String t = events("x_rollback");
        exec("INSERT INTO " + t + " VALUES (1, " + DAY_1 + ", 'view', 100)");
        String first = latestSnapshot(t);
        exec("INSERT INTO " + t + " VALUES (2, " + DAY_1 + ", 'view', 100)");

        assertThat(rows("CALL iceberg_rollback_to_snapshot('" + t + "', " + first + ")").get(0)).endsWith("|" + first);

        assertThat(rows("SELECT event_id FROM " + t)).containsExactly("1");
    }

    @Test
    void addColumnThroughDuckDbKeepsOldRows() throws Exception {
        String t = events("x_add_column");
        exec("INSERT INTO " + t + " VALUES (1, " + DAY_1 + ", 'view', 100)");
        String old = latestSnapshot(t);

        exec("ALTER TABLE " + t + " ADD COLUMN channel VARCHAR");
        exec("INSERT INTO " + t + " VALUES (2, " + DAY_1 + ", 'view', 100, 'web')");

        assertThat(rows("SELECT event_id, channel FROM " + t + " ORDER BY 1")).containsExactly("1|null", "2|web");
        assertThat(rows("SELECT event_id FROM " + t + " AT (VERSION => " + old + ")")).containsExactly("1");
    }

    @Test
    void compactionThroughDuckDbAppliesDeletesAndMergesFiles() throws Exception {
        String t = events("x_rewrite");
        exec("INSERT INTO " + t + " VALUES (1, " + DAY_1 + ", 'view', 100)");
        exec("INSERT INTO " + t + " VALUES (2, " + DAY_1 + ", 'view', 100)");
        exec("INSERT INTO " + t + " VALUES (3, " + DAY_1 + ", 'view', 100)");
        String beforeDelete = latestSnapshot(t);
        exec("DELETE FROM " + t + " WHERE event_id = 2");

        // By default it needs 5 candidate files per partition and does nothing with fewer.
        assertThat(rows("CALL iceberg_rewrite_data_files('" + t + "')").get(0)).isEqualTo("0|0|0");
        String result = rows("CALL iceberg_rewrite_data_files('" + t + "', min_input_files => 2)").get(0);

        assertThat(result).startsWith("3|1|");
        assertThat(rows("SELECT event_id FROM " + t + " ORDER BY 1")).containsExactly("1", "3");
        // One data file, but the delete file stays registered: the docs announce a count of removed ones that this
        // version of the extension does not return.
        assertThat(rows("SELECT content FROM iceberg_files(" + t + ")")).containsExactlyInAnyOrder("0", "1");
        assertThat(rows("SELECT event_id FROM " + t + " AT (VERSION => " + beforeDelete + ") ORDER BY 1"))
                .containsExactly("1", "2", "3");
    }

    @Test
    void noFunctionExpiresSnapshots() throws Exception {
        assertThat(rows("SELECT DISTINCT function_name FROM duckdb_functions() WHERE function_name LIKE 'iceberg%'"))
                .contains("iceberg_rollback_to_snapshot", "iceberg_rewrite_data_files")
                .noneMatch(name -> name.contains("expire"));
    }

    // Driver behaviour that T-016 has to know about.

    @Test
    void aFailedStatementClosesItsJdbcStatementButNotTheConnection() throws Exception {
        try (Statement statement = writer.createStatement()) {
            assertThatThrownBy(() -> statement.execute("SELECT * FROM does_not_exist")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.execute("SELECT 1")).hasMessageContaining("Statement was closed");
        }
        assertThat(value("SELECT 1")).isEqualTo("1");
    }

    private static String dedupe(String table) {
        return "DELETE FROM " + table + " WHERE (filename, file_row_number) IN (SELECT filename, file_row_number FROM"
                + " (SELECT filename, file_row_number, row_number() OVER (PARTITION BY event_id, ts, event_type, user_id"
                + " ORDER BY filename, file_row_number) AS copy FROM " + table + ") WHERE copy > 1)";
    }

    private static String events(String name) throws SQLException {
        String table = "lh.spike." + name;
        exec("CREATE TABLE " + table + " (event_id BIGINT, ts TIMESTAMP, event_type VARCHAR, user_id BIGINT)"
                + " PARTITIONED BY (day(ts), event_type)");
        return table;
    }

    /** An instance that has only the read-only catalog: `jdbc:duckdb:` opens a new database every time. */
    private static Connection readOnlyConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:duckdb:");
        stack.attachDuckDb(connection);
        exec(connection, "DETACH lh");
        exec(connection, "ATTACH '" + LakehouseStack.CATALOG + "' AS ro (TYPE iceberg, READ_ONLY, URI '"
                + stack.polarisUrl() + "/api/catalog', SECRET lakehouse_polaris, ACCESS_DELEGATION_MODE 'none')");
        return connection;
    }

    private static String latestSnapshot(String table) throws SQLException {
        return value("SELECT snapshot_id FROM iceberg_snapshots(" + table + ") ORDER BY sequence_number DESC LIMIT 1");
    }

    private static String serialize(String sql) throws SQLException {
        return value("SELECT json_serialize_sql('" + sql.replace("'", "''") + "')");
    }

    private static String md5Of(Connection connection, String text) throws SQLException {
        return rows(connection, "SELECT md5('" + text + "')").get(0);
    }

    private static String value(String sql) throws SQLException {
        return rows(sql).get(0);
    }

    private static List<String> rows(String sql) throws SQLException {
        return rows(writer, sql);
    }

    private static void exec(String sql) throws SQLException {
        exec(writer, sql);
    }

    private static long update(String sql) throws SQLException {
        try (Statement statement = writer.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static void exec(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Each row as its columns joined with |. A new statement each time: one that fails is closed by the driver. */
    private static List<String> rows(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
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
