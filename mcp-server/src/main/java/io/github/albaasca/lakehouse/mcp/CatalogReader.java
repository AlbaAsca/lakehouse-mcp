package io.github.albaasca.lakehouse.mcp;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * What the catalog knows about its tables, read through DuckDB (docs/architecture.md, decision 5).
 *
 * <p>Columns come from {@code DESCRIBE} and not from {@code information_schema.columns}: in a session that has not
 * read the table yet that view answers one column called {@code __}, and later it misses the columns added since.
 * Names are exact: DuckDB's Iceberg functions tell {@code ns} from {@code NS}, so everything here does too.
 */
public final class CatalogReader {

    private static final String CATALOG = LakehouseSession.CATALOG_ALIAS;

    public record TableRef(String namespace, String table) {}

    /** Without nullability on purpose: DuckDB reports every column as nullable, also the ones Iceberg requires. */
    public record Column(String name, String type) {}

    public record PartitionField(String name, String transform, String sourceColumn) {}

    /** @param partitions the current partition spec; empty if the table is not partitioned */
    public record TableDescription(TableRef table, List<Column> columns, List<PartitionField> partitions) {}

    /**
     * @param parentId null for the first snapshot of a table
     * @param summary the totals Iceberg keeps for this commit, by their Iceberg names
     */
    public record Snapshot(long sequenceNumber, long snapshotId, Long parentId, Instant committedAt, String operation,
            Map<String, String> summary) {}

    private final LakehouseSession session;

    public CatalogReader(LakehouseSession session) {
        this.session = session;
    }

    public List<TableRef> listTables() throws SQLException {
        return session.query("SELECT table_schema, table_name FROM information_schema.tables WHERE table_catalog = "
                + LakehouseSession.quote(CATALOG) + " AND table_type = 'BASE TABLE' ORDER BY 1, 2", rows -> {
                    List<TableRef> tables = new ArrayList<>();
                    while (rows.next()) {
                        tables.add(new TableRef(rows.getString(1), rows.getString(2)));
                    }
                    return tables;
                });
    }

    /** @throws NoSuchElementException if the table does not exist */
    public TableDescription describe(String namespace, String table) throws SQLException {
        requireTable(namespace, table);
        List<Column> columns = session.query("DESCRIBE " + CATALOG + "." + identifier(namespace) + "." + identifier(table),
                rows -> {
                    List<Column> out = new ArrayList<>();
                    while (rows.next()) {
                        out.add(new Column(rows.getString("column_name"), rows.getString("column_type")));
                    }
                    return out;
                });
        // No ORDER BY: the function answers in the order of the spec, which field ids need not follow.
        List<PartitionField> partitions = session.query("SELECT name, transform, source_column FROM "
                + "iceberg_partition_specs(" + functionArgument(namespace, table) + ") WHERE is_default",
                rows -> {
                    List<PartitionField> out = new ArrayList<>();
                    while (rows.next()) {
                        out.add(new PartitionField(rows.getString(1), rows.getString(2), rows.getString(3)));
                    }
                    return out;
                });
        return new TableDescription(new TableRef(namespace, table), columns, partitions);
    }

    /**
     * Every snapshot the table still keeps, oldest first; empty if nothing has been written to it.
     *
     * @throws NoSuchElementException if the table does not exist
     */
    public List<Snapshot> snapshots(String namespace, String table) throws SQLException {
        requireTable(namespace, table);
        // epoch_ms because the driver would read timestamp_ms as a local time, and the result would depend on the machine.
        return session.query("SELECT sequence_number, snapshot_id, parent_id, epoch_ms(timestamp_ms), operation, summary "
                + "FROM iceberg_snapshots(" + functionArgument(namespace, table) + ") ORDER BY sequence_number", rows -> {
                    List<Snapshot> out = new ArrayList<>();
                    while (rows.next()) {
                        Number parent = (Number) rows.getObject(3);
                        out.add(new Snapshot(((Number) rows.getObject(1)).longValue(),
                                ((Number) rows.getObject(2)).longValue(), parent == null ? null : parent.longValue(),
                                Instant.ofEpochMilli(rows.getLong(4)), rows.getString(5), summary(rows.getObject(6))));
                    }
                    return out;
                });
    }

    private static Map<String, String> summary(Object map) {
        Map<String, String> out = new LinkedHashMap<>();
        ((Map<?, ?>) map).forEach((key, value) -> out.put(String.valueOf(key), String.valueOf(value)));
        return out;
    }

    /** Without this the functions answer about «version-hint» when a table is missing, which sends you the wrong way. */
    private void requireTable(String namespace, String table) throws SQLException {
        boolean exists = session.query("SELECT 1 FROM information_schema.tables WHERE table_catalog = "
                + LakehouseSession.quote(CATALOG) + " AND table_type = 'BASE TABLE' AND table_schema = "
                + LakehouseSession.quote(namespace) + " AND table_name = " + LakehouseSession.quote(table),
                rows -> rows.next());
        if (!exists) {
            throw new NoSuchElementException("No table " + CATALOG + "." + namespace + "." + table);
        }
    }

    /** The functions parse this text as a qualified name, so each part is quoted or a dot in it would split it. */
    private static String functionArgument(String namespace, String table) {
        return LakehouseSession.quote(CATALOG + "." + identifier(namespace) + "." + identifier(table));
    }

    private static String identifier(String name) {
        return '"' + name.replace("\"", "\"\"") + '"';
    }
}
