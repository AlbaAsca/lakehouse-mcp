package io.github.albaasca.lakehouse.agent;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/** The command line: {@code ask <question>}. The answer goes to stdout; anything that is not one, to stderr. */
@Component
class AskRunner implements ApplicationRunner, ExitCodeGenerator {

    static final int WRONG_USAGE = 2;

    private final Agent agent;
    private int exitCode;

    AskRunner(Agent agent) {
        this.agent = agent;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> words = args.getNonOptionArgs();
        if (!words.isEmpty() && words.getFirst().equals("heal")) {
            return; // HealRunner's command
        }
        String question = words.isEmpty() ? "" : String.join(" ", words.subList(1, words.size())).strip();
        if (words.isEmpty() || !words.getFirst().equals("ask") || question.isEmpty()) {
            System.err.println("Usage: ask <question>");
            exitCode = WRONG_USAGE;
            return;
        }
        Agent.Answer answer = agent.ask(question);
        (answer.outcome() == Agent.Outcome.ANSWERED ? System.out : System.err).println(answer.text());
        exitCode = answer.outcome().exitCode;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
