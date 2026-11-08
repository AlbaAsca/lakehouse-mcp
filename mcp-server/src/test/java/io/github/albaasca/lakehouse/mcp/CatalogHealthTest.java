package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class CatalogHealthTest {

    @Test
    void goesDownWhenTheCatalogFailsAndComesBackByItself() {
        AtomicBoolean catalogUp = new AtomicBoolean(true);
        CatalogHealth health = new CatalogHealth(() -> {
            if (!catalogUp.get()) {
                throw new java.sql.SQLException("Could not connect to polaris");
            }
        });

        assertThat(health.health().getStatus()).isEqualTo(Status.UP);

        catalogUp.set(false);
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.health().getDetails().get("error").toString()).contains("polaris");

        catalogUp.set(true);
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }
}
