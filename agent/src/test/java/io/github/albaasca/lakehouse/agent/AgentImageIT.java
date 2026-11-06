package io.github.albaasca.lakehouse.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.startupcheck.StartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerStatus;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The image behind {@code make ask}: the real jar and its entrypoint, with a fake Anthropic API on the test's side.
 * No MCP server and no real key: nothing is spent.
 */
class AgentImageIT {

    /** What the root .dockerignore lets through for infra/agent. */
    private static final List<String> CONTEXT = List.of("pom.xml", "producer/pom.xml", "mcp-server/pom.xml", "agent/pom.xml",
            "semantic/pom.xml", "evals/pom.xml", "bench/pom.xml", "testkit/pom.xml", "maintenance/pom.xml", "agent/src/main",
            "testkit/src/main", "infra/agent");
    private static final String ANSWER = "There are 42 events.\nSource: run_query";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Has the JDK the single-file server needs, and every image build here already pulls it. */
    private static final String JDK = "maven:3.10.0-eclipse-temurin-25-noble";

    private static ImageFromDockerfile image;
    private static Network network;
    private static GenericContainer<?> fakeAnthropic;

    /**
     * The fake model runs in a container of its own on a shared network: Testcontainers.exposeHostPorts would be
     * simpler, but it changes every container the JVM starts afterwards, and AgentIT's stack then fails to come up.
     */
    @BeforeAll
    static void buildTheImageAndTheFakeModel() {
        Path root = LakehouseStack.repositoryRoot();
        image = new ImageFromDockerfile("lakehouse-testkit-agent", false).withDockerfilePath("infra/agent/Dockerfile");
        CONTEXT.forEach(path -> image.withFileFromPath(path, root.resolve(path)));

        network = Network.newNetwork();
        fakeAnthropic = new GenericContainer<>(JDK)
                .withNetwork(network)
                .withNetworkAliases("fake-anthropic")
                .withCopyFileToContainer(MountableFile.forClasspathResource("fake-anthropic/FakeAnthropic.java"),
                        "/fake/FakeAnthropic.java")
                .withCommand("java", "/fake/FakeAnthropic.java", ANSWER)
                .waitingFor(Wait.forLogMessage(".*fake-anthropic listening.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        fakeAnthropic.start();
    }

    @AfterAll
    static void stopTheFakeModel() {
        if (fakeAnthropic != null) {
            fakeAnthropic.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    private static List<String> requests() {
        return fakeAnthropic.getLogs(OutputFrame.OutputType.STDOUT).lines()
                .filter(line -> line.startsWith("REQUEST "))
                .map(line -> line.substring("REQUEST ".length()))
                .toList();
    }

    /** Runs the image to the end, whatever its exit code; OneShotStartupCheckStrategy would throw on anything but 0. */
    private static Run run(Map<String, String> env, String... command) {
        StartupCheckStrategy untilItStops = new StartupCheckStrategy() {
            @Override
            public StartupStatus checkStartupState(DockerClient client, String id) {
                return DockerStatus.isContainerStopped(getCurrentState(client, id))
                        ? StartupStatus.SUCCESSFUL : StartupStatus.NOT_YET_KNOWN;
            }
        }.withTimeout(Duration.ofMinutes(2));
        try (GenericContainer<?> container = new GenericContainer<>(image).withNetwork(network).withEnv(env)
                .withCommand(command)
                .withStartupCheckStrategy(untilItStops)) {
            container.start();
            long exit = DockerClientFactory.instance().client().inspectContainerCmd(container.getContainerId()).exec()
                    .getState().getExitCodeLong();
            return new Run((int) exit, container.getLogs(OutputFrame.OutputType.STDOUT),
                    container.getLogs(OutputFrame.OutputType.STDERR));
        }
    }

    private record Run(int exitCode, String stdout, String stderr) {}

    @Test
    void theQuestionReachesTheModelWholeAndItsAnswerComesOut() {
        String question = "How many \"view\" events didn't end in a purchase?  Twice spaced";

        Run run = run(Map.of(
                "AGENT_PROVIDER", "anthropic",
                "AGENT_MODEL", "fake-claude",
                "ANTHROPIC_API_KEY", "fake",
                "SPRING_AI_ANTHROPIC_BASE_URL", "http://fake-anthropic:8080",
                "SPRING_AI_MCP_CLIENT_ENABLED", "false"), "ask", question);

        assertThat(run.exitCode()).as(run.stderr()).isZero();
        assertThat(run.stdout().strip()).isEqualTo(ANSWER);
        List<String> requests = requests();
        assertThat(requests).hasSize(1);
        JsonNode message = JSON.readTree(requests.getFirst()).get("messages").get(0);
        JsonNode content = message.get("content");
        String sent = content.isString() ? content.asString() : content.get(0).get("text").asString();
        assertThat(sent).isEqualTo(question);
    }

    @Test
    void withoutTheKeyItSaysWhichOneIsMissing() {
        Run run = run(Map.of("GEMINI_API_KEY", ""), "ask", "Anything");

        assertThat(run.exitCode()).isEqualTo(5);
        assertThat(run.stderr()).contains("GEMINI_API_KEY is missing in .env").doesNotContain("Exception");
    }

    @Test
    void claudeWithoutAModelSaysSo() {
        Run run = run(Map.of("AGENT_PROVIDER", "anthropic", "ANTHROPIC_API_KEY", "fake"), "ask", "Anything");

        assertThat(run.exitCode()).isEqualTo(5);
        assertThat(run.stderr()).contains("AGENT_MODEL is missing");
    }
}
