package io.github.albaasca.lakehouse.agent;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

/**
 * Turns the long stack of "Client failed to initialize" into one line: the MCP server did not answer, and where the
 * agent looked. Registered in META-INF/spring.factories.
 */
class McpUnreachableAnalyzer extends AbstractFailureAnalyzer<IllegalStateException> implements EnvironmentAware {

    static final String MARKER = "Client failed to initialize";
    private static final String URL_PROPERTY = "spring.ai.mcp.client.streamable-http.connections.lakehouse.url";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, IllegalStateException cause) {
        if (cause.getMessage() == null || !cause.getMessage().contains(MARKER)) {
            return null;
        }
        String url = environment == null ? null : environment.getProperty(URL_PROPERTY);
        return new FailureAnalysis("The MCP server did not respond at " + (url == null ? "(unknown address)" : url) + ".",
                "Start the MCP server, or point MCP_SERVER_URL at it.", cause);
    }
}
