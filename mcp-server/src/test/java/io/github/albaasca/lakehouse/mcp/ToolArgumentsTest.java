package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ToolArgumentsTest {

    private final ToolArguments arguments = ToolArguments.ofServer();
    private final JsonMapper json = JsonMapper.builder().build();

    private String reject(String body) {
        return arguments.rejection(json, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void anInventedArgumentIsRefusedListingTheRealOnes() {
        String answer = reject("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"get_metric\",\"arguments\":{\"name\":\"top_brands\",\"parameters\":{\"limit\":2}}}}");

        assertThat(answer).contains("\"id\":7", "\"isError\":true", "Unknown argument(s) for get_metric: parameters",
                "from, limit, name, to");
    }

    @Test
    void realArgumentsPass() {
        assertThat(reject("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"get_metric\",\"arguments\":{\"name\":\"top_brands\",\"limit\":2}}}")).isNull();
    }

    @Test
    void aToolWithoutArgumentsAcceptsNone() {
        assertThat(reject("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"list_metrics\",\"arguments\":{\"x\":1}}}")).contains("Its arguments are: none");
    }

    @Test
    void otherMessagesAndUnknownToolsAreLeftToTheFramework() {
        assertThat(reject("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")).isNull();
        assertThat(reject("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":"
                + "{\"name\":\"nope\",\"arguments\":{\"x\":1}}}")).isNull();
        assertThat(reject("not json")).isNull();
    }
}
