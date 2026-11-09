package io.github.albaasca.lakehouse.mcp;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Hides {@code user_id} from the analyst (decisions 12 and 21 of docs/architecture.md): it is turned into a salted hash, the
 * same one everywhere, so users can be counted and grouped without being told who they are. The admin reads the real value.
 *
 * <p>Current data is read through views {@code memory.main.<namespace>__<table>}, made once per instance and so seen by
 * every connection of the pool. A view cannot travel in time, so time_travel builds its own projection with {@link
 * #projection()}. The salt lives only in the text of those statements: never in a message, a log or the audit trail.
 */
final class Masking {

    static final String SCHEMA = "memory.main";
    private static final String COLUMN = "user_id";

    private final String salt;
    /** Views already built with the hash. A table without user_id is rebuilt each time: it may gain the column later. */
    private final Set<String> masked = ConcurrentHashMap.newKeySet();

    /** @param salt blank means a random one for this run: counts agree within it, and nobody can reproduce the hashes */
    Masking(String salt) {
        this.salt = salt == null || salt.isBlank() || salt.startsWith("${") ? randomSalt() : salt;
    }

    private static String randomSalt() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** Whether the caller of this tool is the admin. Anything else, even a missing identity, gets the masked data. */
    static boolean isAdmin() {
        Authentication who = SecurityContextHolder.getContext().getAuthentication();
        return who != null && who.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /** The one expression that hides the identifier: the view and time_travel both use it. */
    private String hash() {
        return "left(sha256(CAST(" + COLUMN + " AS VARCHAR) || " + LakehouseSession.quote(salt) + "), 16)";
    }

    /** The select list that replaces user_id and leaves every other column alone. */
    String projection() {
        return "* REPLACE (" + hash() + " AS " + COLUMN + ")";
    }

    /** Whether DuckDB refused {@link #projection()} because the table has no user_id. */
    static boolean lacksColumn(SQLException e) {
        String message = e.getMessage();
        return message != null && message.contains("REPLACE list");
    }

    static String viewName(String namespace, String table) {
        return (namespace + "__" + table).replaceAll("[^A-Za-z0-9_]", "_");
    }

    /** Builds the view of a table if it is not there yet, and returns its full name. */
    String ensureView(LakehouseSession session, String namespace, String table) throws SQLException {
        String view = viewName(namespace, table);
        String full = SCHEMA + "." + view;
        if (masked.contains(view)) {
            return full;
        }
        String source = CatalogReader.qualifiedName(namespace, table);
        session.withConnection(reserved -> {
            try {
                reserved.execute("CREATE OR REPLACE VIEW " + full + " AS SELECT " + projection() + " FROM " + source);
                masked.add(view);
            } catch (SQLException e) {
                if (!lacksColumn(e)) {
                    throw new SQLException("Could not prepare the masked view of " + namespace + "." + table);
                }
                reserved.execute("CREATE OR REPLACE VIEW " + full + " AS SELECT * FROM " + source);
            }
            return null;
        });
        return full;
    }
}
