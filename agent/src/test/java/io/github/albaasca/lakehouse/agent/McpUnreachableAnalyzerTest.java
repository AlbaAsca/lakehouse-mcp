package io.github.albaasca.lakehouse.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.mock.env.MockEnvironment;

class McpUnreachableAnalyzerTest {

    private final McpUnreachableAnalyzer analyzer = new McpUnreachableAnalyzer();

    @Test
    void anUnreachableServerIsOneLineWithTheAddress() {
        analyzer.setEnvironment(new MockEnvironment().withProperty(
                "spring.ai.mcp.client.streamable-http.connections.lakehouse.url", "http://localhost:1"));
        Throwable failure = new RuntimeException("Error creating bean",
                new IllegalStateException("Client failed to initialize by explicit API call"));

        FailureAnalysis analysis = analyzer.analyze(failure);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).isEqualTo("The MCP server did not respond at http://localhost:1.");
        assertThat(analysis.getDescription()).doesNotContain("\n");
    }

    @Test
    void otherFailuresAreLeftAlone() {
        assertThat(analyzer.analyze(new IllegalStateException("something else"))).isNull();
    }
}
