package io.github.albaasca.lakehouse.mcp;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.duckdb.DuckDBConnection;

/**
 * Read-only access to the Iceberg catalog through one embedded DuckDB instance and a small pool of its connections.
 *
 * <p>The catalog is attached {@code READ_ONLY} so that even a {@code SELECT} cannot change a table, whatever the SQL
 * validator lets through (docs/spike-duckdb-iceberg.md, question 5). Repairs go through {@link RepairSession}, a
 * separate instance: no connection here can ever reach a writable catalog (docs/architecture.md, decision 20).
 */
public final class LakehouseSession implements AutoCloseable {

    /** Name of the catalog in the SQL this session runs: {@code lh.<namespace>.<table>}. */
    public static final String CATALOG_ALIAS = "lh";

    /** Iceberg loads avro by itself, so it has to be installed as well for the session to start without internet. */
    private static final List<String> EXTENSIONS = List.of("httpfs", "avro", "iceberg");

    /** Polaris catalog. {@code uri} is the base URL, without a trailing slash. */
    public record Catalog(String name, String uri, String clientId, String clientSecret) {}

    /** S3 store. {@code endpoint} is {@code host:port}, plain HTTP with path-style addresses as SeaweedFS needs. */
    public record Storage(String endpoint, String accessKey, String secretKey, String region) {}

    /**
     * @param extensionDirectory where DuckDB keeps its extensions; null for its default, under the home directory
     * @param installExtensions false never touches the network: the extensions must already be in the directory
     */
    public record Settings(Catalog catalog, Storage storage, int poolSize, Duration queryTimeout, String memoryLimit,
            Path extensionDirectory, boolean installExtensions) {

        public static Settings of(Catalog catalog, Storage storage) {
            return new Settings(catalog, storage, 4, Duration.ofSeconds(30), "400MB", null, true);
        }

        public Settings withPoolSize(int size) {
            return new Settings(catalog, storage, size, queryTimeout, memoryLimit, extensionDirectory, installExtensions);
        }

        public Settings withQueryTimeout(Duration timeout) {
            return new Settings(catalog, storage, poolSize, timeout, memoryLimit, extensionDirectory, installExtensions);
        }

        public Settings withMemoryLimit(String limit) {
            return new Settings(catalog, storage, poolSize, queryTimeout, limit, extensionDirectory, installExtensions);
        }

        public Settings withExtensions(Path directory, boolean install) {
            return new Settings(catalog, storage, poolSize, queryTimeout, memoryLimit, directory, install);
        }
    }

    /** Turns the rows of a query into a value. They are only valid inside this call. */
    @FunctionalInterface
    public interface RowReader<T> {
        T read(ResultSet rows) throws SQLException;
    }

    private final Settings settings;
    private final DuckDBConnection root;
    private final BlockingQueue<Connection> idle;
    private final ScheduledExecutorService watchdog;

    /** Opens the instance and attaches the catalog: throws if Polaris or the extensions are not available. */
    public LakehouseSession(Settings settings) throws SQLException {
        if (settings.poolSize() < 1) {
            throw new IllegalArgumentException("poolSize must be at least 1: " + settings.poolSize());
        }
        this.settings = settings;
        this.idle = new ArrayBlockingQueue<>(settings.poolSize());
        // jdbc:duckdb: opens a new database on every call, so siblings of one instance only come from duplicate().
        this.root = DriverManager.getConnection("jdbc:duckdb:").unwrap(DuckDBConnection.class);
        this.watchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "duckdb-query-timeout");
            thread.setDaemon(true);
            return thread;
        });
        try {
            try (Statement statement = root.createStatement()) {
                for (String sql : startup(settings, true)) {
                    statement.execute(sql);
                }
            }
            for (int i = 0; i < settings.poolSize(); i++) {
                idle.add(root.duplicate());
            }
        } catch (SQLException | RuntimeException e) {
            close();
            throw e;
        }
    }

    /** Shared with {@link RepairSession}: the only difference between the two instances is {@code READ_ONLY}. */
    static List<String> startup(Settings settings, boolean readOnly) {
        Catalog catalog = settings.catalog();
        Storage storage = settings.storage();
        List<String> sql = new ArrayList<>();
        if (settings.extensionDirectory() != null) {
            sql.add("SET extension_directory = " + quote(settings.extensionDirectory().toString()));
        }
        if (settings.installExtensions()) {
            EXTENSIONS.forEach(extension -> sql.add("INSTALL " + extension));
        } else {
            sql.add("SET autoinstall_known_extensions = false");
        }
        EXTENSIONS.forEach(extension -> sql.add("LOAD " + extension));
        sql.add("SET memory_limit = " + quote(settings.memoryLimit()));
        // Without this, an in-memory database spills to ./.tmp and the limit only moves the problem to the disk.
        sql.add("SET temp_directory = ''");
        sql.add("CREATE SECRET lakehouse_s3 (TYPE s3, KEY_ID " + quote(storage.accessKey()) + ", SECRET "
                + quote(storage.secretKey()) + ", REGION " + quote(storage.region()) + ", ENDPOINT "
                + quote(storage.endpoint()) + ", URL_STYLE 'path', USE_SSL false)");
        sql.add("CREATE SECRET lakehouse_polaris (TYPE iceberg, CLIENT_ID " + quote(catalog.clientId())
                + ", CLIENT_SECRET " + quote(catalog.clientSecret()) + ", OAUTH2_SERVER_URI "
                + quote(catalog.uri() + "/api/catalog/v1/oauth/tokens") + ", OAUTH2_SCOPE 'PRINCIPAL_ROLE:ALL')");
        sql.add("ATTACH " + quote(catalog.name()) + " AS " + CATALOG_ALIAS + " (TYPE iceberg, " + (readOnly ? "READ_ONLY, " : "") + "URI "
                + quote(catalog.uri() + "/api/catalog") + ", SECRET lakehouse_polaris, ACCESS_DELEGATION_MODE 'none')");
        return sql;
    }

    static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * Runs one statement on a free connection.
     *
     * @throws SQLTimeoutException if it exceeds the query timeout, or no connection frees up within that time
     * @throws SQLException if DuckDB rejects it; the connection stays usable for the next query
     */
    public <T> T query(String sql, RowReader<T> reader) throws SQLException {
        return withConnection(reserved -> reserved.query(sql, reader));
    }

    /**
     * Keeps one connection for the whole of {@code work}, for what lives in a connection, such as a temporary view.
     * Inside it, use only {@code reserved}: going back to the pool can wait for a connection that this call itself holds.
     *
     * @throws SQLTimeoutException if no connection frees up within the query timeout
     */
    public <T> T withConnection(Work<T> work) throws SQLException {
        Connection connection = borrow();
        try {
            return work.run(new Reserved(connection));
        } finally {
            release(connection);
        }
    }

    /** What to do with a reserved connection. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Reserved reserved) throws SQLException;
    }

    /** One connection of the pool. Each statement has the query timeout to itself; it is not shared by the whole call. */
    public final class Reserved {

        private final Connection connection;

        private Reserved(Connection connection) {
            this.connection = connection;
        }

        /** Runs a statement that returns rows, as {@link LakehouseSession#query} does. */
        public <T> T query(String sql, RowReader<T> reader) throws SQLException {
            return timed(sql, statement -> {
                try (ResultSet rows = statement.executeQuery(sql)) {
                    return reader.read(rows);
                }
            });
        }

        /** Runs a prepared statement (values bound, never written into the text) under the same timeout. */
        public <T> T query(io.github.albaasca.lakehouse.semantic.BoundQuery bound, RowReader<T> reader)
                throws SQLException {
            try (PreparedStatement statement = bound.prepare(connection);
                    Timeout timeout = new Timeout(statement)) {
                try (ResultSet rows = statement.executeQuery()) {
                    return reader.read(rows);
                } catch (SQLException e) {
                    if (timeout.fired()) {
                        throw new SQLTimeoutException("Query exceeded " + settings.queryTimeout(), e);
                    }
                    throw e;
                }
            }
        }

        /** Runs a statement without rows, such as {@code CREATE VIEW}: the driver refuses those in {@code executeQuery}. */
        public void execute(String sql) throws SQLException {
            timed(sql, statement -> statement.execute(sql));
        }

        private <T> T timed(String sql, StatementWork<T> work) throws SQLException {
            // One Statement per query: after an error the driver closes it, not just the failed query.
            try (Statement statement = connection.createStatement();
                    Timeout timeout = new Timeout(statement)) {
                try {
                    return work.run(statement);
                } catch (SQLException e) {
                    if (timeout.fired()) {
                        throw new SQLTimeoutException("Query exceeded " + settings.queryTimeout() + ": " + sql, e);
                    }
                    throw e;
                }
            }
        }
    }

    @FunctionalInterface
    private interface StatementWork<T> {
        T run(Statement statement) throws SQLException;
    }

    private Connection borrow() throws SQLException {
        try {
            Connection connection = idle.poll(settings.queryTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (connection == null) {
                throw new SQLTimeoutException("All " + settings.poolSize() + " connections stayed busy for "
                        + settings.queryTimeout());
            }
            return connection;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for a connection", e);
        }
    }

    private void release(Connection connection) throws SQLException {
        if (watchdog.isShutdown()) {
            connection.close();
        } else {
            idle.add(connection);
        }
    }

    /** Waits for nothing: a query still running ends with its own connection, when it returns it. */
    @Override
    public void close() throws SQLException {
        watchdog.shutdownNow();
        List<Connection> connections = new ArrayList<>();
        idle.drainTo(connections);
        connections.add(root);
        SQLException failure = null;
        for (Connection connection : connections) {
            try {
                connection.close();
            } catch (SQLException e) {
                failure = e;
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * Interrupts a statement when its time is up. The lock makes sure the interrupt cannot land after the query has
     * ended, when the connection may already be running someone else's query.
     */
    private final class Timeout implements AutoCloseable {

        private final Statement statement;
        private final ScheduledFuture<?> task;
        private boolean finished;
        private boolean fired;

        Timeout(Statement statement) {
            this.statement = statement;
            this.task = watchdog.schedule(this::fire, settings.queryTimeout().toMillis(), TimeUnit.MILLISECONDS);
        }

        private synchronized void fire() {
            if (finished) {
                return;
            }
            fired = true;
            try {
                statement.cancel();
            } catch (SQLException e) {
                // Already closed: the query has just ended by itself, so there is nothing left to interrupt.
            }
        }

        synchronized boolean fired() {
            return fired;
        }

        @Override
        public synchronized void close() {
            finished = true;
            task.cancel(false);
        }
    }
}
