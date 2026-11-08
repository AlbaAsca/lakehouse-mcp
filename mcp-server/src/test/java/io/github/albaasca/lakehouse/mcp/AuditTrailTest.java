package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;

/** After a sequence of calls the audit log holds exactly those entries, in order, with nothing secret in them. */
class AuditTrailTest {

    private static final String ANALYST_KEY = "analyst-key";
    private static final String ADMIN_KEY = "admin-key";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<AuditLog.Event> events = new ArrayList<>();
    private final AuditLog log = new AuditLog() {
        @Override
        public void record(Event event) {
            events.add(event);
        }

        @Override
        public List<Event> page(int page, int size) {
            return List.copyOf(events);
        }

        @Override
        public long count() {
            return events.size();
        }
    };
    private final McpSecurity.ApiKeyFilter filter = new McpSecurity.ApiKeyFilter(ANALYST_KEY, ADMIN_KEY, List.of(), log,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private int call(String key, String uri, String body) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setContent(body.getBytes());
        request.addHeader("Authorization", "Bearer " + key);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    private static String tool(String name, String arguments) {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
                + "\",\"arguments\":" + arguments + "}}";
    }

    @Test
    void theLogHoldsExactlyTheCallsInOrderAndNothingSecret() throws Exception {
        call(ANALYST_KEY, "/mcp", tool("run_query", "{\"sql\":\"select 1\"}"));
        call(ANALYST_KEY, "/mcp", tool("apply_fix", "{\"proposalId\":7}"));
        call(ANALYST_KEY, "/mcp", tool("list_tables", "{\"user_id\":\"u-42\",\"apiKey\":\"abc\"}"));
        call(ADMIN_KEY, "/mcp", tool("apply_fix", "{\"proposalId\":7}"));
        call(ADMIN_KEY, "/fix-proposals/7/approvals", "{\"approvedBy\":\"alba\"}");

        assertThat(events).extracting(AuditLog.Event::role, AuditLog.Event::tool, AuditLog.Event::outcome,
                AuditLog.Event::proposalId).containsExactly(
                        org.assertj.core.api.Assertions.tuple("analyst", "run_query", "OK", null),
                        org.assertj.core.api.Assertions.tuple("analyst", "apply_fix", "FORBIDDEN", 7L),
                        org.assertj.core.api.Assertions.tuple("analyst", "list_tables", "REJECTED", null),
                        org.assertj.core.api.Assertions.tuple("admin", "apply_fix", "OK", 7L),
                        org.assertj.core.api.Assertions.tuple("admin", "approve_proposal", "OK", 7L));
        assertThat(events).allSatisfy(e -> assertThat(e.ts()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z")));
        String everything = events.toString();
        assertThat(everything).doesNotContain("u-42", "abc", ADMIN_KEY, ANALYST_KEY);
        assertThat(events.get(0).params()).contains("select 1");
        assertThat(events.get(2).params()).contains("\"user_id\":\"***\"").contains("\"apiKey\":\"***\"");
    }

    @Test
    void sanitizeMasksSecretNamesAndCutsLongText() {
        String out = AuditLog.sanitize(JSON, JSON.readTree("{\"a\":{\"password\":\"p\",\"userId\":3},\"b\":[\"" + "x".repeat(500)
                + "\",\"Bearer " + ADMIN_KEY + "\"],\"c\":\"fine\"}"), List.of(ADMIN_KEY));

        assertThat(out).doesNotContain(ADMIN_KEY).contains("fine").contains("\"password\":\"***\"").contains("\"userId\":\"***\"").doesNotContain("x".repeat(201));
    }

    @Test
    void callsWithoutAKeyAreNotAudited() throws Exception {
        assertThat(call("wrong", "/mcp", tool("list_tables", "{}"))).isEqualTo(200);
        assertThat(events).isEmpty();
    }
}
