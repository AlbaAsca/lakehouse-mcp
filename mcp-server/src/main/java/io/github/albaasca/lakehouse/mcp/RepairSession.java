package io.github.albaasca.lakehouse.mcp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
        STRING,
        /** A calendar day {@code yyyy-MM-dd}, written bare so a template can put it inside its own quotes. */
        DATE,
        /** A non-empty list of plain column names, written as quoted identifiers separated by commas. */
        COLUMNS,
        /** One plain column name, written as a quoted identifier. */
        COLUMN,
        /** One of a closed list of column types ({@link #COLUMN_TYPES}), written bare. */
        COLUMN_TYPE
    }

    static final java.util.Set<String> COLUMN_TYPES = java.util.Set.of("VARCHAR", "BIGINT", "INTEGER", "DOUBLE", "FLOAT",
            "BOOLEAN", "TIMESTAMPTZ");

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

        private static final Pattern COLUMN_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

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
                case DATE -> {
                    if (!(value instanceof String text) || !text.matches("\\d{4}-\\d{2}-\\d{2}")) {
                        throw new IllegalArgumentException(name + " must be a day yyyy-MM-dd: " + value);
                    }
                    java.time.LocalDate.parse(text);
                    yield text;
                }
                case COLUMN -> {
                    if (!(value instanceof String c) || !COLUMN_NAME.matcher(c).matches()) {
                        throw new IllegalArgumentException(name + " must be a plain column name: " + value);
                    }
                    yield "\"" + c + "\"";
                }
                case COLUMN_TYPE -> {
                    if (!(value instanceof String t) || !COLUMN_TYPES.contains(t)) {
                        throw new IllegalArgumentException(name + " must be one of " + COLUMN_TYPES + ": " + value);
                    }
                    yield t;
                }
                case COLUMNS -> {
                    if (!(value instanceof java.util.List<?> list) || list.isEmpty()) {
                        throw new IllegalArgumentException(name + " must be a non-empty list of column names: " + value);
                    }
                    yield list.stream().map(column -> {
                        if (!(column instanceof String c) || !COLUMN_NAME.matcher(c).matches()) {
                            throw new IllegalArgumentException(name + " has a column that is not a plain name: " + column);
                        }
                        return "\"" + c + "\"";
                    }).collect(java.util.stream.Collectors.joining(", "));
                }
            };
        }
    }

    /** Back to a snapshot of the same table. */
    public static final Template ROLLBACK = new Template(
            "CALL iceberg_rollback_to_snapshot('{table}', {snapshot_id})",
            Map.of("table", Kind.TABLE, "snapshot_id", Kind.LONG));

    /**
     * Removes the extra copies that arrived after a snapshot, within one UTC day. Rows of files that already existed at
     * {@code base} are never deleted (the source's own duplicates stay); among identical rows, the ones in older files
     * are kept first, so a new row is deleted only when an identical row is already there.
     */
    public static final Template DEDUPLICATE = new Template(
            "DELETE FROM {table} WHERE (filename, file_row_number) IN ("
                    + "SELECT filename, file_row_number FROM ("
                    + "SELECT filename, file_row_number, "
                    + "filename IN (SELECT DISTINCT filename FROM {table} AT (VERSION => {base})) AS old_file, "
                    + "row_number() OVER (PARTITION BY {columns} ORDER BY "
                    + "(filename IN (SELECT DISTINCT filename FROM {table} AT (VERSION => {base}))) DESC, "
                    + "filename, file_row_number) AS copy "
                    + "FROM {table} WHERE event_time >= TIMESTAMPTZ '{day} 00:00:00+00' "
                    + "AND event_time < TIMESTAMPTZ '{next_day} 00:00:00+00') "
                    + "WHERE copy > 1 AND NOT old_file)",
            Map.of("table", Kind.TABLE, "base", Kind.LONG, "columns", Kind.COLUMNS, "day", Kind.DATE,
                    "next_day", Kind.DATE));

    /** A new optional column; rows already written read it as NULL. */
    public static final Template ADD_COLUMN = new Template(
            "ALTER TABLE {table} ADD COLUMN {column} {type}",
            Map.of("table", Kind.TABLE, "column", Kind.COLUMN, "type", Kind.COLUMN_TYPE));

    /** Longest a repair statement may run: a compaction or rollback that hangs must not hold the server forever. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);

    private final LakehouseSession.Settings settings;
    private final Duration timeout;

    /** Pool size and query timeout of {@code settings} are not used: one connection, for as long as the repair takes. */
    public RepairSession(LakehouseSession.Settings settings) {
        this(settings, DEFAULT_TIMEOUT);
    }

    public RepairSession(LakehouseSession.Settings settings, Duration timeout) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    /** The same catalog and store as the read instance, with the memory limit of the repair instance. */
    public static RepairSession alongside(LakehouseSession.Settings readSettings) {
        return new RepairSession(readSettings.withMemoryLimit(DEFAULT_MEMORY_LIMIT));
    }

    /**
     * Opens a writable instance, runs the template and closes it.
     *
     * @throws IllegalArgumentException if a value does not fit its parameter; nothing is opened then
     * @throws SQLTimeoutException if the statement runs longer than the timeout; it is cancelled and the instance closed
     * @throws SQLException if the catalog cannot be attached or DuckDB rejects the statement
     */
    public void apply(Template template, Map<String, ?> values) throws SQLException {
        run(LakehouseSession.startup(settings, false), template.render(values));
    }

    /** Like {@link #apply}, and returns the first row the statement gives back, columns joined with {@code |} ("" if none). */
    public String applyForResult(Template template, Map<String, ?> values) throws SQLException {
        return run(LakehouseSession.startup(settings, false), template.render(values));
    }

    /**
     * Merges the small data files of a table and applies its delete files. {@code min_input_files} is always explicit:
     * by default (5) DuckDB returns {@code 0|0|0} without error when there are fewer. This extension returns three
     * columns: rewritten_data_files, added_data_files, rewritten_bytes. The delete file stays registered afterwards.
     */
    public static final Template COMPACT = new Template(
            "CALL iceberg_rewrite_data_files('{table}', min_input_files => {min_input_files})",
            Map.of("table", Kind.TABLE, "min_input_files", Kind.LONG));

    String run(List<String> setups, String sql) throws SQLException {
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "repair-timeout");
            thread.setDaemon(true);
            return thread;
        });
        // jdbc:duckdb: is a new in-memory instance each time: nothing is shared with the read instance.
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement statement = connection.createStatement()) {
                for (String setup : setups) {
                    statement.execute(setup);
                }
            }
            try (Statement statement = connection.createStatement()) {
                AtomicBoolean fired = new AtomicBoolean();
                var task = watchdog.schedule(() -> {
                    fired.set(true);
                    try {
                        statement.cancel();
                    } catch (SQLException e) {
                        // Already closed: the statement has just ended by itself.
                    }
                }, timeout.toMillis(), TimeUnit.MILLISECONDS);
                try {
                    if (statement.execute(sql)) {
                        try (var rs = statement.getResultSet()) {
                            if (rs.next()) {
                                StringBuilder row = new StringBuilder();
                                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                                    row.append(i > 1 ? "|" : "").append(rs.getString(i));
                                }
                                return row.toString();
                            }
                        }
                    }
                    return "";
                } catch (SQLException e) {
                    if (fired.get()) {
                        throw new SQLTimeoutException("Repair exceeded " + timeout, e);
                    }
                    throw e;
                } finally {
                    task.cancel(false);
                }
            }
        } finally {
            watchdog.shutdownNow();
        }
    }
}
