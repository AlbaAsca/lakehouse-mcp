package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.albaasca.lakehouse.mcp.DataQuality.DayReport;
import io.github.albaasca.lakehouse.mcp.DataQuality.Report;
import io.github.albaasca.lakehouse.mcp.DataQuality.Settings;
import io.github.albaasca.lakehouse.mcp.DataQuality.Status;
import io.github.albaasca.lakehouse.mcp.DataQuality.VolumeDrop;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Catalog;
import io.github.albaasca.lakehouse.mcp.LakehouseSession.Storage;
import io.github.albaasca.lakehouse.testkit.LakehouseStack;
import io.github.albaasca.lakehouse.testkit.SyntheticLoader;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * data_quality_check against the sample, compared day by day and figure by figure with evals/truth/quality.json, which is
 * computed from the CSV without the lakehouse. The small table covers what the sample does not have.
 */
class QualityToolsIT {

    private static final String NS = "quality_it";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Settings SETTINGS = new Settings(0.7, 7, 3, Set.of("brand", "category_code"));

    private static LakehouseSession session;
    private static CatalogReader reader;
    private static JsonNode truth;

    @BeforeAll
    static void load() throws Exception {
        LakehouseStack stack = LakehouseStack.shared();
        try (Connection writer = DriverManager.getConnection("jdbc:duckdb:")) {
            stack.attachDuckDb(writer);
            SyntheticLoader.load(writer, NS, "events");
            try (Statement statement = writer.createStatement()) {
                statement.execute("CREATE TABLE lh." + NS + ".small (event_time TIMESTAMPTZ, event_type VARCHAR, "
                        + "price DOUBLE, user_session VARCHAR)");
                // 2024-01-02 00:30+02 is still 2024-01-01 in UTC; 2024-01-02 has no rows at all.
                statement.execute("INSERT INTO lh." + NS + ".small VALUES "
                        + "('2024-01-01 10:00:00+00', 'view', 10, 's'), ('2024-01-01 10:00:00+00', 'view', 10, 's'), "
                        + "('2024-01-02 00:30:00+02', 'view', 10, 's'), ('2024-01-03 00:00:00+00', 'cart', 1000, 's'), "
                        + "('2024-01-03 01:00:00+00', 'cart', 0, 's'), ('2024-01-03 02:00:00+00', 'cart', -1, 's'), "
                        + "('2024-01-03 03:00:00+00', 'cart', NULL, 's'), (NULL, 'view', 10, 's')");
            }
        }
        session = new LakehouseSession(LakehouseSession.Settings.of(
                new Catalog(LakehouseStack.CATALOG, stack.polarisUrl(), LakehouseStack.CLIENT_ID, LakehouseStack.CLIENT_SECRET),
                new Storage(stack.s3Endpoint(), LakehouseStack.S3_ACCESS_KEY, LakehouseStack.S3_SECRET_KEY, LakehouseStack.S3_REGION)));
        reader = new CatalogReader(session);
        truth = JSON.readTree(Files.readString(LakehouseStack.repositoryRoot().resolve("evals/truth/quality.json")));
    }

    @AfterAll
    static void close() throws Exception {
        if (session != null) {
            session.close();
        }
    }

    private static QualityTools tools(QualityTools.Drift drift) {
        return new QualityTools(session, reader, SETTINGS, drift);
    }

    private static Map<String, Long> longs(JsonNode object) {
        Map<String, Long> out = new LinkedHashMap<>();
        object.properties().forEach(entry -> out.put(entry.getKey(), entry.getValue().asLong()));
        return out;
    }

    private static void assertMatchesTruth(DayReport day, JsonNode expected) {
        String at = day.day();
        assertThat(day.empty()).as(at).isFalse();
        assertThat(day.rows()).as(at + " rows").isEqualTo(expected.get("rows").asLong());
        assertThat(day.rowsByEventType()).as(at + " rowsByEventType").isEqualTo(longs(expected.get("rows_by_event_type")));
        assertThat(day.nulls()).as(at + " nulls").containsExactlyEntriesOf(longs(expected.get("nulls")));
        assertThat(day.duplicateCopies()).as(at + " duplicates").isEqualTo(expected.get("duplicate_copies").asLong());
        assertThat(day.priceZero()).as(at + " price zero").isEqualTo(expected.get("price_zero").asLong());
        assertThat(day.priceNegative()).as(at + " price negative").isEqualTo(expected.get("price_negative").asLong());
        assertThat(day.priceOutOfRange()).as(at + " price out of range").isEqualTo(expected.get("price_out_of_range").asLong());
    }

    @Test
    void everyFigureOfEveryDayMatchesTheReference() throws Exception {
        Report report = tools(null).check(NS, "events", null, null);

        JsonNode byDay = truth.get("by_day");
        assertThat(report.days()).extracting(DayReport::day).containsExactlyElementsOf(byDay.propertyNames());
        report.days().forEach(day -> assertMatchesTruth(day, byDay.get(day.day())));

        JsonNode totals = truth.get("totals");
        assertThat(report.totals().rows()).isEqualTo(totals.get("rows").asLong());
        assertThat(report.totals().rowsWithoutDay()).isZero();
        assertThat(report.totals().duplicateCopies()).isEqualTo(totals.get("duplicate_copies").asLong());
        assertThat(report.totals().nulls()).containsExactlyEntriesOf(longs(totals.get("nulls")));
        assertThat(report.totals().rowsByEventType()).isEqualTo(longs(totals.get("rows_by_event_type")));
        assertThat(report.totals().priceZero()).isEqualTo(totals.get("price_zero").asLong());
        assertThat(report.totals().priceNegative()).isEqualTo(totals.get("price_negative").asLong());
        assertThat(report.totals().priceOutOfRange()).isEqualTo(totals.get("price_out_of_range").asLong());
    }

    /** The median is recomputed here from the reference, the 7 days before each day, independently of DataQuality. */
    @Test
    void thePlantedDropIsTheOnlyLowVolumeDay() throws Exception {
        Report report = tools(null).check(NS, "events", null, null);

        JsonNode byDay = truth.get("by_day");
        List<String> days = byDay.propertyNames().stream().toList();
        int index = days.indexOf("2024-03-10");
        long[] before = days.subList(index - 7, index).stream()
                .mapToLong(day -> byDay.get(day).get("rows_by_event_type").get("purchase").asLong()).sorted().toArray();
        double median = before[3];
        assertThat(report.volume().drops()).containsExactly(new VolumeDrop("2024-03-10", "purchase", 150, median,
                Math.round(150 / median * 1000) / 1000.0));
        assertThat(report.issues()).anyMatch(issue -> issue.startsWith("Low volume") && issue.contains("2024-03-10"));
    }

    @Test
    void knownNullsAreInformationalAndTheRestAreIssues() throws Exception {
        Report report = tools(null).check(NS, "events", null, null);

        assertThat(report.status()).isEqualTo(Status.ISSUES);
        assertThat(report.informationalNulls()).containsExactly("category_code", "brand");
        assertThat(report.issues()).contains("50 nulls in user_session", "250 exact duplicate rows (extra copies)",
                "201 rows with price <= 0 (100 zero, 101 negative)");
        assertThat(report.issues()).noneMatch(issue -> issue.contains("brand") || issue.contains("category_code"));
        assertThat(report.schemaDrift().status()).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void oneDayAskedForGivesOnlyThatDayJudgedWithTheDaysBefore() throws Exception {
        Report drop = tools(null).check(NS, "events", "2024-03-10", null);
        assertThat(drop.days()).singleElement().satisfies(day -> assertMatchesTruth(day, truth.get("by_day").get("2024-03-10")));
        assertThat(drop.totals().rows()).isEqualTo(truth.get("by_day").get("2024-03-10").get("rows").asLong());
        assertThat(drop.volume().drops()).extracting(VolumeDrop::eventType).containsExactly("purchase");

        assertThat(tools(null).check(NS, "events", "2024-03-09", null).volume().drops()).isEmpty();
        // 2024-03-06 purchase is 0.893 of its median: a stricter call catches it.
        assertThat(tools(null).check(NS, "events", "2024-03-06", 0.9).volume().drops())
                .extracting(VolumeDrop::day, VolumeDrop::eventType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("2024-03-06", "purchase"));

        Report after = tools(null).check(NS, "events", "2024-03-15", null);
        assertThat(after.days()).singleElement().satisfies(day -> assertThat(day.empty()).isTrue());
        assertThat(after.issues()).contains("Empty partition: no rows on 2024-03-15");
    }

    /** Written by hand: UTC days, an empty day, rows without a day, a null price, zero, negative and one outlier. */
    @Test
    void theSmallTableGivesTheFiguresWorkedOutByHand() throws Exception {
        Report report = new QualityTools(session, reader, new Settings(0.7, 7, 1, Set.of()), null)
                .check(NS, "small", null, null);

        assertThat(report.priceOutliers().upperFence()).isEqualTo(10.0);
        assertThat(report.days()).extracting(DayReport::day).containsExactly("2024-01-01", "2024-01-02", "2024-01-03");
        DayReport first = report.days().get(0);
        assertThat(Arrays.asList(first.empty(), first.rows(), first.duplicateCopies(), first.priceOutOfRange(),
                first.priceHighOutliers())).containsExactly(false, 3L, 1L, 0L, 0L);
        assertThat(first.rowsByEventType()).isEqualTo(Map.of("cart", 0L, "view", 3L));
        DayReport second = report.days().get(1);
        assertThat(Arrays.asList(second.empty(), second.rows())).containsExactly(true, 0L);
        DayReport third = report.days().get(2);
        assertThat(Arrays.asList(third.empty(), third.rows(), third.duplicateCopies(), third.priceZero(),
                third.priceNegative(), third.priceHighOutliers())).containsExactly(false, 4L, 0L, 1L, 1L, 1L);
        assertThat(third.nulls()).containsExactly(Map.entry("event_time", 0L), Map.entry("event_type", 0L),
                Map.entry("price", 1L), Map.entry("user_session", 0L));

        assertThat(report.totals().rows()).isEqualTo(8);
        assertThat(report.totals().rowsWithoutDay()).isEqualTo(1);
        assertThat(report.totals().nulls()).containsEntry("event_time", 1L).containsEntry("price", 1L);
        assertThat(report.totals().rowsByEventType()).isEqualTo(Map.of("cart", 4L, "view", 4L));
        assertThat(report.volume().drops()).containsExactly(new VolumeDrop("2024-01-02", "view", 0, 3.0, 0.0),
                new VolumeDrop("2024-01-03", "view", 0, 1.5, 0.0));
        assertThat(report.issues()).contains("Empty partition: no rows on 2024-01-02",
                "1 rows have no event_time, so they belong to no day", "1 prices above the outlier fence");
    }

    @Test
    void schemaDriftComparesTheRegistryWithTheTableColumns() throws Exception {
        List<String> registered = new ArrayList<>(List.of("event_time", "event_type", "product_id", "category_id",
                "category_code", "brand", "price", "user_id", "user_session"));
        QualityTools same = tools(new QualityTools.Drift(NS, "events", "events-value", () -> Optional.of(registered)));
        assertThat(same.check(NS, "events", "2024-03-01", null).schemaDrift().status()).isEqualTo("OK");

        List<String> added = new ArrayList<>(registered);
        added.add("coupon");
        Report drift = tools(new QualityTools.Drift(NS, "events", "events-value", () -> Optional.of(added)))
                .check(NS, "events", "2024-03-01", null);
        assertThat(drift.schemaDrift().status()).isEqualTo("DRIFT");
        assertThat(drift.schemaDrift().missingInTable()).containsExactly("coupon");
        assertThat(drift.schemaDrift().extraInTable()).isEmpty();
        assertThat(drift.issues()).anyMatch(issue -> issue.startsWith("Schema drift"));

        QualityTools down = tools(new QualityTools.Drift(NS, "events", "events-value", () -> {
            throw new IOException("connection refused");
        }));
        assertThat(down.check(NS, "events", "2024-03-01", null).schemaDrift().status()).isEqualTo("UNAVAILABLE");
        assertThat(down.check(NS, "small", null, null).schemaDrift().status()).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    void theToolAnswersJsonAndReadableErrors() throws Exception {
        QualityTools tools = tools(null);

        CallToolResult ok = tools.dataQualityCheck(NS, "events", "2024-03-10", null);
        String text = ((TextContent) ok.content().getFirst()).text();
        assertThat(ok.isError()).as(text).isNotEqualTo(Boolean.TRUE);
        JsonNode answer = JSON.readTree(text);
        assertThat(answer.get("status").asString()).isEqualTo("ISSUES");
        assertThat(answer.get("days").get(0).get("rows").asLong()).isEqualTo(3356);
        assertThat(answer.get("volume").get("drops").get(0).get("eventType").asString()).isEqualTo("purchase");

        assertThat(errorOf(tools.dataQualityCheck(NS, "events", "10/03/2024", null))).contains("2024-03-10");
        assertThat(errorOf(tools.dataQualityCheck(NS, "events", null, 2.0))).contains("minVolumeRatio");
        assertThat(errorOf(tools.dataQualityCheck(NS, "missing", null, null))).isEqualTo("No table lh." + NS + ".missing");
    }

    private static String errorOf(CallToolResult result) {
        String text = ((TextContent) result.content().getFirst()).text();
        assertThat(result.isError()).as(text).isTrue();
        return text;
    }
}
