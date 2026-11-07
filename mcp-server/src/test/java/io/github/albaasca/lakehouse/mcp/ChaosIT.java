package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.albaasca.lakehouse.mcp.LakehouseSession.Catalog;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Settings;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Storage;
import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import io.github.albaasca.lakehouse.testkit.SyntheticLoader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Row counts per day before and after each failure, and the undo by rollback, on a real catalog. */
class ChaosIT {

    private static final String NS = "chaos_it";

    private static LakehouseStack stack;
    private static Connection reader;
    private static Chaos chaos;

    @BeforeAll
    static void start() throws Exception {
        stack = LakehouseStack.shared();
        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:")) {
            stack.attachDuckDb(writer);
            SyntheticLoader.load(writer, NS, "dups");
            SyntheticLoader.load(writer, NS, "empty");
        }
        reader = DriverManager.getConnection("jdbc:duckdb:");
        stack.attachDuckDb(reader);
        run("SET TimeZone = 'UTC'");
        chaos = new Chaos(Settings.of(
                new Catalog(LakehouseStack.CATALOG, stack.polarisUrl(), LakehouseStack.CLIENT_ID, LakehouseStack.CLIENT_SECRET),
                new Storage(stack.s3Endpoint(), LakehouseStack.S3_ACCESS_KEY, LakehouseStack.S3_SECRET_KEY, LakehouseStack.S3_REGION)));
    }

    @AfterAll
    static void close() throws Exception {
        if (reader != null) {
            reader.close();
        }
    }

    @Test
    void duplicatesReinsertsTheExpectedRowsOfOneDayOnly() throws Exception {
        String table = "lh." + NS + ".dups";
        LocalDate day = busiestDay(table);
        long dayRows = count(table, day);
        long others = count(table, null) - dayRows;
        long expected = (long) Math.ceil(dayRows * 0.10);

        Map<String, Object> report = chaos.duplicates(NS, "dups", day, 10);

        assertThat(report).containsEntry("scenario", "duplicates").containsEntry("rowsDuplicated", expected)
                .containsEntry("rowsOfTheDayBefore", dayRows).containsKeys("at", "snapshotBefore", "snapshotAfter");
        assertThat(count(table, day)).isEqualTo(dayRows + expected);
        assertThat(count(table, null) - count(table, day)).isEqualTo(others);

        run("CALL iceberg_rollback_to_snapshot('" + table + "', " + report.get("snapshotBefore") + ")");
        assertThat(count(table, day)).isEqualTo(dayRows);
    }

    @Test
    void emptyPartitionDeletesOneDayAndRollbackBringsItBack() throws Exception {
        String table = "lh." + NS + ".empty";
        LocalDate day = busiestDay(table);
        long dayRows = count(table, day);
        long total = count(table, null);

        Map<String, Object> report = chaos.emptyPartition(NS, "empty", day);

        assertThat(report).containsEntry("scenario", "empty-partition").containsEntry("rowsDeleted", dayRows);
        assertThat(count(table, day)).isZero();
        assertThat(count(table, null)).isEqualTo(total - dayRows);

        run("CALL iceberg_rollback_to_snapshot('" + table + "', " + report.get("snapshotBefore") + ")");
        assertThat(count(table, null)).isEqualTo(total);
    }

    @Test
    void refusesADayWithoutRowsAndBadNames() {
        assertThatThrownBy(() -> chaos.emptyPartition(NS, "empty", LocalDate.of(1999, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chaos.duplicates("a b", "t", LocalDate.of(2019, 10, 1), 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static LocalDate busiestDay(String table) throws SQLException {
        try (Statement s = reader.createStatement();
                ResultSet r = s.executeQuery("SELECT CAST(event_time AS DATE) d FROM " + table
                        + " GROUP BY d ORDER BY count(*) DESC, d LIMIT 1")) {
            r.next();
            return r.getObject(1, LocalDate.class);
        }
    }

    private static long count(String table, LocalDate day) throws SQLException {
        String where = day == null ? "" : " WHERE CAST(event_time AS DATE) = DATE '" + day + "'";
        try (Statement s = reader.createStatement(); ResultSet r = s.executeQuery("SELECT count(*) FROM " + table + where)) {
            r.next();
            return r.getLong(1);
        }
    }

    private static void run(String sql) throws SQLException {
        try (Statement s = reader.createStatement()) {
            s.execute(sql);
        }
    }
}
