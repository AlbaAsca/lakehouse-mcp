package io.github.albaasca.lakehouse.agent;

import java.util.Arrays;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hands the model a tool error as JSON, so it can explain it instead of the agent failing.
 *
 * <p>The MCP callback throws when the server answers with an error, and Spring AI then gives the model the bare message.
 * Gemini reads every tool response as JSON and fails on that text ({@code GoogleGenAiChatModel.parseJsonToMap}).
 */
final class ToolErrors {

    private ToolErrors() {}

    /** The same tools, resolved when the provider is, whose errors arrive as {@code {"error": "..."}}. */
    static ToolCallbackProvider explained(ToolCallbackProvider tools) {
        return () -> Arrays.stream(tools.getToolCallbacks()).map(Explained::new).toArray(ToolCallback[]::new);
    }

    static String asJson(ToolExecutionException e) {
        return JsonMapper.shared().writeValueAsString(Map.of("error", String.valueOf(e.getMessage())));
    }

    private record Explained(ToolCallback tool) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return tool.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return tool.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            try {
                return tool.call(toolInput);
            } catch (ToolExecutionException e) {
                return asJson(e);
            }
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            try {
                return tool.call(toolInput, toolContext);
            } catch (ToolExecutionException e) {
                return asJson(e);
            }
        }
    }
}
