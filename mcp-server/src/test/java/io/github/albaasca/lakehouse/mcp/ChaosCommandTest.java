package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ChaosCommandTest {

    @Test
    void parsesDuplicatesWithDefaultPercent() {
        var p = ChaosCommand.parse(new String[] {"duplicates", "--table", "ecommerce.events", "--day", "2019-10-01"});
        assertThat(p.scenario()).isEqualTo("duplicates");
        assertThat(p.namespace()).isEqualTo("ecommerce");
        assertThat(p.table()).isEqualTo("events");
        assertThat(p.day()).isEqualTo(LocalDate.of(2019, 10, 1));
        assertThat(p.percent()).isEqualTo(10.0);
    }

    @Test
    void parsesPercentAndEmptyPartition() {
        var p = ChaosCommand.parse(new String[] {"empty-partition", "--table", "a.b", "--day", "2019-10-02", "--percent", "25"});
        assertThat(p.scenario()).isEqualTo("empty-partition");
        assertThat(p.percent()).isEqualTo(25.0);
    }

    @Test
    void rejectsMissingAndUnknownScenario() {
        assertThatThrownBy(() -> ChaosCommand.parse(new String[] {})).hasMessageContaining("Missing scenario");
        assertThatThrownBy(() -> ChaosCommand.parse(new String[] {"boom", "--table", "a.b", "--day", "2019-10-01"}))
                .hasMessageContaining("Unknown scenario: boom");
    }

    @Test
    void rejectsBadTable() {
        assertThatThrownBy(() -> ChaosCommand.parse(new String[] {"duplicates", "--table", "events", "--day", "2019-10-01"}))
                .hasMessageContaining("namespace.table");
    }
}
