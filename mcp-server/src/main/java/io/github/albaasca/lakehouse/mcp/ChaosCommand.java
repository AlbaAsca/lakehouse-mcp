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

    static Map<String, Object> run(String[] args, Map<String, String> env) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Usage: chaos duplicates|empty-partition --table ns.table --day YYYY-MM-DD [--percent N]");
        }
        String scenario = args[0];
        String table = option(args, "--table");
        LocalDate day = LocalDate.parse(option(args, "--day"));
        String[] parts = table.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("--table must be namespace.table: " + table);
        }
        Chaos chaos = new Chaos(LakehouseSession.Settings.of(
                new LakehouseSession.Catalog(env.getOrDefault("POLARIS_CATALOG", "lakehouse"), required(env, "POLARIS_URL"),
                        required(env, "POLARIS_CLIENT_ID"), required(env, "POLARIS_CLIENT_SECRET")),
                new LakehouseSession.Storage(required(env, "S3_ENDPOINT"), required(env, "S3_ACCESS_KEY"),
                        required(env, "S3_SECRET_KEY"), env.getOrDefault("S3_REGION", "us-east-1"))));
        return switch (scenario) {
            case "duplicates" -> chaos.duplicates(parts[0], parts[1], day, Double.parseDouble(optionOr(args, "--percent", "10")));
            case "empty-partition" -> chaos.emptyPartition(parts[0], parts[1], day);
            case "consumer-down", "schema-drift" -> throw new IllegalArgumentException(scenario + " is not implemented yet");
            default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
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
