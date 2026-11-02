package io.github.albaasca.lakehouse.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/** Same services and configuration as compose.yaml: a catalog must outlive the Polaris process. */
class PolarisPersistenceIT {

    private static final String REALM = "POLARIS";
    private static final String CLIENT_ID = "root";
    private static final String CLIENT_SECRET = "testkit-secret";
    private static final String S3_ACCESS_KEY = "lakehouse";
    private static final String S3_SECRET_KEY = "testkit-s3";
    private static final String S3_REGION = "us-east-1";
    private static final String DB_PASSWORD = "testkit-db";
    private static final String TABLE_PATH = "/api/catalog/v1/lakehouse/namespaces/testkit/tables/events";

    private static final Map<String, String> PERSISTENCE = Map.of(
            "polaris.persistence.type", "relational-jdbc",
            "quarkus.datasource.username", "polaris",
            "quarkus.datasource.password", DB_PASSWORD,
            "quarkus.datasource.jdbc.url", "jdbc:postgresql://postgres:5432/polaris");

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static Network network;
    private static GenericContainer<?> postgres;
    private static GenericContainer<?> seaweedfs;
    private static GenericContainer<?> polaris;

    @BeforeAll
    static void startStack() throws Exception {
        network = Network.newNetwork();
        postgres = new GenericContainer<>(image("postgres"))
                .withNetwork(network)
                .withNetworkAliases("postgres")
                .withEnv(Map.of("POSTGRES_DB", "polaris", "POSTGRES_USER", "polaris", "POSTGRES_PASSWORD", DB_PASSWORD))
                // Twice: the temporary server used for initdb logs the same line first.
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*", 2));
        postgres.start();

        seaweedfs = new GenericContainer<>(image("seaweedfs"))
                .withNetwork(network)
                .withNetworkAliases("seaweedfs")
                .withEnv(Map.of("S3_ACCESS_KEY", S3_ACCESS_KEY, "S3_SECRET_KEY", S3_SECRET_KEY))
                .withExposedPorts(8333, 9333)
                .waitingFor(Wait.forHttp("/cluster/healthz").forPort(9333).forStatusCode(200));
        seaweedfs.start();

        bootstrap();

        polaris = new GenericContainer<>("apache/polaris:1.8.0")
                .withNetwork(network)
                .withNetworkAliases("polaris")
                .withEnv(PERSISTENCE)
                .withEnv("polaris.realm-context.realms", REALM)
                .withEnv("AWS_REGION", S3_REGION)
                .withEnv("AWS_ACCESS_KEY_ID", S3_ACCESS_KEY)
                .withEnv("AWS_SECRET_ACCESS_KEY", S3_SECRET_KEY)
                .withExposedPorts(8181, 8182)
                .waitingFor(Wait.forHttp("/q/health").forPort(8182).forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        polaris.start();

        // The real init script: bucket, catalog and grants.
        runOnce(new GenericContainer<>(image("polaris"))
                .withEnv(Map.of(
                        "S3_ACCESS_KEY", S3_ACCESS_KEY,
                        "S3_SECRET_KEY", S3_SECRET_KEY,
                        "S3_REGION", S3_REGION,
                        "S3_BUCKET", "lakehouse",
                        "POLARIS_REALM", REALM,
                        "POLARIS_CLIENT_ID", CLIENT_ID,
                        "POLARIS_CLIENT_SECRET", CLIENT_SECRET)));

        String token = token();
        assertThat(post("/api/catalog/v1/lakehouse/namespaces", token, "{\"namespace\":[\"testkit\"]}").statusCode())
                .isEqualTo(200);
        HttpResponse<String> table = post("/api/catalog/v1/lakehouse/namespaces/testkit/tables", token, """
                {"name":"events","schema":{"type":"struct","schema-id":0,
                 "fields":[{"id":1,"name":"event_id","required":false,"type":"long"}]}}""");
        assertThat(table.statusCode()).as(table.body()).isEqualTo(200);
    }

    @AfterAll
    static void stopStack() {
        for (GenericContainer<?> container : new GenericContainer<?>[] {polaris, seaweedfs, postgres}) {
            if (container != null) {
                container.stop();
            }
        }
        if (network != null) {
            network.close();
        }
    }

    @Test
    void catalogAndTableSurviveARestart() throws Exception {
        polaris.stop();
        polaris.start();

        String token = token(); // the root principal must survive as well
        assertThat(get("/api/management/v1/catalogs/lakehouse", token).statusCode()).isEqualTo(200);
        HttpResponse<String> table = get(TABLE_PATH, token);
        assertThat(table.statusCode()).as(table.body()).isEqualTo(200);
        assertThat(table.body()).contains("event_id");
    }

    /** compose runs the bootstrap on every `up`: on a bootstrapped realm it has to exit 0 and change nothing. */
    @Test
    void secondBootstrapExitsCleanlyAndKeepsTheCatalog() throws Exception {
        bootstrap(); // fails the test on a non-zero exit code

        String token = token();
        assertThat(get("/api/management/v1/catalogs/lakehouse", token).statusCode()).isEqualTo(200);
        assertThat(get(TABLE_PATH, token).statusCode()).isEqualTo(200);
    }

    private static void bootstrap() {
        runOnce(new GenericContainer<>("apache/polaris-admin-tool:1.8.0")
                .withEnv(PERSISTENCE)
                .withCommand("bootstrap", "-r", REALM, "-c", REALM + "," + CLIENT_ID + "," + CLIENT_SECRET));
    }

    /** Starting throws unless the container exits with code 0. */
    private static void runOnce(GenericContainer<?> container) {
        try (container) {
            container.withNetwork(network)
                    .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(3)))
                    .start();
        }
    }

    private static ImageFromDockerfile image(String infraDir) {
        return new ImageFromDockerfile("lakehouse-testkit-" + infraDir, false)
                .withDockerfile(Path.of("..", "infra", infraDir, "Dockerfile"));
    }

    private static String token() throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(uri("/api/catalog/v1/oauth/tokens"))
                        .header("Polaris-Realm", REALM)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials&client_id=" + CLIENT_ID
                                + "&client_secret=" + CLIENT_SECRET + "&scope=PRINCIPAL_ROLE:ALL"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        Matcher matcher = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"").matcher(response.body());
        assertThat(matcher.find()).as(response.body()).isTrue();
        return matcher.group(1);
    }

    private static HttpResponse<String> get(String path, String token) throws Exception {
        return HTTP.send(request(path, token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String token, String json) throws Exception {
        return HTTP.send(request(path, token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder request(String path, String token) {
        return HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + token)
                .header("Polaris-Realm", REALM);
    }

    private static URI uri(String path) {
        return URI.create("http://" + polaris.getHost() + ":" + polaris.getMappedPort(8181) + path);
    }
}
