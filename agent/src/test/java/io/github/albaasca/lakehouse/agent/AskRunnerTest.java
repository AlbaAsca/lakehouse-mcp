package io.github.albaasca.lakehouse.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.genai.errors.ClientException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class AskRunnerTest {

    private final ScriptedChatModel model = new ScriptedChatModel();
    private final AskRunner runner = new AskRunner(new Agent(ChatClient.create(model)));

    private void run(String... args) {
        runner.run(new DefaultApplicationArguments(args));
    }

    @Test
    void anAnswerGoesToStdoutAndExitsZero(CapturedOutput output) {
        model.script(prompt -> ScriptedChatModel.says("42"));

        run("ask", "How", "many?");

        assertThat(model.prompts.getFirst().getUserMessage().getText()).isEqualTo("How many?");
        assertThat(output.getOut()).contains("42");
        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void quotaGoesToStderrWithItsOwnExitCode(CapturedOutput output) {
        model.script(prompt -> {
            throw new ClientException(429, "RESOURCE_EXHAUSTED", "Out of quota");
        });

        run("ask", "How many?");

        assertThat(output.getOut()).doesNotContain("Out of quota");
        assertThat(output.getErr()).contains("Out of quota");
        assertThat(runner.getExitCode()).isEqualTo(4);
    }

    @Test
    void anythingButAskAndAQuestionIsWrongUsageAndCallsNoModel(CapturedOutput output) {
        for (String[] args : new String[][] {{}, {"ask"}, {"ask", " "}, {"hello", "world"}}) {
            run(args);

            assertThat(runner.getExitCode()).as(String.join(" ", args)).isEqualTo(AskRunner.WRONG_USAGE);
        }
        assertThat(output.getErr()).contains("Usage: ask <question>");
        assertThat(model.prompts).isEmpty();
    }
}
