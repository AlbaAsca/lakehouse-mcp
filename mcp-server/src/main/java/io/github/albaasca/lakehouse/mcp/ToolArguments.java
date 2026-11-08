package io.github.albaasca.lakehouse.mcp;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.ai.mcp.annotation.McpTool;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The arguments each tool declares, to refuse a call that sends one that does not exist. The framework ignores them in
 * silence, so a model that writes {@code parameters:{limit:2}} instead of {@code limit:2} would get unfiltered data and
 * report it as the answer.
 */
final class ToolArguments {

    private final Map<String, TreeSet<String>> declared = new HashMap<>();

    /** Reads the {@code @McpTool} methods of these classes; the argument names are the parameter names. */
    ToolArguments(Class<?>... toolClasses) {
        for (Class<?> type : toolClasses) {
            for (Method method : type.getDeclaredMethods()) {
                McpTool tool = method.getAnnotation(McpTool.class);
                if (tool == null) {
                    continue;
                }
                TreeSet<String> names = new TreeSet<>();
                for (Parameter parameter : method.getParameters()) {
                    if (!parameter.getType().getName().startsWith("io.modelcontextprotocol")
                            && !parameter.getType().getName().startsWith("org.springframework.ai.mcp")) {
                        names.add(parameter.getName());
                    }
                }
                declared.put(tool.name().isEmpty() ? method.getName() : tool.name(), names);
            }
        }
    }

    /** The tools of this server. */
    static ToolArguments ofServer() {
        return new ToolArguments(CatalogTools.class, QueryTools.class, MetricTools.class, QualityTools.class,
                PipelineTools.class, FixTools.class);
    }

    /** The error to answer a call with, or null if every argument exists (or the tool is unknown: the framework says so). */
    String problem(String tool, JsonNode arguments) {
        TreeSet<String> names = declared.get(tool);
        if (names == null || arguments == null || !arguments.isObject()) {
            return null;
        }
        List<String> unknown = new ArrayList<>();
        for (String name : arguments.propertyNames()) {
            if (!names.contains(name)) {
                unknown.add(name);
            }
        }
        if (unknown.isEmpty()) {
            return null;
        }
        return "Unknown argument(s) for " + tool + ": " + String.join(", ", unknown) + ". Its arguments are: "
                + (names.isEmpty() ? "none" : String.join(", ", names)) + ". Nothing was run.";
    }

    /**
     * The JSON-RPC answer for a single {@code tools/call} message with an unknown argument, or null if there is none.
     * Batches are left to the framework.
     */
    String rejection(JsonMapper json, byte[] body) {
        try {
            JsonNode message = json.readTree(body);
            if (!message.isObject() || !"tools/call".equals(message.path("method").asString(""))) {
                return null;
            }
            JsonNode params = message.path("params");
            String problem = problem(params.path("name").asString(""), params.path("arguments"));
            if (problem == null || !message.has("id")) {
                return null;
            }
            Map<String, Object> answer = new java.util.LinkedHashMap<>();
            answer.put("jsonrpc", "2.0");
            answer.put("id", message.get("id"));
            answer.put("result", Map.of("isError", true, "content", List.of(Map.of("type", "text", "text", problem))));
            return json.writeValueAsString(answer);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
