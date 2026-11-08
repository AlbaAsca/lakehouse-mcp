package io.github.albaasca.lakehouse.agent;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/** Counts the usage of every call and refuses the next one once a limit is reached, which ends the tool loop. */
final class MeteredChatModel implements ChatModel {

    /** @param reason what the limit was, for the person reading the answer */
    static final class BudgetExceededException extends RuntimeException {
        BudgetExceededException(String reason) {
            super(reason);
        }
    }

    private final ChatModel delegate;
    private final UsageMeter meter;

    MeteredChatModel(ChatModel delegate, UsageMeter meter) {
        this.delegate = delegate;
        this.meter = meter;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        meter.exceeded().ifPresent(reason -> {
            throw new BudgetExceededException(reason);
        });
        ChatResponse response = delegate.call(prompt);
        meter.record(response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage());
        return response;
    }

    ChatModel delegate() {
        return delegate;
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }
}
