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

            assertThat(model).isInstanceOf(GoogleGenAiChatModel.class);
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

            assertThat(model).isInstanceOf(AnthropicChatModel.class);
            assertThat(model.getOptions().getModel()).isEqualTo("some-claude");
        }
    }

    @Test
    void geminiWithoutAKeyDoesNotStart() {
        assertThatThrownBy(() -> start(NO_MCP, "--GEMINI_API_KEY=").close())
                .hasStackTraceContaining("Provide 'api-key' for Gemini API");
    }
}
