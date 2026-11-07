package io.github.albaasca.lakehouse.agent;

import static io.github.albaasca.lakehouse.agent.ScriptedChatModel.callsTool;
import static io.github.albaasca.lakehouse.agent.ScriptedChatModel.lastToolResponse;
import static io.github.albaasca.lakehouse.agent.ScriptedChatModel.says;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Why did purchases fall?": the guide in the system prompt names the steps in order, and a model that follows it, over
 * a small sample with a planted drop (day and brand), ends up naming that day and that brand.
 */
class InvestigationGuideTest {

    private static final String DROP_DAY = "2024-03-10";
    private static final String REFERENCE_DAY = "2024-03-09";
    private static final String PLANTED_BRAND = "acme";
    /** purchases per brand, per day; only acme falls on the drop day. */
    private static final Map<String, Map<String, Integer>> PURCHASES = Map.of(
            REFERENCE_DAY, Map.of("acme", 100, "globex", 50, "initech", 30),
            DROP_DAY, Map.of("acme", 20, "globex", 49, "initech", 31));
    private static final List<String> ORDER = List.of("pipeline_health", "data_quality_check", "get_metric", "run_query");

    private final ScriptedChatModel model = new ScriptedChatModel();
    private final List<String> calls = new ArrayList<>();

    private ToolCallback tool(String name, String answer) {
        return FunctionToolCallback.builder(name, (String input) -> {
            calls.add(name);
            return answer;
        }).inputType(String.class).build();
    }

    private static String brandRows() {
        List<String> rows = new ArrayList<>();
        new TreeMap<>(PURCHASES).forEach((day, brands) -> new TreeMap<>(brands)
                .forEach((brand, n) -> rows.add("[\"" + day + "\",\"" + brand + "\"," + n + "]")));
        return "{\"columns\":[\"day\",\"brand\",\"purchases\"],\"rows\":[" + String.join(",", rows) + "],\"truncated\":false}";
    }

    private static int total(String day) {
        return PURCHASES.get(day).values().stream().mapToInt(Integer::intValue).sum();
    }

    @Test
    void theGuideNamesTheStepsInOrder() throws IOException {
        String prompt = new ClassPathResource("prompts/system.md").getContentAsString(StandardCharsets.UTF_8);

        int[] at = ORDER.stream().mapToInt(name -> prompt.indexOf(name, prompt.indexOf("why something dropped"))).toArray();

        assertThat(at).isSorted().doesNotContain(-1);
        assertThat(prompt).contains("Facts").contains("Conjecture");
    }

    @Test
    void aModelThatFollowsTheGuideFindsThePlantedDayAndBrand() {
        List<ToolCallback> tools = List.of(
                tool("pipeline_health", "{\"status\":\"OK\"}"),
                tool("data_quality_check", "{\"status\":\"OK\",\"issues\":[]}"),
                tool("get_metric", "{\"columns\":[\"day\",\"purchases\"],\"rows\":[[\"" + REFERENCE_DAY + "\","
                        + total(REFERENCE_DAY) + "],[\"" + DROP_DAY + "\"," + total(DROP_DAY) + "]]}"),
                tool("run_query", brandRows()));
        model.script(prompt -> {
            int step = model.prompts.size();
            if (step <= ORDER.size()) {
                return callsTool(ORDER.get(step - 1), "\"x\"");
            }
            JsonNode rows = JsonMapper.shared().readTree(lastToolResponse(prompt)).get("rows");
            String brand = null;
            int fall = 0;
            for (JsonNode reference : rows) {
                if (!REFERENCE_DAY.equals(reference.get(0).asString())) {
                    continue;
                }
                for (JsonNode day : rows) {
                    if (DROP_DAY.equals(day.get(0).asString()) && day.get(1).equals(reference.get(1))
                            && reference.get(2).asInt() - day.get(2).asInt() > fall) {
                        fall = reference.get(2).asInt() - day.get(2).asInt();
                        brand = reference.get(1).asString();
                    }
                }
            }
            return says("Facts: purchases fell on " + DROP_DAY + "; " + brand + " lost " + fall + ".\n"
                    + "Conjecture: none verified.");
        });
        Agent agent = new Agent(ChatClient.builder(model).defaultToolCallbacks(tools.toArray(ToolCallback[]::new)).build());

        Agent.Answer answer = agent.ask("Why did purchases fall yesterday?");

        assertThat(calls).containsExactlyElementsOf(ORDER);
        assertThat(answer.outcome()).isEqualTo(Agent.Outcome.ANSWERED);
        assertThat(answer.text()).contains(DROP_DAY).contains(PLANTED_BRAND + " lost 80");
    }
}
