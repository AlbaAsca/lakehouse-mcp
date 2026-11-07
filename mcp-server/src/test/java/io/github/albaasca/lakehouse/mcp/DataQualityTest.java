package io.github.albaasca.lakehouse.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.albaasca.lakehouse.mcp.DataQuality.DayCounts;
import io.github.albaasca.lakehouse.mcp.DataQuality.PriceCounts;
import io.github.albaasca.lakehouse.mcp.DataQuality.PriceRule;
import io.github.albaasca.lakehouse.mcp.DataQuality.Report;
import io.github.albaasca.lakehouse.mcp.DataQuality.SchemaDrift;
import io.github.albaasca.lakehouse.mcp.DataQuality.Settings;
import io.github.albaasca.lakehouse.mcp.DataQuality.Status;
import io.github.albaasca.lakehouse.mcp.DataQuality.VolumeDrop;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DataQualityTest {

    private static final List<String> COLUMNS = List.of("event_time", "event_type", "brand", "price");
    private static final Settings SETTINGS = new Settings(0.7, 7, 3, Set.of("brand"));
    private static final PriceRule PRICE = new PriceRule(PriceRule.RULE, 100.0);
    private static final SchemaDrift NO_DRIFT = SchemaDrift.notApplicable("test");

    private static DayCounts day(String day, long views, long purchases) {
        return new DayCounts(day, views + purchases, Map.of("view", views, "purchase", purchases), Map.of(), 0,
                PriceCounts.NONE);
    }

    /** Ten calm days of 100 purchases, then the given number on 2024-03-11. */
    private static List<DayCounts> calmThen(long purchases) {
        List<DayCounts> days = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            days.add(day(String.format("2024-03-%02d", i), 1000, 100));
        }
        days.add(day("2024-03-11", 1000, purchases));
        return days;
    }

    @Test
    void aDayBelowTheShareOfTheMedianIsADropAndOnlyThatTypeOfThatDay() {
        Report report = DataQuality.build("lh.ns.t", null, calmThen(69), null, COLUMNS, PRICE, NO_DRIFT, SETTINGS);

        assertThat(report.volume().drops()).containsExactly(new VolumeDrop("2024-03-11", "purchase", 69, 100.0, 0.69));
        assertThat(report.status()).isEqualTo(Status.ISSUES);
        assertThat(report.issues()).singleElement().asString().contains("Low volume").contains("purchase")
                .contains("2024-03-11");
    }

    @Test
    void exactlyTheShareIsNotADrop() {
        Report report = DataQuality.build("lh.ns.t", null, calmThen(70), null, COLUMNS, PRICE, NO_DRIFT, SETTINGS);

        assertThat(report.volume().drops()).isEmpty();
        assertThat(report.status()).isEqualTo(Status.OK);
        assertThat(report.issues()).isEmpty();
    }

    @Test
    void theRatioOfTheCallReplacesTheSetting() {
        Report report = DataQuality.build("lh.ns.t", null, calmThen(80), null, COLUMNS, PRICE, NO_DRIFT,
                SETTINGS.withMinVolumeRatio(0.9));

        assertThat(report.volume().drops()).extracting(VolumeDrop::rows).containsExactly(80L);
        assertThat(report.volume().minRatio()).isEqualTo(0.9);
    }

    /** Only the last historyDays count: an old quiet week must not hide a drop (over all 14 days the median is 55). */
    @Test
    void theMedianUsesOnlyTheLastHistoryDays() {
        List<DayCounts> days = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            days.add(day(String.format("2024-03-%02d", i), 1000, 10));
        }
        for (int i = 8; i <= 14; i++) {
            days.add(day(String.format("2024-03-%02d", i), 1000, 100));
        }
        days.add(day("2024-03-15", 1000, 60));

        Report report = DataQuality.build("lh.ns.t", null, days, null, COLUMNS, PRICE, NO_DRIFT, SETTINGS);

        assertThat(report.volume().drops()).containsExactly(new VolumeDrop("2024-03-15", "purchase", 60, 100.0, 0.6));
    }

    @Test
    void withTooLittleHistoryADayIsNotJudged() {
        List<DayCounts> days = List.of(day("2024-03-01", 1000, 100), day("2024-03-02", 1000, 100),
                day("2024-03-03", 1000, 1));

        assertThat(DataQuality.build("lh.ns.t", null, days, null, COLUMNS, PRICE, NO_DRIFT, SETTINGS).volume().drops())
                .isEmpty();
    }

    @Test
    void aMissingDayInTheMiddleIsAnEmptyPartitionAndADrop() {
        List<DayCounts> days = new ArrayList<>(calmThen(100));
        days.removeIf(d -> d.day().equals("2024-03-06"));

        Report report = DataQuality.build("lh.ns.t", null, days, null, COLUMNS, PRICE, NO_DRIFT, SETTINGS);

        assertThat(report.days()).hasSize(11);
        assertThat(report.days().get(5).day()).isEqualTo("2024-03-06");
        assertThat(report.days().get(5).empty()).isTrue();
        assertThat(report.days().get(5).rows()).isZero();
        assertThat(report.days().get(5).rowsByEventType()).isEqualTo(Map.of("purchase", 0L, "view", 0L));
        assertThat(report.issues()).contains("Empty partition: no rows on 2024-03-06");
        assertThat(report.volume().drops()).extracting(VolumeDrop::day, VolumeDrop::eventType)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("2024-03-06", "purchase"),
                        org.assertj.core.groups.Tuple.tuple("2024-03-06", "view"));
    }

    @Test
    void askingForOneDayReportsOnlyItButJudgesItWithTheDaysBefore() {
        Report report = DataQuality.build("lh.ns.t", LocalDate.parse("2024-03-11"), calmThen(50), null, COLUMNS, PRICE,
                NO_DRIFT, SETTINGS);

        assertThat(report.day()).isEqualTo("2024-03-11");
        assertThat(report.days()).extracting(DataQuality.DayReport::day).containsExactly("2024-03-11");
        assertThat(report.totals().rows()).isEqualTo(1050);
        assertThat(report.volume().drops()).extracting(VolumeDrop::rows).containsExactly(50L);

        Report calm = DataQuality.build("lh.ns.t", LocalDate.parse("2024-03-10"), calmThen(50), null, COLUMNS, PRICE,
                NO_DRIFT, SETTINGS);
        assertThat(calm.volume().drops()).isEmpty();
    }

    @Test
    void aDayAskedForWithoutRowsIsEmpty() {
        Report report = DataQuality.build("lh.ns.t", LocalDate.parse("2024-03-12"), calmThen(100), null, COLUMNS, PRICE,
                NO_DRIFT, SETTINGS);

        assertThat(report.days()).singleElement().satisfies(d -> {
            assertThat(d.empty()).isTrue();
            assertThat(d.rows()).isZero();
        });
        assertThat(report.issues()).contains("Empty partition: no rows on 2024-03-12");
    }

    @Test
    void knownNullsAreInformationalAndOtherNullsAreIssues() {
        DayCounts counts = new DayCounts("2024-03-01", 10, Map.of("view", 10L), Map.of("brand", 4L, "price", 2L), 3,
                new PriceCounts(1, 2, 5));

        Report report = DataQuality.build("lh.ns.t", null, List.of(counts), null, COLUMNS, PRICE, NO_DRIFT, SETTINGS);

        assertThat(report.informationalNulls()).containsExactly("brand");
        assertThat(report.totals().nulls()).containsExactly(Map.entry("event_time", 0L), Map.entry("event_type", 0L),
                Map.entry("brand", 4L), Map.entry("price", 2L));
        assertThat(report.totals().priceOutOfRange()).isEqualTo(3);
        assertThat(report.totals().priceHighOutliers()).isEqualTo(5);
        assertThat(report.issues()).containsExactly("2 nulls in price", "3 exact duplicate rows (extra copies)",
                "3 rows with price <= 0 (1 zero, 2 negative)", "5 prices above the outlier fence");
    }

    @Test
    void rowsWithoutADayCountInTheTotalsOnlyWhenNoDayIsAsked() {
        DayCounts undated = new DayCounts(null, 2, Map.of("view", 2L), Map.of("event_time", 2L), 0, PriceCounts.NONE);
        List<DayCounts> days = List.of(day("2024-03-01", 10, 1));

        Report all = DataQuality.build("lh.ns.t", null, days, undated, COLUMNS, PRICE, NO_DRIFT, SETTINGS);
        assertThat(all.totals().rows()).isEqualTo(13);
        assertThat(all.totals().rowsWithoutDay()).isEqualTo(2);
        assertThat(all.totals().nulls().get("event_time")).isEqualTo(2);
        assertThat(all.issues()).contains("2 rows have no event_time, so they belong to no day");

        Report one = DataQuality.build("lh.ns.t", LocalDate.parse("2024-03-01"), days, undated, COLUMNS, PRICE, NO_DRIFT,
                SETTINGS);
        assertThat(one.totals().rows()).isEqualTo(11);
        assertThat(one.totals().rowsWithoutDay()).isZero();
    }

    @Test
    void withoutAPriceColumnThePriceCountsAreNull() {
        DayCounts counts = new DayCounts("2024-03-01", 1, Map.of("view", 1L), Map.of(), 0, null);

        Report report = DataQuality.build("lh.ns.t", null, List.of(counts), null, List.of("event_time"), null, NO_DRIFT,
                SETTINGS);

        assertThat(report.totals().priceOutOfRange()).isNull();
        assertThat(report.days().getFirst().priceZero()).isNull();
        assertThat(report.priceOutliers()).isNull();
    }

    @Test
    void schemaDriftListsWhatIsMissingOnEachSide() {
        SchemaDrift drift = SchemaDrift.compare("events-value", List.of("a", "b", "c"), List.of("a", "c", "d"));

        assertThat(drift.status()).isEqualTo("DRIFT");
        assertThat(drift.missingInTable()).containsExactly("b");
        assertThat(drift.extraInTable()).containsExactly("d");
        assertThat(SchemaDrift.compare("s", List.of("a", "b"), List.of("b", "a")).status()).isEqualTo("OK");

        Report report = DataQuality.build("lh.ns.t", null, calmThen(100), null, COLUMNS, PRICE, drift, SETTINGS);
        assertThat(report.issues()).singleElement().asString().startsWith("Schema drift").contains("[b]").contains("[d]");
    }

    @Test
    void theFieldsOfARegisteredAvroSchema() {
        String schema = "{\"type\":\"record\",\"name\":\"Event\",\"fields\":[{\"name\":\"event_time\",\"type\":\"long\"},"
                + "{\"name\":\"brand\",\"type\":[\"null\",\"string\"]}]}";

        assertThat(SchemaRegistryFields.fieldsOf(schema)).containsExactly("event_time", "brand");
        assertThatThrownBy(() -> SchemaRegistryFields.fieldsOf("\"string\"")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void medianOfOddAndEvenCounts() {
        assertThat(DataQuality.median(new long[] {5, 1, 3})).isEqualTo(3.0);
        assertThat(DataQuality.median(new long[] {4, 1, 3, 2})).isEqualTo(2.5);
    }

    @Test
    void wrongSettingsAndDaysAreErrorsTheModelCanRead() {
        assertThatThrownBy(() -> SETTINGS.withMinVolumeRatio(0)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minVolumeRatio");
        assertThatThrownBy(() -> SETTINGS.withMinVolumeRatio(1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Settings(0.7, 2, 3, Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QualityTools.day("10/03/2024")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2024-03-10");
        assertThat(QualityTools.day(" 2024-03-10 ")).isEqualTo(LocalDate.parse("2024-03-10"));
    }
}
