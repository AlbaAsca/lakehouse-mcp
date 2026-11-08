package io.github.albaasca.lakehouse.mcp;

import io.github.albaasca.lakehouse.mcp.CatalogReader.Column;
import io.github.albaasca.lakehouse.mcp.CatalogReader.PartitionField;
import io.github.albaasca.lakehouse.mcp.CatalogReader.Snapshot;
import io.github.albaasca.lakehouse.mcp.CatalogReader.TableDescription;
import io.github.albaasca.lakehouse.mcp.CatalogReader.TableRef;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** The tools that say what is in the catalog. The field names of these records are the contract with the model. */
@Component
public class CatalogTools {

    /** The sink commits about every 30 s, so a table can keep thousands of snapshots: too many for one answer. */
    static final int MAX_SNAPSHOTS = 20;

    record TableList(List<TableRef> tables) {}

    /**
     * @param snapshotCount every snapshot the table keeps
     * @param snapshots the {@link #MAX_SNAPSHOTS} most recent ones, oldest first
     */
    record TableDetails(TableRef table, List<Column> columns, List<PartitionField> partitions, int snapshotCount,
            List<Snapshot> snapshots) {

        static TableDetails of(TableDescription description, List<Snapshot> snapshots) {
            return new TableDetails(description.table(), description.columns(), description.partitions(),
                    snapshots.size(), snapshots.subList(Math.max(0, snapshots.size() - MAX_SNAPSHOTS), snapshots.size()));
        }
    }

    private final CatalogReader reader;

    public CatalogTools(CatalogReader reader) {
        this.reader = reader;
    }

    @McpTool(name = "list_tables", description = "Lists every table in the lakehouse, sorted by namespace and name.",
            annotations = @McpAnnotations(title = "List tables", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult listTables() {
        return ToolResults.of(() -> new TableList(reader.listTables()));
    }

    @McpTool(name = "describe_table", description = "Describes one table: its columns, its current partitions and its "
            + "most recent snapshots, oldest first, up to " + MAX_SNAPSHOTS + ". snapshotCount says how many it keeps in "
            + "total. Names are case-sensitive; list_tables gives the exact ones.",
            annotations = @McpAnnotations(title = "Describe a table", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public CallToolResult describeTable(
            @McpToolParam(description = "Namespace of the table, as list_tables gives it", required = true) String namespace,
            @McpToolParam(description = "Name of the table, as list_tables gives it", required = true) String table) {
        // A missing argument never gets here: the SDK checks the input schema and answers with an error result.
        return ToolResults.of(() -> TableDetails.of(reader.describe(namespace, table), reader.snapshots(namespace, table)));
    }
}
