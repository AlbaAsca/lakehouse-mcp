package io.github.albaasca.lakehouse.agent;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The server's REST resource {@code /fix-proposals} (list with the analyst key, approve with the admin key) and, to apply,
 * {@code apply_fix} over MCP with the admin key: the server refuses that tool to the analyst. Nothing here is a tool of the
 * model.
 */
class HttpFixDesk implements FixDesk {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String baseUrl;
    private final String analystKey;
    private final String adminKey;
    private final HttpClient http = HttpClient.newHttpClient();

    HttpFixDesk(String baseUrl, String analystKey, String adminKey) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.analystKey = analystKey;
        this.adminKey = adminKey;
    }

    @Override
    public List<Proposal> pending() {
        JsonNode body = send(HttpRequest.newBuilder(URI.create(baseUrl + "/fix-proposals")).GET(), analystKey, 200);
        List<Proposal> out = new ArrayList<>();
        for (JsonNode p : body.path("proposals")) {
            if ("PROPOSED".equals(p.path("status").asString(""))) {
                out.add(new Proposal(p.path("id").asLong(), "PROPOSED", p.path("action").asString(""),
                        p.path("params").toString(), p.path("reason").asString(""), p.path("expectedEffect").asString("")));
            }
        }
        return out;
    }

    @Override
    public void approve(long id, String approvedBy) {
        requireAdminKey();
        String json = JSON.writeValueAsString(Map.of("approvedBy", approvedBy));
        send(HttpRequest.newBuilder(URI.create(baseUrl + "/fix-proposals/" + id + "/approvals"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)), adminKey, 201);
    }

    @Override
    public void apply(long id) {
        requireAdminKey();
        McpSyncClient client = McpClient.sync(HttpClientStreamableHttpTransport.builder(baseUrl)
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + adminKey)).build()).build();
        try {
            client.initialize();
            CallToolResult result = client.callTool(new CallToolRequest("apply_fix", Map.of("proposalId", id)));
            if (Boolean.TRUE.equals(result.isError())) {
                String text = result.content().isEmpty() ? "" : ((TextContent) result.content().getFirst()).text();
                throw new IllegalStateException("apply_fix failed: " + text);
            }
        } finally {
            client.close();
        }
    }

    private void requireAdminKey() {
        if (adminKey == null || adminKey.isBlank()) {
            throw new IllegalStateException("MCP_ADMIN_KEY is not set: only a person with the admin key can approve");
        }
    }

    private JsonNode send(HttpRequest.Builder request, String key, int expected) {
        if (key != null && !key.isBlank()) {
            request.header("Authorization", "Bearer " + key);
        }
        try {
            HttpResponse<String> response = http.send(request.header("Accept", "application/json").build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != expected) {
                throw new IllegalStateException("The server answered " + response.statusCode() + ": " + response.body());
            }
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot reach the server at " + baseUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
