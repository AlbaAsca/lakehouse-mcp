package io.github.albaasca.lakehouse.mcp;

import java.util.Map;

/**
 * {@code compact --table ns.table [--min-files N]}: merges the small data files of a table through the repair instance
 * (docs/architecture.md, decision 6). Connection from the environment, as {@link ChaosCommand}. Meant for the person who
 * runs it (./mk maintain), not for the agent. Expiring snapshots is a separate command (maintenance module).
 */
public final class CompactCommand {

    static final String USAGE = "Usage: compact --table ns.table [--min-files N]";
    static final int DEFAULT_MIN_FILES = 2;

    private CompactCommand() {}

    public static void main(String[] args) throws Exception {
        System.out.println(run(args, System.getenv()));
    }

    record Parsed(String namespace, String table, long minFiles) {}

    static Parsed parse(String[] args) {
        String table = option(args, "--table", null);
        if (table == null) {
            throw new IllegalArgumentException("Missing --table. " + USAGE);
        }
        String[] parts = table.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("--table must be namespace.table: " + table);
        }
        long min = Long.parseLong(option(args, "--min-files", String.valueOf(DEFAULT_MIN_FILES)));
        if (min < 2) {
            throw new IllegalArgumentException("--min-files must be at least 2: " + min);
        }
        return new Parsed(parts[0], parts[1], min);
    }

    static String run(String[] args, Map<String, String> env) throws Exception {
        Parsed p = parse(args);
        LakehouseSession.Settings settings = LakehouseSession.Settings.of(
                new LakehouseSession.Catalog(env.getOrDefault("POLARIS_CATALOG", "lakehouse"),
                        env.getOrDefault("POLARIS_URL", env.getOrDefault("POLARIS_URI", "http://polaris:8181")),
                        required(env, "POLARIS_CLIENT_ID"), required(env, "POLARIS_CLIENT_SECRET")),
                new LakehouseSession.Storage(env.getOrDefault("S3_ENDPOINT", "seaweedfs:8333"), required(env, "S3_ACCESS_KEY"),
                        required(env, "S3_SECRET_KEY"), env.getOrDefault("S3_REGION", "us-east-1")));
        String result = RepairSession.alongside(settings).applyForResult(RepairSession.COMPACT,
                Map.of("table", LakehouseSession.CATALOG_ALIAS + "." + p.namespace() + "." + p.table(),
                        "min_input_files", p.minFiles()));
        return report(p, result);
    }

    /** "0|0|0" is not a success: fewer files than the minimum, nothing was done. */
    static String report(Parsed p, String result) {
        String[] cols = result.split("\\|");
        if (cols.length < 3 || cols[0].equals("0")) {
            return p.namespace() + "." + p.table() + ": nothing to compact (fewer than " + p.minFiles() + " data files"
                    + " to merge); result '" + result + "'";
        }
        return p.namespace() + "." + p.table() + ": rewritten_data_files=" + cols[0] + " added_data_files=" + cols[1]
                + " rewritten_bytes=" + cols[2];
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing environment variable " + name);
        }
        return value;
    }

    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
