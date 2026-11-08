package io.github.albaasca.lakehouse.agent;

import static io.github.albaasca.lakehouse.agent.ScriptedChatModel.callsTool;
import static io.github.albaasca.lakehouse.agent.ScriptedChatModel.says;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The repair cycle with a simulated model and a simulated server: who may approve, and what the model is ever sent. */
@ExtendWith(OutputCaptureExtension.class)
class HealRunnerTest {

    private static final String ADMIN_KEY = "admin-secret-key-xyz";

    record Args(Long proposalId, String reason) {}

    /** The server side: a proposal exists after propose_fix, and apply_fix refuses it until the desk approves. */
    static final class FakeServer implements FixDesk {
        String status; // null until proposed
        final String adminKey = ADMIN_KEY;
        String approvedBy;

        ToolCallback tool(String name, java.util.function.Function<Args, String> body) {
            return FunctionToolCallback.builder(name, body).inputType(Args.class).build();
        }

        List<ToolCallback> tools() {
            return List.of(
                    tool("pipeline_health", a -> "{\"status\":\"OK\"}"),
                    tool("data_quality_check", a -> "{\"duplicates\":" + ("APPLIED".equals(status) ? 0 : 5) + "}"),
                    tool("propose_fix", a -> {
                        status = "PROPOSED";
                        return "{\"id\":1,\"status\":\"PROPOSED\"}";
                    }),
                    tool("apply_fix", a -> "APPROVED".equals(status) ? applyNow() : "error: proposal 1 is not approved"));
        }

        private String applyNow() {
            status = "APPLIED";
            return "{\"id\":1,\"status\":\"APPLIED\"}";
        }

        @Override
        public List<Proposal> pending() {
            return "PROPOSED".equals(status)
                    ? List.of(new Proposal(1, "PROPOSED", "DEDUPLICATE_PARTITION", "{\"day\":\"2019-10-01\"}",
                            "5 exact copies", "5 rows fewer"))
                    : List.of();
        }

        @Override
        public void approve(long id, String by) {
            status = "APPROVED";
            approvedBy = by;
        }

        @Override
        public void apply(long id) {
            applyNow();
        }
    }

    private final ScriptedChatModel model = new ScriptedChatModel();
    private final FakeServer server = new FakeServer();
    private final List<String> toolReplies = new ArrayList<>();

    private HealRunner runner(String consoleInput) {
        model.script(prompt -> {
            boolean verify = prompt.getUserMessage().getText().contains("was approved");
            long done = ScriptedChatModel.toolResponses(prompt);
            if (done > 0) {
                toolReplies.add(ScriptedChatModel.lastToolResponse(prompt));
            }
            if (verify) {
                return done == 0 ? callsTool("data_quality_check", "{}") : says("Fixed: no duplicates left.");
            }
            return switch ((int) done) {
                case 0 -> callsTool("pipeline_health", "{}");
                case 1 -> callsTool("propose_fix", "{\"reason\":\"5 exact copies\"}");
                case 2 -> callsTool("apply_fix", "{\"proposalId\":1}"); // tries to apply what nobody approved
                default -> says("Proposed 1: remove 5 copies.");
            };
        });
        Agent agent = new Agent(ChatClient.builder(model).defaultToolCallbacks(server.tools().toArray(ToolCallback[]::new))
                .build());
        return new HealRunner(agent, server, new BufferedReader(new StringReader(consoleInput)));
    }

    private static void heal(HealRunner runner) {
        runner.run(new DefaultApplicationArguments("heal"));
    }

    @Test
    void ifThePersonSaysNoNothingChangesAndTheModelsApplyWasRefused(CapturedOutput output) {
        HealRunner runner = runner("n\n");

        heal(runner);

        assertThat(toolReplies).anyMatch(reply -> reply.contains("error: proposal 1 is not approved"));
        assertThat(server.status).isEqualTo("PROPOSED");
        assertThat(server.approvedBy).isNull();
        assertThat(output.getOut()).contains("Approve and apply proposal 1? [y/N]").contains("not approved: nothing was changed");
        assertThat(model.prompts).noneMatch(p -> p.getUserMessage().getText().contains("was approved"));
        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void noAnswerOnTheConsoleCountsAsNo() {
        heal(runner(""));

        assertThat(server.status).isEqualTo("PROPOSED");
    }

    @Test
    void ifThePersonSaysYesItIsApprovedAppliedAndVerified(CapturedOutput output) {
        HealRunner runner = runner("y\n");

        heal(runner);

        assertThat(server.status).isEqualTo("APPLIED");
        assertThat(server.approvedBy).startsWith("console:");
        assertThat(toolReplies).anyMatch(reply -> reply.contains("duplicates\":0"));
        assertThat(output.getOut()).contains("approved and applied").contains("Fixed: no duplicates left.");
        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void theAdminKeyNeverReachesTheModel() {
        heal(runner("y\n"));

        assertThat(model.prompts).isNotEmpty();
        for (Prompt prompt : model.prompts) {
            assertThat(prompt.toString()).doesNotContain(ADMIN_KEY);
            for (Message message : prompt.getInstructions()) {
                assertThat(String.valueOf(message.getText())).doesNotContain(ADMIN_KEY);
                assertThat(message.toString()).doesNotContain(ADMIN_KEY);
            }
        }
        assertThat(HealRunner.CYCLE_PROMPT).doesNotContain(ADMIN_KEY).contains("Never call apply_fix");
    }

    @Test
    void otherCommandsAreNotHealsBusiness() {
        HealRunner runner = runner("y\n");

        runner.run(new DefaultApplicationArguments("ask", "hi"));

        assertThat(model.prompts).isEmpty();
        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void healTakesNoArguments(CapturedOutput output) {
        HealRunner runner = runner("y\n");

        runner.run(new DefaultApplicationArguments("heal", "now"));

        assertThat(model.prompts).isEmpty();
        assertThat(runner.getExitCode()).isEqualTo(HealRunner.WRONG_USAGE);
        assertThat(output.getErr()).contains("Usage: heal");
    }
}
