package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** ADD_COLUMN without a catalog: the SQL it renders, the types it takes from the registry and what it refuses. */
class AddColumnTest {

    private static final String V2 = "{\"type\":\"record\",\"name\":\"Event\",\"fields\":["
            + "{\"name\":\"event_time\",\"type\":{\"type\":\"long\",\"logicalType\":\"timestamp-micros\"}},"
            + "{\"name\":\"price\",\"type\":\"double\"},"
            + "{\"name\":\"brand\",\"type\":[\"null\",\"string\"],\"default\":null},"
            + "{\"name\":\"qty\",\"type\":[\"null\",\"int\"],\"default\":null},"
            + "{\"name\":\"tags\",\"type\":{\"type\":\"array\",\"items\":\"string\"}}]}";

    private static AddColumn action() {
        return new AddColumn(null, null, "ecommerce", "events", () -> Optional.of(SchemaRegistryFields.duckDbTypes(V2)),
                "http://127.0.0.1:1", "sink", Duration.ofSeconds(1));
    }

    @Test
    void rendersTheAlterWithQuotedColumnAndWhitelistedType() {
        assertThat(RepairSession.ADD_COLUMN.render(
                Map.of("table", "lh.ecommerce.events", "column", "coupon_code", "type", "VARCHAR")))
                .isEqualTo("ALTER TABLE lh.ecommerce.events ADD COLUMN \"coupon_code\" VARCHAR");
    }

    @Test
    void refusesInjectionInColumnAndType() {
        assertThatThrownBy(() -> RepairSession.ADD_COLUMN.render(
                Map.of("table", "lh.ecommerce.events", "column", "a\"; DROP TABLE x; --", "type", "VARCHAR")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepairSession.ADD_COLUMN.render(
                Map.of("table", "lh.ecommerce.events", "column", "a", "type", "VARCHAR); DROP TABLE x; --")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mapsAvroTypesToDuckDbAndMarksTheUnsupportedOnesNull() {
        Map<String, String> types = SchemaRegistryFields.duckDbTypes(V2);

        assertThat(types).containsEntry("event_time", "TIMESTAMPTZ").containsEntry("price", "DOUBLE")
                .containsEntry("brand", "VARCHAR").containsEntry("qty", "INTEGER").containsEntry("tags", null);
    }

    @Test
    void onlyThePipelineTableWithExactlyTheThreeParametersIsAccepted() {
        assertThatThrownBy(() -> action().validate(Map.of("namespace", "other", "table", "events", "column", "qty")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("only applies to");
        assertThatThrownBy(() -> action().validate(Map.of("namespace", "ecommerce", "table", "events")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> action().validate(
                Map.of("namespace", "ecommerce", "table", "events", "column", "qty", "type", "VARCHAR")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aColumnThatIsNotRegisteredOrHasNoSupportedTypeIsRefusedBeforeTouchingTheTable() {
        assertThatThrownBy(() -> action().validate(Map.of("namespace", "ecommerce", "table", "events", "column", "nope")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not in the registered schema");
        assertThatThrownBy(() -> action().validate(Map.of("namespace", "ecommerce", "table", "events", "column", "tags")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cannot be added automatically");
    }
}
