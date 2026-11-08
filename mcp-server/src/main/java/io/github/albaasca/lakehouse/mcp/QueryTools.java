package io.github.albaasca.lakehouse.mcp;

import io.github.albaasca.lakehouse.mcp.CatalogReader.Snapshot;
import io.github.albaasca.lakehouse.mcp.SqlGuard.QueryResult;
import io.github.albaasca.lakehouse.mcp.SqlGuard.TableName;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The tools that read data, today or in the past. All of their SQL goes through {@link SqlGuard} and runs on the
 * {@code READ_ONLY} instance (decisions 20 and 21 of docs/architecture.md).
 */
@Component
public class QueryTools {

    private static final Logger LOG = LoggerFactory.getLogger(QueryTools.class);

    static final int DEFAULT_MAX_ROWS = 100;
    static final int MAX_ROWS = 1_000;

    /** The only name the SQL of time_travel can read. It lives in one connection, so calls at once do not see each other. */
    static final String AT_SNAPSHOT = "at_snapshot";

    /**
     * Mistakes in the SQL that the validator cannot see, as they depend on the data or the columns, and that the model can
     * fix if it reads them. Any other failure of DuckDB can carry paths of the store, so it stays in the log.
     */
    private static final List<String> FIXABLE_ERRORS = List.of("Binder Error", "Conversion Error", "Out of Range Error");

    /** {@code rows} holds only what JSON says the same on any machine: see {@link #cell}. */
    record Rows(List<String> columns, List<List<Object>> rows, boolean truncated) {

        static Rows of(QueryResult result) {
            List<List<Object>> rows = new ArrayList<>(result.rows().size());
            for (List<Object> row : result.rows()) {
                List<Object> cells = new ArrayList<>(row.size());
                row.forEach(value -> cells.add(cell(value)));
                rows.add(cells);
            }
            return new Rows(result.columns(), rows, result.truncated());
        }
    }

    /** @param snapshotId the one that was read: when the question gave an instant, this is the one it became */
    record PastRows(long snapshotId, Instant committedAt, List<String> columns, List<List<Object>> rows, boolean truncated) {}

    private final LakehouseSession session;
    private final CatalogReader reader;
    private final SqlGuard guard;

    public QueryTools(LakehouseSession session, CatalogReader reader) {
        this.session = session;
        this.reader = reader;
        this.guard = new SqlGuard(session::query);
    }

    @McpTool(name = "run_query", description = "Runs one read-only SELECT on the current data. Name tables in full, as "
            + "<catalog>.<namespace>.<table> with catalog lh; list_tables gives the rest. Table functions and AT (...) are "
            + "not allowed. The answer has columns, rows and truncated, which says there were more rows than maxRows.",
            annotations = @McpAnnotations(title = "Run a query", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult runQuery(
            @McpToolParam(description = "One SELECT", required = true) String sql,
            @McpToolParam(description = "Most rows to return, from 1 to " + MAX_ROWS + "; " + DEFAULT_MAX_ROWS
                    + " if left out", required = false) Integer maxRows) {
        return ToolResults.of(() -> runQueryChecked(sql, rowLimit(maxRows)));
    }

    private Rows runQueryChecked(String sql, int maxRows) throws SQLException {
        List<TableName> tables = reader.listTables().stream()
                .map(table -> new TableName(LakehouseSession.CATALOG_ALIAS, table.namespace(), table.table()))
                .toList();
        return explained(() -> Rows.of(guard.run(sql, tables, maxRows)));
    }

    @McpTool(name = "time_travel", description = "Runs one read-only SELECT on a table as it was in a past snapshot. "
            + "The SELECT reads from a view called " + AT_SNAPSHOT + ", the only table it can name. Say which snapshot with "
            + "exactly one of snapshotId, which describe_table lists, or instant. The answer has the snapshotId and "
            + "committedAt that were read, plus columns, rows and truncated, which says there were more rows than maxRows.",
            annotations = @McpAnnotations(title = "Query the past", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult timeTravel(
            @McpToolParam(description = "Namespace of the table, as list_tables gives it", required = true) String namespace,
            @McpToolParam(description = "Name of the table, as list_tables gives it", required = true) String table,
            @McpToolParam(description = "One SELECT that reads from " + AT_SNAPSHOT, required = true) String sql,
            @McpToolParam(description = "Id of the snapshot to read", required = false) Long snapshotId,
            @McpToolParam(description = "ISO-8601 instant with its zone, such as 2026-03-01T10:00:00Z: reads the last "
                    + "snapshot committed at or before it", required = false) String instant,
            @McpToolParam(description = "Most rows to return, from 1 to " + MAX_ROWS + "; " + DEFAULT_MAX_ROWS
                    + " if left out", required = false) Integer maxRows) {
        return ToolResults.of(() -> {
            int limit = rowLimit(maxRows);
            Snapshot snapshot = snapshot(namespace, table, snapshotId, instant);
            Rows rows = explained(() -> readAt(CatalogReader.qualifiedName(namespace, table), snapshot.snapshotId(), sql, limit));
            return new PastRows(snapshot.snapshotId(), snapshot.committedAt(), rows.columns(), rows.rows(), rows.truncated());
        });
    }

    /**
     * Validates before creating the view, and on the reserved connection, or a pool of one would wait for itself. The view
     * is {@code SELECT *} until analyst has its own projection (T-054).
     */
    private Rows readAt(String qualifiedTable, long snapshotId, String sql, int maxRows) throws SQLException {
        return session.withConnection(reserved -> {
            String guarded = new SqlGuard(reserved::query).guarded(sql, List.of(TableName.of(AT_SNAPSHOT)), maxRows);
            reserved.execute("CREATE OR REPLACE TEMP VIEW " + AT_SNAPSHOT + " AS SELECT * FROM " + qualifiedTable
                    + " AT (VERSION => " + snapshotId + ")");
            try {
                return Rows.of(reserved.query(guarded, SqlGuard.limitedTo(maxRows)));
            } finally {
                drop(reserved);
            }
        });
    }

    /** A view left behind harms nothing: only time_travel can name it, and it replaces it first. So it must not hide the answer. */
    private static void drop(LakehouseSession.Reserved reserved) {
        try {
            reserved.execute("DROP VIEW IF EXISTS " + AT_SNAPSHOT);
        } catch (SQLException e) {
            LOG.warn("Could not drop the time travel view", e);
        }
    }

    private Snapshot snapshot(String namespace, String table, Long snapshotId, String instant) throws SQLException {
        if ((snapshotId == null) == (instant == null)) {
            throw new IllegalArgumentException("Give exactly one of snapshotId and instant");
        }
        Instant moment = instant == null ? null : instant(instant);
        List<Snapshot> snapshots = reader.snapshots(namespace, table);
        String name = LakehouseSession.CATALOG_ALIAS + "." + namespace + "." + table;
        return snapshotId != null ? byId(snapshots, snapshotId, name) : atOrBefore(snapshots, moment, instant, name);
    }

    private static Snapshot byId(List<Snapshot> snapshots, long id, String name) {
        return snapshots.stream().filter(snapshot -> snapshot.snapshotId() == id).findFirst()
                .orElseThrow(() -> new NoSuchElementException("No snapshot " + id + " in " + name
                        + ". describe_table lists the ones it keeps."));
    }

    /** On equal times, the later commit: the list comes oldest first. */
    static Snapshot atOrBefore(List<Snapshot> snapshots, Instant moment, String written, String name) {
        Snapshot best = null;
        for (Snapshot snapshot : snapshots) {
            if (!snapshot.committedAt().isAfter(moment)
                    && (best == null || !snapshot.committedAt().isBefore(best.committedAt()))) {
                best = snapshot;
            }
        }
        if (best == null) {
            throw new NoSuchElementException("No snapshot of " + name + " at or before " + written + ". describe_table "
                    + "lists the ones it keeps.");
        }
        return best;
    }

    /** No zone, no instant: DuckDB or the machine would have to guess it, and the answer would change with them. */
    static Instant instant(String written) {
        try {
            return OffsetDateTime.parse(written).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("instant must be ISO-8601 with its zone, such as 2026-03-01T10:00:00Z: "
                    + written);
        }
    }

    static int rowLimit(Integer maxRows) {
        if (maxRows == null) {
            return DEFAULT_MAX_ROWS;
        }
        if (maxRows < 1 || maxRows > MAX_ROWS) {
            throw new IllegalArgumentException("maxRows must be from 1 to " + MAX_ROWS + ": " + maxRows);
        }
        return maxRows;
    }

    /** One value of a row, as JSON will show it. */
    static Object cell(Object value) {
        return switch (value) {
            case null -> null;
            case String text -> text;
            case Boolean flag -> flag;
            // Exact types only: a sum of BIGINT is a HUGEINT, and a BigInteger as text or double would lose its digits.
            case BigDecimal number -> number;
            case BigInteger number -> number;
            case Number number -> number;
            // A date without a zone stays as it reads: as a java.util.Date, JSON would move it by the zone of the machine.
            case Timestamp time -> time.toLocalDateTime().toString();
            case Date date -> date.toLocalDate().toString();
            case Time time -> time.toLocalTime().toString();
            // Lists, structs, blobs, UUID, OffsetDateTime: their text is readable, and JSON cannot fail on them.
            default -> value.toString();
        };
    }

    @FunctionalInterface
    private interface Read<T> {
        T get() throws SQLException;
    }

    private static <T> T explained(Read<T> read) throws SQLException {
        try {
            return read.get();
        } catch (SQLTimeoutException e) {
            throw e;
        } catch (SQLException e) {
            String message = e.getMessage();
            if (message != null && FIXABLE_ERRORS.stream().anyMatch(message::startsWith)) {
                throw new IllegalArgumentException(message, e);
            }
            throw e;
        }
    }
}
