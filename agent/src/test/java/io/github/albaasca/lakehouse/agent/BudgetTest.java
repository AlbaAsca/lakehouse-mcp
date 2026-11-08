package io.github.albaasca.lakehouse.agent;

import static io.github.albaasca.lakehouse.agent.ScriptedChatModel.callsTool;
import static io.github.albaasca.lakehouse.agent.ScriptedChatModel.says;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

/** A model that never stops calling tools is cut at the exact limit, says why, and leaves its usage in the log. */
class BudgetTest {

    private final ScriptedChatModel model = new ScriptedChatModel();
    private final List<String> log = new ArrayList<>();

    private Agent agent(UsageMeter.Limits limits) {
        UsageMeter meter = new UsageMeter(limits, log::add);
        ToolCallback echo = FunctionToolCallback.builder("echo", (String input) -> "echoed").inputType(String.class).build();
        return new Agent(ChatClient.builder(new MeteredChatModel(model, meter)).defaultToolCallbacks(echo).build(), meter);
    }

    /** 60 input + 40 output tokens per call. */
    private static ChatResponse withUsage(ChatResponse response) {
        return ChatResponse.builder().from(response)
                .metadata(ChatResponseMetadata.builder().usage(new DefaultUsage(60, 40)).build()).build();
    }

    private void neverEnds() {
        model.script(prompt -> withUsage(callsTool("echo", "\"x\"")));
    }

    @Test
    void tokenLimitStopsTheLoopAfterTheCallThatReachedIt() {
        neverEnds();

        Agent.Answer answer = agent(new UsageMeter.Limits(250, 0, 0, 0, 0)).ask("Loop");

        assertThat(answer.outcome()).isEqualTo(Agent.Outcome.BUDGET);
        assertThat(answer.text()).contains("300 tokens").contains("limit 250").contains("AGENT_MAX_TOKENS");
        assertThat(model.prompts).hasSize(3);
        assertThat(log).last().asString().contains("300 tokens").contains("3 model calls");
    }

    @Test
    void costLimitUsesTheConfiguredPrices() {
        neverEnds();
        // 60 * 10 + 40 * 20 = 1400 per million -> $0.0014 a call; two calls pass $0.002.
        Agent.Answer answer = agent(new UsageMeter.Limits(0, 0.002, 0, 10, 20)).ask("Loop");

        assertThat(answer.outcome()).isEqualTo(Agent.Outcome.BUDGET);
        assertThat(answer.text()).contains("$0.002800").contains("AGENT_MAX_USD_PER_QUESTION");
        assertThat(model.prompts).hasSize(2);
        assertThat(log).last().asString().contains("$0.002800");
    }

    @Test
    void runLimitSpansQuestionsButTokenLimitDoesNot() {
        model.script(prompt -> withUsage(says("42")));
        Agent agent = agent(new UsageMeter.Limits(150, 0, 0.002, 10, 20));

        assertThat(agent.ask("One").outcome()).isEqualTo(Agent.Outcome.ANSWERED);
        assertThat(agent.ask("Two").outcome()).isEqualTo(Agent.Outcome.ANSWERED);
        Agent.Answer third = agent.ask("Three");

        assertThat(third.outcome()).isEqualTo(Agent.Outcome.BUDGET);
        assertThat(third.text()).contains("AGENT_MAX_USD_PER_RUN");
    }

    @Test
    void noLimitsNeverStopsAnAnswer() {
        model.script(prompt -> withUsage(says("42")));

        assertThat(agent(UsageMeter.Limits.none()).ask("One").outcome()).isEqualTo(Agent.Outcome.ANSWERED);
    }
}
