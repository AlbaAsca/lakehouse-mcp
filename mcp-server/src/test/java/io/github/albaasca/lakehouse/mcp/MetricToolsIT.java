package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.albaasca.lakehouse.mcp.LakehouseSession.Catalog;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Storage;
import io.github.albaasca.lakehouse.semantic.MetricCatalog;
import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import io.github.albaasca.lakehouse.testkit.SyntheticLoader;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** get_metric on the sample, against the independent reference evals/truth/metrics.json (total and one day). */
class MetricToolsIT {

    private static final String NS = "metric_tools_it";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static LakehouseSession session;
    private static MetricTools tools;
    private static JsonNode truth;

    @BeforeAll
    static void load() throws Exception {
        LakehouseStack stack = LakehouseStack.shared();
        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:")) {
            stack.attachDuckDb(writer);
            SyntheticLoader.load(writer, NS, "events");
        }
        session = new LakehouseSession(LakehouseSession.Settings.of(
                new Catalog(LakehouseStack.CATALOG, stack.polarisUrl(), LakehouseStack.CLIENT_ID, LakehouseStack.CLIENT_SECRET),
                new Storage(stack.s3Endpoint(), LakehouseStack.S3_ACCESS_KEY, LakehouseStack.S3_SECRET_KEY, LakehouseStack.S3_REGION)));
        tools = new MetricTools(session, MetricCatalog.load(), "lh." + NS + ".events");
        truth = JSON.readTree(Files.readString(LakehouseStack.repositoryRoot().resolve("evals/truth/metrics.json")));
    }

    @AfterAll
    static void close() throws Exception {
        if (session != null) {
            session.close();
        }
    }

    private static JsonNode answer(CallToolResult result) throws Exception {
        assertThat(result.isError()).isNotEqualTo(true);
        return JSON.readTree(((TextContent) result.content().getFirst()).text());
    }

    private static double value(JsonNode answer, int row, String column) {
        int index = answer.get("columns").valueStream().map(JsonNode::asString).toList().indexOf(column);
        assertThat(index).as(column).isNotNegative();
        return answer.get("rows").get(row).get(index).asDouble();
    }

    @Test
    void everyMetricOverTheWholeSampleMatchesTheReference() throws Exception {
        JsonNode total = truth.get("total");

        JsonNode funnel = answer(tools.getMetric("conversion_funnel", null, null, null));
        assertThat(funnel.get("definition").get("description").asString()).isNotBlank();
        for (String key : new String[] {"view_sessions", "cart_sessions", "purchase_sessions"}) {
            assertThat(value(funnel, 0, key)).as(key).isEqualTo(total.get("funnel").get(key).asDouble());
        }
        assertThat(value(funnel, 0, "view_to_purchase")).isCloseTo(total.get("funnel").get("view_to_purchase").asDouble(),
                within(1e-6));

        JsonNode abandonment = answer(tools.getMetric("cart_abandonment", null, null, null));
        assertThat(value(abandonment, 0, "abandonment_rate")).isCloseTo(
                total.get("cart_abandonment").get("abandonment_rate").asDouble(), within(1e-6));

        JsonNode ticket = answer(tools.getMetric("average_ticket", null, null, null));
        assertThat(value(ticket, 0, "orders")).isEqualTo(total.get("average_ticket").get("orders").asDouble());
        assertThat(value(ticket, 0, "average_ticket")).isCloseTo(total.get("average_ticket").get("average_ticket").asDouble(),
                within(0.01));

        JsonNode brands = answer(tools.getMetric("top_brands", null, null, 3));
        assertThat(brands.get("rows")).hasSize(4);
        assertThat(brands.get("rows").get(0).get(0).asString())
                .isEqualTo(total.get("top_brands").get("brands").get(0).get("brand").asString());
        assertThat(brands.get("arguments").get("limit").asInt()).isEqualTo(3);
    }

    @Test
    void aDayMatchesTheReference() throws Exception {
        var day = truth.get("by_day").properties().iterator().next();

        JsonNode ticket = answer(tools.getMetric("average_ticket", day.getKey(), day.getKey(), null));

        assertThat(value(ticket, 0, "orders")).isEqualTo(day.getValue().get("average_ticket").get("orders").asDouble());
        assertThat(ticket.get("arguments").get("from").asString()).isEqualTo(day.getKey());
    }

    @Test
    void anUnknownMetricListsTheAvailableOnes() {
        CallToolResult result = tools.getMetric("nope", null, null, null);

        assertThat(result.isError()).isTrue();
        assertThat(((TextContent) result.content().getFirst()).text()).contains("top_brands", "average_ticket");
    }
}
