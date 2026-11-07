package io.github.albaasca.lakehouse.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import io.github.albaasca.lakehouse.testkit.SyntheticLoader;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The YAML metrics against the independent reference (evals/truth/metrics.json, computed from the CSV with the standard
 * library), for the whole sample and for every day. Rates: 1e-6; money: 0.01 (docs/metrics.md, Redondeo).
 */
class SemanticMetricsIT {

    private static final String TABLE = "lh.semantic_metrics_it.events";
    private static final double RATE = 1e-6 + 1e-9;
    private static final double MONEY = 0.01 + 1e-9;

    private static Connection connection;
    private static MetricCatalog catalog;
    private static JsonNode truth;

    @BeforeAll
    static void load() throws Exception {
        connection = DriverManager.getConnection("jdbc:duckdb:");
        LakehouseStack.shared().attachDuckDb(connection);
        SyntheticLoader.load(connection, "semantic_metrics_it", "events");
        catalog = MetricCatalog.load();
        truth = new ObjectMapper().readTree(Files.readString(
                LakehouseStack.repositoryRoot().resolve("evals").resolve("truth").resolve("metrics.json")));
    }

    @AfterAll
    static void close() throws Exception {
        connection.close();
    }

    private static List<Map<String, Object>> run(String metric, Map<String, Object> arguments) throws SQLException {
        BoundQuery query = catalog.query(metric, TABLE, arguments);
        try (PreparedStatement statement = query.prepare(connection); ResultSet rows = statement.executeQuery()) {
            ResultSetMetaData meta = rows.getMetaData();
            List<Map<String, Object>> result = new ArrayList<>();
            while (rows.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnLabel(i), rows.getObject(i));
                }
                result.add(row);
            }
            return result;
        }
    }

    private static void sameNumber(JsonNode expected, Object actual, double tolerance, String where) {
        if (expected.isNull()) {
            assertThat(actual).as(where).isNull();
        } else {
            assertThat(actual).as(where).isNotNull();
            assertThat(((Number) actual).doubleValue()).as(where).isCloseTo(expected.asDouble(), within(tolerance));
        }
    }

    private static void sameFields(JsonNode expected, Map<String, Object> actual, List<String> keys, String where) {
        for (String key : keys) {
            sameNumber(expected.get(key), actual.get(key), key.equals("revenue") || key.equals("average_ticket")
                    ? MONEY : RATE, where + "." + key);
        }
    }

    private void check(JsonNode expected, Map<String, Object> arguments, String where) throws SQLException {
        List<Map<String, Object>> funnel = run("conversion_funnel", arguments);
        assertThat(funnel).as(where).hasSize(1);
        sameFields(expected.get("funnel"), funnel.get(0), List.of("view_sessions", "cart_sessions", "purchase_sessions",
                "view_to_cart", "cart_to_purchase", "view_to_purchase"), where + " funnel");

        List<Map<String, Object>> abandonment = run("cart_abandonment", arguments);
        sameFields(expected.get("cart_abandonment"), abandonment.get(0),
                List.of("cart_sessions", "abandoned_sessions", "abandonment_rate"), where + " abandonment");

        List<Map<String, Object>> ticket = run("average_ticket", arguments);
        sameFields(expected.get("average_ticket"), ticket.get(0), List.of("revenue", "orders", "average_ticket"),
                where + " ticket");

        List<Map<String, Object>> rows = run("top_brands", arguments);
        JsonNode brands = expected.get("top_brands").get("brands");
        // The last row has a null brand: revenue without brand.
        assertThat(rows).as(where + " brands").hasSize(brands.size() + 1);
        for (int i = 0; i < brands.size(); i++) {
            assertThat(rows.get(i).get("brand")).as(where + " brand " + i).isEqualTo(brands.get(i).get("brand").asString());
            sameFields(brands.get(i), rows.get(i), List.of("revenue", "purchases", "share"), where + " brand " + i);
        }
        Map<String, Object> noBrand = rows.get(rows.size() - 1);
        assertThat(noBrand.get("brand")).isNull();
        sameNumber(expected.get("top_brands").get("revenue_without_brand"), noBrand.get("revenue"), MONEY,
                where + " revenue_without_brand");
    }

    @Test
    void wholeSampleMatchesTheReference() throws Exception {
        check(truth.get("total"), Map.of(), "total");
    }

    @Test
    void everyDayMatchesTheReference() throws Exception {
        int days = 0;
        for (Map.Entry<String, JsonNode> day : truth.get("by_day").properties()) {
            check(day.getValue(), Map.of("from", day.getKey(), "to", day.getKey()), day.getKey());
            days++;
        }
        assertThat(days).isGreaterThan(1);
    }

    @Test
    void limitCutsTheRankingAndAWindowWithoutDataGivesNulls() throws Exception {
        List<Map<String, Object>> rows = run("top_brands", Map.of("limit", 3));
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).get("brand")).isEqualTo(truth.get("total").get("top_brands").get("brands").get(0)
                .get("brand").asString());

        Map<String, Object> empty = Map.of("from", "2000-01-02", "to", "2000-01-01");
        Map<String, Object> funnel = run("conversion_funnel", empty).get(0);
        assertThat(((Number) funnel.get("view_sessions")).longValue()).isZero();
        assertThat(funnel.get("view_to_cart")).isNull();
        assertThat(run("average_ticket", empty).get(0).get("average_ticket")).isNull();
        assertThat(run("top_brands", empty)).hasSize(1);
    }
}
