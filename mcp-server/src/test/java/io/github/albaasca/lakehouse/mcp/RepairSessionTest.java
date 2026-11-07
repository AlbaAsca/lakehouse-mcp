package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.albaasca.lakehouse.mcp.RepairSession.Kind;
import io.github.albaasca.lakehouse.mcp.RepairSession.Template;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The repair instance only takes fixed statements, and each value has to fit its parameter. */
class RepairSessionTest {

    @Test
    void theRollbackTemplateWritesTheTableAndTheSnapshot() {
        assertThat(RepairSession.ROLLBACK.render(Map.of("table", "lh.ns.events", "snapshot_id", 42L)))
                .isEqualTo("CALL iceberg_rollback_to_snapshot('lh.ns.events', 42)");
    }

    @Test
    void aTableOutsideTheCatalogOrWithSqlInItIsRefused() {
        for (String table : new String[] {"ns.events", "other.ns.events", "lh.ns.events'); DROP TABLE x; --",
                "lh.ns.ev ents", "lh.\"ns\".events", "lh.ns.events.extra"}) {
            assertThatThrownBy(() -> RepairSession.ROLLBACK.render(Map.of("table", table, "snapshot_id", 1L)))
                    .as(table).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aSnapshotThatIsNotANumberIsRefused() {
        assertThatThrownBy(() -> RepairSession.ROLLBACK.render(Map.of("table", "lh.ns.t", "snapshot_id", "1); DROP")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingOrExtraValuesAreRefused() {
        assertThatThrownBy(() -> RepairSession.ROLLBACK.render(Map.of("table", "lh.ns.t")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepairSession.ROLLBACK.render(
                Map.of("table", "lh.ns.t", "snapshot_id", 1L, "sql", "DROP TABLE lh.ns.t")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void textIsQuotedAsALiteral() {
        Template template = new Template("ALTER TABLE {table} SET COMMENT {comment}",
                Map.of("table", Kind.TABLE, "comment", Kind.STRING));

        assertThat(template.render(Map.of("table", "lh.ns.t", "comment", "it's $1 \\ fine")))
                .isEqualTo("ALTER TABLE lh.ns.t SET COMMENT 'it''s $1 \\ fine'");
    }

    @Test
    void theDeduplicateTemplateTakesOnlyDaysAndPlainColumns() {
        Map<String, Object> ok = Map.of("table", "lh.ns.t", "base", 7L, "columns", java.util.List.of("a", "b"),
                "day", "2019-10-01", "next_day", "2019-10-02");
        assertThat(RepairSession.DEDUPLICATE.render(ok)).startsWith("DELETE FROM lh.ns.t WHERE")
                .contains("PARTITION BY \"a\", \"b\"").contains("AT (VERSION => 7)")
                .contains("TIMESTAMPTZ '2019-10-01 00:00:00+00'");
        for (Object bad : new Object[] {java.util.List.of(), java.util.List.of("a\" ; DROP"), "a"}) {
            assertThatThrownBy(() -> RepairSession.DEDUPLICATE.render(Map.of("table", "lh.ns.t", "base", 7L,
                    "columns", bad, "day", "2019-10-01", "next_day", "2019-10-02")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> RepairSession.DEDUPLICATE.render(Map.of("table", "lh.ns.t", "base", 7L,
                "columns", java.util.List.of("a"), "day", "2019-10-01' OR 1=1", "next_day", "2019-10-02")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aTemplateMustDeclareExactlyItsPlaceholders() {
        assertThatThrownBy(() -> new Template("CALL f({table}, {snapshot_id})", Map.of("table", Kind.TABLE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Template("CALL f({table})", Map.of("table", Kind.TABLE, "x", Kind.LONG)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
