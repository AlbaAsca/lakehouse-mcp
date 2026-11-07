package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import io.github.albaasca.lakehouse.testkit.SyntheticLoader;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
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

/** A real MCP client over HTTP against the whole application; the reference is the catalog's own REST API. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpServerIT {

    private static final String NS = "mcp_it";
    private static final String TABLE = "events";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static long loadedRows;
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
    static void loadTheTable() throws Exception {
        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:")) {
            LakehouseStack.shared().attachDuckDb(writer);
            loadedRows = SyntheticLoader.load(writer, NS, TABLE);
        }
    }

    @AfterAll
    static void closeTheClient() {
        if (client != null) {
            client.closeGracefully();
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

    private static Set<String> fields(JsonNode node) {
        return new HashSet<>(node.propertyNames());
    }

    private static JsonNode catalogMetadata() throws Exception {
        LakehouseStack stack = LakehouseStack.shared();
        HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                stack.polarisUrl() + "/api/catalog/v1/lakehouse/namespaces/" + NS + "/tables/" + TABLE))
                .header("Authorization", "Bearer " + stack.polarisToken())
                .header("Polaris-Realm", LakehouseStack.REALM)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body()).get("metadata");
    }

    @Test
    void theClientSeesTheToolsWithTheirArgumentsAndAsReadOnly() {
        List<Tool> tools = client().listTools().tools();

        assertThat(client().getServerCapabilities().tools()).isNotNull();
        assertThat(client().getServerCapabilities().prompts()).isNull();
        assertThat(client().getServerCapabilities().resources()).isNull();
        assertThat(client().getServerCapabilities().completions()).isNull();
        assertThat(tools).extracting(Tool::name)
                .containsExactlyInAnyOrder("list_tables", "describe_table", "run_query", "time_travel", "pipeline_health");
        assertThat(tools).allSatisfy(tool -> {
            assertThat(tool.description()).isNotBlank();
            assertThat(tool.annotations().readOnlyHint()).isTrue();
        });
        Tool describe = tools.stream().filter(tool -> tool.name().equals("describe_table")).findFirst().orElseThrow();
        JsonNode schema = JSON.valueToTree(describe.inputSchema());
        assertThat(fields(schema.get("properties"))).containsExactlyInAnyOrder("namespace", "table");
        assertThat(schema.get("required").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("namespace", "table");
    }

    @Test
    void listTablesFindsTheTable() {
        JsonNode tables = answer("list_tables", Map.of()).get("tables");

        List<String> found = new ArrayList<>();
        tables.forEach(table -> {
            assertThat(fields(table)).containsExactlyInAnyOrder("namespace", "table");
            found.add(table.get("namespace").asString() + "." + table.get("table").asString());
        });
        assertThat(found).contains(NS + "." + TABLE);
    }

    @Test
    void describeTableMatchesTheCatalogElementByElement() throws Exception {
        JsonNode details = answer("describe_table", Map.of("namespace", NS, "table", TABLE));
        JsonNode metadata = catalogMetadata();

        assertThat(fields(details)).containsExactlyInAnyOrder("table", "columns", "partitions", "snapshotCount", "snapshots");
        assertThat(details.get("table").get("namespace").asString()).isEqualTo(NS);
        assertThat(details.get("table").get("table").asString()).isEqualTo(TABLE);

        // Column by column, against what the sink's table has to look like (SyntheticLoader) and the catalog's order.
        List<String> columns = new ArrayList<>();
        details.get("columns").forEach(column -> columns.add(column.get("name").asString() + " " + column.get("type").asString()));
        assertThat(columns).containsExactly("event_time TIMESTAMP WITH TIME ZONE", "event_type VARCHAR", "product_id BIGINT",
                "category_id BIGINT", "category_code VARCHAR", "brand VARCHAR", "price DOUBLE", "user_id BIGINT",
                "user_session VARCHAR");
        List<String> catalogColumns = new ArrayList<>();
        metadata.get("schemas").get(0).get("fields").forEach(field -> catalogColumns.add(field.get("name").asString()));
        assertThat(details.get("columns").valueStream().map(column -> column.get("name").asString()).toList())
                .isEqualTo(catalogColumns);

        List<String> partitions = new ArrayList<>();
        details.get("partitions").forEach(partition -> partitions.add(
                partition.get("transform").asString() + "(" + partition.get("sourceColumn").asString() + ")"));
        assertThat(partitions).containsExactly("day(event_time)", "identity(event_type)");

        JsonNode catalogSnapshots = metadata.get("snapshots");
        assertThat(catalogSnapshots.size()).isPositive();
        assertThat(details.get("snapshotCount").asInt()).isEqualTo(catalogSnapshots.size());
        assertThat(details.get("snapshots").size()).isEqualTo(catalogSnapshots.size());
        for (int i = 0; i < catalogSnapshots.size(); i++) {
            JsonNode expected = catalogSnapshots.get(i);
            JsonNode snapshot = details.get("snapshots").get(i);
            assertThat(fields(snapshot)).containsExactlyInAnyOrder("sequenceNumber", "snapshotId", "parentId", "committedAt",
                    "operation", "summary");
            assertThat(snapshot.get("snapshotId").asLong()).isEqualTo(expected.get("snapshot-id").asLong());
            assertThat(snapshot.get("sequenceNumber").asLong()).isEqualTo(expected.get("sequence-number").asLong());
            assertThat(Instant.parse(snapshot.get("committedAt").asString()))
                    .isEqualTo(Instant.ofEpochMilli(expected.get("timestamp-ms").asLong()));
            assertThat(snapshot.get("operation").asString()).isEqualTo(expected.get("summary").get("operation").asString());
        }
        JsonNode last = details.get("snapshots").get(catalogSnapshots.size() - 1);
        assertThat(last.get("summary").get("total-records").asLong()).isEqualTo(loadedRows);
        assertThat(last.get("snapshotId").asLong()).isEqualTo(metadata.get("current-snapshot-id").asLong());
    }

    @Test
    void aMissingTableIsAnErrorTheModelCanRead() {
        CallToolResult result = call("describe_table", Map.of("namespace", NS, "table", "missing"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("No table lh." + NS + ".missing");
    }

    @Test
    void aMissingArgumentIsAnErrorTheModelCanRead() {
        CallToolResult result = call("describe_table", Map.of("namespace", NS));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("required property 'table'");
    }
}
