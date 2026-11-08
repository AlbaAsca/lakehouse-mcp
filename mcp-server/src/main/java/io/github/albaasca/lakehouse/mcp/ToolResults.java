package io.github.albaasca.lakehouse.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.ai.mcp.annotation.McpTool;
import java.sql.SQLTimeoutException;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * What every tool answers: its value as JSON text, or an error the model can act on.
 *
 * <p>Text and not {@code structuredContent}, because the Spring AI client only passes {@code content} to the model.
 * Errors are built here because the framework writes the message of an exception twice when it has no cause, and adds
 * the root cause when it has one. The details of a failure in the catalog go to the log, not to the model.
 */
final class ToolResults {

    private static final Logger LOG = LoggerFactory.getLogger(ToolResults.class);

    /** The work of a tool. */
    @FunctionalInterface
    interface Answer {
        Object get() throws SQLException;
    }

    private ToolResults() {}

    /** Timer {@code mcp.tool.calls}, tags {@code tool} and {@code outcome} (success|error). Null: not measured. */
    private static volatile MeterRegistry registry;

    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();

    /** Where every tool is measured. Called once at start; tests may pass null. */
    static void measureWith(MeterRegistry meters) {
        registry = meters;
    }

    /**
     * Runs a tool and measures it: every tool goes through here, so a new one is measured without touching it. The
     * tool is the {@code @McpTool} method that called, found in the stack.
     */
    static CallToolResult of(Answer answer) {
        MeterRegistry meters = registry;
        if (meters == null) {
            return run(answer);
        }
        String tool = callerTool();
        long start = System.nanoTime();
        String outcome = "error";
        try {
            CallToolResult result = run(answer);
            outcome = Boolean.TRUE.equals(result.isError()) ? "error" : "success";
            return result;
        } finally {
            Timer.builder("mcp.tool.calls").description("Calls to each MCP tool").tag("tool", tool)
                    .tag("outcome", outcome).register(meters).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private static String callerTool() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> frames
                .filter(f -> f.getDeclaringClass() != ToolResults.class)
                .findFirst()
                .map(f -> NAMES.computeIfAbsent(f.getClassName() + "#" + f.getMethodName(), k -> nameOf(f)))
                .orElse("unknown"));
    }

    private static String nameOf(StackWalker.StackFrame frame) {
        for (Method method : frame.getDeclaringClass().getDeclaredMethods()) {
            McpTool tool = method.getAnnotation(McpTool.class);
            if (tool != null && method.getName().equals(frame.getMethodName())) {
                return tool.name().isEmpty() ? method.getName() : tool.name();
            }
        }
        return "unknown";
    }

    private static CallToolResult run(Answer answer) {
        try {
            return CallToolResult.builder().addTextContent(JsonMapper.shared().writeValueAsString(answer.get())).build();
        } catch (NoSuchElementException | IllegalArgumentException | SqlGuard.Rejected e) {
            return error(e.getMessage());
        } catch (SQLTimeoutException e) {
            LOG.warn("Tool timed out", e);
            return error("The lakehouse took too long to answer. Try again in a moment.");
        } catch (SQLException e) {
            LOG.error("Tool failed", e);
            return error("The lakehouse could not answer. This is a problem in the server, not in the arguments.");
        }
    }

    private static CallToolResult error(String message) {
        return CallToolResult.builder().isError(true).addTextContent(message).build();
    }
}
