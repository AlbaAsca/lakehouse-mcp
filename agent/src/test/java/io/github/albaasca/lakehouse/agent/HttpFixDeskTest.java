package io.github.albaasca.lakehouse.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The REST half of the desk against a small fake server: which key goes where. */
class HttpFixDeskTest {

    private HttpServer server;
    private final List<String> calls = new ArrayList<>();
    private HttpFixDesk desk;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fix-proposals", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + exchange.getRequestHeaders().getFirst("Authorization") + " " + body);
            boolean get = exchange.getRequestMethod().equals("GET");
            byte[] out = (get
                    ? "{\"proposals\":[{\"id\":7,\"status\":\"PROPOSED\",\"action\":\"ADD_COLUMN\",\"params\":{\"column\":\"x\"},"
                            + "\"reason\":\"r\",\"expectedEffect\":\"e\"},{\"id\":8,\"status\":\"APPLIED\"}]}"
                    : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(get ? 200 : 201, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        desk = new HttpFixDesk("http://127.0.0.1:" + server.getAddress().getPort(), "analyst-k", "admin-k");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void listsOnlyWhatIsWaitingWithTheAnalystKey() {
        List<FixDesk.Proposal> pending = desk.pending();

        assertThat(pending).extracting(FixDesk.Proposal::id).containsExactly(7L);
        assertThat(pending.getFirst().params()).contains("\"column\"");
        assertThat(calls).containsExactly("GET /fix-proposals Bearer analyst-k ");
    }

    @Test
    void approvesWithTheAdminKey() {
        desk.approve(7, "console:alba");

        assertThat(calls).containsExactly("POST /fix-proposals/7/approvals Bearer admin-k {\"approvedBy\":\"console:alba\"}");
    }

    @Test
    void withoutAnAdminKeyItDoesNotEvenTry() {
        HttpFixDesk keyless = new HttpFixDesk("http://127.0.0.1:" + server.getAddress().getPort(), "analyst-k", "");

        assertThatThrownBy(() -> keyless.approve(7, "x")).hasMessageContaining("MCP_ADMIN_KEY");
        assertThat(calls).isEmpty();
    }
}
