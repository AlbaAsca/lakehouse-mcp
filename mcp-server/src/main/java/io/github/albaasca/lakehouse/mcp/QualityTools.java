package io.github.albaasca.lakehouse.mcp;

import io.github.albaasca.lakehouse.mcp.CatalogReader.Column;
import io.github.albaasca.lakehouse.mcp.DataQuality.DayCounts;
import io.github.albaasca.lakehouse.mcp.DataQuality.PriceCounts;
import io.github.albaasca.lakehouse.mcp.DataQuality.PriceRule;
import io.github.albaasca.lakehouse.mcp.DataQuality.Report;
import io.github.albaasca.lakehouse.mcp.DataQuality.SchemaDrift;
import io.github.albaasca.lakehouse.mcp.DataQuality.Settings;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * data_quality_check: nulls, exact duplicates, prices out of range, low-volume days and empty partitions of a table of
 * events, plus schema drift against Schema Registry. The SQL is the server's own, on the {@code READ_ONLY} instance; the
 * names in it come from the catalog and are quoted. The verdict is {@link DataQuality}'s.
 */
public class QualityTools {

    private static final Logger LOG = LoggerFactory.getLogger(QualityTools.class);

    static final String TIME = "event_time";
    static final String TYPE = "event_type";
    static final String PRICE = "price";
    /** The volume key when the table has no event_type column. */
    static final String ALL_TYPES = "(all)";

    /**
     * @param namespace the table fed by {@code subject}; schema drift is only checked for it
     * @param registry null when no Schema Registry is configured
     */
    record Drift(String namespace, String table, String subject, SchemaRegistryFields.Source registry) {}

    private final LakehouseSession session;
    private final CatalogReader reader;
    private final Settings settings;
    private final Drift drift;

    QualityTools(LakehouseSession session, CatalogReader reader, Settings settings, Drift drift) {
        this.session = session;
        this.reader = reader;
        this.settings = settings;
        this.drift = drift;
    }

    @McpTool(name = "data_quality_check", description = "Checks the data of a table of events, by UTC day of event_time. "
            + "status is OK or ISSUES and issues says why. totals and days give, for every day or only the day asked for: "
            + "rows, rowsByEventType, nulls per column, duplicateCopies (extra copies of rows identical in every column), "
            + "priceZero, priceNegative, priceOutOfRange (price <= 0) and priceHighOutliers (see priceOutliers). A day with "
            + "no rows is an empty partition. volume.drops lists the event types of a day with fewer rows than minRatio of "
            + "the median of the days before. informationalNulls are columns the source leaves empty by design: counted, "
            + "not an issue. schemaDrift compares the table's columns with the schema in Schema Registry.",
            annotations = @McpAnnotations(title = "Data quality check", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult dataQualityCheck(
            @McpToolParam(description = "Namespace of the table, as list_tables gives it", required = true) String namespace,
            @McpToolParam(description = "Name of the table, as list_tables gives it", required = true) String table,
            @McpToolParam(description = "Only this UTC day, such as 2024-03-10; every day if left out. The days before it "
                    + "still make the volume median", required = false) String day,
            @McpToolParam(description = "Share of the median below which a day is a drop, above 0 and at most 1; "
                    + "the server's setting if left out", required = false) Double minVolumeRatio) {
        return ToolResults.of(() -> check(namespace, table, day, minVolumeRatio));
    }

    Report check(String namespace, String table, String day, Double minVolumeRatio) throws SQLException {
        LocalDate date = day == null || day.isBlank() ? null : day(day);
        Settings used = minVolumeRatio == null ? settings : settings.withMinVolumeRatio(minVolumeRatio);
        List<String> columns = reader.describe(namespace, table).columns().stream().map(Column::name).toList();
        if (!columns.contains(TIME)) {
            throw new IllegalArgumentException("data_quality_check needs a column " + TIME + " to tell the days apart; "
                    + CatalogReader.qualifiedName(namespace, table) + " has " + columns);
        }
        String name = CatalogReader.qualifiedName(namespace, table);
        PriceRule priceRule = columns.contains(PRICE) ? new PriceRule(PriceRule.RULE, upperFence(name)) : null;

        Map<Long, DayCounts> counts = dayCounts(name, columns, priceRule);
        Map<Long, Map<String, Long>> types = columns.contains(TYPE) ? rowsByType(name) : Map.of();
        Map<Long, Long> duplicates = duplicates(name, columns);

        List<DayCounts> dated = new ArrayList<>();
        DayCounts undated = null;
        for (Map.Entry<Long, DayCounts> entry : counts.entrySet()) {
            Long key = entry.getKey();
            DayCounts c = entry.getValue();
            Map<String, Long> byType = columns.contains(TYPE) ? types.getOrDefault(key, Map.of()) : Map.of(ALL_TYPES, c.rows());
            DayCounts full = new DayCounts(key == null ? null : LocalDate.ofEpochDay(key).toString(), c.rows(), byType,
                    c.nulls(), duplicates.getOrDefault(key, 0L), c.price());
            if (key == null) {
                undated = full;
            } else {
                dated.add(full);
            }
        }
        return DataQuality.build(LakehouseSession.CATALOG_ALIAS + "." + namespace + "." + table, date, dated, undated,
                columns, priceRule, drift(namespace, table, columns), used);
    }

    static LocalDate day(String written) {
        try {
            return LocalDate.parse(written.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("day must be a date such as 2024-03-10: " + written);
        }
    }

    /** epoch_ms and not a cast to DATE: a cast would use the zone of the session, and the day would move with the machine. */
    private static String dayOf() {
        return "epoch_ms(" + CatalogReader.identifier(TIME) + ") // 86400000";
    }

    private Double upperFence(String name) throws SQLException {
        String price = CatalogReader.identifier(PRICE);
        return session.query("SELECT quantile_cont(" + price + ", 0.25), quantile_cont(" + price + ", 0.75) FROM " + name
                + " WHERE " + price + " > 0", rows -> {
                    rows.next();
                    Number q1 = (Number) rows.getObject(1);
                    Number q3 = (Number) rows.getObject(2);
                    return q1 == null || q3 == null ? null
                            : q3.doubleValue() + 3 * (q3.doubleValue() - q1.doubleValue());
                });
    }

    private Map<Long, DayCounts> dayCounts(String name, List<String> columns, PriceRule priceRule) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT " + dayOf() + ", count(*)");
        columns.forEach(column -> sql.append(", count(*) - count(").append(CatalogReader.identifier(column)).append(')'));
        if (priceRule != null) {
            String price = CatalogReader.identifier(PRICE);
            String outlier = priceRule.upperFence() == null ? "false"
                    : price + " > " + BigDecimal.valueOf(priceRule.upperFence()).toPlainString();
            sql.append(", count(*) FILTER (WHERE ").append(price).append(" = 0)")
                    .append(", count(*) FILTER (WHERE ").append(price).append(" < 0)")
                    .append(", count(*) FILTER (WHERE ").append(outlier).append(')');
        }
        sql.append(" FROM ").append(name).append(" GROUP BY 1");
        return session.query(sql.toString(), rows -> {
            Map<Long, DayCounts> out = new HashMap<>();
            while (rows.next()) {
                Long key = epochDay(rows.getObject(1));
                Map<String, Long> nulls = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    nulls.put(columns.get(i), rows.getLong(3 + i));
                }
                int next = 3 + columns.size();
                PriceCounts price = priceRule == null ? null
                        : new PriceCounts(rows.getLong(next), rows.getLong(next + 1), rows.getLong(next + 2));
                out.put(key, new DayCounts(null, rows.getLong(2), Map.of(), nulls, 0, price));
            }
            return out;
        });
    }

    private Map<Long, Map<String, Long>> rowsByType(String name) throws SQLException {
        String type = CatalogReader.identifier(TYPE);
        return session.query("SELECT " + dayOf() + ", CAST(" + type + " AS VARCHAR), count(*) FROM " + name + " WHERE "
                + type + " IS NOT NULL GROUP BY 1, 2", rows -> {
                    Map<Long, Map<String, Long>> out = new HashMap<>();
                    while (rows.next()) {
                        out.computeIfAbsent(epochDay(rows.getObject(1)), key -> new HashMap<>())
                                .put(rows.getString(2), rows.getLong(3));
                    }
                    return out;
                });
    }

    /** Duplicates of a row share its event_time, so they fall on the same day. NULLs group together, as in the reference. */
    private Map<Long, Long> duplicates(String name, List<String> columns) throws SQLException {
        String all = columns.stream().map(CatalogReader::identifier).collect(Collectors.joining(", "));
        return session.query("SELECT " + dayOf() + ", sum(n - 1) FROM (SELECT " + CatalogReader.identifier(TIME)
                + ", count(*) AS n FROM " + name + " GROUP BY " + all + ") GROUP BY 1", rows -> {
                    Map<Long, Long> out = new HashMap<>();
                    while (rows.next()) {
                        out.put(epochDay(rows.getObject(1)), ((Number) rows.getObject(2)).longValue());
                    }
                    return out;
                });
    }

    private static Long epochDay(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private SchemaDrift drift(String namespace, String table, List<String> columns) {
        if (drift == null || drift.registry() == null) {
            return SchemaDrift.notApplicable("No Schema Registry configured");
        }
        if (!namespace.equals(drift.namespace()) || !table.equals(drift.table())) {
            return SchemaDrift.notApplicable("Only " + LakehouseSession.CATALOG_ALIAS + "." + drift.namespace() + "."
                    + drift.table() + " is fed by the subject " + drift.subject());
        }
        try {
            Optional<List<String>> fields = drift.registry().fields();
            return fields.map(names -> SchemaDrift.compare(drift.subject(), names, columns))
                    .orElseGet(() -> SchemaDrift.unavailable(drift.subject(), "The subject is not registered yet"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SchemaDrift.unavailable(drift.subject(), "Interrupted while asking Schema Registry");
        } catch (IOException | RuntimeException e) {
            LOG.warn("Schema Registry did not answer", e);
            return SchemaDrift.unavailable(drift.subject(), "Schema Registry did not answer");
        }
    }
}
