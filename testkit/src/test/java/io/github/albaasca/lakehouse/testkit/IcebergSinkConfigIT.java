package io.github.albaasca.lakehouse.testkit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.images.builder.Transferable;

/**
 * connect/iceberg-sink.json against the real worker of this JVM. The registered copy has made-up secrets and its own
 * names, so it cannot touch the sink of IcebergSinkFlowIT: RUNNING and the data path are for that test.
 */
class IcebergSinkConfigIT {

    private static final String VALIDATE = "/connector-plugins/IcebergSinkConnector/config/validate";
    private static final String CONNECTOR = "config-it-sink";
    private static final Path DIR = LakehouseStack.repositoryRoot().resolve("connect");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Map<String, String> ENV = Map.of(
            "S3_ACCESS_KEY", "test-access",
            "S3_SECRET_KEY", "test-secret",
            "S3_REGION", "us-east-1",
            "POLARIS_REALM", "POLARIS",
            "POLARIS_CLIENT_ID", "root",
            "POLARIS_CLIENT_SECRET", "test-polaris");

    private static LakehouseStack stack;
    private static ConnectWorker connect;

    @BeforeAll
    static void startWorker() {
        connect = ConnectWorker.shared();
        stack = LakehouseStack.sharedStreaming();
    }

    @Test
    void everyTestClassGetsTheSameWorker() {
        assertThat(ConnectWorker.shared()).isSameAs(connect);
    }

    @Test
    void configIsValidForTheBuiltPlugin() throws Exception {
        HttpResponse<String> response = put(VALIDATE, config());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat((Integer) JsonPath.read(response.body(), "$.error_count")).as(response.body()).isZero();
    }

    /** Without this, a validate that ignored the connector or the transforms would look like a pass. */
    @Test
    void validationRejectsBadConnectorAndTransformValues() throws Exception {
        String badInterval = config().replace("\"30000\"", "\"soon\"");
        String badPrecision = config().replace("\"microseconds\"", "\"parsecs\"");

        assertThat(badInterval).isNotEqualTo(config());
        assertThat(badPrecision).isNotEqualTo(config());
        assertThat((Integer) JsonPath.read(put(VALIDATE, badInterval).body(), "$.error_count")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(put(VALIDATE, badPrecision).body(), "$.error_count")).isEqualTo(1);
    }

    /** What docs/architecture.md decision 3 and the partitioning of the brief require. */
    @Test
    void configKeepsTheDecisionsOfTheDesign() throws Exception {
        String config = config();

        assertThat(value(config, "topics")).isEqualTo("ecommerce.events");
        assertThat(value(config, "iceberg.tables")).isEqualTo("ecommerce.events");
        assertThat(value(config, "iceberg.tables.default-partition-by")).isEqualTo("day(event_time),event_type");
        assertThat(value(config, "iceberg.tables.evolve-schema-enabled")).isEqualTo("false");
        assertThat(value(config, "iceberg.control.commit.interval-ms")).isEqualTo("30000");
        assertThat(value(config, "errors.tolerance")).isEqualTo("all");
        assertThat(value(config, "errors.deadletterqueue.topic.name")).isEqualTo("ecommerce.events.dlq");
    }

    @Test
    void registrationReplacesSecretsAndCanRunTwice() throws Exception {
        runRegister(ENV);
        runRegister(ENV);

        String registered = connect.get("/connectors/" + CONNECTOR + "/config");
        assertThat(registered).doesNotContain("${");
        assertThat(value(registered, "iceberg.catalog.credential")).isEqualTo("root:test-polaris");
        assertThat(value(registered, "iceberg.catalog.s3.access-key-id")).isEqualTo("test-access");
        assertThat(value(registered, "iceberg.catalog.s3.secret-access-key")).isEqualTo("test-secret");
        assertThat(value(registered, "iceberg.catalog.client.region")).isEqualTo("us-east-1");
        assertThat(value(registered, "iceberg.catalog.header.Polaris-Realm")).isEqualTo("POLARIS");
        // Nothing of the real sink, which IcebergSinkFlowIT runs on this same worker.
        assertThat(registered).doesNotContain("ecommerce.events");
        assertThat(connect.get("/connectors")).doesNotContain("\"iceberg-sink\"");

        connect.deleteConnector(CONNECTOR);
        assertThat(connect.get("/connectors")).doesNotContain(CONNECTOR);
    }

    @Test
    void removingAConnectorThatDoesNotExistIsNotAnError() throws Exception {
        connect.deleteConnector("no-such-connector");
    }

    @Test
    void registrationStopsWhenASecretIsMissing() {
        Map<String, String> incomplete = Map.of("S3_ACCESS_KEY", "only-this-one");

        assertThatThrownBy(() -> runRegister(incomplete)).isInstanceOf(RuntimeException.class);
    }

    /** The real job and script, on a copy of the file with names of its own: connector, topics and table. */
    private static void runRegister(Map<String, String> env) throws Exception {
        ImageFromDockerfile image = new ImageFromDockerfile("lakehouse-testkit-connect-init", false)
                .withDockerfile(DIR.resolve("Dockerfile"));
        String isolated = Files.readString(DIR.resolve("iceberg-sink.json"))
                .replace("\"iceberg-sink\"", "\"" + CONNECTOR + "\"")
                .replace("ecommerce.events", "config_it.events");
        stack.runOnce(new GenericContainer<>(image)
                .withEnv(env)
                .withCopyToContainer(Transferable.of(isolated), "/iceberg-sink.json"));
    }

    /** The file with its placeholders replaced, as register.sh does. */
    private static String config() throws Exception {
        String text = Files.readString(DIR.resolve("iceberg-sink.json"));
        for (Map.Entry<String, String> entry : ENV.entrySet()) {
            text = text.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        assertThat(text).doesNotContain("${");
        return text;
    }

    private static String value(String json, String key) {
        return JsonPath.read(json, "$['" + key + "']");
    }

    private static HttpResponse<String> put(String path, String body) throws Exception {
        return HTTP.send(
                HttpRequest.newBuilder(connect.url(path))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

}
