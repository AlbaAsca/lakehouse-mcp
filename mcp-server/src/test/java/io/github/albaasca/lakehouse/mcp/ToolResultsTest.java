package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.Test;

class ToolResultsTest {

    private static final String SECRET = "s3://bucket/key?token=secret";

    private static String text(CallToolResult result) {
        assertThat(result.content()).hasSize(1);
        return ((TextContent) result.content().getFirst()).text();
    }

    @Test
    void aValueIsItsJsonAsText() {
        CallToolResult result = ToolResults.of(() -> List.of("a", "b"));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).isEqualTo("[\"a\",\"b\"]");
    }

    @Test
    void aMissingTableKeepsItsMessage() {
        CallToolResult result = ToolResults.of(() -> {
            throw new NoSuchElementException("No table lh.a.b");
        });

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("No table lh.a.b");
    }

    @Test
    void aFailureInTheCatalogDoesNotReachTheModel() {
        CallToolResult result = ToolResults.of(() -> {
            throw new SQLException(SECRET);
        });

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).doesNotContain(SECRET).contains("problem in the server");
    }

    @Test
    void aTimeoutIsToldApartAndDoesNotShowTheQuery() {
        CallToolResult result = ToolResults.of(() -> {
            throw new SQLTimeoutException(SECRET);
        });

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).doesNotContain(SECRET).contains("too long");
    }
}
