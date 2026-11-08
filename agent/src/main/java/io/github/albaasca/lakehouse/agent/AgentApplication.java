package io.github.albaasca.lakehouse.agent;

import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
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

    /** 0 is no limit; the prices are per million tokens and left at 0 until set, as they depend on the model. */
    @Bean
    UsageMeter usageMeter(@Value("${agent.limits.max-tokens:0}") long maxTokens,
            @Value("${agent.limits.max-usd-per-question:0}") double maxUsdPerQuestion,
            @Value("${agent.limits.max-usd-per-run:0}") double maxUsdPerRun,
            @Value("${agent.limits.usd-per-million-input:0}") double input,
            @Value("${agent.limits.usd-per-million-output:0}") double output) {
        return new UsageMeter(new UsageMeter.Limits(maxTokens, maxUsdPerQuestion, maxUsdPerRun, input, output));
    }

    /** Wraps the provider's model where it is created, so the auto-configured chat client keeps all its settings. */
    @Bean
    static BeanPostProcessor meteredModels(ObjectProvider<UsageMeter> meter) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String name) {
                return bean instanceof ChatModel model && !(bean instanceof MeteredChatModel)
                        ? new MeteredChatModel(model, meter.getObject()) : bean;
            }
        };
    }

    /** The server asks for a Bearer key on every call; the agent is an analyst. Without a key, no header. */
    @Bean
    McpSyncHttpClientRequestCustomizer analystKey(@Value("${agent.mcp.analyst-key:}") String key) {
        return (builder, method, endpoint, body, context) -> {
            if (!key.isBlank()) {
                builder.setHeader("Authorization", "Bearer " + key);
            }
        };
    }

    /** The admin key is for this program only (approve, apply); the model's session uses the analyst key. */
    @Bean
    FixDesk fixDesk(@Value("${spring.ai.mcp.client.streamable-http.connections.lakehouse.url}") String url,
            @Value("${agent.mcp.analyst-key:}") String analystKey, @Value("${agent.mcp.admin-key:}") String adminKey) {
        return new HttpFixDesk(url, analystKey, adminKey);
    }

    @Bean
    Agent agent(ChatClient.Builder builder, UsageMeter meter, List<ToolCallbackProvider> tools,
            @Value("classpath:prompts/system.md") Resource systemPrompt,
            @Value("${agent.model-timeout:300s}") java.time.Duration timeout) {
        return new Agent(builder.defaultSystem(systemPrompt)
                .defaultToolCallbacks(tools.stream().map(ToolErrors::explained).toArray(ToolCallbackProvider[]::new))
                .build(), meter, timeout);
    }
}
