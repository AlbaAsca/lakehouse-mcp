package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.albaasca.lakehouse.mcp.CatalogReader.TableRef;
import org.junit.jupiter.api.Test;

class TableRefTest {

    @Test
    void queryNameIsTheFullNameThatRunQueryAccepts() {
        TableRef ref = new TableRef("ecommerce", "events");

        assertThat(ref.queryName()).isEqualTo("lh.ecommerce.events");
        assertThat(ref.namespace()).isEqualTo("ecommerce");
        assertThat(ref.table()).isEqualTo("events");
    }
}
