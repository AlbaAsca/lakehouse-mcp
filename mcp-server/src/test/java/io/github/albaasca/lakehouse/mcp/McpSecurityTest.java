package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Role by tool: what the filter lets through, element by element. */
class McpSecurityTest {

    private static final String ANALYST_KEY = "analyst-key";
    private static final String ADMIN_KEY = "admin-key";
    private static final List<String> ANALYST_TOOLS = List.of("list_tables", "describe_table", "run_query", "get_metric",
            "pipeline_health", "data_quality_check", "time_travel", "propose_fix", "list_metrics");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private record Outcome(int status, Authentication authentication, boolean reachedTheEndpoint) {}

    private static Outcome post(String authorization, String origin, String tool) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setContentType("application/json");
        request.setContent(("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":{}}}").getBytes());
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new McpSecurity.ApiKeyFilter(ANALYST_KEY, ADMIN_KEY, List.of()).doFilter(request, response, chain);
        return new Outcome(response.getStatus(), SecurityContextHolder.getContext().getAuthentication(),
                chain.getRequest() != null);
    }

    static Stream<Arguments> roleByTool() {
        Stream.Builder<Arguments> rows = Stream.builder();
        for (String tool : ANALYST_TOOLS) {
            rows.add(Arguments.of("Bearer " + ANALYST_KEY, tool, true));
            rows.add(Arguments.of("Bearer " + ADMIN_KEY, tool, true));
        }
        rows.add(Arguments.of("Bearer " + ANALYST_KEY, "apply_fix", false));
        rows.add(Arguments.of("Bearer " + ADMIN_KEY, "apply_fix", true));
        return rows.build();
    }

    @ParameterizedTest(name = "{0} calls {1}: allowed={2}")
    @MethodSource("roleByTool")
    void eachRoleReachesOnlyItsTools(String authorization, String tool, boolean allowed) throws Exception {
        Outcome outcome = post(authorization, null, tool);

        assertThat(outcome.reachedTheEndpoint()).isEqualTo(allowed);
        assertThat(outcome.status()).isEqualTo(allowed ? 200 : 403);
        assertThat(outcome.authentication()).isNotNull();
    }

    @Test
    void theAnalystRoleIsOnlyAnalystAndTheAdminKeyGivesAdmin() throws Exception {
        assertThat(post("Bearer " + ANALYST_KEY, null, "list_tables").authentication().getAuthorities())
                .extracting(Object::toString).containsExactly("ROLE_ANALYST");
        assertThat(post("Bearer " + ADMIN_KEY, null, "list_tables").authentication().getAuthorities())
                .extracting(Object::toString).containsExactly("ROLE_ADMIN");
    }

    @Test
    void aBatchWithApplyFixHiddenInsideIsStillDeniedToTheAnalyst() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setContent(("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"run_query\"}},"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"apply_fix\"}}]").getBytes());
        request.addHeader("Authorization", "Bearer " + ANALYST_KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        new McpSecurity.ApiKeyFilter(ANALYST_KEY, ADMIN_KEY, List.of()).doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void withoutAValidKeyNobodyIsAuthenticated() throws Exception {
        for (String authorization : new String[] {null, "", "Bearer ", "Bearer wrong", "Basic " + ANALYST_KEY,
                ANALYST_KEY}) {
            SecurityContextHolder.clearContext();

            assertThat(post(authorization, null, "list_tables").authentication()).as(String.valueOf(authorization)).isNull();
        }
    }

    @Test
    void aBlankConfiguredKeyMatchesNoOne() {
        assertThat(McpSecurity.sameKey("", "")).isFalse();
        assertThat(McpSecurity.sameKey(null, "")).isFalse();
        assertThat(McpSecurity.sameKey("x", null)).isFalse();
        assertThat(McpSecurity.sameKey("k", "k")).isTrue();
        assertThat(McpSecurity.sameKey("k", "K")).isFalse();
    }

    @Test
    void anOriginOutsideLoopbackIsRefusedEvenWithAKey() throws Exception {
        assertThat(post("Bearer " + ADMIN_KEY, "http://evil.example", "list_tables").status()).isEqualTo(403);
        assertThat(post("Bearer " + ADMIN_KEY, "http://localhost:3000", "list_tables").status()).isEqualTo(200);
        assertThat(post("Bearer " + ADMIN_KEY, "http://127.0.0.1:8090", "list_tables").status()).isEqualTo(200);
        assertThat(McpSecurity.originAllowed("https://ok.example", List.of("https://ok.example"))).isTrue();
        assertThat(McpSecurity.originAllowed("not a uri", List.of())).isFalse();
    }
}
