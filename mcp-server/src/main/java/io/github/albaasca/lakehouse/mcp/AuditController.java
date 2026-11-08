package io.github.albaasca.lakehouse.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/** The audit log, newest first, paged. Admin only (see {@link McpSecurity}); links to the neighbour pages (HATEOAS). */
@Controller
@ConditionalOnExpression("!'${lakehouse.ops.url:}'.isEmpty()")
@ResponseBody
@RequestMapping("/audit-events")
public class AuditController {

    static final int MAX_SIZE = 200;

    private final AuditLog audit;

    AuditController(AuditLog audit) {
        this.audit = audit;
    }

    @GetMapping
    public Map<String, Object> events(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), MAX_SIZE);
        long total = audit.count();
        List<Map<String, Object>> items = audit.page(p, s).stream().map(AuditController::resource).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("events", items);
        out.put("page", p);
        out.put("size", s);
        out.put("total", total);
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("self", Map.of("href", href(p, s)));
        if (p > 0) {
            links.put("prev", Map.of("href", href(p - 1, s)));
        }
        if ((long) (p + 1) * s < total) {
            links.put("next", Map.of("href", href(p + 1, s)));
        }
        out.put("_links", links);
        return out;
    }

    private static String href(int page, int size) {
        return "/audit-events?page=" + page + "&size=" + size;
    }

    static Map<String, Object> resource(AuditLog.Event event) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", event.id());
        out.put("ts", event.ts());
        out.put("role", event.role());
        out.put("tool", event.tool());
        out.put("params", event.params());
        out.put("outcome", event.outcome());
        out.put("durationMs", event.durationMs());
        out.put("proposalId", event.proposalId());
        if (event.proposalId() != null) {
            out.put("_links", Map.of("proposal", Map.of("href", "/fix-proposals/" + event.proposalId())));
        }
        return out;
    }
}
