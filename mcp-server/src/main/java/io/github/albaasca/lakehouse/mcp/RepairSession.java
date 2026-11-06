package io.github.albaasca.lakehouse.mcp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The writable instance of DuckDB, for approved repairs and maintenance only (docs/architecture.md, decision 20).
 *
 * <p>It never runs free SQL: only a {@link Template} with typed parameters, so nothing the model writes can get here.
 * Each call opens its own instance with a single connection and closes it when it ends: the memory it takes is only
 * held while a repair runs, and no connection of it outlives the call.
 */
public final class RepairSession {

    /** Memory of the repair instance, apart from the read instance (docs/architecture.md, memory split). */
    public static final String DEFAULT_MEMORY_LIMIT = "256MB";

    /** What a parameter may hold, and how it is written into the SQL. */
    public enum Kind {
        /** {@code lh.<namespace>.<table>}, plain identifiers only, so it is safe as a name and inside quotes. */
        TABLE,
        /** A whole number, such as a snapshot id. */
        LONG,
        /** Any text, written as a quoted SQL literal. */
        STRING
    }

    /**
     * A fixed statement with {@code {name}} placeholders. The SQL comes from the code, never from a request.
     *
     * @param parameters every placeholder of {@code sql}, with its kind; a value without a placeholder is refused too
     */
    public record Template(String sql, Map<String, Kind> parameters) {

        private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z_]+)}");

        public Template {
            Objects.requireNonNull(sql, "sql");
            parameters = Map.copyOf(parameters);
            Matcher matcher = PLACEHOLDER.matcher(sql);
            Set<String> used = new HashSet<>();
            while (matcher.find()) {
                used.add(matcher.group(1));
            }
            if (!used.equals(parameters.keySet())) {
                throw new IllegalArgumentException("Placeholders " + used + " do not match parameters "
                        + parameters.keySet() + ": " + sql);
            }
        }

        /** The statement with every value checked against its kind. */
        String render(Map<String, ?> values) {
            if (!values.keySet().equals(parameters.keySet())) {
                throw new IllegalArgumentException("Expected parameters " + parameters.keySet() + ", got " + values.keySet());
            }
            Matcher matcher = PLACEHOLDER.matcher(sql);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String name = matcher.group(1);
                matcher.appendReplacement(out, Matcher.quoteReplacement(literal(name, parameters.get(name), values.get(name))));
            }
            matcher.appendTail(out);
            return out.toString();
        }

        private static final Pattern TABLE_NAME = Pattern.compile(
                Pattern.quote(LakehouseSession.CATALOG_ALIAS) + "\\.[A-Za-z_][A-Za-z0-9_]*\\.[A-Za-z_][A-Za-z0-9_]*");

        private static String literal(String name, Kind kind, Object value) {
            Objects.requireNonNull(value, name);
            return switch (kind) {
                case TABLE -> {
                    if (!(value instanceof String table) || !TABLE_NAME.matcher(table).matches()) {
                        throw new IllegalArgumentException(name + " must be " + LakehouseSession.CATALOG_ALIAS
                                + ".<namespace>.<table>: " + value);
                    }
                    yield table;
                }
                case LONG -> {
                    if (!(value instanceof Long || value instanceof Integer)) {
                        throw new IllegalArgumentException(name + " must be a whole number: " + value);
                    }
                    yield value.toString();
                }
                case STRING -> {
                    if (!(value instanceof String text) || text.indexOf('\0') >= 0) {
                        throw new IllegalArgumentException(name + " must be text without NUL: " + value);
                    }
                    yield LakehouseSession.quote(text);
                }
            };
        }
    }

    /** Back to a snapshot of the same table. */
    public static final Template ROLLBACK = new Template(
            "CALL iceberg_rollback_to_snapshot('{table}', {snapshot_id})",
            Map.of("table", Kind.TABLE, "snapshot_id", Kind.LONG));

    private final LakehouseSession.Settings settings;

    /** Pool size and query timeout of {@code settings} are not used: one connection, for as long as the repair takes. */
    public RepairSession(LakehouseSession.Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** The same catalog and store as the read instance, with the memory limit of the repair instance. */
    public static RepairSession alongside(LakehouseSession.Settings readSettings) {
        return new RepairSession(readSettings.withMemoryLimit(DEFAULT_MEMORY_LIMIT));
    }

    /**
     * Opens a writable instance, runs the template and closes it.
     *
     * @throws IllegalArgumentException if a value does not fit its parameter; nothing is opened then
     * @throws SQLException if the catalog cannot be attached or DuckDB rejects the statement
     */
    public void apply(Template template, Map<String, ?> values) throws SQLException {
        String sql = template.render(values);
        // jdbc:duckdb: is a new in-memory instance each time: nothing is shared with the read instance.
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement statement = connection.createStatement()) {
                for (String setup : LakehouseSession.startup(settings, false)) {
                    statement.execute(setup);
                }
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }
    }
}
