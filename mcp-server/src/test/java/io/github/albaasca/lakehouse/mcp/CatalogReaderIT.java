package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.albaasca.lakehouse.mcp.CatalogReader.Column;
import io.github.albaasca.lakehouse.mcp.CatalogReader.PartitionField;
import io.github.albaasca.lakehouse.mcp.CatalogReader.Snapshot;
import io.github.albaasca.lakehouse.mcp.CatalogReader.TableDescription;
import io.github.albaasca.lakehouse.mcp.CatalogReader.TableRef;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Catalog;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Settings;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Storage;
import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Each test writes to its own table and checks the reader against what it wrote and against the catalog's own REST API. */
class CatalogReaderIT {

    private static final String NS = "catalog_it";
    private static final String DAY_1 = "TIMESTAMPTZ '2026-01-01 10:00:00+00'";
    private static final String DAY_2 = "TIMESTAMPTZ '2026-01-02 10:00:00+00'";

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

    private static void createEvents(String table) throws SQLException {
        exec("CREATE TABLE lh." + NS + "." + table + " (ts TIMESTAMPTZ, kind VARCHAR, n BIGINT, price DECIMAL(10,2),"
                + " tags VARCHAR[]) PARTITIONED BY (day(ts), kind)");
    }

    private static void insert(String table, int n, String time, String kind) throws SQLException {
        exec("INSERT INTO lh." + NS + "." + table + " (ts, kind, n) VALUES (" + time + ", '" + kind + "', " + n + ")");
    }

    private static LakehouseSession session() throws SQLException {
        return new LakehouseSession(Settings.of(
                new Catalog(LakehouseStack.CATALOG, stack.polarisUrl(), LakehouseStack.CLIENT_ID, LakehouseStack.CLIENT_SECRET),
                new Storage(stack.s3Endpoint(), LakehouseStack.S3_ACCESS_KEY, LakehouseStack.S3_SECRET_KEY, LakehouseStack.S3_REGION))
                .withPoolSize(1));
    }

    private static HttpResponse<String> polaris(String table, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                stack.polarisUrl() + "/api/catalog/v1/lakehouse/namespaces/" + NS + "/tables/" + table))
                .header("Authorization", "Bearer " + stack.polarisToken())
                .header("Polaris-Realm", LakehouseStack.REALM)
                .header("Content-Type", "application/json");
        return HttpClient.newHttpClient().send(
                (body == null ? request.GET() : request.POST(HttpRequest.BodyPublishers.ofString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The reference: the table's metadata as the catalog serves it, with DuckDB out of the way. */
    private static JsonNode metadata(String table) throws IOException, InterruptedException {
        HttpResponse<String> response = polaris(table, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return new ObjectMapper().readTree(response.body()).get("metadata");
    }

    @Test
    void snapshotsComeInOrderAndMatchTheCatalog() throws Exception {
        String table = "snapshot_list"; // Not "snapshots": Polaris answers 500 to a table with that name.
        createEvents(table);
        insert(table, 1, DAY_1, "view");
        insert(table, 2, DAY_2, "cart");
        exec("DELETE FROM lh." + NS + "." + table + " WHERE n = 1");

        List<Snapshot> snapshots;
        try (LakehouseSession session = session()) {
            snapshots = new CatalogReader(session).snapshots(NS, table);
        }

        assertThat(snapshots).extracting(Snapshot::sequenceNumber).containsExactly(1L, 2L, 3L);
        assertThat(snapshots).extracting(Snapshot::operation).containsExactly("append", "append", "delete");
        assertThat(snapshots.get(0).parentId()).isNull();
        assertThat(snapshots.get(1).parentId()).isEqualTo(snapshots.get(0).snapshotId());
        assertThat(snapshots.get(2).parentId()).isEqualTo(snapshots.get(1).snapshotId());
        assertThat(snapshots.get(1).summary()).containsEntry("added-records", "1").containsEntry("total-records", "2");
        assertThat(snapshots.get(2).summary()).containsEntry("added-position-deletes", "1");

        Map<Long, JsonNode> expected = new HashMap<>();
        metadata(table).get("snapshots").forEach(node -> expected.put(node.get("snapshot-id").asLong(), node));
        assertThat(expected).hasSize(3);
        for (Snapshot snapshot : snapshots) {
            JsonNode reference = expected.get(snapshot.snapshotId());
            assertThat(reference).as("snapshot %d in the catalog", snapshot.snapshotId()).isNotNull();
            assertThat(snapshot.sequenceNumber()).isEqualTo(reference.get("sequence-number").asLong());
            assertThat(snapshot.committedAt()).isEqualTo(Instant.ofEpochMilli(reference.get("timestamp-ms").asLong()));
            assertThat(snapshot.operation()).isEqualTo(reference.get("summary").get("operation").asString());
        }
        assertThat(snapshots.get(2).snapshotId()).isEqualTo(metadata(table).get("current-snapshot-id").asLong());
    }

    @Test
    void describeGivesTheColumnsAndPartitionsOfTheCatalog() throws Exception {
        String table = "described";
        createEvents(table);

        TableDescription description;
        try (LakehouseSession session = session()) {
            // First thing this session reads: a table it has not loaded yet is where information_schema.columns fails.
            description = new CatalogReader(session).describe(NS, table);
        }

        assertThat(description.table()).isEqualTo(new TableRef(NS, table));
        assertThat(description.columns()).containsExactly(
                new Column("ts", "TIMESTAMP WITH TIME ZONE"), new Column("kind", "VARCHAR"), new Column("n", "BIGINT"),
                new Column("price", "DECIMAL(10,2)"), new Column("tags", "VARCHAR[]"));
        assertThat(description.partitions()).extracting(PartitionField::transform, PartitionField::sourceColumn)
                .containsExactly(tuple("day", "ts"), tuple("identity", "kind"));

        JsonNode metadata = metadata(table);
        List<String> names = new ArrayList<>();
        metadata.get("schemas").get(0).get("fields").forEach(field -> names.add(field.get("name").asString()));
        assertThat(description.columns()).extracting(Column::name).containsExactlyElementsOf(names);
        List<String> partitionNames = new ArrayList<>();
        metadata.get("partition-specs").get(0).get("fields").forEach(field -> partitionNames.add(field.get("name").asString()));
        assertThat(description.partitions()).extracting(PartitionField::name).containsExactlyElementsOf(partitionNames);
    }

    @Test
    void aTableWithoutPartitionsNorWritesHasEmptyLists() throws Exception {
        exec("CREATE TABLE lh." + NS + ".empty_flat (id BIGINT)");

        try (LakehouseSession session = session()) {
            CatalogReader reader = new CatalogReader(session);

            assertThat(reader.describe(NS, "empty_flat").partitions()).isEmpty();
            assertThat(reader.describe(NS, "empty_flat").columns()).containsExactly(new Column("id", "BIGINT"));
            assertThat(reader.snapshots(NS, "empty_flat")).isEmpty();
        }
    }

    /** The default spec, not all the specs: rows are written with the current one. */
    @Test
    void afterChangingThePartitionsOnlyTheCurrentOnesAreListed() throws Exception {
        String table = "respec";
        createEvents(table);
        HttpResponse<String> committed = polaris(table, """
                {"requirements":[],"updates":[
                 {"action":"add-spec","spec":{"spec-id":1,"fields":[
                  {"source-id":3,"field-id":1002,"name":"n_only","transform":"identity"}]}},
                 {"action":"set-default-spec","spec-id":-1}]}""");
        assertThat(committed.statusCode()).as(committed.body()).isEqualTo(200);
        assertThat(metadata(table).get("partition-specs")).hasSize(2);

        try (LakehouseSession session = session()) {
            assertThat(new CatalogReader(session).describe(NS, table).partitions())
                    .containsExactly(new PartitionField("n_only", "identity", "n"));
        }
    }

    /** The server stays open for hours, and schema drift is one of the failures it has to report. */
    @Test
    void aSessionAlreadyOpenSeesEveryChangeInTheCatalog() throws Exception {
        String table = "changing";
        createEvents(table);
        insert(table, 1, DAY_1, "view");

        try (LakehouseSession session = session()) {
            CatalogReader reader = new CatalogReader(session);
            assertThat(reader.describe(NS, table).columns()).hasSize(5);
            assertThat(reader.snapshots(NS, table)).hasSize(1);
            assertThat(reader.listTables()).contains(new TableRef(NS, table)).doesNotContain(new TableRef(NS, "later"));

            exec("ALTER TABLE lh." + NS + "." + table + " ADD COLUMN added VARCHAR");
            insert(table, 2, DAY_2, "cart");
            exec("CREATE TABLE lh." + NS + ".later (id BIGINT)");

            assertThat(reader.describe(NS, table).columns()).hasSize(6).contains(new Column("added", "VARCHAR"));
            assertThat(reader.snapshots(NS, table)).extracting(Snapshot::sequenceNumber).containsExactly(1L, 2L);
            assertThat(reader.listTables()).contains(new TableRef(NS, "later"));

            exec("DROP TABLE lh." + NS + ".later");

            assertThat(reader.listTables()).doesNotContain(new TableRef(NS, "later"));
            assertThatThrownBy(() -> reader.describe(NS, "later")).isInstanceOf(NoSuchElementException.class);
        }
    }

    @Test
    void listsTablesSortedAndOnlyFromTheCatalog() throws Exception {
        exec("CREATE TABLE lh." + NS + ".zz_listed (id BIGINT)");
        exec("CREATE TABLE lh." + NS + ".aa_listed (id BIGINT)");

        try (LakehouseSession session = session()) {
            List<TableRef> tables = new CatalogReader(session).listTables();

            assertThat(tables).filteredOn(ref -> ref.table().endsWith("_listed")).containsExactly(
                    new TableRef(NS, "aa_listed"), new TableRef(NS, "zz_listed"));
            assertThat(tables).extracting(TableRef::namespace).doesNotContain("main", "information_schema", "pg_catalog");
        }
    }

    @Test
    void aMissingTableIsSaidClearlyAndAHostileNameIsOnlyAName() throws Exception {
        exec("CREATE TABLE lh." + NS + ".real_one (id BIGINT)");

        try (LakehouseSession session = session()) {
            CatalogReader reader = new CatalogReader(session);
            assertThatThrownBy(() -> reader.snapshots(NS, "missing"))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("lh.catalog_it.missing");
            assertThatThrownBy(() -> reader.describe("nowhere", "real_one")).isInstanceOf(NoSuchElementException.class);
            // Iceberg tells these apart, so a different case is another table.
            assertThatThrownBy(() -> reader.snapshots(NS, "REAL_ONE")).isInstanceOf(NoSuchElementException.class);

            for (String hostile : List.of("real_one' OR '1'='1", "real_one\"; DROP TABLE x; --", "real_one'); DROP TABLE x; --")) {
                assertThatThrownBy(() -> reader.describe(NS, hostile)).as(hostile).isInstanceOf(NoSuchElementException.class);
                assertThatThrownBy(() -> reader.snapshots(hostile, "real_one")).as(hostile).isInstanceOf(NoSuchElementException.class);
            }

            assertThat(reader.describe(NS, "real_one").columns()).containsExactly(new Column("id", "BIGINT"));
        }
    }
}
