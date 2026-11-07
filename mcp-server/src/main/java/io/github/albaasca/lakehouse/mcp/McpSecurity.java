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
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/fix-proposals/*/approvals").hasRole(ADMIN)
                        .anyRequest().hasAnyRole(ANALYST, ADMIN))
                .addFilterBefore(new ApiKeyFilter(analystKey, adminKey, allowedOrigins), AuthorizationFilter.class)
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

    static final class ApiKeyFilter extends OncePerRequestFilter {

        private final String analystKey;
        private final String adminKey;
        private final List<String> allowedOrigins;
        private final JsonMapper json = JsonMapper.builder().build();

        ApiKeyFilter(String analystKey, String adminKey, List<String> allowedOrigins) {
            this.analystKey = analystKey;
            this.adminKey = adminKey;
            this.allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!originAllowed(request.getHeader("Origin"), allowedOrigins)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Origin not allowed");
                return;
            }
            String header = request.getHeader("Authorization");
            String given = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : null;
            String role = sameKey(given, adminKey) ? ADMIN : sameKey(given, analystKey) ? ANALYST : null;
            HttpServletRequest next = request;
            if (role != null) {
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        role.toLowerCase(), null, AuthorityUtils.createAuthorityList("ROLE_" + role)));
                if (ADMIN.equals(role)) {
                    // admin also holds the analyst role: hasAnyRole(ANALYST, ADMIN) covers it
                } else if ("POST".equals(request.getMethod()) && request.getRequestURI().equals("/mcp")) {
                    byte[] body = request.getInputStream().readAllBytes();
                    next = new CachedBody(request, body);
                    if (calledTools(json, body).stream().anyMatch(ADMIN_TOOLS::contains)) {
                        response.sendError(HttpServletResponse.SC_FORBIDDEN, "This tool needs the admin role");
                        return;
                    }
                }
            }
            chain.doFilter(next, response);
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
