package io.github.albaasca.lakehouse.testkit;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Writes testdata/synthetic-events.csv.gz straight into an Iceberg table, so query tests do not go through Kafka.
 *
 * <p>Same columns and partitions as the table the sink creates (producer/src/main/avro/event.avsc and
 * connect/iceberg-sink.json), but the files are written by DuckDB: repairs of files written by the sink need the real
 * path (T-038).
 */
public final class SyntheticLoader {

    private static final String COLUMNS = "event_time TIMESTAMPTZ, event_type VARCHAR NOT NULL, product_id BIGINT, "
            + "category_id BIGINT, category_code VARCHAR, brand VARCHAR, price DOUBLE, user_id BIGINT, user_session VARCHAR";
    private static final String CSV_COLUMNS = "{'event_time': 'TIMESTAMPTZ', 'event_type': 'VARCHAR', "
            + "'product_id': 'BIGINT', 'category_id': 'BIGINT', 'category_code': 'VARCHAR', 'brand': 'VARCHAR', "
            + "'price': 'DOUBLE', 'user_id': 'BIGINT', 'user_session': 'VARCHAR'}";

    private SyntheticLoader() {}

    /** The sample in the repository. */
    public static Path sample() {
        return LakehouseStack.repositoryRoot().resolve("testdata").resolve("synthetic-events.csv.gz");
    }

    /**
     * Creates {@code lh.<namespace>.<table>}, which must not exist yet, with every row of the sample, duplicates
     * included.
     *
     * @param writer a connection with the catalog attached by {@link LakehouseStack#attachDuckDb(Connection)}
     * @return the rows in the new table
     */
    public static long load(Connection writer, String namespace, String table) throws SQLException {
        String name = LakehouseStack.DUCKDB_CATALOG + "." + namespace + "." + table;
        String path = sample().toString().replace("'", "''");
        try (Statement statement = writer.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + LakehouseStack.DUCKDB_CATALOG + "." + namespace);
            statement.execute("CREATE TABLE " + name + " (" + COLUMNS + ") PARTITIONED BY (day(event_time), event_type)");
            statement.execute("INSERT INTO " + name + " SELECT * FROM read_csv('" + path
                    + "', header = true, auto_detect = false, columns = " + CSV_COLUMNS + ")");
            try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + name)) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }
}
