package io.github.albaasca.lakehouse.mcp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reproducible failures for the self-repair demo. Lives in mcp-server because it needs the same DuckDB/Polaris/S3 start-up
 * as {@link RepairSession}; it is a tool for the person running the demo, never exposed over MCP.
 *
 * <p>Each scenario returns what it did (scenario, table, day, rows, snapshot before and after, when), so a repair can be
 * checked against it. Table-only scenarios: {@code duplicates} and {@code empty-partition}; the rest are planned (T-037 follow-ups).
 */
public final class Chaos {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final LakehouseSession.Settings settings;
    private final Clock clock;

    public Chaos(LakehouseSession.Settings settings) {
        this(settings, Clock.systemUTC());
    }

    Chaos(LakehouseSession.Settings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    /**
     * Inserts again {@code ceil(percent% of the day's rows)} of the rows of a UTC day (the first ones by session, time and
     * product, so it is repeatable). Undo: roll back to {@code snapshotBefore}.
     */
    public Map<String, Object> duplicates(String namespace, String table, LocalDate day, double percent) throws SQLException {
        if (!(percent > 0 && percent <= 100)) {
            throw new IllegalArgumentException("percent must be in (0, 100]: " + percent);
        }
        String name = name(namespace, table);
        String range = range(day);
        return inSession(connection -> {
            long total = count(connection, "SELECT count(*) FROM " + name + " WHERE " + range);
            long copies = (long) Math.ceil(total * percent / 100.0);
            if (total == 0) {
                throw new IllegalArgumentException("No rows on " + day + " in " + namespace + "." + table);
            }
            String before = snapshot(connection, name);
            run(connection, "INSERT INTO " + name + " SELECT * FROM " + name + " WHERE " + range
                    + " ORDER BY user_session, event_time, product_id, event_type LIMIT " + copies);
            Map<String, Object> out = report("duplicates", namespace, table, day, before, snapshot(connection, name));
            out.put("rowsOfTheDayBefore", total);
            out.put("rowsDuplicated", copies);
            return out;
        });
    }

    /** Deletes every row of a UTC day with a normal Iceberg commit. Undo: roll back to {@code snapshotBefore}. */
    public Map<String, Object> emptyPartition(String namespace, String table, LocalDate day) throws SQLException {
        String name = name(namespace, table);
        String range = range(day);
        return inSession(connection -> {
            long total = count(connection, "SELECT count(*) FROM " + name + " WHERE " + range);
            if (total == 0) {
                throw new IllegalArgumentException("No rows on " + day + " in " + namespace + "." + table);
            }
            String before = snapshot(connection, name);
            run(connection, "DELETE FROM " + name + " WHERE " + range);
            Map<String, Object> out = report("empty-partition", namespace, table, day, before, snapshot(connection, name));
            out.put("rowsDeleted", total);
            return out;
        });
    }

    private Map<String, Object> report(String scenario, String namespace, String table, LocalDate day, String before,
            String after) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scenario", scenario);
        out.put("table", namespace + "." + table);
        out.put("day", day.toString());
        out.put("snapshotBefore", before);
        out.put("snapshotAfter", after);
        out.put("at", Instant.now(clock).toString());
        return out;
    }

    private static String name(String namespace, String table) {
        if (namespace == null || table == null || !IDENTIFIER.matcher(namespace).matches()
                || !IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("namespace and table must be plain identifiers: " + namespace + "." + table);
        }
        return LakehouseSession.CATALOG_ALIAS + "." + namespace + "." + table;
    }

    /** The day in UTC, as Iceberg partitions it (day(event_time)). */
    private static String range(LocalDate day) {
        return "event_time >= TIMESTAMPTZ '" + day + " 00:00:00+00' AND event_time < TIMESTAMPTZ '" + day.plusDays(1)
                + " 00:00:00+00'";
    }

    private interface Work {
        Map<String, Object> run(Connection connection) throws SQLException;
    }

    private Map<String, Object> inSession(Work work) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:")) {
            for (String setup : LakehouseSession.startup(settings, false)) {
                run(connection, setup);
            }
            run(connection, "SET TimeZone = 'UTC'");
            return work.run(connection);
        }
    }

    private static void run(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static String snapshot(Connection connection, String name) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT snapshot_id FROM iceberg_snapshots(" + name + ") ORDER BY sequence_number DESC LIMIT 1")) {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
