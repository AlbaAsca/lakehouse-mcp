package io.github.albaasca.lakehouse.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** A catalog must outlive the Polaris process. */
class PolarisPersistenceIT {

    private static final String TABLE_PATH = "/api/catalog/v1/lakehouse/namespaces/testkit/tables/events";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static LakehouseStack stack;

    @BeforeAll
    static void createTable() throws Exception {
        stack = LakehouseStack.shared();
        String token = stack.polarisToken();
        assertThat(post("/api/catalog/v1/lakehouse/namespaces", token, "{\"namespace\":[\"testkit\"]}").statusCode())
                .isEqualTo(200);
        HttpResponse<String> table = post("/api/catalog/v1/lakehouse/namespaces/testkit/tables", token, """
                {"name":"events","schema":{"type":"struct","schema-id":0,
                 "fields":[{"id":1,"name":"event_id","required":false,"type":"long"}]}}""");
        assertThat(table.statusCode()).as(table.body()).isEqualTo(200);
    }

    @Test
    void catalogAndTableSurviveARestart() throws Exception {
        stack.restartPolaris();

        String token = stack.polarisToken(); // the root principal must survive as well
        assertThat(get("/api/management/v1/catalogs/lakehouse", token).statusCode()).isEqualTo(200);
        HttpResponse<String> table = get(TABLE_PATH, token);
        assertThat(table.statusCode()).as(table.body()).isEqualTo(200);
        assertThat(table.body()).contains("event_id");
    }

    /** compose runs the bootstrap on every `up`: on a bootstrapped realm it has to exit 0 and change nothing. */
    @Test
    void secondBootstrapExitsCleanlyAndKeepsTheCatalog() throws Exception {
        stack.bootstrap(); // fails the test on a non-zero exit code

        String token = stack.polarisToken();
        assertThat(get("/api/management/v1/catalogs/lakehouse", token).statusCode()).isEqualTo(200);
        assertThat(get(TABLE_PATH, token).statusCode()).isEqualTo(200);
    }

    private static HttpResponse<String> get(String path, String token) throws Exception {
        return HTTP.send(request(path, token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String token, String json) throws Exception {
        return HTTP.send(request(path, token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder request(String path, String token) {
        return HttpRequest.newBuilder(URI.create(stack.polarisUrl() + path))
                .header("Authorization", "Bearer " + token)
                .header("Polaris-Realm", LakehouseStack.REALM);
    }
}
