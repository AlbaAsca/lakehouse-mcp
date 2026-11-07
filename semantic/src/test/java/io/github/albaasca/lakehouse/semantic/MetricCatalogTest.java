package io.github.albaasca.lakehouse.semantic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricCatalogTest {

    private static final String VALID = """
            name: sample
            description: A sample
            unit: rows
            parameters:
              - name: from
                type: date
              - name: limit
                type: integer
                default: 10
                min: 1
            sql: |
              SELECT ':not_a_marker', CAST(:from AS DATE), x::INT FROM ${table} LIMIT :limit
            """;

    private static MetricCatalog one(String yaml) {
        return MetricCatalog.fromSources(Map.of("sample.yaml", yaml));
    }

    @Test
    void theFourMetricsOfTheRepositoryLoad() {
        assertThat(MetricCatalog.load().all()).extracting(Metric::name)
                .containsExactly("average_ticket", "cart_abandonment", "conversion_funnel", "top_brands");
    }

    @Test
    void bindsInOrderWithDefaultsAndSkipsLiteralsAndCasts() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("from", "2024-03-01");
        BoundQuery query = one(VALID).query("sample", "lh.ns.events", arguments);
        assertThat(query.sql()).isEqualTo("SELECT ':not_a_marker', CAST(? AS DATE), x::INT FROM lh.ns.events LIMIT ?");
        assertThat(query.values()).isEqualTo(List.of("2024-03-01", 10L));
    }

    @Test
    void anOpenBoundIsNull() {
        BoundQuery query = one(VALID).query("sample", "events", Map.of("limit", 3));
        assertThat(query.values()).containsExactly(null, 3L);
    }

    @Test
    void aMaliciousValueIsRejectedOrBoundNeverConcatenated() {
        assertThatThrownBy(() -> one(VALID).query("sample", "events", Map.of("from", "2024-03-01'; DROP TABLE x; --")))
                .isInstanceOf(MetricException.class).hasMessageContaining("not a valid date");
        assertThatThrownBy(() -> one(VALID).query("sample", "events; DROP TABLE x", Map.of()))
                .isInstanceOf(MetricException.class).hasMessageContaining("Not a table name");
        assertThatThrownBy(() -> one(VALID).query("sample", "events", Map.of("limit", 0)))
                .isInstanceOf(MetricException.class).hasMessageContaining(">= 1");
        assertThatThrownBy(() -> one(VALID).query("sample", "events", Map.of("nope", 1)))
                .isInstanceOf(MetricException.class).hasMessageContaining("no parameter 'nope'");
    }

    @Test
    void unknownMetricListsTheKnownOnes() {
        assertThatThrownBy(() -> one(VALID).get("other")).isInstanceOf(MetricException.class)
                .hasMessageContaining("Unknown metric 'other'").hasMessageContaining("sample");
    }

    @Test
    void malformedYamlNamesTheFile() {
        assertThatThrownBy(() -> one("name: [unclosed\nsql: x")).isInstanceOf(MetricException.class)
                .hasMessageContaining("sample.yaml: malformed YAML");
    }

    @Test
    void missingFieldsAndUnknownKeysAreReported() {
        assertThatThrownBy(() -> one(VALID.replace("unit: rows\n", ""))).hasMessageContaining("sample.yaml: 'unit' is required");
        assertThatThrownBy(() -> one(VALID + "extra: 1\n")).hasMessageContaining("unknown key 'extra'");
        assertThatThrownBy(() -> one("- a\n- b\n")).hasMessageContaining("expected a mapping");
        assertThatThrownBy(() -> one(VALID.replace("type: date", "type: text"))).hasMessageContaining("use date or integer");
        assertThatThrownBy(() -> one(VALID.replace("name: sample", "name: Bad Name"))).hasMessageContaining("snake_case");
    }

    @Test
    void parametersAndSqlMustAgree() {
        assertThatThrownBy(() -> one(VALID.replace("CAST(:from AS DATE)", "CAST(:until AS DATE)")))
                .hasMessageContaining("uses ':until'");
        assertThatThrownBy(() -> one(VALID.replace("CAST(:from AS DATE)", "CAST(NULL AS DATE)")))
                .hasMessageContaining("parameter 'from' is not used");
        assertThatThrownBy(() -> one(VALID.replace("${table}", "events"))).hasMessageContaining("${table}");
    }

    @Test
    void aMetricNameCannotBeDefinedTwice() {
        Map<String, String> sources = new java.util.LinkedHashMap<>();
        sources.put("a.yaml", VALID);
        sources.put("b.yaml", VALID);
        assertThatThrownBy(() -> MetricCatalog.fromSources(sources)).hasMessageContaining("already defined in a.yaml");
    }
}
