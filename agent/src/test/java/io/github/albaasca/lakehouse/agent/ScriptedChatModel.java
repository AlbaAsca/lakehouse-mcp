package io.github.albaasca.lakehouse.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** A model that follows a script, so no test needs a key. It keeps every prompt it was sent. */
final class ScriptedChatModel implements ChatModel {

    final List<Prompt> prompts = new ArrayList<>();
    private Function<Prompt, ChatResponse> script = prompt -> {
        throw new IllegalStateException("No script");
    };

    void script(Function<Prompt, ChatResponse> script) {
        this.prompts.clear();
        this.script = script;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        return script.apply(prompt);
    }

    /** The advisor runs the tool loop only with options like these, which is what every real model has. */
    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().build();
    }

    static ChatResponse says(String... parts) {
        return new ChatResponse(List.of(parts).stream()
                .map(part -> new Generation(new AssistantMessage(part)))
                .toList());
    }

    static ChatResponse callsTool(String tool, String arguments) {
        return callsTool(tool, arguments, Map.of());
    }

    static ChatResponse callsTool(String tool, String arguments, Map<String, Object> metadata) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .properties(metadata)
                .toolCalls(List.of(new AssistantMessage.ToolCall("id-1", "function", tool, arguments)))
                .build())));
    }

    static ChatResponse callsTools(String tool, String arguments, int times) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(IntStream.range(0, times)
                        .mapToObj(i -> new AssistantMessage.ToolCall("id-" + i, "function", tool, arguments))
                        .toList())
                .build())));
    }

    /** What the last tool answered, as the real model would read it. */
    static String lastToolResponse(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .reduce((earlier, later) -> later)
                .orElseThrow()
                .getResponses().getLast().responseData();
    }

    static long toolResponses(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(ToolResponseMessage.class::isInstance)
                .map(ToolResponseMessage.class::cast)
                .mapToLong(message -> message.getResponses().size())
                .sum();
    }
}
