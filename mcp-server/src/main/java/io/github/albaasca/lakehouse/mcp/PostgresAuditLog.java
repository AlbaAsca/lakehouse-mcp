package io.github.albaasca.lakehouse.mcp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/** {@link AuditLog} on the ops Postgres, next to the proposals. Insert and read only. */
final class PostgresAuditLog implements AuditLog {

    private final String url;
    private final String user;
    private final String password;

    PostgresAuditLog(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    PostgresAuditLog initialize() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS audit_log (id BIGSERIAL PRIMARY KEY, "
                    + "ts TIMESTAMPTZ NOT NULL, role TEXT NOT NULL, tool TEXT NOT NULL, params TEXT NOT NULL, "
                    + "outcome TEXT NOT NULL, duration_ms BIGINT NOT NULL, proposal_id BIGINT)");
        }
        return this;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /** A failure to audit must not break the call that was already answered: it is logged and swallowed. */
    @Override
    public void record(Event event) {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("INSERT INTO audit_log (ts, role, tool, params, "
                        + "outcome, duration_ms, proposal_id) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            statement.setTimestamp(1, Timestamp.from(event.ts()));
            statement.setString(2, event.role());
            statement.setString(3, event.tool());
            statement.setString(4, event.params());
            statement.setString(5, event.outcome());
            statement.setLong(6, event.durationMs());
            statement.setObject(7, event.proposalId());
            statement.executeUpdate();
        } catch (SQLException e) {
            org.slf4j.LoggerFactory.getLogger(PostgresAuditLog.class).error("Could not write the audit log", e);
        }
    }

    @Override
    public List<Event> page(int page, int size) {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("SELECT id, ts, role, tool, params, outcome, "
                        + "duration_ms, proposal_id FROM audit_log ORDER BY id DESC LIMIT ? OFFSET ?")) {
            statement.setInt(1, size);
            statement.setLong(2, (long) page * size);
            try (ResultSet rows = statement.executeQuery()) {
                List<Event> out = new ArrayList<>();
                while (rows.next()) {
                    long proposal = rows.getLong(8);
                    out.add(new Event(rows.getLong(1), rows.getTimestamp(2).toInstant(), rows.getString(3),
                            rows.getString(4), rows.getString(5), rows.getString(6), rows.getLong(7),
                            rows.wasNull() ? null : proposal));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the audit log", e);
        }
    }

    @Override
    public long count() {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM audit_log")) {
            rows.next();
            return rows.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the audit log", e);
        }
    }
}
