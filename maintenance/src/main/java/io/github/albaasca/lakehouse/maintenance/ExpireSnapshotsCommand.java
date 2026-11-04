package io.github.albaasca.lakehouse.maintenance;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;

/**
 * One-shot process for what DuckDB cannot do: expire snapshots (decision 6 of docs/architecture.md). Exit code 0 when
 * everything went well, 1 on a failure or on files left behind, 2 on wrong arguments or configuration.
 */
public final class ExpireSnapshotsCommand {

    static final Duration DEFAULT_RETENTION = Duration.ofDays(7);

    private static final String USAGE = "usage: --table <namespace.table>"
            + " [--retention <ISO-8601 duration, default P7D> | --older-than <ISO-8601 instant>]";

    private ExpireSnapshotsCommand() {}

    public static void main(String[] args) throws IOException {
        Options options;
        Map<String, String> catalogProperties;
        try {
            options = Options.parse(args);
            catalogProperties = catalogProperties(System.getenv());
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }
        SnapshotExpirer.Result result;
        try (RESTCatalog catalog = new RESTCatalog()) {
            catalog.initialize("lakehouse", catalogProperties);
            result = SnapshotExpirer.expire(catalog, options.table(), options.cutoff(Instant.now()));
        }
        System.out.println(options.table() + " " + result.summary());
        if (!result.failedDeletes().isEmpty()) {
            result.failedDeletes().forEach(path -> System.err.println("not deleted: " + path));
            System.exit(1);
        }
    }

    /** The same catalog and storage settings as the sink (connect/iceberg-sink.json). */
    static Map<String, String> catalogProperties(Map<String, String> env) {
        String polaris = env.getOrDefault("POLARIS_URL", "http://polaris:8181");
        return Map.ofEntries(
                Map.entry("uri", polaris + "/api/catalog"),
                Map.entry("warehouse", "lakehouse"),
                Map.entry("oauth2-server-uri", polaris + "/api/catalog/v1/oauth/tokens"),
                Map.entry("credential", required(env, "POLARIS_CLIENT_ID") + ":" + required(env, "POLARIS_CLIENT_SECRET")),
                Map.entry("scope", "PRINCIPAL_ROLE:ALL"),
                Map.entry("header.Polaris-Realm", required(env, "POLARIS_REALM")),
                Map.entry("io-impl", "org.apache.iceberg.aws.s3.S3FileIO"),
                Map.entry("s3.endpoint", env.getOrDefault("S3_ENDPOINT", "http://seaweedfs:8333")),
                Map.entry("s3.path-style-access", "true"),
                Map.entry("s3.access-key-id", required(env, "S3_ACCESS_KEY")),
                Map.entry("s3.secret-access-key", required(env, "S3_SECRET_KEY")),
                Map.entry("client.region", required(env, "S3_REGION")));
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing environment variable " + name);
        }
        return value;
    }

    /** {@code olderThan} is null unless given: then the cutoff depends on when the process runs. */
    record Options(TableIdentifier table, Duration retention, Instant olderThan) {

        Instant cutoff(Instant now) {
            return olderThan != null ? olderThan : now.minus(retention);
        }

        static Options parse(String[] args) {
            String table = null;
            Duration retention = null;
            Instant olderThan = null;
            for (int i = 0; i < args.length; i += 2) {
                if (i + 1 == args.length) {
                    throw new IllegalArgumentException(USAGE);
                }
                switch (args[i]) {
                    case "--table" -> table = args[i + 1];
                    case "--retention" -> retention = retention(args[i + 1]);
                    case "--older-than" -> olderThan = instant(args[i + 1]);
                    default -> throw new IllegalArgumentException(USAGE);
                }
            }
            if (table == null || table.isBlank() || !table.contains(".") || (retention != null && olderThan != null)) {
                throw new IllegalArgumentException(USAGE);
            }
            return new Options(TableIdentifier.parse(table), retention != null ? retention : DEFAULT_RETENTION, olderThan);
        }

        private static Instant instant(String text) {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("A moment must look like 2026-01-31T10:00:00Z: " + text);
            }
        }

        private static Duration retention(String text) {
            try {
                Duration retention = Duration.parse(text);
                if (retention.isNegative()) {
                    throw new IllegalArgumentException("Retention cannot be negative: " + text);
                }
                return retention;
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Retention must be an ISO-8601 duration such as P7D: " + text);
            }
        }
    }
}
