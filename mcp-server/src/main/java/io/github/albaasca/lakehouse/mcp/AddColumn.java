package io.github.albaasca.lakehouse.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ADD_COLUMN: the table gets a column the producer already sends (schema drift). With schema evolution off in the sink,
 * Iceberg ignores the unknown field without an error, so the fix adds the column through the repair instance and then
 * restarts the connector with its tasks, so the writers load the new table schema. Parameters: {@code namespace},
 * {@code table}, {@code column}; the type is not the model's to choose, it comes from the registered schema.
 *
 * <p>Only the pipeline table and only a column that is in the registry and not yet in the table are accepted. Rows
 * written before keep the column as NULL. Events sent between the drift and this fix did not keep the field: it was
 * dropped on write and is not recovered.
 */
final class AddColumn implements FixAction {

    private final CatalogReader reader;
    private final RepairSession repair;
    private final String namespace;
    private final String table;
    private final SchemaRegistryFields.TypeSource registry;
    private final String base;
    private final String connector;
    private final Duration timeout;
    private final HttpClient http;

    AddColumn(CatalogReader reader, RepairSession repair, String namespace, String table,
            SchemaRegistryFields.TypeSource registry, String connectUrl, String connector, Duration timeout) {
        this.reader = reader;
        this.repair = repair;
        this.namespace = namespace;
        this.table = table;
        this.registry = registry;
        this.base = connectUrl.endsWith("/") ? connectUrl.substring(0, connectUrl.length() - 1) : connectUrl;
        this.connector = connector;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Type type() {
        return Type.ADD_COLUMN;
    }

    @Override
    public Map<String, Object> validate(Map<String, Object> params) throws SQLException {
        if (params == null || !params.keySet().equals(Set.of("namespace", "table", "column"))) {
            throw new IllegalArgumentException("ADD_COLUMN needs exactly the parameters namespace, table and column, got "
                    + (params == null ? "none" : params.keySet()));
        }
        if (!namespace.equals(params.get("namespace")) || !table.equals(params.get("table"))) {
            throw new IllegalArgumentException("ADD_COLUMN only applies to " + namespace + "." + table + ", got "
                    + params.get("namespace") + "." + params.get("table"));
        }
        if (!(params.get("column") instanceof String column) || column.isBlank()) {
            throw new IllegalArgumentException("column must be text");
        }
        Map<String, String> types;
        try {
            Optional<Map<String, String>> fields = registry.types();
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("The subject is not registered, so there is no schema to take the column from");
            }
            types = fields.get();
        } catch (IOException e) {
            throw new IllegalStateException("Schema Registry did not answer: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while asking Schema Registry", e);
        }
        if (!types.containsKey(column)) {
            throw new IllegalArgumentException("Column " + column + " is not in the registered schema " + types.keySet());
        }
        String type = types.get(column);
        if (type == null) {
            throw new IllegalArgumentException("Column " + column + " has a type that cannot be added automatically");
        }
        if (reader.describe(namespace, table).columns().stream().anyMatch(c -> c.name().equalsIgnoreCase(column))) {
            throw new IllegalArgumentException("Column " + column + " is already in " + namespace + "." + table);
        }
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("namespace", namespace);
        canonical.put("table", table);
        canonical.put("column", column);
        canonical.put("type", type);
        // Same checks as when it is applied, so a bad name is refused at once.
        RepairSession.ADD_COLUMN.render(values(canonical));
        return canonical;
    }

    @Override
    public String expectedEffect(Map<String, Object> params) {
        return "The table " + params.get("namespace") + "." + params.get("table") + " gets the column " + params.get("column")
                + " (" + params.get("type") + "), and the connector " + connector + " is restarted to pick it up."
                + " Rows already written read it as NULL. Events sent since the producer started using it have lost the field"
                + " (it was dropped on write) and are not recovered; from the fix on it is filled in.";
    }

    @Override
    public void apply(Map<String, Object> params) throws SQLException {
        repair.apply(RepairSession.ADD_COLUMN, values(params));
        String path = "/connectors/" + URLEncoder.encode(connector, StandardCharsets.UTF_8).replace("+", "%20")
                + "/restart?includeTasks=true&onlyFailed=false";
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("The column was added but POST " + path + " answered HTTP "
                        + response.statusCode() + ": " + response.body() + "; restart the connector by hand");
            }
        } catch (IOException e) {
            throw new IllegalStateException("The column was added but Kafka Connect did not answer: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while restarting " + connector, e);
        }
    }

    private static Map<String, Object> values(Map<String, Object> params) {
        return Map.of("table", LakehouseSession.CATALOG_ALIAS + "." + params.get("namespace") + "." + params.get("table"),
                "column", params.get("column"), "type", params.get("type"));
    }
}
