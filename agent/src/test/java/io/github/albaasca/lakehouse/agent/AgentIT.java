package io.github.albaasca.lakehouse.agent;

import static io.github.albaasca.lakehouse.agent.ScriptedChatModel.callsTool;
import static io.github.albaasca.lakehouse.agent.ScriptedChatModel.lastToolResponse;
import static io.github.albaasca.lakehouse.agent.ScriptedChatModel.says;
import static io.github.albaasca.lakehouse.agent.ScriptedChatModel.toolResponses;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import io.github.albaasca.lakehouse.testkit.SyntheticLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The whole agent, its real MCP client and the image of the real server over the real table: only the model is scripted.
 * What it must find is what the loader wrote, which never went through the server.
 */
@SpringBootTest(properties = {"spring.ai.model.chat=none", "AGENT_MAX_TOOL_CALLS=" + AgentIT.LIMIT})
class AgentIT {

    static final int LIMIT = 3;

    private static final String NS = "agent_it";
    private static final String COUNT = "{\"sql\":\"SELECT count(*) AS n FROM lh." + NS + ".events\"}";
    private static final int PORT = 8090;
    /** The same paths as McpServerImageIT: what the root .dockerignore lets through. */
    private static final List<String> CONTEXT = List.of("pom.xml", "producer/pom.xml", "mcp-server/pom.xml", "agent/pom.xml",
            "semantic/pom.xml", "evals/pom.xml","testkit/pom.xml", "maintenance/pom.xml", "mcp-server/src/main",
            "testkit/src/main", "infra/mcp-server");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static GenericContainer<?> server;
    private static long loadedRows;

    @TestConfiguration
    static class Model {
        @Bean
        ScriptedChatModel scriptedChatModel() {
            return new ScriptedChatModel();
        }
    }

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private Agent agent;

    @DynamicPropertySource
    static void theRealServer(DynamicPropertyRegistry registry) throws Exception {
        registry.add("MCP_SERVER_URL", startTheServer());
    }

    private static java.util.function.Supplier<Object> startTheServer() throws Exception {
        LakehouseStack stack = LakehouseStack.shared();
        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:")) {
            stack.attachDuckDb(writer);
            loadedRows = SyntheticLoader.load(writer, NS, "events");
        }
        Path root = LakehouseStack.repositoryRoot();
        ImageFromDockerfile image = new ImageFromDockerfile("lakehouse-testkit-mcp-server", false)
                .withDockerfilePath("infra/mcp-server/Dockerfile");
        CONTEXT.forEach(path -> image.withFileFromPath(path, root.resolve(path)));
        server = new GenericContainer<>(image)
                .withNetwork(stack.network())
                .withEnv(Map.of(
                        "MCP_SERVER_ADDRESS", "0.0.0.0",
                        "POLARIS_CLIENT_ID", LakehouseStack.CLIENT_ID,
                        "POLARIS_CLIENT_SECRET", LakehouseStack.CLIENT_SECRET,
                        "S3_ACCESS_KEY", LakehouseStack.S3_ACCESS_KEY,
                        "S3_SECRET_KEY", LakehouseStack.S3_SECRET_KEY,
                        "S3_REGION", LakehouseStack.S3_REGION))
                .withExposedPorts(PORT)
                .waitingFor(Wait.forHealthcheck().withStartupTimeout(Duration.ofMinutes(3)));
        server.start();
        String url = "http://" + server.getHost() + ":" + server.getMappedPort(PORT);
        return () -> url;
    }

    @AfterAll
    static void stopTheServer() {
        if (server != null) {
            server.stop();
        }
    }

    private ScriptedChatModel model() {
        return (ScriptedChatModel) chatModel;
    }

    /** The MCP callback hands the model the content of the result, whose text is the tool's JSON. */
    private static JsonNode toolJson(String response) {
        return JSON.readTree(JSON.readTree(response).get(0).get("text").asString());
    }

    @Test
    void theFigureComesFromTheRealQueryAndReachesTheAnswer() {
        model().script(prompt -> model().prompts.size() == 1
                ? callsTool("run_query", COUNT)
                : says("There are " + toolJson(lastToolResponse(prompt)).get("rows").get(0).get(0).asLong()
                        + " events.\nSource: run_query"));

        Agent.Answer answer = agent.ask("How many events are there?");

        assertThat(answer.outcome()).isEqualTo(Agent.Outcome.ANSWERED);
        assertThat(loadedRows).isPositive();
        assertThat(answer.text()).isEqualTo("There are " + loadedRows + " events.\nSource: run_query");
    }

    @Test
    void theModelIsOfferedTheServerToolsAndTheSystemPrompt() {
        model().script(prompt -> says("Nothing to look up"));

        agent.ask("Hello");

        var prompt = model().prompts.getFirst();
        assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactlyInAnyOrder("list_tables", "describe_table", "run_query", "time_travel");
        assertThat(prompt.getSystemMessage().getText()).contains("Never invent");
    }

    @Test
    void aQueryTheServerRejectsIsAnErrorTheModelReadsAndNothingChanges() {
        String drop = "{\"sql\":\"DROP TABLE lh." + NS + ".events\"}";
        model().script(prompt -> switch (model().prompts.size()) {
            case 1 -> callsTool("run_query", drop);
            case 2 -> callsTool("run_query", COUNT);
            default -> says(toolJson(lastToolResponse(prompt)).get("rows").get(0).get(0).asString());
        });

        Agent.Answer answer = agent.ask("Remove the table");

        String rejection = lastToolResponse(model().prompts.get(1));
        assertThat(rejection).contains("Error calling tool");
        assertThat(answer).isEqualTo(new Agent.Answer(Agent.Outcome.ANSWERED, Long.toString(loadedRows)));
    }

    @Test
    void aModelThatNeverEndsIsCutAtTheLimit() {
        model().script(prompt -> callsTool("run_query", COUNT));

        Agent.Answer answer = agent.ask("Loop forever");

        assertThat(answer.outcome()).isEqualTo(Agent.Outcome.TOOL_LIMIT);
        assertThat(answer.text()).contains("(" + LIMIT + ")").contains("AGENT_MAX_TOOL_CALLS");
        // One call to the model per tool result, plus the one whose request was turned down.
        assertThat(model().prompts).hasSize(LIMIT + 1);
        assertThat(toolResponses(model().prompts.getLast())).isEqualTo(LIMIT);
    }

    @Test
    void severalCallsAtOnceAlsoCountTowardsTheLimit() {
        model().script(prompt -> ScriptedChatModel.callsTools("run_query", COUNT, LIMIT + 1));

        Agent.Answer answer = agent.ask("All at once");

        assertThat(answer.outcome()).isEqualTo(Agent.Outcome.TOOL_LIMIT);
        assertThat(model().prompts).hasSize(1);
    }
}
