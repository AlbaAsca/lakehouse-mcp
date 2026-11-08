package io.github.albaasca.lakehouse.semantic;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * The metrics defined in {@code metrics/*.yaml}. Loading validates everything, so a bad file fails when the catalog is
 * built (at startup) with the file name and the problem, not when somebody asks for the metric.
 */
public final class MetricCatalog {

    public static final String DEFAULT_LOCATION = "classpath*:metrics/*.yaml";

    private static final Set<String> KEYS = Set.of("name", "description", "unit", "sql", "parameters");
    private static final Set<String> PARAMETER_KEYS = Set.of("name", "type", "default", "min");
    private static final Set<String> TYPES = Set.of("date", "integer");

    private final Map<String, Metric> metrics;

    private MetricCatalog(Map<String, Metric> metrics) {
        this.metrics = metrics;
    }

    public static MetricCatalog load() {
        return load(DEFAULT_LOCATION);
    }

    public static MetricCatalog load(String locationPattern) {
        Map<String, String> sources = new LinkedHashMap<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(locationPattern)) {
                try (InputStream in = resource.getInputStream()) {
                    sources.put(resource.getFilename(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new MetricException("Cannot read metric definitions from " + locationPattern, e);
        }
        if (sources.isEmpty()) {
            throw new MetricException("No metric definitions found in " + locationPattern);
        }
        return fromSources(sources);
    }

    /** @param sources YAML text by file name */
    public static MetricCatalog fromSources(Map<String, String> sources) {
        Map<String, Metric> metrics = new LinkedHashMap<>();
        Map<String, String> files = new LinkedHashMap<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Metric metric = parse(source.getKey(), source.getValue());
            String previous = files.put(metric.name(), source.getKey());
            if (previous != null) {
                throw new MetricException(source.getKey() + ": metric '" + metric.name() + "' is already defined in "
                        + previous);
            }
            metrics.put(metric.name(), metric);
        }
        return new MetricCatalog(Map.copyOf(metrics));
    }

    public Collection<Metric> all() {
        return metrics.values().stream().sorted((a, b) -> a.name().compareTo(b.name())).toList();
    }

    public Metric get(String name) {
        Metric metric = metrics.get(name);
        if (metric == null) {
            throw new MetricException("Unknown metric '" + name + "'. Known: "
                    + all().stream().map(Metric::name).toList());
        }
        return metric;
    }

    public BoundQuery query(String name, String table, Map<String, ?> arguments) {
        return get(name).bind(table, arguments);
    }

    private static Metric parse(String file, String text) {
        Object root;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            root = new Yaml(new SafeConstructor(options)).load(text);
        } catch (YAMLException e) {
            throw new MetricException(file + ": malformed YAML: " + e.getMessage().replace('\n', ' '), e);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new MetricException(file + ": expected a mapping with name, description, unit, sql, parameters");
        }
        for (Object key : map.keySet()) {
            if (!KEYS.contains(String.valueOf(key))) {
                throw new MetricException(file + ": unknown key '" + key + "'");
            }
        }
        String name = text(file, map, "name");
        String description = text(file, map, "description");
        String unit = text(file, map, "unit");
        String sql = text(file, map, "sql");
        if (!name.matches("[a-z][a-z0-9_]*")) {
            throw new MetricException(file + ": name '" + name + "' must be snake_case");
        }
        List<Metric.Parameter> parameters = new ArrayList<>();
        Object declared = map.get("parameters");
        if (declared != null && !(declared instanceof List<?>)) {
            throw new MetricException(file + ": 'parameters' must be a list");
        }
        if (declared instanceof List<?> list) {
            for (Object item : list) {
                parameters.add(parameter(file, item));
            }
        }
        List<String> names = parameters.stream().map(Metric.Parameter::name).toList();
        if (names.size() != names.stream().distinct().count()) {
            throw new MetricException(file + ": a parameter is declared twice");
        }
        for (String used : Metric.markers(sql)) {
            if (!names.contains(used)) {
                throw new MetricException(file + ": the sql uses ':" + used + "' but it is not a declared parameter");
            }
        }
        for (String declaredName : names) {
            if (!Metric.markers(sql).contains(declaredName)) {
                throw new MetricException(file + ": parameter '" + declaredName + "' is not used in the sql");
            }
        }
        if (!sql.contains("${table}")) {
            throw new MetricException(file + ": the sql must read from ${table}");
        }
        return new Metric(name, description, unit, sql, List.copyOf(parameters));
    }

    private static Metric.Parameter parameter(String file, Object item) {
        if (!(item instanceof Map<?, ?> map)) {
            throw new MetricException(file + ": each parameter must be a mapping with name and type");
        }
        for (Object key : map.keySet()) {
            if (!PARAMETER_KEYS.contains(String.valueOf(key))) {
                throw new MetricException(file + ": unknown parameter key '" + key + "'");
            }
        }
        String name = text(file, map, "name");
        String type = text(file, map, "type");
        if (!TYPES.contains(type)) {
            throw new MetricException(file + ": parameter '" + name + "' has type '" + type + "'; use date or integer");
        }
        Long min = null;
        if (map.get("min") != null) {
            min = integer(file, name, "min", map.get("min"));
        }
        Object defaultValue = map.get("default");
        Metric.Parameter parameter = new Metric.Parameter(name, type, null, min);
        try {
            defaultValue = parameter.convert(defaultValue == null ? null : defaultValue.toString());
        } catch (MetricException e) {
            throw new MetricException(file + ": default of '" + name + "': " + e.getMessage(), e);
        }
        return new Metric.Parameter(name, type, defaultValue, min);
    }

    private static long integer(String file, String parameter, String key, Object value) {
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        throw new MetricException(file + ": '" + key + "' of parameter '" + parameter + "' must be an integer");
    }

    private static String text(String file, Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new MetricException(file + ": '" + key + "' is required and must be text");
        }
        return string.strip();
    }
}
