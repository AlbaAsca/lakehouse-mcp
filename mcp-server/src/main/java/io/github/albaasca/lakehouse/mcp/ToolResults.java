package io.github.albaasca.lakehouse.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.sql.SQLException;
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

    static CallToolResult of(Answer answer) {
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
