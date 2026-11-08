package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What McpServerIT leaves out: a server that is already running while the lakehouse changes, a history longer than one
 * answer, arguments written to break it, several clients at once and a start that must fail. The reference is the
 * catalog's own REST API.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerEdgeIT {

    private static final String NS = "mcp_edge_it";
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
            client = McpClient.sync(HttpClientStreamableHttpTransport.builder("http://localhost:" + port)
                    .requestBuilder(java.net.http.HttpRequest.newBuilder().header("Authorization", "Bearer analyst-it-key")).build()).build();
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

    private JsonNode describe(String table) {
        return answer("describe_table", Map.of("namespace", NS, "table", table));
    }

    private List<String> listed() {
        List<String> found = new ArrayList<>();
        answer("list_tables", Map.of()).get("tables")
                .forEach(table -> found.add(table.get("namespace").asString() + "." + table.get("table").asString()));
        return found;
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

    @Test
    void aTableCreatedOrDroppedWhileRunningIsSeenAtOnce() throws Exception {
        String name = NS + ".comes_and_goes";
        answer("list_tables", Map.of());

        exec("CREATE TABLE lh." + name + " (id BIGINT)");
        assertThat(listed()).contains(name);

        exec("DROP TABLE lh." + name);
        assertThat(listed()).doesNotContain(name);
        CallToolResult gone = call("describe_table", Map.of("namespace", NS, "table", "comes_and_goes"));
        assertThat(gone.isError()).isTrue();
        assertThat(text(gone)).isEqualTo("No table lh." + name);
    }

    @Test
    void aCommitWhileRunningIsSeenAtOnce() throws Exception {
        exec("CREATE TABLE lh." + NS + ".grows (id BIGINT)");
        exec("INSERT INTO lh." + NS + ".grows VALUES (1)");
        assertThat(describe("grows").get("snapshotCount").asInt()).isEqualTo(catalogMetadata("grows").get("snapshots").size());
        int before = describe("grows").get("snapshotCount").asInt();

        exec("INSERT INTO lh." + NS + ".grows VALUES (2)");

        JsonNode details = describe("grows");
        JsonNode metadata = catalogMetadata("grows");
        assertThat(details.get("snapshotCount").asInt()).isEqualTo(before + 1);
        assertThat(details.get("snapshots").get(before).get("snapshotId").asLong())
                .isEqualTo(metadata.get("current-snapshot-id").asLong());
        assertThat(details.get("snapshots").get(before).get("summary").get("total-records").asLong()).isEqualTo(2L);
    }

    /** Element by element against the catalog, with the chain of parents that the short history of McpServerIT cannot show. */
    @Test
    void aLongHistoryGivesExactlyTheLastSnapshotsOfTheCatalog() throws Exception {
        String table = "long_history";
        exec("CREATE TABLE lh." + NS + "." + table + " (id BIGINT)");
        for (int i = 0; i < CatalogTools.MAX_SNAPSHOTS + 3; i++) {
            exec("INSERT INTO lh." + NS + "." + table + " VALUES (" + i + ")");
        }

        JsonNode details = describe(table);

        List<JsonNode> expected = new ArrayList<>();
        catalogMetadata(table).get("snapshots").forEach(expected::add);
        expected.sort(Comparator.comparingLong(snapshot -> snapshot.get("sequence-number").asLong()));
        assertThat(expected).hasSizeGreaterThanOrEqualTo(CatalogTools.MAX_SNAPSHOTS + 3);
        assertThat(details.get("snapshotCount").asInt()).isEqualTo(expected.size());
        List<JsonNode> window = expected.subList(expected.size() - CatalogTools.MAX_SNAPSHOTS, expected.size());
        assertThat(details.get("snapshots").size()).isEqualTo(window.size());
        for (int i = 0; i < window.size(); i++) {
            JsonNode want = window.get(i);
            JsonNode got = details.get("snapshots").get(i);
            assertThat(got.get("snapshotId").asLong()).isEqualTo(want.get("snapshot-id").asLong());
            assertThat(got.get("parentId").asLong()).isEqualTo(want.get("parent-snapshot-id").asLong());
            assertThat(got.get("sequenceNumber").asLong()).isEqualTo(want.get("sequence-number").asLong());
            assertThat(Instant.parse(got.get("committedAt").asString()))
                    .isEqualTo(Instant.ofEpochMilli(want.get("timestamp-ms").asLong()));
            assertThat(got.get("summary").get("total-records").asString())
                    .isEqualTo(want.get("summary").get("total-records").asString());
        }
    }

    @Test
    void aTableWithoutDataOrPartitionsAnswersEmptyLists() throws Exception {
        exec("CREATE TABLE lh." + NS + ".empty_one (id BIGINT, name VARCHAR)");

        JsonNode details = describe("empty_one");

        assertThat(details.get("columns").size()).isEqualTo(2);
        assertThat(details.get("partitions").isArray()).isTrue();
        assertThat(details.get("partitions").size()).isZero();
        assertThat(details.get("snapshotCount").asInt()).isZero();
        assertThat(details.get("snapshots").isArray()).isTrue();
        assertThat(details.get("snapshots").size()).isZero();
    }

    @Test
    void argumentsWrittenToBreakOutOfTheQueryAreJustNamesThatDoNotExist() throws Exception {
        exec("CREATE TABLE lh." + NS + ".survivor (id BIGINT)");
        for (String hostile : List.of("x' OR '1'='1", "survivor\"; DROP TABLE lh." + NS + ".survivor; --", "..", "", "%", "_urvivor",
                "SURVIVOR", "ñandú 表")) {
            CallToolResult asTable = call("describe_table", Map.of("namespace", NS, "table", hostile));
            assertThat(asTable.isError()).as(hostile).isTrue();
            assertThat(text(asTable)).isEqualTo("No table lh." + NS + "." + hostile);

            CallToolResult asNamespace = call("describe_table", Map.of("namespace", hostile, "table", "survivor"));
            assertThat(asNamespace.isError()).as(hostile).isTrue();
            assertThat(text(asNamespace)).isEqualTo("No table lh." + hostile + ".survivor");
        }
        assertThat(listed()).contains(NS + ".survivor");
    }

    @Test
    void argumentsOfTheWrongShapeAreErrorsTheModelCanRead() {
        Map<String, Object> nullTable = new HashMap<>();
        nullTable.put("namespace", NS);
        nullTable.put("table", null);
        for (Map<String, Object> wrong : List.of(Map.<String, Object>of("namespace", NS, "table", 123),
                Map.<String, Object>of("namespace", List.of(NS), "table", "t"), nullTable, Map.<String, Object>of())) {
            CallToolResult result = call("describe_table", wrong);
            assertThat(result.isError()).as(wrong.toString()).isTrue();
            assertThat(text(result)).as(wrong.toString()).isNotBlank();
        }
    }

    @Test
    void severalCallsAtOnceAllGetTheSameRightAnswer() throws Exception {
        exec("CREATE TABLE lh." + NS + ".busy (id BIGINT, kind VARCHAR) PARTITIONED BY (kind)");
        exec("INSERT INTO lh." + NS + ".busy VALUES (1, 'a')");
        String expected = text(call("describe_table", Map.of("namespace", NS, "table", "busy")));
        McpSyncClient shared = client();

        // More calls than the pool has connections.
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Future<CallToolResult>> calls = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                calls.add(pool.submit(() -> shared.callTool(
                        new CallToolRequest("describe_table", Map.of("namespace", NS, "table", "busy")))));
            }
            for (Future<CallToolResult> call : calls) {
                CallToolResult result = call.get();
                assertThat(result.isError()).as(text(result)).isNotEqualTo(Boolean.TRUE);
                assertThat(text(result)).isEqualTo(expected);
            }
        }
    }

    @Test
    void withoutTheCatalogTheServerDoesNotStart() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(McpServerApplication.class).run(
                "--spring.main.banner-mode=off", "--server.port=0",
                "--lakehouse.catalog.uri=http://127.0.0.1:1",
                "--lakehouse.catalog.client-id=x", "--lakehouse.catalog.client-secret=x",
                "--lakehouse.storage.access-key=x", "--lakehouse.storage.secret-key=x", "--lakehouse.storage.region=x"))
                .hasMessageContaining("lakehouseSession");
    }

    /** The tools container defines these variables, so the environment of these starts must not see the machine's. */
    private static StandardEnvironment noVariables() {
        return new StandardEnvironment() {
            @Override
            protected void customizePropertySources(MutablePropertySources sources) {}
        };
    }

    @Test
    void withoutCredentialsTheServerDoesNotStartAndSaysWhichOneIsMissing() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(McpServerApplication.class).environment(noVariables())
                .run("--spring.main.banner-mode=off", "--server.port=0"))
                .hasStackTraceContaining("POLARIS_CLIENT_ID");
    }

    @Test
    void withoutStorageCredentialsTheServerDoesNotStartAndSaysWhichOneIsMissing() throws Exception {
        exec("CREATE TABLE lh." + NS + ".no_keys (id BIGINT, kind VARCHAR) PARTITIONED BY (kind)");
        exec("INSERT INTO lh." + NS + ".no_keys VALUES (1, 'a')");
        LakehouseStack stack = LakehouseStack.shared();

        Throwable failure = null;
        try (ConfigurableApplicationContext started = new SpringApplicationBuilder(McpServerApplication.class)
                .environment(noVariables())
                .run("--spring.main.banner-mode=off", "--server.port=0",
                        "--lakehouse.catalog.name=" + LakehouseStack.CATALOG,
                        "--lakehouse.catalog.uri=" + stack.polarisUrl(),
                        "--lakehouse.catalog.client-id=" + LakehouseStack.CLIENT_ID,
                        "--lakehouse.catalog.client-secret=" + LakehouseStack.CLIENT_SECRET,
                        "--lakehouse.storage.endpoint=" + stack.s3Endpoint())) {
            CatalogTools tools = started.getBean(CatalogTools.class);
            fail("It started without storage credentials. list_tables: " + text(tools.listTables())
                    + " describe_table: " + text(tools.describeTable(NS, "no_keys")));
        } catch (RuntimeException e) {
            failure = e;
        }
        assertThat(failure).hasStackTraceContaining("S3_ACCESS_KEY");
    }
}
