package io.github.albaasca.lakehouse.mcp;

import java.sql.SQLException;
import java.util.Map;

/**
 * One repair the agent may propose, from a closed list ({@link Type}): the model picks a name and gives parameters, never
 * SQL. To add a repair, add a value to {@link Type} and an implementation to {@link FixActions}.
 */
interface FixAction {

    /** The closed list of repairs. This enum is what the MCP tool offers to the model. */
    enum Type {
        ROLLBACK_SNAPSHOT,
        DEDUPLICATE_PARTITION,
        RESUME_CONNECTOR
    }

    Type type();

    /**
     * Checks the parameters against the catalog and returns them in canonical form (what is stored and later applied).
     *
     * @throws IllegalArgumentException if they do not make sense; nothing is proposed then
     */
    Map<String, Object> validate(Map<String, Object> params) throws SQLException;

    /** What will change, in a sentence for the person who approves. Takes canonical parameters. */
    String expectedEffect(Map<String, Object> params) throws SQLException;

    /** Does the repair. Takes canonical parameters, already approved. */
    void apply(Map<String, Object> params) throws SQLException;
}
