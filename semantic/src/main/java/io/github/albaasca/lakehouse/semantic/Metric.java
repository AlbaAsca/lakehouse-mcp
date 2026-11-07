package io.github.albaasca.lakehouse.semantic;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One metric: a SQL text with {@code :name} markers for its parameters and {@code ${table}} for the events table.
 *
 * @param unit what the numbers mean (ratio, currency, sessions...); informative
 */
public record Metric(String name, String description, String unit, String sql, List<Parameter> parameters) {

    private static final Pattern TABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

    /**
     * @param type {@code date} (inclusive bound, ISO text) or {@code integer}
     * @param defaultValue used when the call does not give it; {@code null} means "no value" (an open date bound)
     * @param min lower bound of an integer, or {@code null}
     */
    public record Parameter(String name, String type, Object defaultValue, Long min) {

        Object convert(Object raw) {
            if (raw == null) {
                return null;
            }
            try {
                if (type.equals("date")) {
                    return raw instanceof LocalDate date ? date.toString() : LocalDate.parse(raw.toString()).toString();
                }
                long number = raw instanceof Number n && !(raw instanceof Double || raw instanceof Float)
                        ? n.longValue() : Long.parseLong(raw.toString());
                if (min != null && number < min) {
                    throw new MetricException("Parameter '" + name + "' must be >= " + min + ", got " + number);
                }
                return number;
            } catch (DateTimeParseException | NumberFormatException e) {
                throw new MetricException("Parameter '" + name + "' is not a valid " + type + ": " + raw, e);
            }
        }
    }

    /** Binds the call. Unknown parameters are an error; missing ones take their default. */
    public BoundQuery bind(String table, Map<String, ?> arguments) {
        if (table == null || !TABLE.matcher(table).matches()) {
            throw new MetricException("Not a table name: " + table);
        }
        Map<String, Object> converted = new LinkedHashMap<>();
        for (String key : arguments.keySet()) {
            if (parameters.stream().noneMatch(p -> p.name().equals(key))) {
                throw new MetricException("Metric '" + name + "' has no parameter '" + key + "'");
            }
        }
        for (Parameter parameter : parameters) {
            Object raw = arguments.containsKey(parameter.name()) ? arguments.get(parameter.name()) : null;
            converted.put(parameter.name(), parameter.convert(raw != null ? raw : parameter.defaultValue()));
        }
        String text = sql.replace("${table}", table);
        StringBuilder out = new StringBuilder();
        List<Object> values = new ArrayList<>();
        for (Token token : tokens(text)) {
            if (token.marker == null) {
                out.append(token.text);
            } else {
                out.append('?');
                values.add(converted.get(token.marker));
            }
        }
        return new BoundQuery(out.toString(), values);
    }

    /** Names used as {@code :name} in the SQL, outside string literals and {@code ::} casts. */
    static List<String> markers(String sql) {
        return tokens(sql).stream().map(t -> t.marker).filter(m -> m != null).distinct().toList();
    }

    private record Token(String text, String marker) {}

    private static List<Token> tokens(String sql) {
        List<Token> result = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'') {
                int end = sql.indexOf('\'', i + 1);
                while (end >= 0 && end + 1 < sql.length() && sql.charAt(end + 1) == '\'') {
                    end = sql.indexOf('\'', end + 2);
                }
                end = end < 0 ? sql.length() - 1 : end;
                plain.append(sql, i, end + 1);
                i = end + 1;
            } else if (c == ':' && i + 1 < sql.length() && sql.charAt(i + 1) == ':') {
                plain.append("::");
                i += 2;
            } else if (c == ':' && i + 1 < sql.length() && Character.isLetter(sql.charAt(i + 1))) {
                int end = i + 1;
                while (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end)) || sql.charAt(end) == '_')) {
                    end++;
                }
                result.add(new Token(plain.toString(), null));
                plain.setLength(0);
                result.add(new Token(null, sql.substring(i + 1, end)));
                i = end;
            } else {
                plain.append(c);
                i++;
            }
        }
        result.add(new Token(plain.toString(), null));
        return result;
    }
}
