package io.github.albaasca.lakehouse.mcp;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ROLLBACK_SNAPSHOT: the table goes back to one of its snapshots, through the repair instance of {@link RepairSession}
 * (decisions 5 and 20). Parameters: {@code namespace}, {@code table}, {@code snapshotId}.
 */
final class RollbackSnapshot implements FixAction {

    private final CatalogReader reader;
    private final RepairSession repair;

    RollbackSnapshot(CatalogReader reader, RepairSession repair) {
        this.reader = reader;
        this.repair = repair;
    }

    @Override
    public Type type() {
        return Type.ROLLBACK_SNAPSHOT;
    }

    @Override
    public Map<String, Object> validate(Map<String, Object> params) throws SQLException {
        if (params == null || !params.keySet().equals(java.util.Set.of("namespace", "table", "snapshotId"))) {
            throw new IllegalArgumentException(
                    "ROLLBACK_SNAPSHOT needs exactly the parameters namespace, table and snapshotId, got "
                            + (params == null ? "none" : params.keySet()));
        }
        String namespace = text(params, "namespace");
        String table = text(params, "table");
        long snapshotId = whole(params.get("snapshotId"));
        // Same checks as when it is applied, so a bad name is refused at once.
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("namespace", namespace);
        canonical.put("table", table);
        canonical.put("snapshotId", snapshotId);
        RepairSession.ROLLBACK.render(values(canonical));
        var snapshots = reader.snapshots(namespace, table);
        if (snapshots.stream().noneMatch(s -> s.snapshotId() == snapshotId)) {
            throw new IllegalArgumentException("Snapshot " + snapshotId + " is not a snapshot of " + namespace + "." + table
                    + "; list its snapshots with describe_table");
        }
        return canonical;
    }

    @Override
    public String expectedEffect(Map<String, Object> params) throws SQLException {
        String base = "The table " + params.get("namespace") + "." + params.get("table") + " goes back to snapshot "
                + params.get("snapshotId") + ": rows written after it are no longer visible.";
        long target = whole(params.get("snapshotId"));
        var snapshots = reader.snapshots((String) params.get("namespace"), (String) params.get("table"));
        var chosen = snapshots.stream().filter(s -> s.snapshotId() == target).findFirst().orElse(null);
        if (chosen == null) {
            return base;
        }
        var discarded = snapshots.stream().filter(s -> s.sequenceNumber() > chosen.sequenceNumber()).toList();
        if (discarded.isEmpty()) {
            return base + " It is already the current snapshot: nothing is lost.";
        }
        long written = 0;
        StringBuilder list = new StringBuilder();
        for (var s : discarded) {
            long added = number(s.summary().get("added-records"));
            long deleted = number(s.summary().get("deleted-records"));
            if ("append".equals(s.operation()) || "overwrite".equals(s.operation())) {
                written += added;
            }
            list.append(list.length() == 0 ? "" : ", ").append(s.snapshotId()).append(" (").append(s.operation())
                    .append(" +").append(added).append(" -").append(deleted).append(")");
        }
        long rowsNow = number(snapshots.get(snapshots.size() - 1).summary().get("total-records"));
        long rowsThen = number(chosen.summary().get("total-records"));
        StringBuilder out = new StringBuilder(base).append(" Snapshots discarded: ").append(list)
                .append(". Rows in the table: ").append(rowsNow).append(" now, ").append(rowsThen).append(" after the rollback.");
        if (written > 0) {
            out.append(" WARNING: ").append(written).append(" rows were added by writes after that snapshot and will be lost;"
                    + " check they are not good data before approving.");
        }
        return out.toString();
    }

    private static long number(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void apply(Map<String, Object> params) throws SQLException {
        repair.apply(RepairSession.ROLLBACK, values(params));
    }

    private static Map<String, Object> values(Map<String, Object> params) {
        return Map.of("table", LakehouseSession.CATALOG_ALIAS + "." + params.get("namespace") + "." + params.get("table"),
                "snapshot_id", whole(params.get("snapshotId")));
    }

    private static String text(Map<String, Object> params, String name) {
        if (!(params.get(name) instanceof String value) || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be text");
        }
        return value;
    }

    static long whole(Object value) {
        if (value instanceof Long || value instanceof Integer) {
            return ((Number) value).longValue();
        }
        if (value instanceof String text && text.strip().matches("-?\\d{1,18}")) {
            return Long.parseLong(text.strip());
        }
        throw new IllegalArgumentException("snapshotId must be a whole number: " + value);
    }
}
