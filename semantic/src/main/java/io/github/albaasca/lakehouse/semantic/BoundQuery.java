package io.github.albaasca.lakehouse.semantic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * SQL with {@code ?} markers and the values for them, in order. Values are never written into the text.
 *
 * @param values {@code null}, {@code Long} or {@code String} (dates travel as ISO text and the SQL casts them)
 */
public record BoundQuery(String sql, List<Object> values) {

    public PreparedStatement prepare(Connection connection) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < values.size(); i++) {
                Object value = values.get(i);
                if (value == null) {
                    statement.setNull(i + 1, Types.VARCHAR);
                } else if (value instanceof Long number) {
                    statement.setLong(i + 1, number);
                } else {
                    statement.setString(i + 1, value.toString());
                }
            }
        } catch (SQLException | RuntimeException e) {
            statement.close();
            throw e;
        }
        return statement;
    }
}
