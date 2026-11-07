package io.github.albaasca.lakehouse.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.stereotype.Component;

/** The tools that say whether the pipeline is alive. */
@Component
public class PipelineTools {

    private final PipelineHealth health;

    PipelineTools(PipelineHealth health) {
        this.health = health;
    }

    @McpTool(name = "pipeline_health", description = "Says whether the pipeline from Kafka to the lakehouse is alive. "
            + "status is OK, WARN or FAIL, the worst of three checks, and reasons says why. lag: messages in the topic the "
            + "sink has not committed yet. connector: the state of the sink and its tasks in Kafka Connect. freshness: "
            + "seconds since the last snapshot of the table (the time it was written, not event_time). A source that does "
            + "not answer is reported as FAIL inside the result, not as an error.",
            annotations = @McpAnnotations(title = "Pipeline health", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    public CallToolResult pipelineHealth() {
        return ToolResults.of(health::check);
    }
}
