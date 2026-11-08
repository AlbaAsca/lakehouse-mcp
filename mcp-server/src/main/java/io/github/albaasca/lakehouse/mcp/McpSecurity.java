package io.github.albaasca.lakehouse.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two roles, two API keys, sent as {@code Authorization: Bearer <key>}. The analyst reads and proposes; only the admin
 * runs apply_fix and approves proposals. The model gets the analyst key, so a person with the admin key stays the only
 * way to approve. A key that is not set (blank) matches nobody: without keys everything but the health check is 401.
 */
final class McpSecurity {

    static final String ANALYST = "ANALYST";
    static final String ADMIN = "ADMIN";
    /** Tools that need the admin role; every other tool is for the analyst. */
    static final Set<String> ADMIN_TOOLS = Set.of("apply_fix");

    private McpSecurity() {}

    static SecurityFilterChain chain(HttpSecurity http, String analystKey, String adminKey, List<String> allowedOrigins)
            throws Exception {
        return chain(http, analystKey, adminKey, allowedOrigins, AuditLog.NONE);
    }

    static SecurityFilterChain chain(HttpSecurity http, String analystKey, String adminKey, List<String> allowedOrigins,
            AuditLog audit) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(requests -> requests
                        // error dispatches (e.g. a 500) must not be re-judged as unauthenticated and masked as 401
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/fix-proposals/*/approvals").hasRole(ADMIN)
                        .requestMatchers("/audit-events", "/audit-events/**").hasRole(ADMIN)
                        .anyRequest().hasAnyRole(ANALYST, ADMIN))
                .addFilterBefore(new ApiKeyFilter(analystKey, adminKey, allowedOrigins, audit,
                        java.time.Clock.systemUTC()), AuthorizationFilter.class)
                .build();
    }

    static boolean sameKey(String given, String expected) {
        if (given == null || expected == null || expected.isBlank()) {
            return false;
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return MessageDigest.isEqual(sha.digest(given.getBytes(StandardCharsets.UTF_8)),
                    sha.digest(expected.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The MCP specification asks the server to validate Origin, so a web page cannot talk to a local server. */
    static boolean originAllowed(String origin, List<String> allowed) {
        if (origin == null) {
            return true;
        }
        if (allowed.contains(origin)) {
            return true;
        }
        try {
            String host = URI.create(origin).getHost();
            return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The tools a JSON-RPC body calls (a single message or a batch), empty if it calls none. */
    static List<String> calledTools(JsonMapper json, byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            List<JsonNode> messages = new java.util.ArrayList<>();
            if (root.isArray()) {
                root.forEach(messages::add);
            } else {
                messages.add(root);
            }
            return messages.stream()
                    .filter(m -> "tools/call".equals(m.path("method").asString("")))
                    .map(m -> m.path("params").path("name").asString(""))
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** A tool call to audit; {@code arguments} may be null. */
    record Call(String tool, JsonNode arguments, Long proposalId) {}

    /** Every {@code tools/call} of a JSON-RPC body (single or batch), with its arguments and proposal id if any. */
    static List<Call> callsOf(JsonMapper json, byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            List<JsonNode> messages = new java.util.ArrayList<>();
            if (root.isArray()) {
                root.forEach(messages::add);
            } else {
                messages.add(root);
            }
            List<Call> out = new java.util.ArrayList<>();
            for (JsonNode m : messages) {
                if (!"tools/call".equals(m.path("method").asString(""))) {
                    continue;
                }
                JsonNode arguments = m.path("params").path("arguments");
                JsonNode id = arguments.path("proposalId");
                out.add(new Call(m.path("params").path("name").asString(""), arguments,
                        id.isIntegralNumber() ? id.asLong() : null));
            }
            return out;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    static final class ApiKeyFilter extends OncePerRequestFilter {

        private final String analystKey;
        private final String adminKey;
        private final List<String> allowedOrigins;
        private final JsonMapper json = JsonMapper.builder().build();
        private final ToolArguments arguments = ToolArguments.ofServer();

        private final AuditLog audit;
        private final java.time.Clock clock;

        ApiKeyFilter(String analystKey, String adminKey, List<String> allowedOrigins) {
            this(analystKey, adminKey, allowedOrigins, AuditLog.NONE, java.time.Clock.systemUTC());
        }

        ApiKeyFilter(String analystKey, String adminKey, List<String> allowedOrigins, AuditLog audit,
                java.time.Clock clock) {
            this.analystKey = analystKey;
            this.adminKey = adminKey;
            this.allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins;
            this.audit = audit == null ? AuditLog.NONE : audit;
            this.clock = clock;
        }

        /** One audit entry per tool call in the body, or one for an approval. */
        private void record(String role, List<Call> calls, String outcome, long startedNanos) {
            long millis = (System.nanoTime() - startedNanos) / 1_000_000;
            for (Call call : calls) {
                audit.record(AuditLog.Event.of(clock.instant(), role.toLowerCase(), call.tool(),
                        AuditLog.sanitize(json, call.arguments(), List.of(analystKey == null ? "" : analystKey,
                                adminKey == null ? "" : adminKey)), outcome, millis, call.proposalId()));
            }
        }

        private static String outcomeOf(int status) {
            return status == 403 ? "FORBIDDEN" : status < 400 ? "OK" : "ERROR";
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!originAllowed(request.getHeader("Origin"), allowedOrigins)) {
                forbid(response, "Origin not allowed");
                return;
            }
            String header = request.getHeader("Authorization");
            String given = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : null;
            String role = sameKey(given, adminKey) ? ADMIN : sameKey(given, analystKey) ? ANALYST : null;
            HttpServletRequest next = request;
            List<Call> calls = List.of();
            long started = System.nanoTime();
            if (role != null) {
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        role.toLowerCase(), null, AuthorityUtils.createAuthorityList("ROLE_" + role)));
                if ("POST".equals(request.getMethod()) && request.getRequestURI().equals("/mcp")) {
                    byte[] body = request.getInputStream().readAllBytes();
                    next = new CachedBody(request, body);
                    calls = callsOf(json, body);
                    // admin also holds the analyst role: hasAnyRole(ANALYST, ADMIN) covers it
                    if (!ADMIN.equals(role) && calledTools(json, body).stream().anyMatch(ADMIN_TOOLS::contains)) {
                        forbid(response, "This tool needs the admin role");
                        record(role, calls, "FORBIDDEN", started);
                        return;
                    }
                    String rejection = arguments.rejection(json, body);
                    if (rejection != null) {
                        response.setStatus(HttpServletResponse.SC_OK);
                        response.setContentType("application/json;charset=UTF-8");
                        response.getWriter().write(rejection);
                        record(role, calls, "REJECTED", started);
                        return;
                    }
                } else if ("POST".equals(request.getMethod())
                        && request.getRequestURI().matches("/fix-proposals/\\d+/approvals")) {
                    String[] parts = request.getRequestURI().split("/");
                    calls = List.of(new Call("approve_proposal", null, Long.parseLong(parts[2])));
                }
            }
            try {
                chain.doFilter(next, response);
            } catch (IOException | ServletException | RuntimeException e) {
                if (!calls.isEmpty()) {
                    record(role, calls, "ERROR", started);
                }
                throw e;
            }
            if (!calls.isEmpty()) {
                record(role, calls, outcomeOf(response.getStatus()), started);
            }
        }

        /** 403 written directly: sendError would forward to /error, which needs a key and turns it into 401. */
        private static void forbid(HttpServletResponse response, String message) throws IOException {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write(message);
        }
    }

    /** A request whose body was already read, so the MCP endpoint can read it again. */
    private static final class CachedBody extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBody(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
