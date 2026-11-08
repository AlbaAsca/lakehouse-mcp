package io.github.albaasca.lakehouse.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/** The real application.yaml choosing the real models. Fake keys and no MCP server: nothing is called. */
class ProviderConfigurationTest {

    private static final String NO_MCP = "--spring.ai.mcp.client.enabled=false";

    private static ConfigurableApplicationContext start(String... args) {
        return SpringApplication.run(AgentApplication.class, args);
    }

    @Test
    void geminiIsTheDefaultAndOnlyAsksForItsOwnKey() {
        try (var context = start(NO_MCP, "--GEMINI_API_KEY=fake", "--AGENT_MODEL=some-gemini", "--ANTHROPIC_API_KEY=")) {
            ChatModel model = context.getBean(ChatModel.class);

            assertThat(model).isInstanceOf(MeteredChatModel.class);
            assertThat(((MeteredChatModel) model).delegate()).isInstanceOf(GoogleGenAiChatModel.class);
            assertThat(model.getOptions().getModel()).isEqualTo("some-gemini");
        }
    }

    @Test
    void geminiHasADefaultModel() {
        try (var context = start(NO_MCP, "--GEMINI_API_KEY=fake")) {
            assertThat(context.getBean(ChatModel.class).getOptions().getModel()).startsWith("gemini-");
        }
    }

    @Test
    void anthropicIsChosenByConfigurationWithoutTheGeminiKey() {
        try (var context = start(NO_MCP, "--AGENT_PROVIDER=anthropic", "--ANTHROPIC_API_KEY=fake", "--GEMINI_API_KEY=",
                "--AGENT_MODEL=some-claude")) {
            ChatModel model = context.getBean(ChatModel.class);

            assertThat(((MeteredChatModel) model).delegate()).isInstanceOf(AnthropicChatModel.class);
            assertThat(model.getOptions().getModel()).isEqualTo("some-claude");
        }
    }

    /** A fake OpenAI-compatible service: records what arrives and answers with the given status and body. */
    private static final class FakeOpenAi implements AutoCloseable {
        final java.util.List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.List<String> auth = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.List<String> bodies = new java.util.concurrent.CopyOnWriteArrayList<>();
        final com.sun.net.httpserver.HttpServer server;

        FakeOpenAi(int status, String reply) throws java.io.IOException {
            server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                paths.add(exchange.getRequestURI().getPath());
                auth.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                byte[] bytes = reply.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        /** Like GitHub Models: the base URL has a path of its own and no /v1. */
        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/inference";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final String COMPLETION = """
            {"id":"c1","object":"chat.completion","created":1,"model":"some-model","choices":[{"index":0,
            "message":{"role":"assistant","content":"42 purchases"},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}""";

    @Test
    void openAiCompatibleUsesBaseUrlKeyAndModelFromConfiguration() throws Exception {
        try (var fake = new FakeOpenAi(200, COMPLETION);
                var context = start(NO_MCP, "--AGENT_PROVIDER=openai", "--AGENT_BASE_URL=" + fake.baseUrl(),
                        "--AGENT_API_KEY=fake-key", "--AGENT_MODEL=some-model", "--GEMINI_API_KEY=", "--ANTHROPIC_API_KEY=")) {
            ChatModel model = context.getBean(ChatModel.class);

            assertThat(model).isInstanceOf(MeteredChatModel.class);
            assertThat(((MeteredChatModel) model).delegate().getClass().getName()).contains("OpenAi");
            assertThat(model.getOptions().getModel()).isEqualTo("some-model");

            Agent.Answer answer = context.getBean(Agent.class).ask("How many?");

            assertThat(answer).isEqualTo(new Agent.Answer(Agent.Outcome.ANSWERED, "42 purchases"));
            assertThat(fake.paths).containsExactly("/inference/chat/completions");
            assertThat(fake.auth).containsExactly("Bearer fake-key");
            assertThat(fake.bodies.getFirst()).contains("\"model\":\"some-model\"");
            assertThat(context.getBean(UsageMeter.class)).isNotNull();
        }
    }

    @Test
    void openAiCompatibleOutOfQuotaIsReportedAsQuota() throws Exception {
        try (var fake = new FakeOpenAi(429, "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit\"}}");
                var context = start(NO_MCP, "--AGENT_PROVIDER=openai", "--AGENT_BASE_URL=" + fake.baseUrl(),
                        "--AGENT_API_KEY=fake-key", "--AGENT_MODEL=some-model", "--GEMINI_API_KEY=", "--ANTHROPIC_API_KEY=")) {
            Agent.Answer answer = context.getBean(Agent.class).ask("How many?");

            assertThat(answer.outcome()).isEqualTo(Agent.Outcome.QUOTA);
            assertThat(answer.text()).contains("not an answer");
        }
    }

    @Test
    void geminiWithoutAKeyDoesNotStart() {
        assertThatThrownBy(() -> start(NO_MCP, "--GEMINI_API_KEY=").close())
                .hasStackTraceContaining("Provide 'api-key' for Gemini API");
    }
}
