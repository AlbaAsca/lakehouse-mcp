package io.github.albaasca.lakehouse.agent;

import com.anthropic.errors.RateLimitException;
import com.google.genai.errors.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.model.tool.ToolCallLimitExceededException;
import org.springframework.util.StringUtils;

/**
 * One question, one answer. The tool loop is the {@code ToolCallingAdvisor} that {@link ChatClient} registers, and its
 * limit is {@code spring.ai.tools.limits.max-total-tool-calls} (AGENT_MAX_TOOL_CALLS in application.yaml).
 */
public class Agent {

    private static final int TOO_MANY_REQUESTS = 429;

    /** @param exitCode what the process returns; 1 stays for failures and 2 for a wrong command line */
    public enum Outcome {
        ANSWERED(0), TOOL_LIMIT(3), QUOTA(4), BUDGET(5), TIMEOUT(6);

        final int exitCode;

        Outcome(int exitCode) {
            this.exitCode = exitCode;
        }
    }

    public record Answer(Outcome outcome, String text) {}

    private final ChatClient chat;
    private final UsageMeter meter;
    private final Duration timeout;

    public Agent(ChatClient chat) {
        this(chat, new UsageMeter(UsageMeter.Limits.none()));
    }

    /** @param meter the one the chat's model reports to, see {@link MeteredChatModel} */
    public Agent(ChatClient chat, UsageMeter meter) {
        this(chat, meter, Duration.ZERO);
    }

    /**
     * @param timeout longest a whole question may take (model and tools); zero or negative means no limit. It is
     *                provider-neutral: the Gemini and Anthropic clients each have their own HTTP options, but none
     *                bounds the whole tool loop.
     */
    public Agent(ChatClient chat, UsageMeter meter, Duration timeout) {
        this.chat = chat;
        this.meter = meter;
        this.timeout = timeout;
    }

    /** @throws RuntimeException if the provider fails for any reason but quota, or the model ends without saying anything */
    public Answer ask(String question) {
        meter.startQuestion();
        try {
            return answer(question);
        } finally {
            meter.logQuestion();
        }
    }

    private Answer answer(String question) {
        ChatResponse response;
        try {
            response = call(question);
        } catch (TimeoutException e) {
            return new Answer(Outcome.TIMEOUT, "Stopped before reaching an answer: the model did not finish within "
                    + Math.max(1, Math.round(timeout.toMillis() / 1000.0)) + " s. Try again, or raise AGENT_MODEL_TIMEOUT.");
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
                if (t instanceof MeteredChatModel.BudgetExceededException budget) {
                    return new Answer(Outcome.BUDGET, "Stopped before reaching an answer: " + budget.getMessage()
                            + ". Ask something narrower or raise the limit.");
                }
            }
            return quotaMessage(e)
                    .map(message -> new Answer(Outcome.QUOTA, "The model provider's quota has run out, so this is not an "
                            + "answer about the data. Try again later or change AGENT_MODEL. The provider says: " + message))
                    .orElseThrow(() -> e);
        }
        if (response == null || response.getResults().isEmpty()) {
            throw new IllegalStateException("The model returned no answer");
        }
        Generation first = response.getResults().getFirst();
        String finishReason = first.getMetadata().getFinishReason();
        if (ToolCallLimitExceededException.FINISH_REASON.equals(finishReason)) {
            return new Answer(Outcome.TOOL_LIMIT, "Stopped before reaching an answer: " + first.getOutput().getText()
                    + " Ask something narrower or raise AGENT_MAX_TOOL_CALLS.");
        }
        String text = text(response.getResults());
        if (!StringUtils.hasText(text)) {
            throw new IllegalStateException("The model ended without an answer (finish reason: " + finishReason + ")");
        }
        return new Answer(Outcome.ANSWERED, text);
    }

    private ChatResponse call(String question) throws TimeoutException {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return chat.prompt().user(question).call().chatResponse();
        }
        CompletableFuture<ChatResponse> future = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                future.complete(chat.prompt().user(question).call().chatResponse());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, "agent-question");
        worker.setDaemon(true);
        worker.start();
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            worker.interrupt();
            throw new TimeoutException();
        } catch (InterruptedException e) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the model", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            if (e.getCause() instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    private static final class TimeoutException extends Exception {
        TimeoutException() {
            super(null, null, false, false);
        }
    }

    /** Gemini gives one generation per part of its reply, so the first one alone can be half of the answer, or a thought. */
    private static String text(List<Generation> generations) {
        return generations.stream()
                .filter(generation -> !Boolean.TRUE.equals(
                        generation.getOutput().getMetadata().get(GoogleGenAiChatModel.THOUGHT_METADATA_KEY)))
                .map(generation -> generation.getOutput().getText())
                .filter(StringUtils::hasText)
                .collect(Collectors.joining())
                .strip();
    }

    /** The cause is what counts: Spring AI wraps what the Gemini client throws in a plain RuntimeException. */
    private static Optional<String> quotaMessage(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ApiException gemini && gemini.code() == TOO_MANY_REQUESTS) {
                return Optional.of(gemini.message());
            }
            if (t instanceof RateLimitException anthropic) {
                return Optional.of(anthropic.getMessage());
            }
        }
        return Optional.empty();
    }
}
