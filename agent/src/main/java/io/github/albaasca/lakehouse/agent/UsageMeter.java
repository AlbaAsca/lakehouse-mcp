package io.github.albaasca.lakehouse.agent;

import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.ai.chat.metadata.Usage;

/**
 * Counts what the model has used, per question and per run, and says when a limit is reached. A limit of 0 is no limit.
 *
 * <p>The cost comes from the usage the provider reports and a price per million tokens, which is configuration and
 * not looked up anywhere: with prices of 0 the budget in USD never stops anything.
 */
public class UsageMeter {

    /** Usage lines go to stderr: Spring's console log is stdout, where only the answer belongs. */
    private final Consumer<String> log;

    /** @param maxTokens per question, input and output together */
    public record Limits(long maxTokens, double maxUsdPerQuestion, double maxUsdPerRun,
            double usdPerMillionInput, double usdPerMillionOutput) {
        public static Limits none() {
            return new Limits(0, 0, 0, 0, 0);
        }
    }

    private final Limits limits;
    private long questionInput;
    private long questionOutput;
    private double runUsd;
    private int calls;

    public UsageMeter(Limits limits) {
        this(limits, line -> System.err.println("[usage] " + line));
    }

    UsageMeter(Limits limits, Consumer<String> log) {
        this.limits = limits;
        this.log = log;
    }

    public void startQuestion() {
        questionInput = 0;
        questionOutput = 0;
        calls = 0;
    }

    public void record(Usage usage) {
        long input = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        long output = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        questionInput += input;
        questionOutput += output;
        calls++;
        runUsd += usd(input, output);
        log.accept("model call " + calls + ": " + input + " input + " + output + " output tokens; question so far "
                + questionTokens() + " tokens, $" + format(questionUsd()) + "; run $" + format(runUsd));
    }

    public long questionTokens() {
        return questionInput + questionOutput;
    }

    public double questionUsd() {
        return usd(questionInput, questionOutput);
    }

    public double runUsd() {
        return runUsd;
    }

    /** Written once per question, after the last call, so the cost of each question can be read in the log. */
    public void logQuestion() {
        if (calls == 0) {
            return;
        }
        log.accept("question used " + questionTokens() + " tokens (" + questionInput + " input, " + questionOutput
                + " output) in " + calls + " model calls, $" + format(questionUsd()));
    }

    /** Why the next call to the model must not happen, if so. */
    public Optional<String> exceeded() {
        if (limits.maxTokens() > 0 && questionTokens() >= limits.maxTokens()) {
            return Optional.of("the question used " + questionTokens() + " tokens (limit " + limits.maxTokens()
                    + ", AGENT_MAX_TOKENS)");
        }
        if (limits.maxUsdPerQuestion() > 0 && questionUsd() >= limits.maxUsdPerQuestion()) {
            return Optional.of("the question cost $" + format(questionUsd()) + " (limit $"
                    + format(limits.maxUsdPerQuestion()) + ", AGENT_MAX_USD_PER_QUESTION)");
        }
        if (limits.maxUsdPerRun() > 0 && runUsd >= limits.maxUsdPerRun()) {
            return Optional.of("the run cost $" + format(runUsd) + " (limit $" + format(limits.maxUsdPerRun())
                    + ", AGENT_MAX_USD_PER_RUN)");
        }
        return Optional.empty();
    }

    private double usd(long input, long output) {
        return (input * limits.usdPerMillionInput() + output * limits.usdPerMillionOutput()) / 1_000_000d;
    }

    private static String format(double usd) {
        return String.format(java.util.Locale.ROOT, "%.6f", usd);
    }
}
