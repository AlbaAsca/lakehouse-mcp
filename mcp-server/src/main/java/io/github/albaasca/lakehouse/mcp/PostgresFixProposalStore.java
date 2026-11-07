package io.github.albaasca.lakehouse.mcp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@link FixProposalStore} on Postgres. One short connection per call: proposals are rare. */
final class PostgresFixProposalStore implements FixProposalStore {

    private static final String COLUMNS = "id, action, params, reason, expected_effect, status, approved_by, created_at, "
            + "approved_at, applied_at, error";

    private final String url;
    private final String user;
    private final String password;

    PostgresFixProposalStore(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** Creates the table if it is not there. Fails if the database cannot be reached: the server does not start then. */
    PostgresFixProposalStore initialize() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS fix_proposals ("
                    + "id BIGSERIAL PRIMARY KEY, action TEXT NOT NULL, params TEXT NOT NULL, reason TEXT NOT NULL, "
                    + "expected_effect TEXT NOT NULL, status TEXT NOT NULL, approved_by TEXT, "
                    + "created_at TIMESTAMPTZ NOT NULL, approved_at TIMESTAMPTZ, applied_at TIMESTAMPTZ, error TEXT)");
        }
        return this;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    @Override
    public long create(String action, String params, String reason, String expectedEffect, Instant now) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement("INSERT INTO fix_proposals (action, params, "
                        + "reason, expected_effect, status, created_at) VALUES (?, ?, ?, ?, 'PROPOSED', ?) RETURNING id")) {
            statement.setString(1, action);
            statement.setString(2, params);
            statement.setString(3, reason);
            statement.setString(4, expectedEffect);
            statement.setTimestamp(5, Timestamp.from(now));
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    @Override
    public Optional<Proposal> find(long id) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT " + COLUMNS + " FROM fix_proposals WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(read(rows)) : Optional.empty();
            }
        }
    }

    @Override
    public List<Proposal> list() throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT " + COLUMNS + " FROM fix_proposals ORDER BY id DESC")) {
            List<Proposal> out = new ArrayList<>();
            while (rows.next()) {
                out.add(read(rows));
            }
            return out;
        }
    }

    @Override
    public boolean approve(long id, String approvedBy, Instant now) throws SQLException {
        return update("UPDATE fix_proposals SET status = 'APPROVED', approved_by = ?, approved_at = ? "
                + "WHERE id = ? AND status = 'PROPOSED'", approvedBy, Timestamp.from(now), id) == 1;
    }

    @Override
    public boolean claim(long id) throws SQLException {
        return update("UPDATE fix_proposals SET status = 'APPLYING' WHERE id = ? AND status = 'APPROVED'", id) == 1;
    }

    @Override
    public void finish(long id, Status status, String error, Instant now) throws SQLException {
        update("UPDATE fix_proposals SET status = ?, error = ?, applied_at = ? WHERE id = ? AND status = 'APPLYING'",
                status.name(), error, Timestamp.from(now), id);
    }

    private int update(String sql, Object... values) throws SQLException {
        try (Connection connection = connect(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setObject(i + 1, values[i]);
            }
            return statement.executeUpdate();
        }
    }

    private static Proposal read(ResultSet rows) throws SQLException {
        return new Proposal(rows.getLong(1), rows.getString(2), rows.getString(3), rows.getString(4), rows.getString(5),
                Status.valueOf(rows.getString(6)), rows.getString(7), instant(rows, 8), instant(rows, 9), instant(rows, 10),
                rows.getString(11));
    }

    private static Instant instant(ResultSet rows, int column) throws SQLException {
        Timestamp value = rows.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
