package io.github.albaasca.lakehouse.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * RESUME_CONNECTOR: the sink consumes again. Resumes the connector if it is paused and restarts its FAILED tasks, both
 * through the REST API of Kafka Connect. Nothing is lost: the data waited in Kafka. Parameter: {@code connector}, which
 * has to be the one this server watches (the model cannot point the call at any other).
 */
final class ResumeConnector implements FixAction {

    private final String base;
    private final String allowed;
    private final Duration timeout;
    private final HttpClient http;

    ResumeConnector(String connectUrl, String allowedConnector, Duration timeout) {
        this.base = connectUrl.endsWith("/") ? connectUrl.substring(0, connectUrl.length() - 1) : connectUrl;
        this.allowed = allowedConnector;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Type type() {
        return Type.RESUME_CONNECTOR;
    }

    @Override
    public Map<String, Object> validate(Map<String, Object> params) throws SQLException {
        if (params == null || !params.keySet().equals(Set.of("connector"))) {
            throw new IllegalArgumentException("RESUME_CONNECTOR needs exactly the parameter connector, got "
                    + (params == null ? "none" : params.keySet()));
        }
        if (!(params.get("connector") instanceof String name) || !name.equals(allowed)) {
            throw new IllegalArgumentException("connector must be " + allowed + ", got " + params.get("connector"));
        }
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("connector", name);
        return canonical;
    }

    @Override
    public String expectedEffect(Map<String, Object> params) {
        return "The connector " + params.get("connector") + " is resumed if it is paused and its failed tasks are restarted."
                + " Messages waiting in Kafka are written to the table; nothing is lost or repeated.";
    }

    @Override
    public void apply(Map<String, Object> params) throws SQLException {
        String name = (String) params.get("connector");
        String path = "/connectors/" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        try {
            JsonNode status = JsonMapper.shared().readTree(send("GET", path + "/status", 200));
            send("PUT", path + "/resume", 202);
            for (JsonNode task : status.path("tasks")) {
                if ("FAILED".equals(task.path("state").asString(null))) {
                    send("POST", path + "/tasks/" + task.path("id").asInt() + "/restart", 204);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Kafka Connect did not answer: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while resuming " + name, e);
        }
    }

    /** Any 2xx is accepted next to the expected code (Connect answers 202 or 204 depending on the version). */
    private String send(String method, String path, int expected) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                        .method(method, HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(method + " " + path + " answered HTTP " + response.statusCode() + " (expected "
                    + expected + "): " + response.body());
        }
        return response.body();
    }
}
