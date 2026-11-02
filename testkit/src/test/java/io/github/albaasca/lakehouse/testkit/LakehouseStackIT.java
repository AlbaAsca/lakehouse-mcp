package io.github.albaasca.lakehouse.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** Same round trip as infra/smoke.sh, from outside the Docker network and without the compose stack. */
class LakehouseStackIT {

    @Test
    void duckDbWritesAndReadsARowInIcebergThroughPolaris() throws Exception {
        LakehouseStack stack = LakehouseStack.shared();

        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = writer.createStatement()) {
            stack.attachDuckDb(writer);
            statement.execute("CREATE SCHEMA IF NOT EXISTS lh.stack_it");
            statement.execute("DROP TABLE IF EXISTS lh.stack_it.t");
            statement.execute("CREATE TABLE lh.stack_it.t (id INT, v VARCHAR)");
            statement.execute("INSERT INTO lh.stack_it.t VALUES (1, 'ok')");
        }

        // A second database: the row can only come from the catalog and the object store.
        try (Connection reader = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = reader.createStatement()) {
            stack.attachDuckDb(reader);
            try (ResultSet rows = statement.executeQuery("SELECT id, v FROM lh.stack_it.t")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt("id")).isEqualTo(1);
                assertThat(rows.getString("v")).isEqualTo("ok");
                assertThat(rows.next()).isFalse();
            }
        }
    }

    /** The ports published by compose.yaml: reaching any of them would mean depending on `./mk up`. */
    @Test
    void usesItsOwnMappedPortsAndNotThoseOfTheComposeStack() {
        LakehouseStack stack = LakehouseStack.shared();

        int polarisPort = URI.create(stack.polarisUrl()).getPort();
        int s3Port = Integer.parseInt(stack.s3Endpoint().substring(stack.s3Endpoint().lastIndexOf(':') + 1));

        assertThat(polarisPort).isNotIn(8181, 8182, 8333);
        assertThat(s3Port).isNotIn(8181, 8182, 8333);
    }

    @Test
    void sharedReturnsTheSameStack() {
        assertThat(LakehouseStack.shared()).isSameAs(LakehouseStack.shared());
    }
}
