package io.github.albaasca.lakehouse.mcp;

import io.github.albaasca.lakehouse.mcp.FixProposalStore.Proposal;
import io.github.albaasca.lakehouse.mcp.FixProposalStore.Status;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Where a person reviews and approves proposals. Not an MCP tool: the model does not see this resource. Responses carry
 * {@code _links} to what can be done next (HATEOAS): {@code approve} only while the proposal is PROPOSED.
 *
 * <p>Not annotated as a component: {@link McpServerApplication.OpsConfiguration} registers it only when the ops database
 * is configured.
 */
@ResponseBody
@RequestMapping("/fix-proposals")
public class FixProposalController {

    /** @param approvedBy who approves; recorded with the proposal */
    record Approval(String approvedBy) {}

    private final FixProposals proposals;
    private final ApprovalGate gate;

    FixProposalController(FixProposals proposals, ApprovalGate gate) {
        this.proposals = proposals;
        this.gate = gate;
    }

    @GetMapping
    public Map<String, Object> list() throws SQLException {
        List<Map<String, Object>> items = proposals.list().stream().map(FixProposalController::resource).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("proposals", items);
        out.put("_links", Map.of("self", link("/fix-proposals")));
        return out;
    }

    @GetMapping("/{id}")
    public Map<String, Object> one(@PathVariable long id) throws SQLException {
        return resource(proposals.get(id));
    }

    @PostMapping("/{id}/approvals")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable long id, @RequestBody Approval approval,
            HttpServletRequest request) throws SQLException {
        gate.check(request, approval.approvedBy());
        return ResponseEntity.status(HttpStatus.CREATED).body(resource(proposals.approve(id, approval.approvedBy())));
    }

    static Map<String, Object> resource(Proposal proposal) {
        Map<String, Object> out = new LinkedHashMap<>(FixTools.view(proposal, null));
        out.put("approvedBy", proposal.approvedBy());
        out.put("createdAt", proposal.createdAt());
        out.put("approvedAt", proposal.approvedAt());
        out.put("appliedAt", proposal.appliedAt());
        out.put("error", proposal.error());
        String self = "/fix-proposals/" + proposal.id();
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("self", link(self));
        links.put("collection", link("/fix-proposals"));
        if (proposal.status() == Status.PROPOSED) {
            links.put("approve", Map.of("href", self + "/approvals", "method", "POST"));
        }
        out.put("_links", links);
        return out;
    }

    private static Map<String, Object> link(String href) {
        return Map.of("href", href);
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<Map<String, String>> forbidden(SecurityException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }
}
