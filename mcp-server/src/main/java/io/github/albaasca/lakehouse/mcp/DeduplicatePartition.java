package io.github.albaasca.lakehouse.mcp;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DEDUPLICATE_PARTITION: removes the extra copies written after a snapshot, in one UTC day of the table. Parameters:
 * {@code namespace}, {@code table}, {@code day} (yyyy-MM-dd) and {@code sinceSnapshotId}, the last good snapshot.
 *
 * <p>It acts only on data files added after {@code sinceSnapshotId}, so duplicates the source already had stay (the data
 * is dirty on purpose, brief) and what is removed is only what arrived since. A row that arrived after it and is not a
 * copy of anything is kept. If the copies cannot be told from new data (the load is still running), ROLLBACK_SNAPSHOT
 * is the safer repair; it is also the only one that is exact when the table has no event_time to scope by.
 */
final class DeduplicatePartition implements FixAction {

    private final CatalogReader reader;
    private final RepairSession repair;

    DeduplicatePartition(CatalogReader reader, RepairSession repair) {
        this.reader = reader;
        this.repair = repair;
    }

    @Override
    public Type type() {
        return Type.DEDUPLICATE_PARTITION;
    }

    @Override
    public Map<String, Object> validate(Map<String, Object> params) throws SQLException {
        if (params == null || !params.keySet().equals(Set.of("namespace", "table", "day", "sinceSnapshotId"))) {
            throw new IllegalArgumentException(
                    "DEDUPLICATE_PARTITION needs exactly the parameters namespace, table, day and sinceSnapshotId, got "
                            + (params == null ? "none" : params.keySet()));
        }
        String namespace = text(params, "namespace");
        String table = text(params, "table");
        LocalDate day = day(params.get("day"));
        long since = RollbackSnapshot.whole(params.get("sinceSnapshotId"));
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("namespace", namespace);
        canonical.put("table", table);
        canonical.put("day", day.toString());
        canonical.put("sinceSnapshotId", since);
        var snapshots = reader.snapshots(namespace, table);
        if (snapshots.stream().noneMatch(s -> s.snapshotId() == since)) {
            throw new IllegalArgumentException("Snapshot " + since + " is not a snapshot of " + namespace + "." + table
                    + "; list its snapshots with describe_table");
        }
        // Same checks as when it is applied, so a bad name is refused at once.
        RepairSession.DEDUPLICATE.render(values(canonical, columns(namespace, table)));
        return canonical;
    }

    @Override
    public String expectedEffect(Map<String, Object> params) {
        return "In " + params.get("namespace") + "." + params.get("table") + ", on " + params.get("day")
                + " (UTC), the rows written after snapshot " + params.get("sinceSnapshotId")
                + " that are exact copies of another row are deleted, keeping one. Duplicates that already existed at that"
                + " snapshot are not touched.";
    }

    @Override
    public void apply(Map<String, Object> params) throws SQLException {
        repair.apply(RepairSession.DEDUPLICATE,
                values(params, columns((String) params.get("namespace"), (String) params.get("table"))));
    }

    private List<String> columns(String namespace, String table) throws SQLException {
        List<String> columns = reader.describe(namespace, table).columns().stream().map(CatalogReader.Column::name).toList();
        if (!columns.contains("event_time")) {
            throw new IllegalArgumentException(namespace + "." + table
                    + " has no event_time column to scope the day by; use ROLLBACK_SNAPSHOT");
        }
        return columns;
    }

    private static Map<String, Object> values(Map<String, Object> params, List<String> columns) {
        LocalDate day = LocalDate.parse((String) params.get("day"));
        return Map.of("table", LakehouseSession.CATALOG_ALIAS + "." + params.get("namespace") + "." + params.get("table"),
                "base", RollbackSnapshot.whole(params.get("sinceSnapshotId")), "columns", columns, "day", day.toString(),
                "next_day", day.plusDays(1).toString());
    }

    private static LocalDate day(Object value) {
        try {
            return LocalDate.parse(String.valueOf(value));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("day must be yyyy-MM-dd: " + value);
        }
    }

    private static String text(Map<String, Object> params, String name) {
        if (!(params.get(name) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be text");
        }
        return value;
    }
}
