package io.github.albaasca.lakehouse.mcp;

import io.github.albaasca.lakehouse.mcp.FixProposalStore.Proposal;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;

/**
 * propose_fix and apply_fix. There is no tool to approve: approval enters through {@link FixProposalController}, which the
 * model does not have. apply_fix on a proposal nobody approved fails and changes nothing.
 */
public class FixTools {

    private final FixProposals proposals;

    FixTools(FixProposals proposals) {
        this.proposals = proposals;
    }

    @McpTool(name = "propose_fix", description = "Proposes a repair; it changes nothing until a person approves it outside "
            + "this conversation. action is from a closed list. ROLLBACK_SNAPSHOT takes params namespace, table and "
            + "snapshotId (a snapshot of that table, from describe_table) and takes the table back to it. "
            + "DEDUPLICATE_PARTITION takes namespace, table, day (yyyy-MM-dd, UTC) and sinceSnapshotId (the last snapshot "
            + "before the duplicates arrived) and deletes only the exact copies written after that snapshot on that day; "
            + "duplicates the source already had stay. ADD_COLUMN takes namespace, table and column (a column that "
            + "data_quality_check reports in schemaDrift.missingInTable); its type comes from Schema Registry. Old rows "
            + "get NULL; events sent before the fix do not get the field back. Give the "
            + "reason: the person reads it to decide. Answers the proposal, with its id and status PROPOSED.",
            annotations = @McpAnnotations(title = "Propose a fix", readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    public CallToolResult proposeFix(
            @McpToolParam(description = "The repair to propose", required = true) FixAction.Type action,
            @McpToolParam(description = "Parameters of the action, as an object", required = true) Map<String, Object> params,
            @McpToolParam(description = "Why this repair, with the evidence found", required = true) String reason) {
        return ToolResults.of(() -> view(proposals.propose(action, params, reason), null));
    }

    @McpTool(name = "apply_fix", description = "Applies a proposal that a person has approved. If it was not approved it "
            + "fails and nothing changes; ask the person to approve it. Applying twice does the repair once: the second "
            + "call answers appliedNow false.",
            annotations = @McpAnnotations(title = "Apply a fix", readOnlyHint = false, destructiveHint = true,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult applyFix(
            @McpToolParam(description = "Id of the proposal, from propose_fix", required = true) Long proposalId) {
        return ToolResults.of(() -> {
            if (proposalId == null) {
                throw new IllegalArgumentException("proposalId is needed");
            }
            try {
                FixProposals.Applied applied = proposals.apply(proposalId);
                return view(applied.proposal(), applied.appliedNow());
            } catch (IllegalStateException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
        });
    }

    static Map<String, Object> view(Proposal proposal, Boolean appliedNow) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", proposal.id());
        out.put("status", proposal.status());
        out.put("action", proposal.action());
        out.put("params", proposal.params());
        out.put("reason", proposal.reason());
        out.put("expectedEffect", proposal.expectedEffect());
        if (appliedNow != null) {
            out.put("appliedNow", appliedNow);
        }
        return out;
    }
}
