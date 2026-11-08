package io.github.albaasca.lakehouse.mcp;

import java.time.LocalDate;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code chaos <scenario> --table ns.table --day YYYY-MM-DD [--percent 10]}; connection from the environment
 * (POLARIS_URL, POLARIS_CLIENT_ID, POLARIS_CLIENT_SECRET, POLARIS_CATALOG, S3_ENDPOINT, S3_ACCESS_KEY, S3_SECRET_KEY,
 * S3_REGION). Prints the JSON report to stdout. Run it as {@code java -cp mcp-server.jar ...ChaosCommand}.
 */
public final class ChaosCommand {

    private ChaosCommand() {}

    public static void main(String[] args) throws Exception {
        System.out.println(JsonMapper.builder().build().writerWithDefaultPrettyPrinter().writeValueAsString(run(args, System.getenv())));
    }

    static final String USAGE = "Usage: chaos duplicates|empty-partition --table ns.table --day YYYY-MM-DD [--percent N]";

    /** Parsed command line; validated before any connection is opened. */
    record Parsed(String scenario, String namespace, String table, LocalDate day, double percent) {}

    static Parsed parse(String[] args) {
        if (args.length == 0 || args[0].isBlank()) {
            throw new IllegalArgumentException("Missing scenario. " + USAGE);
        }
        String scenario = args[0];
        if (!scenario.equals("duplicates") && !scenario.equals("empty-partition")) {
            if (scenario.equals("schema-drift")) {
                throw new IllegalArgumentException(
                        "schema-drift is injected by the producer: java -cp producer.jar ...producer.SchemaDriftCommand");
            }
            if (scenario.equals("consumer-down")) {
                throw new IllegalArgumentException(
                        "consumer-down stops a container, so it runs from the host: ./mk chaos SCENARIO=consumer-down"
                                + " (undo: ./mk chaos-undo SCENARIO=consumer-down)");
            }
            throw new IllegalArgumentException("Unknown scenario: " + scenario + ". " + USAGE);
        }
        String table = option(args, "--table");
        LocalDate day = LocalDate.parse(option(args, "--day"));
        String[] parts = table.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("--table must be namespace.table: " + table);
        }
        return new Parsed(scenario, parts[0], parts[1], day, Double.parseDouble(optionOr(args, "--percent", "10")));
    }

    static Map<String, Object> run(String[] args, Map<String, String> env) throws Exception {
        Parsed p = parse(args);
        String polaris = env.getOrDefault("POLARIS_URL", env.getOrDefault("POLARIS_URI", "http://polaris:8181"));
        Chaos chaos = new Chaos(LakehouseSession.Settings.of(
                new LakehouseSession.Catalog(env.getOrDefault("POLARIS_CATALOG", "lakehouse"), polaris,
                        required(env, "POLARIS_CLIENT_ID"), required(env, "POLARIS_CLIENT_SECRET")),
                new LakehouseSession.Storage(env.getOrDefault("S3_ENDPOINT", "seaweedfs:8333"), required(env, "S3_ACCESS_KEY"),
                        required(env, "S3_SECRET_KEY"), env.getOrDefault("S3_REGION", "us-east-1"))));
        return switch (p.scenario()) {
            case "duplicates" -> chaos.duplicates(p.namespace(), p.table(), p.day(), p.percent());
            case "empty-partition" -> chaos.emptyPartition(p.namespace(), p.table(), p.day());
            default -> throw new IllegalArgumentException("Unknown scenario: " + p.scenario());
        };
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing environment variable " + name);
        }
        return value;
    }

    private static String option(String[] args, String name) {
        String value = optionOr(args, name, null);
        if (value == null) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return value;
    }

    private static String optionOr(String[] args, String name, String fallback) {
        for (int i = 1; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
