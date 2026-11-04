package io.github.albaasca.lakehouse.agent;

import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;

/** Answers one question and exits: the exit code says how it went, see {@link Agent.Outcome}. */
@SpringBootApplication
public class AgentApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(AgentApplication.class, args)));
    }

    @Bean
    Agent agent(ChatClient.Builder builder, List<ToolCallbackProvider> tools,
            @Value("classpath:prompts/system.md") Resource systemPrompt) {
        return new Agent(builder.defaultSystem(systemPrompt)
                .defaultToolCallbacks(tools.toArray(ToolCallbackProvider[]::new))
                .build());
    }
}
