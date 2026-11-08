package io.github.albaasca.lakehouse.mcp;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports DOWN when the catalog stops answering after start, so /actuator/health does not say everything is fine while
 * every tool fails. It only reports: restarting would not bring Polaris back, and the status returns to UP by itself.
 */
public final class CatalogHealth implements HealthIndicator {

    /** One cheap call to the catalog; throws if it cannot be reached. */
    @FunctionalInterface
    public interface Probe {
        void check() throws Exception;
    }

    private final Probe probe;

    public CatalogHealth(Probe probe) {
        this.probe = probe;
    }

    @Override
    public Health health() {
        try {
            probe.check();
            return Health.up().build();
        } catch (Exception e) {
            return Health.down().withDetail("error", String.valueOf(e.getMessage())).build();
        }
    }
}
