package io.github.albaasca.lakehouse.testkit;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exploration pass: records what each statement does, to decide the assertions. */
class DuckDbIcebergSpikeIT {

    private static final String T = "lh.spike.events";
    private PrintWriter out;
    private Statement st;

    @Test
    void explore() throws Exception {
        LakehouseStack stack = LakehouseStack.shared();
        Files.createDirectories(Path.of("target"));
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(Path.of("target/spike-probe.txt")), true);
                Connection c = DriverManager.getConnection("jdbc:duckdb:");
                Statement s = c.createStatement()) {
            out = writer;
            st = s;
            stack.attachDuckDb(c);
            run();
            readOnly(stack);
        }
    }

    private void run() throws Exception {
        p("SELECT version()");
        p("SELECT extension_name, extension_version FROM duckdb_extensions() WHERE extension_name IN ('iceberg','httpfs')");
        p("CREATE SCHEMA lh.spike");
        // Q1
        p("CREATE TABLE " + T + " (event_id BIGINT, ts TIMESTAMP, event_type VARCHAR, user_id BIGINT, price DOUBLE)"
                + " PARTITIONED BY (day(ts), event_type)");
        p("INSERT INTO " + T + " VALUES (1, TIMESTAMP '2026-01-01 10:00:00', 'view', 100, 1.0),"
                + "(2, TIMESTAMP '2026-01-01 11:00:00', 'cart', 100, 2.0),"
                + "(3, TIMESTAMP '2026-01-02 10:00:00', 'view', 200, 3.0)");
        p("SELECT * FROM " + T + " ORDER BY event_id");
        p("SELECT * FROM iceberg_partition_stats(" + T + ")");
        p("SELECT * FROM iceberg_partition_specs(" + T + ")");
        p("SELECT * FROM iceberg_table_properties(" + T + ")");
        // TIMESTAMPTZ variant, the type a sink would use for an instant
        p("CREATE TABLE lh.spike.events_tz (event_id BIGINT, ts TIMESTAMPTZ, event_type VARCHAR) PARTITIONED BY (day(ts), event_type)");
        p("INSERT INTO lh.spike.events_tz VALUES (1, TIMESTAMPTZ '2026-01-01 10:00:00+00', 'view')");
        p("SELECT * FROM lh.spike.events_tz");
        // Q3
        p("SELECT * FROM iceberg_snapshots(" + T + ")");
        p("SELECT * FROM iceberg_snapshots('" + T + "')");
        p("SELECT * FROM iceberg_metadata(" + T + ")");
        p("SELECT * FROM iceberg_files(" + T + ")");
        p("SELECT * FROM iceberg_history(" + T + ")");
        p("SELECT * FROM iceberg_load_table_response(" + T + ")");
        String first = one("SELECT snapshot_id FROM iceberg_snapshots(" + T + ") ORDER BY sequence_number DESC LIMIT 1");
        p("INSERT INTO " + T + " VALUES (4, TIMESTAMP '2026-01-02 12:00:00', 'cart', 200, 4.0)");
        // Q2
        p("SELECT count(*) FROM " + T);
        p("SELECT count(*) FROM " + T + " AT (VERSION => " + first + ")");
        p("SELECT count(*) FROM iceberg_scan('" + T + "', snapshot_from_id = " + first + ")");
        p("SELECT count(*) FROM iceberg_scan(" + T + ", snapshot_from_id = " + first + ")");
        String ms = one("SELECT timestamp_ms FROM iceberg_snapshots(" + T + ") WHERE snapshot_id = " + first);
        p("SELECT count(*) FROM " + T + " AT (TIMESTAMP => TIMESTAMP '" + ms + "')");
        p("SELECT count(*) FROM " + T + " AT (VERSION => 12345)");
        p("SELECT count(*) FROM " + T + " AT (VERSION => " + first + "::BIGINT)");
        // Q4: virtual columns
        p("SELECT file_path, file_row_number, event_id FROM " + T + " ORDER BY event_id");
        p("SELECT filename, file_row_number, event_id FROM " + T + " ORDER BY event_id");
        p("SELECT rowid, event_id FROM " + T + " ORDER BY event_id");
        p("DELETE FROM " + T + " WHERE event_id = 4");
        p("SELECT event_id FROM " + T + " ORDER BY event_id");
        p("SELECT * FROM iceberg_snapshots(" + T + ")");
        p("SELECT * FROM iceberg_files(" + T + ")");
        p("SELECT count(*) FROM " + T + " AT (VERSION => " + first + ")");
        // Q4: delete a whole partition
        p("DELETE FROM " + T + " WHERE ts >= TIMESTAMP '2026-01-02' AND ts < TIMESTAMP '2026-01-03'");
        p("SELECT event_id FROM " + T + " ORDER BY event_id");
        // Q4: duplicates in the same file
        p("INSERT INTO " + T + " VALUES (9, TIMESTAMP '2026-01-05 10:00:00', 'view', 900, 9.0),"
                + "(9, TIMESTAMP '2026-01-05 10:00:00', 'view', 900, 9.0)");
        p("SELECT file_path, file_row_number, event_id FROM " + T + " WHERE event_id = 9");
        p("DELETE FROM " + T + " WHERE event_id = 9 AND file_row_number = 1");
        p("SELECT file_path, file_row_number, event_id FROM " + T + " WHERE event_id = 9");
        // Q4: duplicates in different files
        p("INSERT INTO " + T + " VALUES (10, TIMESTAMP '2026-01-06 10:00:00', 'view', 900, 9.0)");
        p("INSERT INTO " + T + " VALUES (10, TIMESTAMP '2026-01-06 10:00:00', 'view', 900, 9.0)");
        p("SELECT file_path, file_row_number, event_id FROM " + T + " WHERE event_id = 10");
        String keep = one("SELECT max(file_path) FROM " + T + " WHERE event_id = 10");
        p("DELETE FROM " + T + " WHERE event_id = 10 AND file_path = '" + keep + "' AND file_row_number = 0");
        p("SELECT file_path, file_row_number, event_id FROM " + T + " WHERE event_id = 10");
        // Q4: rowid based and generic duplicate removal, three copies
        p("INSERT INTO " + T + " VALUES (11, TIMESTAMP '2026-01-07 10:00:00', 'view', 900, 9.0)");
        p("INSERT INTO " + T + " VALUES (11, TIMESTAMP '2026-01-07 10:00:00', 'view', 900, 9.0),(11, TIMESTAMP '2026-01-07 10:00:00', 'view', 900, 9.0)");
        p("SELECT rowid, file_path, file_row_number FROM " + T + " WHERE event_id = 11");
        p("DELETE FROM " + T + " WHERE event_id = 11 AND (file_path, file_row_number) IN ("
                + "SELECT file_path, file_row_number FROM (SELECT file_path, file_row_number, row_number() OVER"
                + " (PARTITION BY event_id ORDER BY file_path, file_row_number) rn FROM " + T + " WHERE event_id = 11) WHERE rn > 1)");
        p("SELECT rowid, file_path, file_row_number FROM " + T + " WHERE event_id = 11");
        // Q4: rewrite of a partition in a transaction
        p("INSERT INTO " + T + " VALUES (12, TIMESTAMP '2026-01-08 10:00:00', 'view', 900, 9.0),(12, TIMESTAMP '2026-01-08 10:00:00', 'view', 900, 9.0)");
        String before = one("SELECT count(*) FROM iceberg_snapshots(" + T + ")");
        p("BEGIN");
        p("CREATE TEMP TABLE keep AS SELECT DISTINCT * FROM " + T + " WHERE ts >= TIMESTAMP '2026-01-08' AND ts < TIMESTAMP '2026-01-09'");
        p("DELETE FROM " + T + " WHERE ts >= TIMESTAMP '2026-01-08' AND ts < TIMESTAMP '2026-01-09'");
        p("INSERT INTO " + T + " SELECT * FROM keep");
        p("COMMIT");
        p("SELECT event_id FROM " + T + " WHERE event_id = 12");
        out.println("snapshots before rewrite: " + before + " after: "
                + one("SELECT count(*) FROM iceberg_snapshots(" + T + ")"));
        p("SELECT * FROM iceberg_metadata(" + T + ")");
        // Table created over REST, like a table not created by DuckDB: no write.delete.mode, spec from the catalog
        restTable();
        // Q6
        for (String sql : List.of(
                "SELECT 1",
                "SELECT 1; SELECT 2",
                "DROP TABLE x",
                "INSERT INTO x VALUES (1)",
                "DELETE FROM x",
                "UPDATE x SET a = 1",
                "CREATE TABLE x (a INT)",
                "CREATE TABLE x AS SELECT 1",
                "COPY x TO 'y.csv'",
                "ATTACH 'x.db'",
                "SET enable_external_access = true",
                "PRAGMA version",
                "CALL pragma_version()",
                "INSTALL x",
                "LOAD x",
                "EXPLAIN SELECT 1",
                "DESCRIBE x",
                "SHOW TABLES",
                "SUMMARIZE x",
                "VALUES (1)",
                "FROM x",
                "PIVOT x ON a USING sum(b)",
                "WITH c AS (SELECT 1) SELECT * FROM c",
                "SELECT * FROM read_csv('/etc/passwd')",
                "FROM read_csv('/etc/passwd')",
                "SELECT * FROM '/etc/passwd'",
                "SELECT * FROM 's3://b/x.parquet'",
                "SELECT * FROM glob('*')",
                "SELECT * FROM query('SELECT 1')",
                "SELECT * FROM query_table('x')",
                "SELECT * FROM lh.spike.events AT (VERSION => 1)",
                "SELECT * FROM iceberg_snapshots(lh.spike.events)",
                "SELECT * FROM (SELECT * FROM read_text('/etc/passwd')) t",
                "SELECT (SELECT content FROM read_text('/etc/passwd'))",
                "SELECT getenv('HOME')",
                "SELECT * FROM x, LATERAL read_csv(a)",
                "SELECT 1 UNION ALL SELECT * FROM read_csv('a')")) {
            out.println("--- json_serialize_sql: " + sql);
            p("SELECT json_serialize_sql('" + sql.replace("'", "''") + "')");
        }
        // Q7
        p("SET VARIABLE salt = 'pepper'");
        p("CREATE TEMP VIEW events_masked AS SELECT event_id, ts, event_type, md5('pepper' || user_id::VARCHAR) AS user_id, price FROM " + T);
        p("SELECT * FROM events_masked ORDER BY event_id LIMIT 3");
        p("SELECT user_id, count(*) FROM events_masked GROUP BY 1 ORDER BY 2 DESC");
        p("SELECT * FROM temp.main.events_masked LIMIT 1");
        p("SELECT * FROM events_masked AT (VERSION => " + first + ")");
        p("SELECT json_serialize_sql('SELECT * FROM events_masked')");
        p("EXPLAIN SELECT event_type FROM events_masked WHERE event_type = 'view'");
        p("CREATE VIEW lh.spike.events_catalog_view AS SELECT event_id FROM " + T);
        p("SELECT * FROM lh.spike.events_catalog_view LIMIT 1");
        // Bonus: operations the architecture assigns elsewhere
        p("SELECT count(*) FROM " + T);
        p("CALL iceberg_rollback_to_snapshot('" + T + "', " + first + ")");
        p("SELECT * FROM iceberg_rollback_to_snapshot('" + T + "', " + first + ")");
        p("SELECT count(*) FROM " + T);
        p("CALL iceberg_rewrite_data_files('" + T + "')");
        p("SELECT * FROM iceberg_rewrite_data_files('" + T + "')");
        p("SELECT * FROM iceberg_files(" + T + ")");
    }

    private void restTable() throws Exception {
        LakehouseStack stack = LakehouseStack.shared();
        String body = """
                {"name":"rest_events","schema":{"type":"struct","schema-id":0,"fields":[
                 {"id":1,"name":"event_id","required":false,"type":"long"},
                 {"id":2,"name":"ts","required":false,"type":"timestamptz"},
                 {"id":3,"name":"event_type","required":false,"type":"string"}]},
                 "partition-spec":{"spec-id":0,"fields":[
                 {"source-id":2,"field-id":1000,"name":"ts_day","transform":"day"},
                 {"source-id":3,"field-id":1001,"name":"event_type","transform":"identity"}]}}""";
        var response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                stack.polarisUrl() + "/api/catalog/v1/lakehouse/namespaces/spike/tables"))
                        .header("Authorization", "Bearer " + stack.polarisToken())
                        .header("Polaris-Realm", LakehouseStack.REALM)
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        out.println("REST create: " + response.statusCode() + " " + response.body().substring(0, Math.min(600, response.body().length())));
        String r = "lh.spike.rest_events";
        p("SELECT * FROM iceberg_table_properties(" + r + ")");
        p("INSERT INTO " + r + " VALUES (1, TIMESTAMPTZ '2026-01-01 10:00:00+00', 'view'),(1, TIMESTAMPTZ '2026-01-01 10:00:00+00', 'view'),(2, TIMESTAMPTZ '2026-01-02 10:00:00+00', 'cart')");
        p("SELECT file_path, file_row_number, event_id FROM " + r + " ORDER BY 3,2");
        p("DELETE FROM " + r + " WHERE event_id = 1 AND file_row_number = 1");
        p("DELETE FROM " + r + " WHERE event_id = 2");
        p("SELECT file_path, file_row_number, event_id FROM " + r + " ORDER BY 3,2");
    }

    private void readOnly(LakehouseStack stack) throws Exception {
        p("ATTACH 'lakehouse' AS ro (TYPE iceberg, READ_ONLY, URI '" + stack.polarisUrl()
                + "/api/catalog', SECRET lakehouse_polaris, ACCESS_DELEGATION_MODE 'none')");
        p("SELECT database_name, readonly FROM duckdb_databases() WHERE database_name IN ('lh','ro')");
        p("SELECT count(*) FROM ro.spike.events");
        String snap = one("SELECT snapshot_id FROM iceberg_snapshots(ro.spike.events) ORDER BY sequence_number LIMIT 1");
        p("SELECT count(*) FROM ro.spike.events AT (VERSION => " + snap + ")");
        p("INSERT INTO ro.spike.events (event_id) VALUES (99)");
        p("DELETE FROM ro.spike.events");
        p("UPDATE ro.spike.events SET price = 0");
        p("DROP TABLE ro.spike.events");
        p("CREATE TABLE ro.spike.x (a INT)");
        p("CREATE SCHEMA ro.spike2");
        p("ALTER TABLE ro.spike.events ADD COLUMN z INT");
        p("CALL set_iceberg_table_properties('ro.spike.events', {'a': 'b'})");
        p("CALL iceberg_rollback_to_snapshot('ro.spike.events', " + snap + ")");
        p("SELECT * FROM iceberg_rollback_to_snapshot('ro.spike.events', " + snap + ")");
        p("SELECT count(*) FROM ro.spike.events");
        p("CREATE TEMP VIEW ro_masked AS SELECT event_id, md5(user_id::VARCHAR) u FROM ro.spike.events");
        p("SELECT * FROM ro_masked LIMIT 1");
    }

    private void p(String sql) {
        out.println(">>> " + sql);
        try (ResultSet rs = st.execute(sql) ? st.getResultSet() : null) {
            if (rs == null) {
                out.println("OK, updates=" + st.getUpdateCount());
                return;
            }
            int n = rs.getMetaData().getColumnCount();
            StringBuilder header = new StringBuilder();
            for (int i = 1; i <= n; i++) {
                header.append(rs.getMetaData().getColumnName(i)).append(" | ");
            }
            out.println(header);
            for (int row = 0; rs.next() && row < 30; row++) {
                StringBuilder line = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    String v = String.valueOf(rs.getObject(i));
                    line.append(v.length() > 1500 ? v.substring(0, 1500) + "..." : v).append(" | ");
                }
                out.println(line);
            }
        } catch (SQLException e) {
            String m = e.getMessage();
            out.println("ERROR: " + (m.length() > 500 ? m.substring(0, 500) + "..." : m));
        }
    }

    private String one(String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
