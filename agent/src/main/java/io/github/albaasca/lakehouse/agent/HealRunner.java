package io.github.albaasca.lakehouse.agent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * The command line {@code heal}: the repair cycle with a person in the loop. The model detects, diagnoses and proposes
 * (its tools, analyst key) and stops. Then this program, not the model, shows each new proposal on the console and asks.
 * Only a "yes" from the console makes the program approve (admin key, {@link FixDesk}), apply and ask the model to
 * verify. The model never holds the admin key and has no tool to approve; the prompts tell it not to apply, and the
 * server would refuse it anyway.
 */
@Component
class HealRunner implements ApplicationRunner, ExitCodeGenerator {

    static final int WRONG_USAGE = 2;
    private static final int FAILED = 1;

    static final String CYCLE_PROMPT = """
            Run one repair cycle on the lakehouse.
            1. Detect: call pipeline_health, then data_quality_check on every table of list_tables.
            2. Diagnose: find the cause with the evidence the tools give you (describe_table for snapshots, run_query).
            3. Propose: for each problem you can fix with propose_fix, make one proposal, with the evidence as the reason.
            4. Stop. A person decides on the console. Never call apply_fix and never say a proposal is approved: you cannot
               approve anything.
            Finish with a short summary: what is wrong, what you proposed (ids) and the effect you expect. If nothing is
            wrong, say so and propose nothing.""";

    private static final Set<String> YES = Set.of("y", "yes", "s", "si", "sí");

    private final Agent agent;
    private final FixDesk desk;
    private final BufferedReader console;
    private int exitCode;

    @org.springframework.beans.factory.annotation.Autowired
    HealRunner(Agent agent, FixDesk desk) {
        this(agent, desk, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
    }

    HealRunner(Agent agent, FixDesk desk, BufferedReader console) {
        this.agent = agent;
        this.desk = desk;
        this.console = console;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> words = args.getNonOptionArgs();
        if (words.isEmpty() || !words.getFirst().equals("heal")) {
            return;
        }
        if (words.size() > 1) {
            System.err.println("Usage: heal");
            exitCode = WRONG_USAGE;
            return;
        }
        try {
            exitCode = cycle();
        } catch (RuntimeException | IOException e) {
            System.err.println("heal failed: " + e.getMessage());
            exitCode = FAILED;
        }
    }

    private int cycle() throws IOException {
        Set<Long> before = desk.pending().stream().map(FixDesk.Proposal::id).collect(Collectors.toSet());
        Agent.Answer found = agent.ask(CYCLE_PROMPT);
        (found.outcome() == Agent.Outcome.ANSWERED ? System.out : System.err).println(found.text());
        if (found.outcome() != Agent.Outcome.ANSWERED) {
            return found.outcome().exitCode;
        }
        List<FixDesk.Proposal> proposals = desk.pending().stream().filter(p -> !before.contains(p.id())).toList();
        if (proposals.isEmpty()) {
            System.out.println("\nNo new proposals: nothing to approve.");
            return 0;
        }
        for (FixDesk.Proposal p : proposals) {
            int code = decide(p);
            if (code != 0) {
                return code;
            }
        }
        return 0;
    }

    private int decide(FixDesk.Proposal p) throws IOException {
        System.out.printf("%nProposal %d: %s %s%n  Why: %s%n  Expected effect: %s%n", p.id(), p.action(), p.params(),
                p.reason(), p.expectedEffect());
        System.out.printf("Approve and apply proposal %d? [y/N] ", p.id());
        System.out.flush();
        String line = console.readLine();
        if (line == null || !YES.contains(line.strip().toLowerCase(Locale.ROOT))) {
            System.out.printf("Proposal %d not approved: nothing was changed.%n", p.id());
            return 0;
        }
        desk.approve(p.id(), "console:" + System.getProperty("user.name", "unknown"));
        desk.apply(p.id());
        System.out.printf("Proposal %d approved and applied. Verifying...%n", p.id());
        Agent.Answer verified = agent.ask("Proposal " + p.id() + " (" + p.action() + " " + p.params() + ") was approved by a "
                + "person and applied. Verify it: call data_quality_check on the affected table and say whether the problem "
                + "is gone, using only what the tools return. Do not propose or apply anything else.");
        (verified.outcome() == Agent.Outcome.ANSWERED ? System.out : System.err).println(verified.text());
        return verified.outcome().exitCode;
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
