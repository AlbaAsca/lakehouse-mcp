import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** AgentImageIT's model: answers every request with the text in args[0] and prints each request body on one line. */
public class FakeAnthropic {
    public static void main(String[] args) throws Exception {
        String text = args[0].replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        byte[] body = ("{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"fake-claude\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],\"stop_reason\":\"end_turn\","
                + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}")
                .getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("REQUEST " + request.replace("\n", " "));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        System.out.println("fake-anthropic listening");
    }
}
