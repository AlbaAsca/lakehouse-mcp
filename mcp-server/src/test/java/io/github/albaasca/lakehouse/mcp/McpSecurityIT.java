package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.albaasca.lakehouse.mcp.CatalogReader.Snapshot;
import io.github.albaasca.lakehouse.mcp.FixProposalStore.Proposal;
import io.github.albaasca.lakehouse.mcp.FixProposalStore.Status;
import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import io.github.albaasca.lakehouse.testkit.SyntheticLoader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole application over real HTTP with both keys and the ops Postgres: no key is 401 (except health), the analyst
 * cannot approve or call apply_fix (403), the admin approves (201), and a foreign Origin is 403 even with a key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpSecurityIT {

    private static final String NS = "mcp_security_it";
    private static final String ANALYST = "Bearer analyst-sec-key";
    private static final String ADMIN = "Bearer admin-sec-key";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String INIT = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
            + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"it\",\"version\":\"1\"}}}";
    private static final String APPLY = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":"
            + "{\"name\":\"apply_fix\",\"arguments\":{\"proposalId\":1}}}";

    @LocalServerPort
    private int port;

    @Autowired
    private FixProposals proposals;

    @Autowired
    private CatalogReader reader;

    @DynamicPropertySource
    static void lakehouse(DynamicPropertyRegistry registry) {
        LakehouseStack stack = LakehouseStack.shared();
        registry.add("lakehouse.catalog.name", () -> LakehouseStack.CATALOG);
        registry.add("lakehouse.catalog.uri", stack::polarisUrl);
        registry.add("lakehouse.catalog.client-id", () -> LakehouseStack.CLIENT_ID);
        registry.add("lakehouse.catalog.client-secret", () -> LakehouseStack.CLIENT_SECRET);
        registry.add("lakehouse.storage.endpoint", stack::s3Endpoint);
        registry.add("lakehouse.storage.access-key", () -> LakehouseStack.S3_ACCESS_KEY);
        registry.add("lakehouse.storage.secret-key", () -> LakehouseStack.S3_SECRET_KEY);
        registry.add("lakehouse.storage.region", () -> LakehouseStack.S3_REGION);
        registry.add("lakehouse.ops.url", stack::opsJdbcUrl);
        registry.add("lakehouse.ops.user", stack::dbUser);
        registry.add("lakehouse.ops.password", stack::dbPassword);
        registry.add("lakehouse.security.analyst-key", () -> "analyst-sec-key");
        registry.add("lakehouse.security.admin-key", () -> "admin-sec-key");
    }

    @BeforeAll
    static void loadATable() throws Exception {
        LakehouseStack stack = LakehouseStack.shared();
        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:")) {
            stack.attachDuckDb(writer);
            SyntheticLoader.load(writer, NS, "events");
        }
    }

    private int send(String method, String path, String authorization, String origin, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    private Proposal newProposal() throws Exception {
        long oldest = reader.snapshots(NS, "events").stream().min(Comparator.comparingLong(Snapshot::sequenceNumber))
                .orElseThrow().snapshotId();
        return proposals.propose(FixAction.Type.ROLLBACK_SNAPSHOT,
                Map.of("namespace", NS, "table", "events", "snapshotId", oldest), "security it");
    }

    @Test
    void healthIsOpenAndEverythingElseNeedsAKey() throws Exception {
        assertThat(send("GET", "/actuator/health", null, null, null)).isEqualTo(200);
        assertThat(send("POST", "/mcp", null, null, INIT)).isEqualTo(401);
        assertThat(send("GET", "/fix-proposals", null, null, null)).isEqualTo(401);
        assertThat(send("POST", "/fix-proposals/1/approvals", null, null, "{\"approvedBy\":\"x\"}")).isEqualTo(401);
        assertThat(send("GET", "/fix-proposals", "Bearer wrong-key", null, null)).isEqualTo(401);
        assertThat(send("GET", "/fix-proposals", "Basic YWRtaW46YWRtaW4=", null, null)).isEqualTo(401);
    }

    @Test
    void theAnalystReadsButCannotApproveAndTheAdminCan() throws Exception {
        Proposal proposal = newProposal();
        String path = "/fix-proposals/" + proposal.id() + "/approvals";
        String body = "{\"approvedBy\":\"alba\"}";

        assertThat(send("GET", "/fix-proposals", ANALYST, null, null)).isEqualTo(200);
        assertThat(send("POST", path, ANALYST, null, body)).isEqualTo(403);
        assertThat(proposals.get(proposal.id()).status()).isEqualTo(Status.PROPOSED);

        assertThat(send("POST", path, ADMIN, null, body)).isEqualTo(201);
        Proposal approved = proposals.get(proposal.id());
        assertThat(approved.status()).isEqualTo(Status.APPROVED);
        assertThat(approved.approvedBy()).isEqualTo("alba");
    }

    @Test
    void applyFixOverMcpIsForbiddenToTheAnalystButNotToTheAdmin() throws Exception {
        assertThat(send("POST", "/mcp", ANALYST, null, INIT)).isEqualTo(200);
        assertThat(send("POST", "/mcp", ANALYST, null, APPLY)).isEqualTo(403);
        assertThat(send("POST", "/mcp", null, null, APPLY)).isEqualTo(401);
        // the admin gets past the role check: the proposal does not exist, so the tool answers, not the filter
        assertThat(send("POST", "/mcp", ADMIN, null, APPLY)).isNotIn(401, 403);
        // a batch hiding apply_fix among other calls is refused too
        assertThat(send("POST", "/mcp", ANALYST, null, "[" + INIT + "," + APPLY + "]")).isEqualTo(403);
    }

    @Test
    void aForeignOriginIsRefusedEvenWithTheAdminKey() throws Exception {
        assertThat(send("POST", "/mcp", ADMIN, "http://evil.example", INIT)).isEqualTo(403);
        assertThat(send("GET", "/fix-proposals", ADMIN, "http://evil.example", null)).isEqualTo(403);
        assertThat(send("GET", "/fix-proposals", ADMIN, "http://localhost:3000", null)).isEqualTo(200);
    }
}
