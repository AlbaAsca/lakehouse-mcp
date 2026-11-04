package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the real driver hands to {@link QueryTools#cell} and how it reads as JSON, on machines in different zones. The
 * expected text is written by hand from the SQL literal: the same value must come out, whatever the machine's zone.
 */
class QueryToolsCellTest {

    private static final List<String> ZONES = List.of("UTC", "Europe/Madrid", "America/Sao_Paulo", "Asia/Kolkata");

    private static Object driverValue(String expression, String zone) throws SQLException {
        TimeZone before = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT " + expression)) {
            rows.next();
            return QueryTools.cell(rows.getObject(1));
        } finally {
            TimeZone.setDefault(before);
        }
    }

    static Stream<Arguments> values() {
        return ZONES.stream().flatMap(zone -> Stream.of(
                Arguments.of(zone, "TIMESTAMP '2026-10-25 02:30:00'", "2026-10-25T02:30"),
                Arguments.of(zone, "TIMESTAMP '2026-03-01 10:00:00.123456'", "2026-03-01T10:00:00.123456"),
                // Before 1582 the old java.sql types change calendar.
                Arguments.of(zone, "TIMESTAMP '1500-01-01 10:00:00'", "1500-01-01T10:00"),
                Arguments.of(zone, "DATE '2026-03-29'", "2026-03-29"),
                Arguments.of(zone, "DATE '1500-01-01'", "1500-01-01"),
                Arguments.of(zone, "TIME '10:00:00.5'", "10:00:00.5")));
    }

    @ParameterizedTest
    @MethodSource("values")
    void aDateWithoutZoneReadsTheSameOnAnyMachine(String zone, String expression, String expected) throws Exception {
        assertThat(String.valueOf(driverValue(expression, zone))).as(expression + " on " + zone)
                .startsWith(expected);
    }

    /** 02:30 does not exist in Madrid that night: the clock jumps from 02:00 to 03:00. Today it reads 03:30. */
    @Disabled("T-155")
    @Test
    void aTimestampInTheSkippedHourKeepsItsValue() throws Exception {
        assertThat(driverValue("TIMESTAMP '2026-03-29 02:30:00'", "Europe/Madrid")).isEqualTo("2026-03-29T02:30");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/Madrid", "Asia/Kolkata"})
    void aTimestampWithZoneKeepsItsInstant(String zone) throws Exception {
        Object cell = driverValue("TIMESTAMPTZ '2026-03-01 10:00:00+00'", zone);

        assertThat(OffsetDateTime.parse((String) cell).toInstant()).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));
    }

    /** Text with a hash in it, such as Object.toString gives, changes on each run and tells the model nothing. */
    @ParameterizedTest
    @ValueSource(strings = {"'abc'::BLOB", "[1, 2]", "['a', 'b']", "{'a': 1, 'b': 'x'}", "MAP {'k': 1}",
            "'6ba7b810-9dad-11d1-80b4-00c04fd430c8'::UUID", "INTERVAL 3 DAY", "[{'a': [1]}]", "'a'::VARCHAR",
            "TIMETZ '10:00:00+01'", "'10101'::BIT"})
    void aComplexValueIsReadableText(String expression) throws Exception {
        assertThat(String.valueOf(driverValue(expression, "UTC"))).as(expression).doesNotContain("@");
    }

    @ParameterizedTest
    @ValueSource(strings = {"'nan'::DOUBLE", "'inf'::DOUBLE", "'-inf'::DOUBLE", "'nan'::FLOAT", "1.5::FLOAT"})
    void aSpecialNumberStaysValidJson(String expression) throws Exception {
        String json = JsonMapper.shared().writeValueAsString(List.of(driverValue(expression, "UTC")));

        JsonNode read = JsonMapper.shared().readTree(json);
        assertThat(read.size()).as(json).isEqualTo(1);
    }
}
