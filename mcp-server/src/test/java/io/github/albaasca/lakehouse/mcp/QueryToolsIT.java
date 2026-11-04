package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.albaasca.lakehouse.mcp.LakehouseSession.Catalog;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Settings;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Storage;
import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A real MCP client against the whole application. The reference is what the test itself wrote to the table, a value at a
 * time, and the snapshots of the catalog's own REST API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QueryToolsIT {

    private static final String NS = "query_tools_it";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static Connection writer;
    private static McpSyncClient client;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void lakehouse(DynamicPropertyRegistry registry) {
        LakehouseStack stack = LakehouseStack.shared();
        registry.add("lakehouse.catalog.name", () -> LakehouseStack.CATALOG);
        registry.add("lakehouse.catalog.uri", stack::polarisUrl);
        registry.add("lakehouse.catalog.client-id", () -> LakehouseStack.CLIENT_ID);
        registry.add("lakehouse.catalog.client-secret", () -> LakehouseStack.CLIENT_SECRET);
        registry.add("lakehouse.storage.endpoint", stack::s3Endpoint);
        registry.add("lakehouse.storage.access-key", () -> LakehouseStack.S3_ACCESS_KEY);
        registry.add("lakehouse.storage.secret-key", () -> LakehouseStack.S3_SECRET_KEY);
        registry.add("lakehouse.storage.region", () -> LakehouseStack.S3_REGION);
    }

    @BeforeAll
    static void openTheWriter() throws Exception {
        writer = DriverManager.getConnection("jdbc:duckdb:");
        LakehouseStack.shared().attachDuckDb(writer);
        exec("CREATE SCHEMA IF NOT EXISTS lh." + NS);
    }

    @AfterAll
    static void close() throws Exception {
        if (client != null) {
            client.closeGracefully();
        }
        writer.close();
    }

    private static void exec(String sql) throws SQLException {
        try (Statement statement = writer.createStatement()) {
            statement.execute(sql);
        }
    }

    private McpSyncClient client() {
        if (client == null) {
            client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port).build()).build();
            client.initialize();
        }
        return client;
    }

    private CallToolResult call(String tool, Map<String, Object> arguments) {
        return client().callTool(new CallToolRequest(tool, arguments));
    }

    private static String text(CallToolResult result) {
        assertThat(result.content()).hasSize(1);
        return ((TextContent) result.content().getFirst()).text();
    }

    private JsonNode answer(String tool, Map<String, Object> arguments) {
        CallToolResult result = call(tool, arguments);
        assertThat(result.isError()).as(text(result)).isNotEqualTo(Boolean.TRUE);
        return JSON.readTree(text(result));
    }

    private String error(String tool, Map<String, Object> arguments) {
        CallToolResult result = call(tool, arguments);
        assertThat(result.isError()).as(text(result)).isTrue();
        return text(result);
    }

    private static List<String> columns(JsonNode answer) {
        return answer.get("columns").valueStream().map(JsonNode::asString).toList();
    }

    /** Each row as its cells' text, so a failure shows the row. A JSON null reads "null", and is not an empty text. */
    private static List<List<String>> rows(JsonNode answer) {
        List<List<String>> rows = new ArrayList<>();
        answer.get("rows").forEach(row -> rows.add(
                row.valueStream().map(cell -> cell.isNull() ? "null" : cell.asString()).toList()));
        return rows;
    }

    private static Map<String, Object> past(String table, Object snapshotId, String sql) {
        return Map.of("namespace", NS, "table", table, "snapshotId", snapshotId, "sql", sql);
    }

    private static JsonNode catalogMetadata(String table) throws Exception {
        LakehouseStack stack = LakehouseStack.shared();
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                stack.polarisUrl() + "/api/catalog/v1/lakehouse/namespaces/" + NS + "/tables/" + table))
                .header("Authorization", "Bearer " + stack.polarisToken())
                .header("Polaris-Realm", LakehouseStack.REALM)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("metadata");
    }

    private static long currentSnapshot(String table) throws Exception {
        return catalogMetadata(table).get("current-snapshot-id").asLong();
    }

    private static Instant committedAt(String table, long id) throws Exception {
        for (JsonNode snapshot : catalogMetadata(table).get("snapshots")) {
            if (snapshot.get("snapshot-id").asLong() == id) {
                return Instant.ofEpochMilli(snapshot.get("timestamp-ms").asLong());
            }
        }
        throw new AssertionError("No snapshot " + id);
    }

    // run_query

    @Test
    void runQueryGivesTheRowsOneByOne() throws Exception {
        exec("CREATE TABLE lh." + NS + ".now_rows (id BIGINT, name VARCHAR, price DOUBLE)");
        exec("INSERT INTO lh." + NS + ".now_rows VALUES (1, 'a', 1.5), (2, 'b', NULL), (3, NULL, 3.25)");

        JsonNode answer = answer("run_query",
                Map.of("sql", "SELECT id, name, price FROM lh." + NS + ".now_rows ORDER BY id"));

        assertThat(new java.util.HashSet<>(answer.propertyNames())).containsExactlyInAnyOrder("columns", "rows", "truncated");
        assertThat(columns(answer)).containsExactly("id", "name", "price");
        assertThat(rows(answer)).containsExactly(List.of("1", "a", "1.5"), List.of("2", "b", "null"), List.of("3", "null", "3.25"));
        assertThat(answer.get("rows").get(1).get(2).isNull()).isTrue();
        assertThat(answer.get("truncated").asBoolean()).isFalse();
    }

    @Test
    void runQueryCutsAtTheLimitAndSaysSo() throws Exception {
        exec("CREATE TABLE lh." + NS + ".many (id BIGINT)");
        exec("INSERT INTO lh." + NS + ".many VALUES (1), (2), (3)");
        String sql = "SELECT id FROM lh." + NS + ".many ORDER BY id";

        JsonNode cut = answer("run_query", Map.of("sql", sql, "maxRows", 2));
        assertThat(rows(cut)).containsExactly(List.of("1"), List.of("2"));
        assertThat(cut.get("truncated").asBoolean()).isTrue();

        JsonNode exact = answer("run_query", Map.of("sql", sql, "maxRows", 3));
        assertThat(exact.get("rows").size()).isEqualTo(3);
        assertThat(exact.get("truncated").asBoolean()).isFalse();
    }

    /** Types that the JSON of the answer could lose or move: a huge sum, a date without a zone, a list. */
    @Test
    void runQueryKeepsTheValueOfEveryType() throws Exception {
        exec("CREATE TABLE lh." + NS + ".types (n BIGINT, ts TIMESTAMPTZ, plain TIMESTAMP, day DATE, price DECIMAL(10,2), "
                + "tags VARCHAR[], flag BOOLEAN)");
        exec("INSERT INTO lh." + NS + ".types VALUES (9007199254740993, TIMESTAMPTZ '2026-03-01 10:00:00+00', "
                + "TIMESTAMP '2026-03-01 10:00:00', DATE '2026-03-01', 12.50, ['a', 'b'], true)");
        String table = "lh." + NS + ".types";

        JsonNode row = answer("run_query", Map.of("sql", "SELECT n, sum(n) AS total, epoch_ms(ts) AS ms, plain, day, price, "
                + "tags, flag FROM " + table + " GROUP BY ALL")).get("rows").get(0);

        assertThat(row.get(0).bigIntegerValue()).isEqualTo(new java.math.BigInteger("9007199254740993"));
        assertThat(row.get(1).bigIntegerValue()).isEqualTo(new java.math.BigInteger("9007199254740993"));
        assertThat(row.get(2).asLong()).isEqualTo(Instant.parse("2026-03-01T10:00:00Z").toEpochMilli());
        assertThat(row.get(3).asString()).isEqualTo("2026-03-01T10:00");
        assertThat(row.get(4).asString()).isEqualTo("2026-03-01");
        assertThat(row.get(5).decimalValue()).isEqualByComparingTo("12.50");
        assertThat(row.get(6).asString()).contains("a").contains("b");
        assertThat(row.get(7).asBoolean()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"DROP TABLE lh." + NS + ".forbidden", "SELECT * FROM read_csv('/etc/passwd')",
            "SELECT * FROM '/etc/passwd'", "SELECT getenv('HOME')", "SELECT 1; SELECT 2",
            "SELECT * FROM lh." + NS + ".forbidden AT (VERSION => 1)", "SELECT * FROM at_snapshot", "SELECT FROM"})
    void runQueryRefusesWithAClearError(String sql) throws Exception {
        exec("CREATE TABLE IF NOT EXISTS lh." + NS + ".forbidden (id BIGINT)");

        assertThat(error("run_query", Map.of("sql", sql))).isNotBlank().doesNotContain("problem in the server");
        assertThat(answer("list_tables", Map.of()).toString()).contains("forbidden");
    }

    @Test
    void aMistakeInTheSqlTellsTheModelWhatToFix() throws Exception {
        exec("CREATE TABLE lh." + NS + ".mistakes (id BIGINT, name VARCHAR)");
        exec("INSERT INTO lh." + NS + ".mistakes VALUES (1, 'x')");

        assertThat(error("run_query", Map.of("sql", "SELECT nope FROM lh." + NS + ".mistakes"))).contains("nope");
        assertThat(error("run_query", Map.of("sql", "SELECT CAST(name AS INTEGER) FROM lh." + NS + ".mistakes")))
                .contains("Conversion Error");
    }

    @Test
    void aLimitOutOfRangeIsAnErrorTheModelCanRead() {
        assertThat(error("run_query", Map.of("sql", "SELECT 1", "maxRows", 0))).contains("maxRows");
        assertThat(error("run_query", Map.of("sql", "SELECT 1", "maxRows", QueryTools.MAX_ROWS + 1))).contains("maxRows");
    }

    // time_travel

    @Test
    void theSnapshotBeforeAChangeGivesTheRowsOfBefore() throws Exception {
        String table = "travel";
        exec("CREATE TABLE lh." + NS + "." + table + " (id BIGINT, name VARCHAR)");
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (1, 'a'), (2, 'b')");
        long before = currentSnapshot(table);
        exec("DELETE FROM lh." + NS + "." + table + " WHERE id = 1");
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (3, 'c')");
        long after = currentSnapshot(table);
        String sql = "SELECT id, name FROM at_snapshot ORDER BY id";

        JsonNode old = answer("time_travel", past(table, before, sql));
        assertThat(columns(old)).containsExactly("id", "name");
        assertThat(rows(old)).containsExactly(List.of("1", "a"), List.of("2", "b"));
        assertThat(old.get("snapshotId").asLong()).isEqualTo(before);
        assertThat(Instant.parse(old.get("committedAt").asString())).isEqualTo(committedAt(table, before));
        assertThat(old.get("truncated").asBoolean()).isFalse();

        assertThat(rows(answer("time_travel", past(table, after, sql)))).containsExactly(List.of("2", "b"), List.of("3", "c"));
        assertThat(rows(answer("run_query", Map.of("sql", "SELECT id, name FROM lh." + NS + "." + table + " ORDER BY id"))))
                .containsExactly(List.of("2", "b"), List.of("3", "c"));

        JsonNode cut = answer("time_travel", Map.of("namespace", NS, "table", table, "snapshotId", before, "sql", sql, "maxRows", 1));
        assertThat(rows(cut)).containsExactly(List.of("1", "a"));
        assertThat(cut.get("truncated").asBoolean()).isTrue();
    }

    @Test
    void anInstantWithAZoneReadsTheLastSnapshotAtOrBeforeIt() throws Exception {
        String table = "by_instant";
        exec("CREATE TABLE lh." + NS + "." + table + " (id BIGINT)");
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (1)");
        long first = currentSnapshot(table);
        Thread.sleep(5);
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (2)");
        long second = currentSnapshot(table);
        Instant firstAt = committedAt(table, first);
        Instant secondAt = committedAt(table, second);
        assertThat(secondAt).isAfter(firstAt);
        String sql = "SELECT id FROM at_snapshot ORDER BY id";

        Map<String, Object> exact = Map.of("namespace", NS, "table", table, "instant", firstAt.toString(), "sql", sql);
        JsonNode atFirst = answer("time_travel", exact);
        assertThat(atFirst.get("snapshotId").asLong()).isEqualTo(first);
        assertThat(rows(atFirst)).containsExactly(List.of("1"));

        // The same moment written in another zone, and the instant just before the second commit.
        String madrid = firstAt.atOffset(java.time.ZoneOffset.ofHours(2)).toString();
        assertThat(answer("time_travel", Map.of("namespace", NS, "table", table, "instant", madrid, "sql", sql))
                .get("snapshotId").asLong()).isEqualTo(first);
        assertThat(answer("time_travel", Map.of("namespace", NS, "table", table, "instant",
                secondAt.minusMillis(1).toString(), "sql", sql)).get("snapshotId").asLong()).isEqualTo(first);

        JsonNode atSecond = answer("time_travel", Map.of("namespace", NS, "table", table, "instant", secondAt.toString(), "sql", sql));
        assertThat(rows(atSecond)).containsExactly(List.of("1"), List.of("2"));

        assertThat(error("time_travel", Map.of("namespace", NS, "table", table, "instant",
                firstAt.minusSeconds(3600).toString(), "sql", sql))).contains("No snapshot");
        assertThat(error("time_travel", Map.of("namespace", NS, "table", table, "instant", "2026-03-01T10:00:00", "sql", sql)))
                .contains("zone");
    }

    /** The combination decision 21 left unproved: a temporary view with AT over READ_ONLY, on a snapshot with an old schema. */
    @Test
    void aSnapshotWithAnOlderSchemaAnswersWithTheColumnsItHad() throws Exception {
        String table = "old_schema";
        exec("CREATE TABLE lh." + NS + "." + table + " (id BIGINT, name VARCHAR)");
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (1, 'a')");
        long before = currentSnapshot(table);
        exec("ALTER TABLE lh." + NS + "." + table + " ADD COLUMN added VARCHAR");
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (2, 'b', 'new')");
        long after = currentSnapshot(table);

        JsonNode old = answer("time_travel", past(table, before, "SELECT * FROM at_snapshot ORDER BY id"));
        assertThat(columns(old)).containsExactly("id", "name");
        assertThat(rows(old)).containsExactly(List.of("1", "a"));

        JsonNode current = answer("time_travel", past(table, after, "SELECT * FROM at_snapshot ORDER BY id"));
        assertThat(columns(current)).containsExactly("id", "name", "added");
        assertThat(rows(current)).containsExactly(List.of("1", "a", "null"), List.of("2", "b", "new"));

        assertThat(error("time_travel", past(table, before, "SELECT added FROM at_snapshot"))).contains("added");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DROP VIEW at_snapshot", "SELECT * FROM read_csv('/etc/passwd')", "SELECT * FROM '/etc/passwd'",
            "SELECT getenv('HOME') FROM at_snapshot", "SELECT 1 FROM at_snapshot; SELECT 2",
            "SELECT * FROM at_snapshot AT (VERSION => 1)", "SELECT * FROM lh." + NS + ".guarded",
            "SELECT * FROM lh." + NS + ".guarded AT (VERSION => 1)", "SELECT * FROM temp.main.at_snapshot"})
    void timeTravelRefusesWithAClearError(String sql) throws Exception {
        exec("CREATE TABLE IF NOT EXISTS lh." + NS + ".guarded (id BIGINT)");
        exec("INSERT INTO lh." + NS + ".guarded VALUES (1)");

        assertThat(error("time_travel", past("guarded", currentSnapshot("guarded"), sql)))
                .isNotBlank().doesNotContain("problem in the server");
    }

    @Test
    void wrongArgumentsAreErrorsTheModelCanRead() throws Exception {
        String table = "arguments";
        exec("CREATE TABLE lh." + NS + "." + table + " (id BIGINT)");
        String sql = "SELECT * FROM at_snapshot";

        assertThat(error("time_travel", Map.of("namespace", NS, "table", table, "sql", sql))).contains("exactly one");
        assertThat(error("time_travel", Map.of("namespace", NS, "table", table, "sql", sql, "snapshotId", 1,
                "instant", "2026-03-01T10:00:00Z"))).contains("exactly one");
        assertThat(error("time_travel", past(table, 1, sql))).contains("No snapshot 1");
        assertThat(error("time_travel", past("missing", 1, sql))).isEqualTo("No table lh." + NS + ".missing");
        assertThat(error("time_travel", past("x\" AT (VERSION => 1); --", 1, sql))).contains("No table");
    }

    /** The id of another table must not read this one: it has to belong to the table named. */
    @Test
    void theIdOfAnotherTableIsNotFound() throws Exception {
        exec("CREATE TABLE lh." + NS + ".mine (id BIGINT)");
        exec("CREATE TABLE lh." + NS + ".theirs (id BIGINT)");
        exec("INSERT INTO lh." + NS + ".mine VALUES (1)");
        exec("INSERT INTO lh." + NS + ".theirs VALUES (2)");

        assertThat(error("time_travel", past("mine", currentSnapshot("theirs"), "SELECT * FROM at_snapshot")))
                .contains("No snapshot");
    }

    /** More calls than the pool has connections, each on its own snapshot: a shared view would mix them up. */
    @Test
    void callsAtOnceEachReadTheirOwnSnapshot() throws Exception {
        String table = "at_once";
        exec("CREATE TABLE lh." + NS + "." + table + " (id BIGINT)");
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (1)");
        long first = currentSnapshot(table);
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (2)");
        long second = currentSnapshot(table);
        McpSyncClient shared = client();

        Map<Long, List<List<String>>> expected = new HashMap<>();
        expected.put(first, List.of(List.of("1")));
        expected.put(second, List.of(List.of("1"), List.of("2")));
        record Call(long snapshot, Future<CallToolResult> result) {}
        try (ExecutorService pool = Executors.newFixedThreadPool(12)) {
            List<Call> calls = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                long snapshot = i % 2 == 0 ? first : second;
                calls.add(new Call(snapshot, pool.submit(() -> shared.callTool(new CallToolRequest("time_travel",
                        past(table, snapshot, "SELECT id FROM at_snapshot ORDER BY id"))))));
            }
            for (Call call : calls) {
                CallToolResult result = call.result().get();
                assertThat(result.isError()).as(text(result)).isNotEqualTo(Boolean.TRUE);
                assertThat(rows(JSON.readTree(text(result)))).isEqualTo(expected.get(call.snapshot()));
            }
        }
    }

    private static long viewsLeft(LakehouseSession session) throws SQLException {
        return session.query("SELECT count(*) FROM duckdb_views() WHERE view_name = 'at_snapshot'", rows -> {
            rows.next();
            return rows.getLong(1);
        });
    }

    /**
     * A pool of one proves the connection stays reserved and that nothing waits for itself; and the view must be gone after
     * a good call, a call refused by the validator and a call that fails while running.
     */
    @Test
    void onAPoolOfOneNothingWaitsForItselfAndTheViewNeverStays() throws Exception {
        String table = "pool_of_one";
        exec("CREATE TABLE lh." + NS + "." + table + " (id BIGINT)");
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (1)");
        long id = currentSnapshot(table);
        LakehouseStack stack = LakehouseStack.shared();
        Settings settings = Settings.of(
                new Catalog(LakehouseStack.CATALOG, stack.polarisUrl(), LakehouseStack.CLIENT_ID, LakehouseStack.CLIENT_SECRET),
                new Storage(stack.s3Endpoint(), LakehouseStack.S3_ACCESS_KEY, LakehouseStack.S3_SECRET_KEY, LakehouseStack.S3_REGION))
                .withPoolSize(1);

        try (LakehouseSession session = new LakehouseSession(settings)) {
            QueryTools tools = new QueryTools(session, new CatalogReader(session));

            CallToolResult good = tools.timeTravel(NS, table, "SELECT id FROM at_snapshot", id, null, null);
            assertThat(good.isError()).as(text(good)).isNotEqualTo(Boolean.TRUE);
            assertThat(rows(JSON.readTree(text(good)))).containsExactly(List.of("1"));
            assertThat(viewsLeft(session)).isZero();

            assertThat(tools.timeTravel(NS, table, "DROP TABLE x", id, null, null).isError()).isTrue();
            assertThat(viewsLeft(session)).isZero();

            assertThat(tools.timeTravel(NS, table, "SELECT nope FROM at_snapshot", id, null, null).isError()).isTrue();
            assertThat(viewsLeft(session)).isZero();

            CallToolResult current = tools.runQuery("SELECT id FROM lh." + NS + "." + table, null);
            assertThat(current.isError()).as(text(current)).isNotEqualTo(Boolean.TRUE);
        }
    }
}
