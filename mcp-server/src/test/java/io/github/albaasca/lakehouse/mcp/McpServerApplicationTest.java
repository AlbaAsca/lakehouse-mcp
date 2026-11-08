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
    void missingOrUnresolvedVariablesAreNamedAndStopTheStart() {
        LakehouseProperties unresolved = new LakehouseProperties(
                new LakehouseSession.Catalog("c", "http://x", "${POLARIS_CLIENT_ID}", ""),
                new LakehouseSession.Storage("s:1", null, "${S3_SECRET_KEY}", "eu"), null);

        org.assertj.core.api.Assertions.assertThatThrownBy(unresolved::requireCredentials)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("POLARIS_CLIENT_ID").hasMessageContaining("POLARIS_CLIENT_SECRET")
                .hasMessageContaining("S3_ACCESS_KEY").hasMessageContaining("S3_SECRET_KEY")
                .hasMessageNotContaining("S3_REGION");
    }

    @Test
    void withAllVariablesItPasses() {
        new LakehouseProperties(new LakehouseSession.Catalog("c", "http://x", "id", "secret"),
                new LakehouseSession.Storage("s:1", "k", "s", "eu"), null).requireCredentials();
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
