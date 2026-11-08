package io.github.albaasca.lakehouse.mcp;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Append-only record of who called what: no update, no delete. */
interface AuditLog {

    /** Nothing is kept (no ops database). */
    AuditLog NONE = new AuditLog() {
        @Override
        public void record(Event event) {}

        @Override
        public List<Event> page(int page, int size) {
            return List.of();
        }

        @Override
        public long count() {
            return 0;
        }
    };

    /**
     * @param params sanitized JSON of the arguments (never a key or a user id in clear)
     * @param outcome OK, ERROR, FORBIDDEN (role) or REJECTED (unknown argument)
     * @param proposalId the proposal the call is about, or null
     */
    record Event(long id, Instant ts, String role, String tool, String params, String outcome, long durationMs,
            Long proposalId) {

        static Event of(Instant ts, String role, String tool, String params, String outcome, long durationMs,
                Long proposalId) {
            return new Event(0, ts, role, tool, params, outcome, durationMs, proposalId);
        }
    }

    void record(Event event);

    /** Newest first. */
    List<Event> page(int page, int size);

    long count();

    String MASK = "***";
    Pattern SECRET_NAME = Pattern.compile("(?i).*(key|secret|token|password|passwd|authorization|credential|user_?id).*");
    int MAX_TEXT = 200;

    /** Arguments as JSON with secret-looking names masked, long strings cut and any given secret value hidden. */
    static String sanitize(JsonMapper json, JsonNode arguments, List<String> secrets) {
        if (arguments == null || arguments.isMissingNode() || arguments.isNull()) {
            return "{}";
        }
        return json.writeValueAsString(clean(json, arguments, secrets));
    }

    private static JsonNode clean(JsonMapper json, JsonNode node, List<String> secrets) {
        if (node.isObject()) {
            ObjectNode out = json.createObjectNode();
            for (String name : node.propertyNames()) {
                out.set(name, SECRET_NAME.matcher(name).matches() ? json.<JsonNode>valueToTree(MASK)
                        : clean(json, node.get(name), secrets));
            }
            return out;
        }
        if (node.isArray()) {
            var out = json.createArrayNode();
            node.forEach(item -> out.add(clean(json, item, secrets)));
            return out;
        }
        if (node.isString()) {
            String text = node.asString();
            for (String secret : secrets) {
                if (secret != null && !secret.isBlank() && text.toLowerCase(Locale.ROOT).contains(secret.toLowerCase(Locale.ROOT))) {
                    return json.<JsonNode>valueToTree(MASK);
                }
            }
            return text.length() > MAX_TEXT ? json.<JsonNode>valueToTree(text.substring(0, MAX_TEXT) + "…") : node;
        }
        return node;
    }
}
