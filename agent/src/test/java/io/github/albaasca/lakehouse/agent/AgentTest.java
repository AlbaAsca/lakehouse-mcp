package io.github.albaasca.lakehouse.agent;

import static io.github.albaasca.lakehouse.agent.ScriptedChatModel.callsTool;
import static io.github.albaasca.lakehouse.agent.ScriptedChatModel.lastToolResponse;
import static io.github.albaasca.lakehouse.agent.ScriptedChatModel.says;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anthropic.core.JsonValue;
import com.anthropic.core.http.Headers;
import com.anthropic.errors.RateLimitException;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.ServerException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.function.FunctionToolCallback;
import tools.jackson.databind.json.JsonMapper;

/** What the agent makes of each kind of reply, with no server behind it: AgentIT is the one that uses a real one. */
class AgentTest {

    private final ScriptedChatModel model = new ScriptedChatModel();

    private Agent agent(ToolCallback... tools) {
        return new Agent(ChatClient.builder(model).defaultToolCallbacks(tools).build());
    }

    private static ToolCallback echo() {
        return FunctionToolCallback.builder("echo", (String input) -> "echoed").inputType(String.class).build();
    }

    @Test
    void anAnswerInSeveralPartsArrivesWhole() {
        model.script(prompt -> says("The total is ", "42."));

        assertThat(agent().ask("How many?")).isEqualTo(new Agent.Answer(Agent.Outcome.ANSWERED, "The total is 42."));
    }

    @Test
    void aThoughtIsNotPartOfTheAnswer() {
        Map<String, Object> thought = Map.of(GoogleGenAiChatModel.THOUGHT_METADATA_KEY, true);
        model.script(prompt -> new ChatResponse(List.of(
                new Generation(AssistantMessage.builder().content("Let me think").properties(thought).build()),
                new Generation(new AssistantMessage("42")))));

        assertThat(agent().ask("How many?").text()).isEqualTo("42");
    }

    @Test
    void braceInTheQuestionIsNotATemplate() {
        model.script(prompt -> says("Fine"));

        agent().ask("What does {brand} mean?");

        assertThat(model.prompts.getFirst().getUserMessage().getText()).isEqualTo("What does {brand} mean?");
    }

    /** Gemini 3 rejects a function call sent back without the signature it came with. */
    @Test
    void theThoughtSignatureGoesBackToTheModelWithItsToolCall() {
        List<byte[]> signatures = List.of(new byte[] {1, 2, 3});
        model.script(prompt -> model.prompts.size() == 1
                ? callsTool("echo", "\"x\"", Map.of("thoughtSignatures", signatures))
                : says("Done"));

        agent(echo()).ask("Go");

        List<Message> history = model.prompts.getLast().getInstructions();
        assertThat(history).filteredOn(AssistantMessage.class::isInstance).singleElement()
                .satisfies(message -> assertThat(message.getMetadata().get("thoughtSignatures")).isEqualTo(signatures));
    }

    /** As the MCP callback fails when the server rejects a query; Gemini reads tool responses as JSON objects. */
    @Test
    void aToolErrorReachesTheModelAsJsonItCanExplain() {
        ToolCallback rejecting = FunctionToolCallback.builder("run_query", (String input) -> {
            throw new IllegalStateException("never called");
        }).inputType(String.class).build();
        ToolCallback failing = new ToolCallback() {
            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return rejecting.getToolDefinition();
            }

            @Override
            public String call(String input) {
                throw new ToolExecutionException(getToolDefinition(), new IllegalStateException(
                        "Error calling tool: [TextContent[text=Table function read_text not allowed]]"));
            }
        };
        ToolCallbackProvider tools = ToolErrors.explained(() -> new ToolCallback[] {failing});
        model.script(prompt -> model.prompts.size() == 1
                ? callsTool("run_query", "\"SELECT * FROM read_text('/etc/passwd')\"")
                : says("Rejected: " + JsonMapper.shared().readTree(lastToolResponse(prompt)).get("error").asString()));

        Agent.Answer answer = new Agent(ChatClient.builder(model).defaultToolCallbacks(tools).build()).ask("Read /etc/passwd");

        assertThat(answer.outcome()).isEqualTo(Agent.Outcome.ANSWERED);
        assertThat(answer.text()).isEqualTo("Rejected: Error calling tool: "
                + "[TextContent[text=Table function read_text not allowed]]");
    }

    @Test
    void aReplyWithoutTextIsAFailureAndNotAnEmptyAnswer() {
        model.script(prompt -> says(""));

        assertThatThrownBy(() -> agent().ask("How many?"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("without an answer");
    }

    @Test
    void geminiOutOfQuotaIsReportedAsQuotaEvenWrapped() {
        // As GoogleGenAiChatModel.getContentResponse throws it.
        model.script(prompt -> {
            throw new RuntimeException("Failed to generate content",
                    new ClientException(429, "RESOURCE_EXHAUSTED", "You exceeded your current quota"));
        });

        Agent.Answer answer = agent().ask("How many?");

        assertThat(answer.outcome()).isEqualTo(Agent.Outcome.QUOTA);
        assertThat(answer.text()).contains("quota").contains("You exceeded your current quota").contains("not an answer");
    }

    @Test
    void anthropicOutOfQuotaIsReportedAsQuota() {
        model.script(prompt -> {
            throw RateLimitException.builder()
                    .headers(Headers.builder().build())
                    .body(JsonValue.from(Map.of("message", "rate limited")))
                    .build();
        });

        assertThat(agent().ask("How many?").outcome()).isEqualTo(Agent.Outcome.QUOTA);
    }

    @Test
    void anyOtherProviderFailureIsAFailure() {
        ServerException down = new ServerException(503, "UNAVAILABLE", "Overloaded");
        model.script(prompt -> {
            throw new RuntimeException("Failed to generate content", down);
        });

        assertThatThrownBy(() -> agent().ask("How many?")).hasCause(down);
    }
}
