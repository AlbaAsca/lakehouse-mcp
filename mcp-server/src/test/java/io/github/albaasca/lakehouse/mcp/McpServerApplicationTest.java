package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.albaasca.lakehouse.mcp.LakehouseSession.Settings;
import io.github.albaasca.lakehouse.mcp.McpServerApplication.LakehouseProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class McpServerApplicationTest {

    private static Settings settingsWith(String extensionDirectory) {
        return new LakehouseProperties(null, null, extensionDirectory).settings();
    }

    @Test
    void withADirectoryTheExtensionsComeFromItAndNothingIsDownloaded() {
        Settings settings = settingsWith("/opt/duckdb-extensions");

        assertThat(settings.extensionDirectory()).isEqualTo(Path.of("/opt/duckdb-extensions"));
        assertThat(settings.installExtensions()).isFalse();
    }

    @Test
    void withoutADirectoryDuckDbUsesItsDefaultAndInstallsThem() {
        for (String none : new String[] {null, "", " "}) {
            Settings settings = settingsWith(none);

            assertThat(settings.extensionDirectory()).isNull();
            assertThat(settings.installExtensions()).isTrue();
        }
    }
}
