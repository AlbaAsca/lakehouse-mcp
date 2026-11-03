package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.albaasca.lakehouse.mcp.CatalogReader.Column;
import io.github.albaasca.lakehouse.mcp.CatalogReader.PartitionField;
import io.github.albaasca.lakehouse.mcp.CatalogReader.Snapshot;
import io.github.albaasca.lakehouse.mcp.CatalogReader.TableRef;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Catalog;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Settings;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Storage;
import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The cases CatalogReaderIT leaves out, with expected values written by hand from the Iceberg spec: tables that DuckDB
 * did not create, every partition transform, rollback, schema changed behind an open session, awkward names.
 */
class CatalogReaderEdgeIT {

    private static final String NS = "catalog_edge_it";

    private static LakehouseStack stack;
    private static Connection writer;

    @BeforeAll
    static void start() throws Exception {
        stack = LakehouseStack.shared();
        writer = DriverManager.getConnection("jdbc:duckdb:");
        stack.attachDuckDb(writer);
        exec("CREATE SCHEMA IF NOT EXISTS lh." + NS);
    }

    @AfterAll
    static void stop() throws Exception {
        writer.close();
    }

    private static void exec(String sql) throws SQLException {
        try (Statement statement = writer.createStatement()) {
            statement.execute(sql);
        }
    }

    private static LakehouseSession session() throws SQLException {
        return new LakehouseSession(Settings.of(
                new Catalog(LakehouseStack.CATALOG, stack.polarisUrl(), LakehouseStack.CLIENT_ID, LakehouseStack.CLIENT_SECRET),
                new Storage(stack.s3Endpoint(), LakehouseStack.S3_ACCESS_KEY, LakehouseStack.S3_SECRET_KEY, LakehouseStack.S3_REGION))
                .withPoolSize(2));
    }

    private static HttpResponse<String> polaris(String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                stack.polarisUrl() + "/api/catalog/v1/lakehouse/namespaces/" + NS + path))
                .header("Authorization", "Bearer " + stack.polarisToken())
                .header("Polaris-Realm", LakehouseStack.REALM)
                .header("Content-Type", "application/json");
        return HttpClient.newHttpClient().send(
                (body == null ? request.GET() : request.POST(HttpRequest.BodyPublishers.ofString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode metadata(String table) throws IOException, InterruptedException {
        HttpResponse<String> response = polaris("/tables/" + URLEncoder.encode(table, StandardCharsets.UTF_8), null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return new ObjectMapper().readTree(response.body()).get("metadata");
    }

    private static void commit(String table, String body) throws IOException, InterruptedException {
        HttpResponse<String> response = polaris("/tables/" + table, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private static String quoted(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }

    /** Created through the REST API, as a Java writer such as the sink would: required column, nested types, all transforms. */
    @Test
    void aTableDuckDbDidNotCreateIsDescribedWithEveryTypeAndTransform() throws Exception {
        String table = "from_rest";
        HttpResponse<String> created = polaris("/tables", """
                {"name":"from_rest","schema":{"type":"struct","fields":[
                 {"id":1,"name":"id","required":true,"type":"long"},
                 {"id":2,"name":"kind","required":false,"type":"string"},
                 {"id":3,"name":"t_year","required":false,"type":"timestamptz"},
                 {"id":4,"name":"t_month","required":false,"type":"timestamptz"},
                 {"id":5,"name":"t_day","required":false,"type":"timestamp"},
                 {"id":6,"name":"t_hour","required":false,"type":"timestamptz"},
                 {"id":7,"name":"addr","required":false,"type":{"type":"struct","fields":[
                   {"id":8,"name":"city","required":false,"type":"string"},
                   {"id":9,"name":"zip","required":false,"type":"int"}]}},
                 {"id":10,"name":"attrs","required":false,"type":{"type":"map","key-id":11,"key":"string","value-id":12,"value":"long","value-required":false}},
                 {"id":13,"name":"tags","required":false,"type":{"type":"list","element-id":14,"element":"string","element-required":false}},
                 {"id":15,"name":"price","required":false,"type":"decimal(10, 2)"}]},
                 "partition-spec":{"spec-id":0,"fields":[
                 {"source-id":1,"field-id":1000,"name":"id_bucket","transform":"bucket[8]"},
                 {"source-id":2,"field-id":1001,"name":"kind_trunc","transform":"truncate[3]"},
                 {"source-id":3,"field-id":1002,"name":"t_year_y","transform":"year"},
                 {"source-id":4,"field-id":1003,"name":"t_month_m","transform":"month"},
                 {"source-id":5,"field-id":1004,"name":"t_day_d","transform":"day"},
                 {"source-id":6,"field-id":1005,"name":"t_hour_h","transform":"hour"}]}}""");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);

        try (LakehouseSession session = session()) {
            CatalogReader reader = new CatalogReader(session);

            assertThat(reader.describe(NS, table).columns()).containsExactly(
                    new Column("id", "BIGINT"),
                    new Column("kind", "VARCHAR"),
                    new Column("t_year", "TIMESTAMP WITH TIME ZONE"),
                    new Column("t_month", "TIMESTAMP WITH TIME ZONE"),
                    new Column("t_day", "TIMESTAMP"),
                    new Column("t_hour", "TIMESTAMP WITH TIME ZONE"),
                    new Column("addr", "STRUCT(city VARCHAR, zip INTEGER)"),
                    new Column("attrs", "MAP(VARCHAR, BIGINT)"),
                    new Column("tags", "VARCHAR[]"),
                    new Column("price", "DECIMAL(10,2)"));
            assertThat(reader.describe(NS, table).partitions()).containsExactly(
                    new PartitionField("id_bucket", "bucket[8]", "id"),
                    new PartitionField("kind_trunc", "truncate[3]", "kind"),
                    new PartitionField("t_year_y", "year", "t_year"),
                    new PartitionField("t_month_m", "month", "t_month"),
                    new PartitionField("t_day_d", "day", "t_day"),
                    new PartitionField("t_hour_h", "hour", "t_hour"));
            assertThat(reader.snapshots(NS, table)).isEmpty();
        }
    }

    /** The order of a spec is the order of its fields in the catalog; field ids need not follow it. */
    @Disabled("T-017: CatalogReader.describe sorts by field_id; remove this line with the fix")
    @Test
    void partitionsKeepTheOrderOfTheSpec() throws Exception {
        String table = "spec_order";
        exec("CREATE TABLE lh." + NS + "." + table + " (a BIGINT, b BIGINT) PARTITIONED BY (a)");
        commit(table, """
                {"requirements":[],"updates":[
                 {"action":"add-spec","spec":{"spec-id":1,"fields":[
                  {"source-id":2,"field-id":1005,"name":"b_first","transform":"identity"},
                  {"source-id":1,"field-id":1004,"name":"a_second","transform":"identity"}]}},
                 {"action":"set-default-spec","spec-id":-1}]}""");

        try (LakehouseSession session = session()) {
            assertThat(new CatalogReader(session).describe(NS, table).partitions()).containsExactly(
                    new PartitionField("b_first", "identity", "b"), new PartitionField("a_second", "identity", "a"));
        }
    }

    /** Schema drift from a writer that is not DuckDB, seen by a session that already read the table. */
    @Test
    void anOpenSessionSeesAColumnRenamedAndRemovedOutsideDuckDb() throws Exception {
        String table = "drifted";
        exec("CREATE TABLE lh." + NS + "." + table + " (id BIGINT, kind VARCHAR, gone VARCHAR) PARTITIONED BY (kind)");

        try (LakehouseSession session = session()) {
            CatalogReader reader = new CatalogReader(session);
            assertThat(reader.describe(NS, table).partitions()).containsExactly(new PartitionField("identity_kind_2", "identity", "kind"));

            commit(table, """
                    {"requirements":[],"updates":[
                     {"action":"add-schema","schema":{"type":"struct","schema-id":1,"fields":[
                      {"id":1,"name":"id","required":false,"type":"long"},
                      {"id":2,"name":"category","required":false,"type":"string"}]}},
                     {"action":"set-current-schema","schema-id":-1}]}""");

            assertThat(reader.describe(NS, table).columns()).containsExactly(
                    new Column("id", "BIGINT"), new Column("category", "VARCHAR"));
            assertThat(reader.describe(NS, table).partitions()).containsExactly(
                    new PartitionField("identity_kind_2", "identity", "category"));
        }
    }

    /** After a rollback the order of the list and the current snapshot stop being the same thing. */
    @Test
    void afterARollbackTheChainIsRightAndTheLastInTheListIsNotTheCurrentOne() throws Exception {
        String table = "rolled_back";
        exec("CREATE TABLE lh." + NS + "." + table + " (id BIGINT)");
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (1)");
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (2)");
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (3)");

        try (LakehouseSession session = session()) {
            CatalogReader reader = new CatalogReader(session);
            List<Snapshot> before = reader.snapshots(NS, table);
            assertThat(before).hasSize(3);
            long first = before.get(0).snapshotId();

            exec("CALL iceberg_rollback_to_snapshot('lh." + NS + "." + table + "', " + first + ")");

            assertThat(metadata(table).get("current-snapshot-id").asLong()).isEqualTo(first);
            List<Snapshot> afterRollback = reader.snapshots(NS, table);
            assertThat(afterRollback).extracting(Snapshot::snapshotId)
                    .containsExactly(before.get(0).snapshotId(), before.get(1).snapshotId(), before.get(2).snapshotId());
            assertThat(afterRollback.getLast().snapshotId()).isNotEqualTo(first);

            exec("INSERT INTO lh." + NS + "." + table + " VALUES (4)");

            List<Snapshot> afterWrite = reader.snapshots(NS, table);
            assertThat(afterWrite).hasSize(4);
            assertThat(afterWrite.get(3).parentId()).isEqualTo(first);
            assertThat(afterWrite.get(3).sequenceNumber()).isEqualTo(4L);
            assertThat(afterWrite.get(3).summary()).containsEntry("total-records", "2");
        }
    }

    /** The table is listed and DESCRIBE reads it, so its partitions and snapshots have to read too. */
    @Disabled("T-017: CatalogReader.functionArgument does not quote the name; remove this line with the fix")
    @Test
    void aTableWithADotInItsNameIsReadLikeAnyOther() throws Exception {
        String table = "do.t";
        exec("CREATE TABLE lh." + NS + "." + quoted(table) + " (id BIGINT, kind VARCHAR) PARTITIONED BY (kind)");
        exec("INSERT INTO lh." + NS + "." + quoted(table) + " VALUES (1, 'a')");

        try (LakehouseSession session = session()) {
            CatalogReader reader = new CatalogReader(session);

            assertThat(reader.listTables()).contains(new TableRef(NS, table));
            assertThat(reader.describe(NS, table).partitions()).containsExactly(new PartitionField("identity_kind_2", "identity", "kind"));
            assertThat(reader.snapshots(NS, table)).extracting(Snapshot::operation).containsExactly("append");
        }
    }

    /** Whatever the catalog accepts as a name has to read like any other. */
    @ParameterizedTest
    @ValueSource(strings = {"Mixed_Case", "with space", "with-hyphen", "quo'te", "ñandú", "select"})
    void aTableWithAnAwkwardNameIsReadLikeAnyOther(String table) throws Exception {
        exec("CREATE TABLE lh." + NS + "." + quoted(table) + " (id BIGINT, kind VARCHAR) PARTITIONED BY (kind)");
        exec("INSERT INTO lh." + NS + "." + quoted(table) + " VALUES (1, 'a')");

        try (LakehouseSession session = session()) {
            CatalogReader reader = new CatalogReader(session);

            assertThat(reader.listTables()).contains(new TableRef(NS, table));
            assertThat(reader.describe(NS, table).columns()).containsExactly(
                    new Column("id", "BIGINT"), new Column("kind", "VARCHAR"));
            assertThat(reader.describe(NS, table).partitions()).containsExactly(new PartitionField("identity_kind_2", "identity", "kind"));
            assertThat(reader.snapshots(NS, table)).extracting(Snapshot::operation).containsExactly("append");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"with space", "Upper"})
    void aNamespaceWithAnAwkwardNameIsReadLikeAnyOther(String namespace) throws Exception {
        exec("CREATE SCHEMA IF NOT EXISTS lh." + quoted(namespace));
        exec("CREATE TABLE lh." + quoted(namespace) + ".inside (id BIGINT)");
        exec("INSERT INTO lh." + quoted(namespace) + ".inside VALUES (1)");

        try (LakehouseSession session = session()) {
            CatalogReader reader = new CatalogReader(session);

            assertThat(reader.listTables()).contains(new TableRef(namespace, "inside"));
            assertThat(reader.describe(namespace, "inside").columns()).containsExactly(new Column("id", "BIGINT"));
            assertThat(reader.snapshots(namespace, "inside")).hasSize(1);
        }
    }

    /** The commit time is an instant: the zone of the machine has no say in it. */
    @Test
    void theCommitTimeDoesNotDependOnTheZoneOfTheMachine() throws Exception {
        String table = "zoned";
        exec("CREATE TABLE lh." + NS + "." + table + " (id BIGINT)");
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (1)");
        long expected = metadata(table).get("snapshots").get(0).get("timestamp-ms").asLong();

        TimeZone original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        try (LakehouseSession session = session()) {
            assertThat(new CatalogReader(session).snapshots(NS, table).get(0).committedAt())
                    .isEqualTo(Instant.ofEpochMilli(expected));
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void readsFromSeveralThreadsDoNotBlockAFullPool() throws Exception {
        String table = "shared_read";
        exec("CREATE TABLE lh." + NS + "." + table + " (id BIGINT)");
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (1)");

        try (LakehouseSession session = session()) {
            CatalogReader reader = new CatalogReader(session);
            ExecutorService pool = Executors.newFixedThreadPool(6);
            try {
                List<Future<Integer>> results = new ArrayList<>();
                for (int i = 0; i < 12; i++) {
                    results.add(pool.submit(() -> reader.describe(NS, table).columns().size()
                            + reader.snapshots(NS, table).size() + reader.listTables().indexOf(new TableRef(NS, table)) * 0));
                }
                for (Future<Integer> result : results) {
                    assertThat(result.get(60, TimeUnit.SECONDS)).isEqualTo(2);
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
