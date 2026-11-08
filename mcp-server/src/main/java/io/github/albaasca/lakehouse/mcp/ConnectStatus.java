package io.github.albaasca.lakehouse.mcp;

import io.github.albaasca.lakehouse.mcp.PipelineHealth.ConnectorState;
import io.github.albaasca.lakehouse.mcp.PipelineHealth.TaskState;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The state of one connector, from {@code GET /connectors/<name>/status} of the Kafka Connect REST API. */
final class ConnectStatus implements PipelineHealth.ConnectorSource {

    private final URI status;
    private final Duration timeout;
    private final HttpClient http;

    ConnectStatus(String connectUrl, String connector, Duration timeout) {
        String base = connectUrl.endsWith("/") ? connectUrl.substring(0, connectUrl.length() - 1) : connectUrl;
        this.status = URI.create(base + "/connectors/"
                + URLEncoder.encode(connector, StandardCharsets.UTF_8).replace("+", "%20") + "/status");
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public ConnectorState read() throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(status).timeout(timeout).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return new ConnectorState(false, null, List.of());
        }
        if (response.statusCode() != 200) {
            throw new IOException("it answered HTTP " + response.statusCode());
        }
        JsonNode body = JsonMapper.shared().readTree(response.body());
        List<TaskState> tasks = new ArrayList<>();
        body.path("tasks").forEach(task -> tasks.add(new TaskState(task.path("id").asInt(-1),
                task.path("state").asString(null), firstLine(task.path("trace").asString(null)))));
        return new ConnectorState(true, body.path("connector").path("state").asString(null), tasks);
    }

    /** A failed task carries its whole stack trace; its first line says what went wrong. */
    static String firstLine(String trace) {
        if (trace == null || trace.isBlank()) {
            return null;
        }
        String line = trace.strip().lines().findFirst().orElse("");
        return line.length() > 500 ? line.substring(0, 500) + "…" : line;
    }
}
