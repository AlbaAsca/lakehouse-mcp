package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.List;
import java.util.NoSuchElementException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

class ToolResultsTest {

    private static final String SECRET = "s3://bucket/key?token=secret";

    private static String text(CallToolResult result) {
        assertThat(result.content()).hasSize(1);
        return ((TextContent) result.content().getFirst()).text();
    }

    @McpTool(name = "fake_tool", description = "test")
    CallToolResult fakeTool(boolean fail) {
        return ToolResults.of(() -> {
            if (fail) {
                throw new NoSuchElementException("nope");
            }
            return "ok";
        });
    }

    @Test
    void everyCallIsCountedPerToolAndOutcome() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        ToolResults.measureWith(meters);
        try {
            fakeTool(false);
            fakeTool(false);
            fakeTool(true);
        } finally {
            ToolResults.measureWith(null);
        }

        assertThat(meters.get("mcp.tool.calls").tags("tool", "fake_tool", "outcome", "success").timer().count())
                .isEqualTo(2);
        assertThat(meters.get("mcp.tool.calls").tags("tool", "fake_tool", "outcome", "error").timer().count())
                .isEqualTo(1);
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
