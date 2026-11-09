package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T-054 against the whole server, with a real client per role. The reference is what the test wrote: the user ids it
 * inserted. Every call is made more than once so that it lands on different connections of the pool.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MaskingIT {

    private static final String NS = "masking_it";
    private static final List<Long> USERS = List.of(1_000_000_001L, 1_000_000_002L, 1_000_000_003L);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static McpSyncClient analyst;
    private static McpSyncClient admin;
    private static long firstSnapshot;

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
        registry.add("lakehouse.security.analyst-key", () -> "analyst-mask-key");
        registry.add("lakehouse.security.admin-key", () -> "admin-mask-key");
        registry.add("lakehouse.masking.salt", () -> "it-salt-9f3c");
        registry.add("lakehouse.pipeline.namespace", () -> NS);
        registry.add("lakehouse.pipeline.table", () -> "events");
    }

    @BeforeAll
    static void writeTheTable() throws Exception {
        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:"); Statement s = writer.createStatement()) {
            LakehouseStack.shared().attachDuckDb(writer);
            s.execute("CREATE SCHEMA IF NOT EXISTS lh." + NS);
            s.execute("CREATE TABLE lh." + NS + ".events (event_type VARCHAR, user_id BIGINT, price DOUBLE, "
                    + "user_session VARCHAR, event_time TIMESTAMP, product_id BIGINT, brand VARCHAR)");
            s.execute("INSERT INTO lh." + NS + ".events VALUES ('view', 1000000001, 1.0, 's1', TIMESTAMP '2026-03-01 10:00:00', 1, 'a'), "
                    + "('purchase', 1000000001, 2.0, 's1', TIMESTAMP '2026-03-01 10:05:00', 1, 'a'), "
                    + "('view', 1000000002, 3.0, 's2', TIMESTAMP '2026-03-01 11:00:00', 2, 'b')");
            firstSnapshot = currentSnapshot();
            s.execute("INSERT INTO lh." + NS + ".events VALUES ('view', 1000000003, 4.0, 's3', TIMESTAMP '2026-03-01 12:00:00', 3, 'c')");
        }
    }

    /** The catalog's own REST API, as QueryToolsIT does: not the code under test. */
    private static long currentSnapshot() throws Exception {
        LakehouseStack stack = LakehouseStack.shared();
        java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(stack.polarisUrl()
                        + "/api/catalog/v1/lakehouse/namespaces/" + NS + "/tables/events"))
                        .header("Authorization", "Bearer " + stack.polarisToken())
                        .header("Polaris-Realm", LakehouseStack.REALM).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("metadata").get("current-snapshot-id").asLong();
    }

    @AfterAll
    static void close() {
        for (McpSyncClient client : new McpSyncClient[] {analyst, admin}) {
            if (client != null) {
                client.closeGracefully();
            }
        }
    }

    private McpSyncClient client(String key) {
        McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                .requestBuilder(java.net.http.HttpRequest.newBuilder().header("Authorization", "Bearer " + key)).build())
                .build();
        client.initialize();
        return client;
    }

    private McpSyncClient analyst() {
        if (analyst == null) {
            analyst = client("analyst-mask-key");
        }
        return analyst;
    }

    private McpSyncClient admin() {
        if (admin == null) {
            admin = client("admin-mask-key");
        }
        return admin;
    }

    private static CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(new CallToolRequest(tool, arguments));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    private static JsonNode ok(CallToolResult result) {
        assertThat(result.isError()).as(text(result)).isNotEqualTo(Boolean.TRUE);
        return JSON.readTree(text(result));
    }

    private static List<String> column(JsonNode answer, int index) {
        List<String> values = new ArrayList<>();
        answer.get("rows").forEach(row -> values.add(row.get(index).asString()));
        return values;
    }

    private static final String VIEW = "memory.main." + NS + "__events";

    @Test
    void distinctUsersAgreeInBothRolesAndTheAnalystNeverSeesARealId() {
        for (int i = 0; i < 8; i++) { // more calls than connections in the pool
            JsonNode real = ok(call(admin(), "run_query",
                    Map.of("sql", "SELECT count(DISTINCT user_id) FROM lh." + NS + ".events")));
            JsonNode masked = ok(call(analyst(), "run_query",
                    Map.of("sql", "SELECT count(DISTINCT user_id) FROM " + VIEW)));
            assertThat(column(real, 0)).containsExactly("3");
            assertThat(column(masked, 0)).isEqualTo(column(real, 0));

            JsonNode ids = ok(call(analyst(), "run_query", Map.of("sql", "SELECT user_id FROM " + VIEW)));
            Set<String> real3 = new HashSet<>(USERS.stream().map(String::valueOf).toList());
            assertThat(column(ids, 0)).hasSize(4).doesNotContainAnyElementsOf(real3);
        }
        JsonNode adminIds = ok(call(admin(), "run_query", Map.of("sql", "SELECT DISTINCT user_id FROM lh." + NS + ".events")));
        assertThat(column(adminIds, 0)).containsExactlyInAnyOrderElementsOf(USERS.stream().map(String::valueOf).toList());
    }

    @Test
    void theAnalystCannotReachTheBaseTableNorTheSalt() {
        for (String sql : List.of("SELECT * FROM lh." + NS + ".events", "SELECT * FROM " + NS + ".events",
                "SELECT sql FROM duckdb_views()", "SELECT * FROM duckdb_views")) {
            CallToolResult result = call(analyst(), "run_query", Map.of("sql", sql));
            assertThat(result.isError()).as(sql).isTrue();
            assertThat(text(result)).as(sql).doesNotContain("it-salt-9f3c");
        }
    }

    @Test
    void timeTravelGivesTheAnalystTheSameHashAndTheAdminTheRealId() {
        Map<String, Object> args = Map.of("namespace", NS, "table", "events", "snapshotId", firstSnapshot,
                "sql", "SELECT DISTINCT user_id FROM at_snapshot");
        JsonNode adminAnswer = ok(call(admin(), "time_travel", args));
        assertThat(column(adminAnswer, 0)).containsExactlyInAnyOrder("1000000001", "1000000002");
        for (int i = 0; i < 8; i++) {
            JsonNode masked = ok(call(analyst(), "time_travel", args));
            assertThat(column(masked, 0)).hasSize(2).doesNotContain("1000000001", "1000000002");
        }
        // The hash is the same one the current view gives.
        JsonNode now = ok(call(analyst(), "run_query", Map.of("sql", "SELECT DISTINCT user_id FROM " + VIEW)));
        assertThat(column(now, 0)).containsAll(column(ok(call(analyst(), "time_travel", args)), 0));
    }

    @Test
    void getMetricStillWorksForTheAnalyst() {
        JsonNode answer = ok(call(analyst(), "get_metric", Map.of("name", "average_ticket")));
        assertThat(answer.get("rows")).isNotEmpty();
    }
}
