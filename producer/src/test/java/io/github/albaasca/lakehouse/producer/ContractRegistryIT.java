package io.github.albaasca.lakehouse.producer;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.avro.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Data contract (T-043), level 2: against a real Schema Registry. The subject uses BACKWARD: the committed v1 and a
 * compatible v2 are registered, and a schema with a new required field is rejected with 409. A separate subject name
 * keeps this test away from the one the other ITs register.
 */
class ContractRegistryIT {

    private static final String SUBJECT = "ecommerce.events.contract-it-value";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String V1 = "../contracts/ecommerce.events-value/v1.avsc";
    private static final String FIXTURES = "src/test/resources/contracts/";

    private static String registry;

    @BeforeAll
    static void start() throws Exception {
        registry = LakehouseStack.sharedStreaming().schemaRegistryUrl();
        HttpResponse<String> config = send("PUT", "/config/" + SUBJECT, "{\"compatibility\":\"BACKWARD\"}");
        assertThat(config.statusCode()).as(config.body()).isEqualTo(200);
    }

    @Test
    void versionsRegisterAndTheBrokenOneGets409() throws Exception {
        assertThat(register(V1).statusCode()).isEqualTo(200);
        HttpResponse<String> v2 = register(FIXTURES + "compatible-v2.avsc");
        assertThat(v2.statusCode()).as(v2.body()).isEqualTo(200);

        HttpResponse<String> broken = register(FIXTURES + "broken-added-required.avsc");
        assertThat(broken.statusCode()).as(broken.body()).isEqualTo(409);

        HttpResponse<String> versions = send("GET", "/subjects/" + SUBJECT + "/versions", null);
        assertThat(versions.body().replaceAll("\\s", "")).isEqualTo("[1,2]");
    }

    private static HttpResponse<String> register(String file) throws Exception {
        String schema = new Schema.Parser().parse(Files.readString(Path.of(file))).toString();
        String escaped = schema.replace("\\", "\\\\").replace("\"", "\\\"");
        return send("POST", "/subjects/" + SUBJECT + "/versions", "{\"schema\":\"" + escaped + "\"}");
    }

    private static HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(registry + path))
                .header("Content-Type", "application/vnd.schemaregistry.v1+json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
