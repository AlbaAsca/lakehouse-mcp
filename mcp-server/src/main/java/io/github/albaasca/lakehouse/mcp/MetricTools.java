package io.github.albaasca.lakehouse.mcp;

import io.github.albaasca.lakehouse.semantic.Metric;
import io.github.albaasca.lakehouse.semantic.MetricCatalog;
import io.github.albaasca.lakehouse.semantic.MetricException;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * The business metrics of the semantic layer (metrics/*.yaml), so the model asks for one by name instead of writing its
 * own SQL. The SQL is ours, not the model's: only the dates and the limit come from the call, and travel as values.
 */
public class MetricTools {

    record Parameter(String name, String type, Object defaultValue) {}

    record Definition(String name, String description, String unit, List<Parameter> parameters) {

        static Definition of(Metric metric) {
            return new Definition(metric.name(), metric.description(), metric.unit(), metric.parameters().stream()
                    .map(p -> new Parameter(p.name(), p.type(), p.defaultValue())).toList());
        }
    }

    /** @param definition the words that say what the numbers mean; {@code arguments} are the ones that were applied */
    record MetricValue(Definition definition, Map<String, Object> arguments, List<String> columns,
            List<List<Object>> rows) {}

    private final LakehouseSession session;
    private final MetricCatalog catalog;
    private final String table;
    private final Masking masking;

    /** @param table the events table, fully named, such as lh.ecommerce.events */
    public MetricTools(LakehouseSession session, MetricCatalog catalog, String table) {
        this(session, catalog, table, new Masking(null));
    }

    public MetricTools(LakehouseSession session, MetricCatalog catalog, String table, Masking masking) {
        this.session = session;
        this.catalog = catalog;
        this.table = table;
        this.masking = masking;
    }

    /** The table the metric reads: the real one for the admin, the masked view for anyone else. */
    private String tableFor() throws java.sql.SQLException {
        if (Masking.isAdmin()) {
            return table;
        }
        String name = table.startsWith(LakehouseSession.CATALOG_ALIAS + ".")
                ? table.substring(LakehouseSession.CATALOG_ALIAS.length() + 1) : table;
        int dot = name.lastIndexOf('.');
        return masking.ensureView(session, name.substring(0, dot), name.substring(dot + 1));
    }

    @McpTool(name = "list_metrics", description = "Lists the business metrics get_metric can compute, each with what it "
            + "means, its unit and its parameters. Prefer them to writing SQL for the same question.",
            annotations = @McpAnnotations(title = "List metrics", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult listMetrics() {
        return ToolResults.of(() -> catalog.all().stream().map(Definition::of).toList());
    }

    @McpTool(name = "get_metric", description = "Computes one business metric by name over a range of days (list_metrics "
            + "says which exist and what each means). The answer has the definition, the arguments applied, columns and "
            + "rows. An unknown name is an error that lists the available ones.",
            annotations = @McpAnnotations(title = "Get a metric", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult getMetric(
            @McpToolParam(description = "Name of the metric, as list_metrics gives it", required = true) String name,
            @McpToolParam(description = "First day, inclusive, as 2026-03-01 (UTC); open if left out", required = false)
            String from,
            @McpToolParam(description = "Last day, inclusive, as 2026-03-31 (UTC); open if left out", required = false)
            String to,
            @McpToolParam(description = "Most entries of a ranking, for the metrics that have one (top_brands)",
                    required = false) Integer limit) {
        return ToolResults.of(() -> {
            try {
                Metric metric = catalog.get(name == null ? "" : name);
                Map<String, Object> arguments = new LinkedHashMap<>();
                if (from != null) {
                    arguments.put("from", from);
                }
                if (to != null) {
                    arguments.put("to", to);
                }
                if (limit != null) {
                    arguments.put("limit", limit.longValue());
                }
                // Bound once on the real name to check the arguments, and again on the view if the caller is not the admin.
                var checkedBound = metric.bind(table, arguments);
                var bound = Masking.isAdmin() ? checkedBound : metric.bind(tableFor(), arguments);
                SqlGuard.QueryResult result = session.withConnection(
                        reserved -> reserved.query(bound, SqlGuard.limitedTo(QueryTools.MAX_ROWS)));
                List<List<Object>> rows = result.rows().stream()
                        .map(row -> row.stream().map(QueryTools::cell).toList()).toList();
                return new MetricValue(Definition.of(metric), arguments, result.columns(), rows);
            } catch (MetricException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        });
    }
}
