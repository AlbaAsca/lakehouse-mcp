package io.github.albaasca.lakehouse.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The top-level field names of the latest Avro schema of one subject, from the Schema Registry REST API. */
final class SchemaRegistryFields {

    /** Where data_quality_check reads the registered fields; empty when the subject does not exist. */
    @FunctionalInterface
    interface Source {
        Optional<List<String>> fields() throws IOException, InterruptedException;
    }

    private SchemaRegistryFields() {}

    static Source of(String registryUrl, String subject, Duration timeout) {
        String base = registryUrl.endsWith("/") ? registryUrl.substring(0, registryUrl.length() - 1) : registryUrl;
        URI latest = URI.create(base + "/subjects/" + URLEncoder.encode(subject, StandardCharsets.UTF_8).replace("+", "%20")
                + "/versions/latest");
        HttpClient http = HttpClient.newBuilder().connectTimeout(timeout).build();
        return () -> {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(latest).timeout(timeout).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return Optional.empty();
            }
            if (response.statusCode() != 200) {
                throw new IOException("it answered HTTP " + response.statusCode());
            }
            return Optional.of(fieldsOf(JsonMapper.shared().readTree(response.body()).path("schema").asString("")));
        };
    }

    /** The registry returns the schema itself as a JSON string inside its answer. */
    static List<String> fieldsOf(String avroSchema) {
        JsonNode schema = JsonMapper.shared().readTree(avroSchema);
        List<String> names = new ArrayList<>();
        schema.path("fields").forEach(field -> names.add(field.path("name").asString()));
        if (names.isEmpty()) {
            throw new IllegalStateException("The registered schema is not an Avro record with fields");
        }
        return names;
    }
}
