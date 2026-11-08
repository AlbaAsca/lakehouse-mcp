package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.albaasca.lakehouse.mcp.LakehouseSession.Catalog;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Settings;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Storage;
import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import io.github.albaasca.lakehouse.testkit.SyntheticLoader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The connection of the server against a real catalog, with the sample loaded without Kafka. */
class LakehouseSessionIT {

    private static final String TABLE = "lh.session_it.events";
    private static final String ROW_SQL = "SELECT epoch_us(event_time), event_type, product_id, category_id, "
            + "category_code, brand, price, user_id, user_session FROM " + TABLE;
    private static final String ENDLESS = "SELECT sum(i) FROM range(1000000000000000) t(i)";
    private static final DateTimeFormatter CSV_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");

    private static LakehouseStack stack;
    private static long loaded;

    @BeforeAll
    static void load() throws Exception {
        stack = LakehouseStack.shared();
        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:")) {
            stack.attachDuckDb(writer);
            loaded = SyntheticLoader.load(writer, "session_it", "events");
        }
    }

    private static Settings settings() {
        return Settings.of(
                new Catalog(LakehouseStack.CATALOG, stack.polarisUrl(), LakehouseStack.CLIENT_ID, LakehouseStack.CLIENT_SECRET),
                new Storage(stack.s3Endpoint(), LakehouseStack.S3_ACCESS_KEY, LakehouseStack.S3_SECRET_KEY, LakehouseStack.S3_REGION));
    }

    private static List<String> strings(LakehouseSession session, String sql) throws SQLException {
        return session.query(sql, rows -> {
            int columns = rows.getMetaData().getColumnCount();
            List<String> out = new ArrayList<>();
            while (rows.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    row.add(String.valueOf(rows.getString(i)));
                }
                out.add(String.join("|", row));
            }
            return out;
        });
    }

    /** The reference: read with plain Java from the versioned file, without DuckDB. */
    private static List<String> rowsOfTheSample() throws IOException {
        List<String> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(Files.newInputStream(SyntheticLoader.sample())), StandardCharsets.UTF_8))) {
            assertThat(reader.readLine()).isEqualTo(
                    "event_time,event_type,product_id,category_id,category_code,brand,price,user_id,user_session");
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String[] f = line.split(",", -1);
                assertThat(f).hasSize(9);
                Instant time = LocalDateTime.parse(f[0], CSV_TIME).toInstant(ZoneOffset.UTC);
                rows.add(String.join("|", String.valueOf(ChronoUnit.MICROS.between(Instant.EPOCH, time)), f[1], f[2], f[3],
                        nullIfEmpty(f[4]), nullIfEmpty(f[5]), String.valueOf(Double.parseDouble(f[6])), f[7], nullIfEmpty(f[8])));
            }
        }
        return rows;
    }

    private static String nullIfEmpty(String field) {
        return field.isEmpty() ? "null" : field;
    }

    @Test
    void readsEveryRowOfTheSampleThroughTheServerConnection() throws Exception {
        List<String> expected = rowsOfTheSample();
        Collections.sort(expected);

        List<String> actual;
        try (LakehouseSession session = new LakehouseSession(settings())) {
            actual = session.query(ROW_SQL, rows -> {
                List<String> out = new ArrayList<>();
                while (rows.next()) {
                    out.add(String.join("|", rows.getString(1), rows.getString(2), rows.getString(3), rows.getString(4),
                            String.valueOf(rows.getString(5)), String.valueOf(rows.getString(6)),
                            String.valueOf(rows.getDouble(7)), rows.getString(8), String.valueOf(rows.getString(9))));
                }
                return out;
            });
        }
        Collections.sort(actual);

        assertThat(loaded).isEqualTo(50_000);
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(actual.get(i)).as("sorted row %d", i).isEqualTo(expected.get(i));
        }
    }

    /** Figures from infra/synthetic/README.md, which come from the generator and not from this code. */
    @Test
    void theSampleKeepsItsDirtyCasesAndItsPartitions() throws Exception {
        try (LakehouseSession session = new LakehouseSession(settings())) {
            assertThat(strings(session, "SELECT count(*) FILTER (category_code IS NULL), count(*) FILTER (brand IS NULL), "
                    + "count(*) FILTER (user_session IS NULL), count(*) FILTER (price = 0), count(*) FILTER (price < 0) FROM "
                    + TABLE)).containsExactly("11952|5029|50|100|102");
            assertThat(strings(session, "SELECT count(*) FROM (SELECT DISTINCT * FROM " + TABLE + ")"))
                    .containsExactly("49750");
            assertThat(strings(session, "SELECT count(*) FROM " + TABLE
                    + " WHERE brand = 'synth-nimbora' AND event_type = 'purchase' AND event_time::DATE = DATE '2024-03-10'"))
                    .containsExactly("10");
            assertThat(strings(session, "SELECT transform, source_column FROM iceberg_partition_specs('" + TABLE
                    + "') ORDER BY field_id")).containsExactly("day|event_time", "identity|event_type");
        }
    }

    /** Every data file holds one partition only, and each partition has the rows the plain Java reference counts. */
    @Test
    void theRowsAreReallyStoredByDayAndEventType() throws Exception {
        Map<String, Long> expected = new TreeMap<>();
        for (String row : rowsOfTheSample()) {
            String[] f = row.split("\\|", -1);
            long day = Math.floorDiv(Long.parseLong(f[0]), 86_400_000_000L);
            expected.merge(LocalDate.ofEpochDay(day) + "|" + f[1], 1L, Long::sum);
        }
        String utcDay = "strftime(timezone('UTC', event_time), '%Y-%m-%d')";

        try (LakehouseSession session = new LakehouseSession(settings())) {
            Map<String, Long> actual = session.query("SELECT " + utcDay + " || '|' || event_type, count(*) FROM " + TABLE
                    + " GROUP BY 1", rows -> {
                        Map<String, Long> out = new TreeMap<>();
                        while (rows.next()) {
                            out.put(rows.getString(1), rows.getLong(2));
                        }
                        return out;
                    });
            assertThat(actual).containsExactlyEntriesOf(expected);

            assertThat(strings(session, "SELECT count(*) FROM (SELECT filename FROM " + TABLE + " GROUP BY filename "
                    + "HAVING count(DISTINCT event_type) > 1 OR count(DISTINCT " + utcDay + ") > 1)"))
                    .containsExactly("0");
            assertThat(Long.parseLong(strings(session, "SELECT count(DISTINCT filename) FROM " + TABLE).get(0)))
                    .isGreaterThanOrEqualTo(expected.size());
        }
    }

    /** The server stays open for hours: it has to see what the sink writes after it started. */
    @Test
    void aSessionAlreadyOpenSeesNewTablesAndNewRows() throws Exception {
        try (LakehouseSession session = new LakehouseSession(settings().withPoolSize(1));
                Connection writer = DriverManager.getConnection("jdbc:duckdb:")) {
            assertThat(strings(session, "SELECT count(*) FROM " + TABLE)).containsExactly("50000");

            stack.attachDuckDb(writer);
            SyntheticLoader.load(writer, "session_it_fresh", "events");
            String fresh = "lh.session_it_fresh.events";
            assertThat(strings(session, "SELECT count(*) FROM " + fresh)).containsExactly("50000");

            try (Statement statement = writer.createStatement()) {
                statement.execute("INSERT INTO " + LakehouseStack.DUCKDB_CATALOG + ".session_it_fresh.events "
                        + "SELECT * FROM " + LakehouseStack.DUCKDB_CATALOG + ".session_it_fresh.events LIMIT 7");
            }
            assertThat(strings(session, "SELECT count(*) FROM " + fresh)).containsExactly("50007");
        }
    }

    /** T-156: Polaris answers HTTP 500 to a table named like an Iceberg metadata table; the wiki lists those names. */
    @Test
    void tablesNamedLikeIcebergMetadataTablesAreRefusedByTheCatalogButOtherNamesWork() throws Exception {
        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = writer.createStatement()) {
            stack.attachDuckDb(writer);
            String ns = LakehouseStack.DUCKDB_CATALOG + ".reserved_names_it";
            statement.execute("CREATE SCHEMA IF NOT EXISTS " + ns);

            statement.execute("CREATE TABLE " + ns + ".events_history (id BIGINT)");
            for (String name : new String[] {"history", "snapshots"}) {
                assertThatThrownBy(() -> statement.execute("CREATE TABLE " + ns + "." + name + " (id BIGINT)"))
                        .as(name).isInstanceOf(SQLException.class);
            }
        }
    }

    @Test
    void aQueryOverTheTimeLimitIsCutAndTheConnectionKeepsWorking() throws Exception {
        try (LakehouseSession session = new LakehouseSession(
                settings().withPoolSize(1).withQueryTimeout(Duration.ofSeconds(1)))) {
            long start = System.nanoTime();

            assertThatThrownBy(() -> strings(session, ENDLESS))
                    .isInstanceOf(SQLTimeoutException.class)
                    .hasMessageContaining("exceeded");

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(15));
            assertThat(strings(session, "SELECT 1")).containsExactly("1");
        }
    }

    @Test
    void theValidatorRunsOnTheCatalogWithTheLimitAndTheTimeoutOfTheSession() throws Exception {
        List<SqlGuard.TableName> allowed = List.of(SqlGuard.TableName.of(TABLE));
        // Table functions are off limits here, so slow means a cross join. More tables hit the memory limit first.
        String heavy = "SELECT sum(a.product_id + b.product_id + c.product_id) FROM "
                + TABLE + " a, " + TABLE + " b, " + TABLE + " c";
        try (LakehouseSession session = new LakehouseSession(
                settings().withPoolSize(1).withQueryTimeout(Duration.ofSeconds(1)))) {
            SqlGuard guard = new SqlGuard(session::query);

            SqlGuard.QueryResult count = guard.run("SELECT count(*) FROM " + TABLE, allowed, 10);
            SqlGuard.QueryResult cut = guard.run("SELECT event_type FROM " + TABLE, allowed, 1);

            assertThat(count.rows()).containsExactly(List.of(loaded));
            assertThat(cut.rows()).hasSize(1);
            assertThat(cut.truncated()).isTrue();
            assertThatThrownBy(() -> guard.run(heavy, allowed, 10)).isInstanceOf(SQLTimeoutException.class);
        }
    }

    @Test
    void aFailedQueryDoesNotBreakTheNextOneNorCountsAsATimeout() throws Exception {
        try (LakehouseSession session = new LakehouseSession(settings().withPoolSize(1))) {
            assertThatThrownBy(() -> strings(session, "SELECT * FROM lh.session_it.missing"))
                    .isInstanceOf(SQLException.class)
                    .isNotInstanceOf(SQLTimeoutException.class);

            assertThat(strings(session, "SELECT count(*) FROM " + TABLE)).containsExactly("50000");
        }
    }

    @Test
    void aQueryThatFinishesInTimeIsNotInterruptedLater() throws Exception {
        try (LakehouseSession session = new LakehouseSession(
                settings().withPoolSize(1).withQueryTimeout(Duration.ofMillis(800)))) {
            assertThat(strings(session, "SELECT 1")).containsExactly("1");
            TimeUnit.MILLISECONDS.sleep(1200);

            assertThat(strings(session, "SELECT 2")).containsExactly("2");
        }
    }

    @Test
    void everyConnectionOfThePoolUsesUtc() throws Exception {
        int poolSize = 3;
        try (LakehouseSession session = new LakehouseSession(settings().withPoolSize(poolSize))) {
            // Each query holds its connection until all have one, so every pool connection is checked once.
            CountDownLatch allRunning = new CountDownLatch(poolSize);
            List<CompletableFuture<String>> zones = new ArrayList<>();
            for (int i = 0; i < poolSize; i++) {
                zones.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return session.query("SELECT current_setting('TimeZone')", rows -> {
                            allRunning.countDown();
                            try {
                                assertThat(allRunning.await(20, TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException e) {
                                throw new SQLException(e);
                            }
                            rows.next();
                            return rows.getString(1);
                        });
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }));
            }
            for (CompletableFuture<String> zone : zones) {
                assertThat(zone.get(30, TimeUnit.SECONDS)).isEqualTo("UTC");
            }
        }
    }

    @Test
    void connectionsOfThePoolWorkAtTheSameTime() throws Exception {
        try (LakehouseSession session = new LakehouseSession(settings().withPoolSize(2))) {
            // Each query holds its connection until the other has one too: with a single connection this would time out.
            CountDownLatch bothRunning = new CountDownLatch(2);
            List<CompletableFuture<Long>> queries = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                queries.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return session.query("SELECT count(*) FROM " + TABLE, rows -> {
                            bothRunning.countDown();
                            try {
                                assertThat(bothRunning.await(20, TimeUnit.SECONDS)).isTrue();
                            } catch (InterruptedException e) {
                                throw new SQLException(e);
                            }
                            rows.next();
                            return rows.getLong(1);
                        });
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }));
            }

            assertThat(queries.get(0).get(30, TimeUnit.SECONDS)).isEqualTo(50_000);
            assertThat(queries.get(1).get(30, TimeUnit.SECONDS)).isEqualTo(50_000);
        }
    }

    @Test
    void whenThePoolIsBusyTheCallerGivesUpInsteadOfWaitingForever() throws Exception {
        try (LakehouseSession session = new LakehouseSession(
                settings().withPoolSize(1).withQueryTimeout(Duration.ofMillis(500)))) {
            CountDownLatch holding = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(1);
            CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
                try {
                    session.query("SELECT 1", rows -> {
                        holding.countDown();
                        try {
                            done.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            throw new SQLException(e);
                        }
                        return null;
                    });
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(holding.await(20, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> strings(session, "SELECT 2"))
                    .isInstanceOf(SQLTimeoutException.class)
                    .hasMessageContaining("busy");

            done.countDown();
            holder.get(30, TimeUnit.SECONDS);
            assertThat(strings(session, "SELECT 3")).containsExactly("3");
        }
    }

    @Test
    void aQueryOverTheMemoryLimitFailsAndTheSessionSurvives() throws Exception {
        try (LakehouseSession session = new LakehouseSession(settings().withPoolSize(1).withMemoryLimit("64MB"))) {
            assertThatThrownBy(() -> strings(session,
                    "SELECT count(*) FROM (SELECT DISTINCT i FROM range(100000000) t(i))"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Out of Memory");

            assertThat(strings(session, "SELECT 1")).containsExactly("1");
        }
    }

    private static String latestSnapshot(LakehouseSession session, String table) throws SQLException {
        return strings(session, "SELECT snapshot_id FROM iceberg_snapshots('" + table
                + "') ORDER BY sequence_number DESC LIMIT 1").get(0);
    }

    /**
     * Without the validator, a rollback or a write fails on every connection of the pool and the table stays as it was;
     * the same rollback through the repair instance is applied, and the open read instance sees it.
     */
    @Test
    void onlyTheRepairInstanceCanChangeATable() throws Exception {
        String table = "lh.session_it_repair.events";
        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:")) {
            stack.attachDuckDb(writer);
            SyntheticLoader.load(writer, "session_it_repair", "events");
        }
        int poolSize = 3;
        try (LakehouseSession session = new LakehouseSession(settings().withPoolSize(poolSize));
                Connection writer = DriverManager.getConnection("jdbc:duckdb:")) {
            String loadedSnapshot = latestSnapshot(session, table);
            stack.attachDuckDb(writer);
            try (Statement statement = writer.createStatement()) {
                statement.execute("INSERT INTO " + LakehouseStack.DUCKDB_CATALOG + ".session_it_repair.events "
                        + "SELECT * FROM " + LakehouseStack.DUCKDB_CATALOG + ".session_it_repair.events LIMIT 7");
            }
            String current = latestSnapshot(session, table);
            assertThat(current).isNotEqualTo(loadedSnapshot);
            List<String> writes = List.of(
                    "SELECT * FROM iceberg_rollback_to_snapshot('" + table + "', " + loadedSnapshot + ")",
                    "CALL iceberg_rollback_to_snapshot('" + table + "', " + loadedSnapshot + ")",
                    "INSERT INTO " + table + " SELECT * FROM " + table + " LIMIT 1",
                    "DELETE FROM " + table,
                    "DROP TABLE " + table);

            // Nested, so that every connection of the pool is held at once and each one is tried.
            List<Integer> tried = new ArrayList<>();
            tryEveryConnection(session, poolSize, writes, tried);
            assertThat(tried).hasSize(poolSize);

            assertThat(latestSnapshot(session, table)).isEqualTo(current);
            assertThat(strings(session, "SELECT count(*) FROM " + table)).containsExactly("50007");

            RepairSession.alongside(settings()).apply(RepairSession.ROLLBACK,
                    Map.of("table", table, "snapshot_id", Long.parseLong(loadedSnapshot)));

            assertThat(strings(session, "SELECT count(*) FROM " + table)).containsExactly("50000");
        }
    }

    private static void tryEveryConnection(LakehouseSession session, int left, List<String> writes, List<Integer> tried)
            throws SQLException {
        if (left == 0) {
            return;
        }
        session.withConnection(reserved -> {
            for (String write : writes) {
                assertThatThrownBy(() -> reserved.execute(write)).as(write)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("attached in read-only mode");
            }
            tried.add(left);
            tryEveryConnection(session, left - 1, writes, tried);
            return null;
        });
    }

    @Test
    void startsWithoutDownloadingOnceTheExtensionsAreInItsDirectory(@TempDir Path preloaded, @TempDir Path empty)
            throws Exception {
        new LakehouseSession(settings().withExtensions(preloaded, true)).close();

        try (LakehouseSession offline = new LakehouseSession(settings().withExtensions(preloaded, false))) {
            assertThat(strings(offline, "SELECT count(*) FROM " + TABLE)).containsExactly("50000");
            // Features that DuckDB could fetch on first use: dates over timestamptz, and the parser behind the validator.
            assertThat(strings(offline, "SELECT count(*) FROM " + TABLE + " WHERE brand = 'synth-nimbora' "
                    + "AND event_type = 'purchase' AND event_time::DATE = DATE '2024-03-10'")).containsExactly("10");
            assertThat(strings(offline, "SELECT json_extract(json_serialize_sql('SELECT 1'), '$.error')"))
                    .containsExactly("false");
        }

        // The same setting with nothing to load fails, so the session above did not download them.
        assertThatThrownBy(() -> new LakehouseSession(settings().withExtensions(empty, false)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("httpfs");
    }

    @Test
    void aSessionThatCannotReachTheCatalogFailsWhenItOpens() {
        Settings unreachable = Settings.of(
                new Catalog(LakehouseStack.CATALOG, "http://127.0.0.1:1", LakehouseStack.CLIENT_ID, LakehouseStack.CLIENT_SECRET),
                settings().storage());

        assertThatThrownBy(() -> new LakehouseSession(unreachable)).isInstanceOf(SQLException.class);
    }
}
