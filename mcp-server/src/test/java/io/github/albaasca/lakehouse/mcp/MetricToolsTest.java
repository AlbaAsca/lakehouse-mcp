package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.albaasca.lakehouse.semantic.MetricCatalog;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;

/** What does not need the lakehouse: the listing and the mistakes in the call (the session is never touched). */
class MetricToolsTest {

    private final MetricTools tools = new MetricTools(null, MetricCatalog.load(), "lh.ecommerce.events");

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().getFirst()).text();
    }

    @Test
    void listsTheFourMetricsWithTheirDefinition() {
        CallToolResult result = tools.listMetrics();

        assertThat(result.isError()).isNotEqualTo(true);
        assertThat(text(result)).contains("conversion_funnel", "cart_abandonment", "average_ticket", "top_brands",
                "description", "parameters");
    }

    @Test
    void anUnknownMetricAnswersWithTheAvailableOnes() {
        CallToolResult result = tools.getMetric("nope", null, null, null);

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("Unknown metric 'nope'", "average_ticket", "cart_abandonment",
                "conversion_funnel", "top_brands");
    }

    @Test
    void badArgumentsAreAnErrorTheModelCanFix() {
        CallToolResult badDate = tools.getMetric("average_ticket", "yesterday", null, null);
        CallToolResult noLimit = tools.getMetric("average_ticket", null, null, 3);
        CallToolResult zero = tools.getMetric("top_brands", null, null, 0);

        assertThat(badDate.isError()).isTrue();
        assertThat(text(badDate)).contains("from");
        assertThat(noLimit.isError()).isTrue();
        assertThat(text(noLimit)).contains("no parameter 'limit'");
        assertThat(zero.isError()).isTrue();
    }
}
