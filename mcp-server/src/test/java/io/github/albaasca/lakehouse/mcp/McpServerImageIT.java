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
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The image of infra/mcp-server as compose.yaml runs it: same variables, the defaults for the rest, and its 1 GB of budget. */
class McpServerImageIT {

    private static final String NS = "mcp_image_it";
    private static final String TABLE = "events";
    private static final int PORT = 8090;
    private static final long MEMORY_BUDGET = 1024L * 1024 * 1024;
    /** What the root .dockerignore lets through: Testcontainers does not read it. */
    private static final List<String> CONTEXT = List.of("pom.xml", "producer/pom.xml", "mcp-server/pom.xml", "agent/pom.xml",
            "semantic/pom.xml", "evals/pom.xml","testkit/pom.xml", "maintenance/pom.xml", "mcp-server/src/main",
            "testkit/src/main", "infra/mcp-server");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static GenericContainer<?> server;
    private static McpSyncClient client;
    private static long loadedRows;

    private static ImageFromDockerfile image() {
        Path root = LakehouseStack.repositoryRoot();
        ImageFromDockerfile image = new ImageFromDockerfile("lakehouse-testkit-mcp-server", false)
                .withDockerfilePath("infra/mcp-server/Dockerfile");
        CONTEXT.forEach(path -> image.withFileFromPath(path, root.resolve(path)));
        return image;
    }

    @BeforeAll
    static void startTheImage() throws Exception {
        LakehouseStack stack = LakehouseStack.shared();
        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:")) {
            stack.attachDuckDb(writer);
            loadedRows = SyntheticLoader.load(writer, NS, TABLE);
        }
        server = new GenericContainer<>(image())
                .withNetwork(stack.network())
                .withEnv(Map.of(
                        "MCP_SERVER_ADDRESS", "0.0.0.0",
                        "POLARIS_CLIENT_ID", LakehouseStack.CLIENT_ID,
                        "POLARIS_CLIENT_SECRET", LakehouseStack.CLIENT_SECRET,
                        "S3_ACCESS_KEY", LakehouseStack.S3_ACCESS_KEY,
                        "S3_SECRET_KEY", LakehouseStack.S3_SECRET_KEY,
                        "S3_REGION", LakehouseStack.S3_REGION))
                .withExposedPorts(PORT)
                .withCreateContainerCmdModifier(command -> command.getHostConfig()
                        .withMemory(MEMORY_BUDGET)
                        .withMemorySwap(MEMORY_BUDGET))
                .waitingFor(Wait.forHealthcheck().withStartupTimeout(Duration.ofMinutes(3)));
        server.start();
        client = McpClient.sync(HttpClientStreamableHttpTransport.builder(baseUrl()).build()).build();
        client.initialize();
    }

    @AfterAll
    static void stopTheImage() {
        if (client != null) {
            client.closeGracefully();
        }
        if (server != null) {
            server.stop();
        }
    }

    private static String baseUrl() {
        return "http://" + server.getHost() + ":" + server.getMappedPort(PORT);
    }

    private static String run(String... command) throws Exception {
        ExecResult result = server.execInContainer(command);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        return result.getStdout();
    }

    @Test
    void itsOwnHealthcheckReportsHealthyAndActuatorAgrees() throws Exception {
        assertThat(server.isHealthy()).isTrue();

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl() + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).get("status").asString()).isEqualTo("UP");
    }

    @Test
    void aClientListsTheToolsThatQuery() {
        assertThat(client.listTools().tools()).extracting(Tool::name)
                .contains("list_tables", "describe_table", "run_query", "time_travel");
    }

    @Test
    void aClientQueriesTheTableThroughTheServicesOfTheNetwork() {
        CallToolResult result = client.callTool(new CallToolRequest("run_query",
                Map.of("sql", "SELECT count(*) AS n FROM lh." + NS + "." + TABLE)));

        String text = ((TextContent) result.content().getFirst()).text();
        assertThat(result.isError()).as(text).isNotEqualTo(Boolean.TRUE);
        JsonNode rows = JSON.readTree(text).get("rows");
        assertThat(rows.get(0).get(0).asLong()).isEqualTo(loadedRows);
    }

    /** The server refuses to start if they are missing (LakehouseSessionIT), so here only where they live is left to see. */
    @Test
    void theExtensionsComeFromTheImageAndTheServerAddsNone() throws Exception {
        String home = run("sh", "-c", "find \"$HOME\" -name '*.duckdb_extension'");
        String preloaded = run("find", "/opt/duckdb-extensions", "-name", "*.duckdb_extension", "-printf", "%f\n");

        assertThat(home).isBlank();
        assertThat(preloaded.lines()).containsExactlyInAnyOrder(
                "httpfs.duckdb_extension", "avro.duckdb_extension", "iceberg.duckdb_extension");
    }

    @Test
    void itRunsWithTheHeapOfTheBudgetAndNotAsRoot() throws Exception {
        assertThat(run("cat", "/proc/1/cmdline")).contains("-Xmx256m");
        assertThat(run("id", "-u").strip()).isNotEqualTo("0");
    }
}
