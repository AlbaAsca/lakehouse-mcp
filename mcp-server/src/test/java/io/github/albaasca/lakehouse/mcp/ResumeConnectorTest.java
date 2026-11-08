package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** RESUME_CONNECTOR against a simulated Kafka Connect: which calls it makes, and what it refuses. */
class ResumeConnectorTest {

    private HttpServer connect;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile String status;
    private volatile int resumeCode;

    @BeforeEach
    void start() throws IOException {
        status = "{\"name\":\"sink\",\"connector\":{\"state\":\"PAUSED\"},\"tasks\":[{\"id\":0,\"state\":\"PAUSED\"}]}";
        resumeCode = 202;
        connect = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        connect.createContext("/", exchange -> {
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            boolean isStatus = exchange.getRequestURI().getPath().endsWith("/status");
            int code = isStatus ? 200 : exchange.getRequestURI().getPath().endsWith("/resume") ? resumeCode : 204;
            byte[] body = (isStatus ? status : "").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, code == 204 ? -1 : body.length);
            if (code != 204) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        connect.start();
    }

    @AfterEach
    void stop() {
        connect.stop(0);
    }

    private ResumeConnector action() {
        return new ResumeConnector("http://127.0.0.1:" + connect.getAddress().getPort(), "sink", Duration.ofSeconds(5));
    }

    @Test
    void resumesAPausedConnectorWithoutRestartingHealthyTasks() throws Exception {
        action().apply(Map.of("connector", "sink"));

        assertThat(calls).containsExactly("GET /connectors/sink/status", "PUT /connectors/sink/resume");
    }

    @Test
    void restartsOnlyTheFailedTasks() throws Exception {
        status = "{\"connector\":{\"state\":\"RUNNING\"},\"tasks\":[{\"id\":0,\"state\":\"RUNNING\"},"
                + "{\"id\":1,\"state\":\"FAILED\",\"trace\":\"boom\"}]}";

        action().apply(Map.of("connector", "sink"));

        assertThat(calls).containsExactly("GET /connectors/sink/status", "PUT /connectors/sink/resume",
                "POST /connectors/sink/tasks/1/restart");
    }

    @Test
    void anErrorFromConnectFailsTheFix() {
        resumeCode = 500;

        assertThatThrownBy(() -> action().apply(Map.of("connector", "sink")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void onlyTheWatchedConnectorAndNothingElseIsAccepted() throws Exception {
        assertThat(action().validate(Map.of("connector", "sink"))).isEqualTo(Map.of("connector", "sink"));
        assertThatThrownBy(() -> action().validate(Map.of("connector", "other")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> action().validate(Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> action().validate(Map.of("connector", "sink", "url", "http://evil")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
    }
}
