package io.github.albaasca.lakehouse.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.albaasca.lakehouse.maintenance.ExpireSnapshotsCommand.Options;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.catalog.TableIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExpireSnapshotsCommandTest {

    private static final Map<String, String> ENV = Map.of(
            "POLARIS_CLIENT_ID", "id",
            "POLARIS_CLIENT_SECRET", "secret",
            "POLARIS_REALM", "REALM",
            "S3_ACCESS_KEY", "key",
            "S3_SECRET_KEY", "s3secret",
            "S3_REGION", "eu");

    @Test
    void retentionDefaultsToSevenDays() {
        Options options = Options.parse(new String[] {"--table", "ecommerce.events"});

        assertThat(options.table()).isEqualTo(TableIdentifier.of("ecommerce", "events"));
        assertThat(options.retention()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void retentionIsAnIsoDuration() {
        Options options = Options.parse(new String[] {"--retention", "PT36H", "--table", "ns.t"});

        assertThat(options.retention()).isEqualTo(Duration.ofHours(36));
        assertThat(options.cutoff(Instant.parse("2026-01-02T12:00:00Z"))).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void anExactMomentDoesNotDependOnWhenItRuns() {
        Options options = Options.parse(new String[] {"--table", "ns.t", "--older-than", "2026-01-01T00:00:00Z"});

        assertThat(options.cutoff(Instant.parse("2030-01-01T00:00:00Z"))).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    }

    @Test
    void anUnreadableMomentIsRefused() {
        assertThatThrownBy(() -> Options.parse(new String[] {"--table", "ns.t", "--older-than", "yesterday"}))
                .hasMessageContaining("yesterday");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "--table", "--table events", "--retention P1D", "--table ns.t --other x",
        "--table ns.t --retention P1D --older-than 2026-01-01T00:00:00Z"
    })
    void badArgumentsAreRefusedWithTheUsage(String line) {
        String[] args = line.isEmpty() ? new String[0] : line.split(" ");

        assertThatThrownBy(() -> Options.parse(args)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("usage");
    }

    @ParameterizedTest
    @ValueSource(strings = {"7", "7d", "-P1D"})
    void aRetentionThatMakesNoSenseIsRefused(String retention) {
        assertThatThrownBy(() -> Options.parse(new String[] {"--table", "ns.t", "--retention", retention}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(retention);
    }

    @Test
    void catalogUsesTheAddressesOfTheStackByDefault() {
        Map<String, String> properties = ExpireSnapshotsCommand.catalogProperties(ENV);

        assertThat(properties)
                .containsEntry("uri", "http://polaris:8181/api/catalog")
                .containsEntry("oauth2-server-uri", "http://polaris:8181/api/catalog/v1/oauth/tokens")
                .containsEntry("credential", "id:secret")
                .containsEntry("header.Polaris-Realm", "REALM")
                .containsEntry("s3.endpoint", "http://seaweedfs:8333")
                .containsEntry("s3.access-key-id", "key")
                .containsEntry("s3.secret-access-key", "s3secret")
                .containsEntry("client.region", "eu");
    }

    @Test
    void aMissingVariableIsNamed() {
        Map<String, String> env = new HashMap<>(ENV);
        env.remove("S3_SECRET_KEY");

        assertThatThrownBy(() -> ExpireSnapshotsCommand.catalogProperties(env)).hasMessageContaining("S3_SECRET_KEY");
    }
}
