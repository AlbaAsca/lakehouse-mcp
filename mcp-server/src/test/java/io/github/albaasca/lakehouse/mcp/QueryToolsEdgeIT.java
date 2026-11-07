package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
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
 * The tester's own reference for run_query and time_travel: a model of the table kept in Java, written without looking at
 * what the server answers, compared row by row at every snapshot.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QueryToolsEdgeIT {

    private static final String NS = "query_edge_" + System.currentTimeMillis();
    /** Secret value: its digits must not be able to appear by chance in the namespace (it holds the clock). */
    private static final long SECRET = 7_777_777_777L;
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
        registry.add("lakehouse.security.analyst-key", () -> "analyst-it-key");
    }

    @BeforeAll
    static void openTheWriter() throws Exception {
        writer = DriverManager.getConnection("jdbc:duckdb:");
        LakehouseStack.shared().attachDuckDb(writer);
        exec("CREATE SCHEMA lh." + NS);
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

    private CallToolResult call(String tool, Map<String, Object> arguments) {
        if (client == null) {
            client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                    .requestBuilder(java.net.http.HttpRequest.newBuilder().header("Authorization", "Bearer analyst-it-key")).build()).build();
            client.initialize();
        }
        return client.callTool(new CallToolRequest(tool, arguments));
    }

    private static String text(CallToolResult result) {
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

    private static List<List<String>> rows(JsonNode answer) {
        List<List<String>> rows = new ArrayList<>();
        answer.get("rows").forEach(row -> rows.add(
                row.valueStream().map(cell -> cell.isNull() ? "null" : cell.asString()).toList()));
        return rows;
    }

    private static List<String> columns(JsonNode answer) {
        return answer.get("columns").valueStream().map(JsonNode::asString).toList();
    }

    private static Map<String, Object> past(String table, Object snapshotId, String sql) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("namespace", NS);
        arguments.put("table", table);
        arguments.put("snapshotId", snapshotId);
        arguments.put("sql", sql);
        return arguments;
    }

    private static JsonNode metadata(String table) throws Exception {
        LakehouseStack stack = LakehouseStack.shared();
        String encoded = java.net.URLEncoder.encode(table, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                stack.polarisUrl() + "/api/catalog/v1/lakehouse/namespaces/" + NS + "/tables/" + encoded))
                .header("Authorization", "Bearer " + stack.polarisToken())
                .header("Polaris-Realm", LakehouseStack.REALM)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("metadata");
    }

    private static long current(String table) throws Exception {
        return metadata(table).get("current-snapshot-id").asLong();
    }

    private static Instant committedAt(String table, long id) throws Exception {
        for (JsonNode snapshot : metadata(table).get("snapshots")) {
            if (snapshot.get("snapshot-id").asLong() == id) {
                return Instant.ofEpochMilli(snapshot.get("timestamp-ms").asLong());
            }
        }
        throw new AssertionError("No snapshot " + id);
    }

    private static List<List<String>> expected(TreeMap<Long, String> model) {
        List<List<String>> rows = new ArrayList<>();
        model.forEach((id, name) -> rows.add(List.of(String.valueOf(id), name == null ? "null" : name)));
        return rows;
    }

    /** Random inserts and deletes, each its own snapshot. Afterwards every snapshot must give the rows it had, by id and by instant. */
    @Test
    void everySnapshotGivesItsRowsOneByOne() throws Exception {
        // Not "history": Polaris answers 500 when creating a table with that name.
        String table = "change_log";
        exec("CREATE TABLE lh." + NS + "." + table + " (id BIGINT, name VARCHAR)");
        Random random = new Random(21);
        TreeMap<Long, String> model = new TreeMap<>();
        Map<Long, List<List<String>>> history = new LinkedHashMap<>();
        long nextId = 1;

        for (int round = 0; round < 8; round++) {
            if (round % 3 == 2) {
                List<Long> ids = new ArrayList<>(model.keySet());
                long gone = ids.get(random.nextInt(ids.size()));
                exec("DELETE FROM lh." + NS + "." + table + " WHERE id = " + gone);
                model.remove(gone);
            } else {
                List<String> values = new ArrayList<>();
                int count = 1 + random.nextInt(5);
                for (int i = 0; i < count; i++) {
                    long id = nextId++;
                    String name = random.nextInt(4) == 0 ? null : "name " + random.nextInt(1000) + "'s";
                    model.put(id, name);
                    values.add("(" + id + ", " + (name == null ? "NULL" : "'" + name.replace("'", "''") + "'") + ")");
                }
                exec("INSERT INTO lh." + NS + "." + table + " VALUES " + String.join(", ", values));
            }
            history.put(current(table), expected(model));
            Thread.sleep(3);
        }
        assertThat(history).hasSize(8);

        String sql = "SELECT id, name FROM at_snapshot ORDER BY id";
        for (Map.Entry<Long, List<List<String>>> entry : history.entrySet()) {
            JsonNode byId = answer("time_travel", past(table, entry.getKey(), sql));
            assertThat(rows(byId)).as("snapshot " + entry.getKey()).isEqualTo(entry.getValue());

            Map<String, Object> byInstant = new HashMap<>();
            byInstant.put("namespace", NS);
            byInstant.put("table", table);
            byInstant.put("sql", sql);
            byInstant.put("instant", committedAt(table, entry.getKey()).toString());
            JsonNode atInstant = answer("time_travel", byInstant);
            assertThat(atInstant.get("snapshotId").asLong()).isEqualTo(entry.getKey());
            assertThat(rows(atInstant)).isEqualTo(entry.getValue());
        }
        assertThat(rows(answer("run_query", Map.of("sql", "SELECT id, name FROM lh." + NS + "." + table + " ORDER BY id"))))
                .isEqualTo(expected(model));
    }

    /** The old schema after a column was dropped or renamed, which the developer's test (a column added) does not reach. */
    @Test
    void aSnapshotKeepsAColumnDroppedOrRenamedLater() throws Exception {
        String table = "schema_changes";
        exec("CREATE TABLE lh." + NS + "." + table + " (id BIGINT, old_name VARCHAR, gone VARCHAR)");
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (1, 'a', 'x')");
        long before = current(table);
        exec("ALTER TABLE lh." + NS + "." + table + " DROP COLUMN gone");
        exec("ALTER TABLE lh." + NS + "." + table + " RENAME COLUMN old_name TO new_name");
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (2, 'b')");
        long after = current(table);

        JsonNode old = answer("time_travel", past(table, before, "SELECT * FROM at_snapshot ORDER BY id"));
        assertThat(columns(old)).containsExactly("id", "old_name", "gone");
        assertThat(rows(old)).containsExactly(List.of("1", "a", "x"));

        JsonNode now = answer("time_travel", past(table, after, "SELECT * FROM at_snapshot ORDER BY id"));
        assertThat(columns(now)).containsExactly("id", "new_name");
        assertThat(rows(now)).containsExactly(List.of("1", "a"), List.of("2", "b"));
    }

    /** SQL an agent writes every day: it must work, and give what was worked out by hand. */
    @Test
    void ordinaryQueriesWorkOnTheView() throws Exception {
        String table = "ordinary";
        exec("CREATE TABLE lh." + NS + "." + table + " (id BIGINT, kind VARCHAR)");
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (1, 'a'), (2, 'a'), (3, 'b')");
        long id = current(table);
        exec("DELETE FROM lh." + NS + "." + table);

        assertThat(rows(answer("time_travel", past(table, id, "SELECT count(*) AS n FROM at_snapshot;"))))
                .containsExactly(List.of("3"));
        assertThat(rows(answer("time_travel", past(table, id, "SELECT kind, count(*) AS n FROM at_snapshot GROUP BY kind ORDER BY kind -- by kind"))))
                .containsExactly(List.of("a", "2"), List.of("b", "1"));
        assertThat(rows(answer("time_travel", past(table, id,
                "WITH a AS (SELECT id FROM at_snapshot WHERE kind = 'a') SELECT max(id) AS top FROM a"))))
                .containsExactly(List.of("2"));
        assertThat(rows(answer("time_travel", past(table, id,
                "SELECT x.id, y.id FROM at_snapshot x JOIN at_snapshot y ON x.id = y.id + 1 ORDER BY 1"))))
                .containsExactly(List.of("2", "1"), List.of("3", "2"));
        assertThat(rows(answer("time_travel", past(table, id, "SELECT * FROM AT_SNAPSHOT WHERE id = 3"))))
                .containsExactly(List.of("3", "b"));
        assertThat(rows(answer("run_query", Map.of("sql", "SELECT count(*) AS n FROM lh." + NS + "." + table))))
                .containsExactly(List.of("0"));
    }

    @Test
    void aNameThatNeedsQuotesWorks() throws Exception {
        String table = "odd name.v2";
        exec("CREATE TABLE lh." + NS + ".\"" + table + "\" (id BIGINT)");
        exec("INSERT INTO lh." + NS + ".\"" + table + "\" VALUES (7)");
        long id = current(table);

        assertThat(rows(answer("time_travel", past(table, id, "SELECT id FROM at_snapshot")))).containsExactly(List.of("7"));
    }

    @Test
    void theLimitsHoldAtTheCeiling() throws Exception {
        String table = "ceiling";
        exec("CREATE TABLE lh." + NS + "." + table + " (id BIGINT)");
        exec("INSERT INTO lh." + NS + "." + table + " SELECT i FROM range(1001) t(i)");
        long id = current(table);
        Map<String, Object> arguments = past(table, id, "SELECT id FROM at_snapshot ORDER BY id");

        JsonNode byDefault = answer("time_travel", arguments);
        assertThat(byDefault.get("rows").size()).isEqualTo(100);
        assertThat(byDefault.get("truncated").asBoolean()).isTrue();

        arguments.put("maxRows", 1000);
        JsonNode full = answer("time_travel", arguments);
        assertThat(full.get("rows").size()).isEqualTo(1000);
        assertThat(full.get("truncated").asBoolean()).isTrue();
        assertThat(full.get("rows").get(999).get(0).asLong()).isEqualTo(999);
    }

    /** The back doors: the view must not be a way to read another table or to run anything but one SELECT. */
    @ParameterizedTest
    @ValueSource(strings = {
            "WITH at_snapshot AS (SELECT * FROM lh.%s.secret) SELECT * FROM at_snapshot",
            "SELECT * FROM at_snapshot a, lh.%s.secret b",
            "SELECT * FROM at_snapshot WHERE id IN (SELECT id FROM lh.%s.secret)",
            "SELECT (SELECT max(id) FROM lh.%s.secret) FROM at_snapshot",
            "SELECT * FROM at_snapshot UNION ALL SELECT * FROM lh.%s.secret",
            "SELECT * FROM main.at_snapshot",
            "SELECT * FROM temp.at_snapshot",
            "SELECT * FROM iceberg_snapshots('lh.%s.secret')",
            "SELECT * FROM iceberg_scan('s3://warehouse/x')",
            "CREATE OR REPLACE TEMP VIEW at_snapshot AS SELECT 1",
            "ATTACH ':memory:' AS other",
            "COPY (SELECT * FROM at_snapshot) TO '/tmp/out.csv'",
            "INSERT INTO lh.%s.secret VALUES (2)",
            "PRAGMA database_list",
            "SET VARIABLE x = 1",
            "",
            "   ",
            "-- only a comment"})
    void noBackDoorThroughTheView(String template) throws Exception {
        exec("CREATE TABLE IF NOT EXISTS lh." + NS + ".secret (id BIGINT)");
        exec("INSERT INTO lh." + NS + ".secret VALUES (" + SECRET + ")");
        exec("CREATE TABLE IF NOT EXISTS lh." + NS + ".public_one (id BIGINT)");
        exec("INSERT INTO lh." + NS + ".public_one VALUES (1)");

        String message = error("time_travel", past("public_one", current("public_one"), template.formatted(NS)));

        assertThat(message).isNotBlank().doesNotContain("problem in the server").doesNotContain(String.valueOf(SECRET));
    }

    /** A value of the wrong type must come back as something the model can read, not a broken call. */
    @Test
    void argumentsOfTheWrongTypeAreReadableErrors() throws Exception {
        String table = "wrong_types";
        exec("CREATE TABLE lh." + NS + "." + table + " (id BIGINT)");
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (1)");
        long id = current(table);
        String sql = "SELECT id FROM at_snapshot";

        assertThat(error("time_travel", past(table, Long.MAX_VALUE, sql))).contains("No snapshot");
        assertThat(error("time_travel", past(table, -1, sql))).contains("No snapshot");
        assertThat(error("time_travel", past(table, "abc", sql))).isNotBlank();
        assertThat(error("time_travel", past(table, 1.5, sql))).isNotBlank();
        Map<String, Object> badLimit = past(table, id, sql);
        badLimit.put("maxRows", "many");
        assertThat(error("time_travel", badLimit)).isNotBlank();
        Map<String, Object> noSql = past(table, id, sql);
        noSql.remove("sql");
        assertThat(error("time_travel", noSql)).isNotBlank();
        assertThat(error("run_query", Map.of())).isNotBlank();
    }

    /** A failure inside DuckDB that the model cannot fix must not show paths of the store. */
    @Test
    void anErrorNeverShowsWhereTheFilesLive() throws Exception {
        String table = "paths";
        exec("CREATE TABLE lh." + NS + "." + table + " (id BIGINT, name VARCHAR)");
        exec("INSERT INTO lh." + NS + "." + table + " VALUES (1, 'x')");
        long id = current(table);

        for (String sql : List.of("SELECT nope FROM at_snapshot", "SELECT CAST(name AS INTEGER) FROM at_snapshot",
                "SELECT error('boom') FROM at_snapshot", "SELECT 1 / CAST(name AS BIGINT) FROM at_snapshot")) {
            assertThat(error("time_travel", past(table, id, sql))).doesNotContain("s3://").doesNotContain("warehouse")
                    .doesNotContain("Secret").doesNotContain(LakehouseStack.S3_SECRET_KEY);
        }
    }
}
