package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CompactCommandTest {

    @Test
    void parsesTableAndMinimum() {
        var p = CompactCommand.parse(new String[] {"--table", "ecommerce.events", "--min-files", "3"});
        assertThat(p).isEqualTo(new CompactCommand.Parsed("ecommerce", "events", 3));
        assertThat(CompactCommand.parse(new String[] {"--table", "a.b"}).minFiles()).isEqualTo(2);
    }

    @Test
    void refusesBadArguments() {
        assertThatThrownBy(() -> CompactCommand.parse(new String[] {})).hasMessageContaining("Missing --table");
        assertThatThrownBy(() -> CompactCommand.parse(new String[] {"--table", "events"}))
                .hasMessageContaining("namespace.table");
        assertThatThrownBy(() -> CompactCommand.parse(new String[] {"--table", "a.b", "--min-files", "1"}))
                .hasMessageContaining("at least 2");
    }

    @Test
    void zeroZeroZeroIsReportedAsNothingToCompactNotAsSuccess() {
        var p = new CompactCommand.Parsed("a", "b", 5);
        assertThat(CompactCommand.report(p, "0|0|0")).contains("nothing to compact");
        assertThat(CompactCommand.report(p, "")).contains("nothing to compact");
        assertThat(CompactCommand.report(p, "3|1|1234"))
                .contains("rewritten_data_files=3", "added_data_files=1", "rewritten_bytes=1234");
    }

    @Test
    void compactTemplateAlwaysCarriesAnExplicitMinimum() {
        assertThat(RepairSession.COMPACT.render(java.util.Map.of("table", "lh.a.b", "min_input_files", 2L)))
                .isEqualTo("CALL iceberg_rewrite_data_files('lh.a.b', min_input_files => 2)");
    }
}
