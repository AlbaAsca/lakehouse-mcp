package io.github.albaasca.lakehouse.mcp;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The verdict of data_quality_check, from counts already read from the table. Nothing here touches the lakehouse, so
 * every rule can be tested with numbers written by hand.
 *
 * <p>The counts follow evals/truth/compute_quality_truth.py: the day is the UTC date of event_time, a null is a missing
 * value, a duplicate is each extra copy of a row identical in every column, and price is out of range when it is
 * {@code <= 0} (a missing price is a null, not out of range).
 */
final class DataQuality {

    enum Status { OK, ISSUES }

    /**
     * @param minVolumeRatio a day of one event type is a drop when its rows are below this share of the median
     * @param historyDays how many days before it make that median
     * @param minHistoryDays fewer days of history than this and the day is not judged
     * @param knownNulls columns the source leaves empty by design: reported, but not an issue
     */
    record Settings(double minVolumeRatio, int historyDays, int minHistoryDays, Set<String> knownNulls) {

        Settings {
            if (!(minVolumeRatio > 0 && minVolumeRatio <= 1)) {
                throw new IllegalArgumentException("minVolumeRatio must be above 0 and at most 1: " + minVolumeRatio);
            }
            if (historyDays < 1 || minHistoryDays < 1 || minHistoryDays > historyDays) {
                throw new IllegalArgumentException("Need 1 <= minHistoryDays <= historyDays: " + minHistoryDays + ", "
                        + historyDays);
            }
            knownNulls = Set.copyOf(knownNulls);
        }

        Settings withMinVolumeRatio(double ratio) {
            return new Settings(ratio, historyDays, minHistoryDays, knownNulls);
        }
    }

    /**
     * What the table holds for one day, or for the rows without a day.
     *
     * @param price null when the table has no price column
     */
    record DayCounts(String day, long rows, Map<String, Long> rowsByEventType, Map<String, Long> nulls,
            long duplicateCopies, PriceCounts price) {}

    /** @param highOutliers prices above the upper fence of {@link PriceRule} */
    record PriceCounts(long zero, long negative, long highOutliers) {

        static final PriceCounts NONE = new PriceCounts(0, 0, 0);

        PriceCounts plus(PriceCounts other) {
            return new PriceCounts(zero + other.zero, negative + other.negative, highOutliers + other.highOutliers);
        }
    }

    /** @param empty true when the table has no row at all for this day */
    record DayReport(String day, boolean empty, long rows, Map<String, Long> rowsByEventType, Map<String, Long> nulls,
            long duplicateCopies, Long priceZero, Long priceNegative, Long priceOutOfRange, Long priceHighOutliers) {}

    /** @param rowsWithoutDay rows whose event_time is null, so they belong to no day */
    record Totals(long rows, long rowsWithoutDay, Map<String, Long> rowsByEventType, Map<String, Long> nulls,
            long duplicateCopies, Long priceZero, Long priceNegative, Long priceOutOfRange, Long priceHighOutliers) {}

    record VolumeDrop(String day, String eventType, long rows, double median, double ratio) {}

    record VolumeCheck(double minRatio, int historyDays, int minHistoryDays, List<VolumeDrop> drops) {}

    /** @param upperFence null when there is no positive price to measure */
    record PriceRule(String rule, Double upperFence) {

        static final String RULE = "Outlier = price above Q3 + 3 x IQR of the positive prices of the whole table";
    }

    /**
     * @param status OK, DRIFT, UNAVAILABLE (the registry did not answer) or NOT_APPLICABLE (the table is not fed by
     *        the registered topic)
     */
    record SchemaDrift(String status, String reason, String subject, List<String> missingInTable,
            List<String> extraInTable) {

        static SchemaDrift notApplicable(String reason) {
            return new SchemaDrift("NOT_APPLICABLE", reason, null, List.of(), List.of());
        }

        static SchemaDrift unavailable(String subject, String reason) {
            return new SchemaDrift("UNAVAILABLE", reason, subject, List.of(), List.of());
        }

        /** Names compared exactly, as Iceberg and Avro both do. */
        static SchemaDrift compare(String subject, List<String> registryFields, List<String> tableColumns) {
            List<String> missing = registryFields.stream().filter(field -> !tableColumns.contains(field)).toList();
            List<String> extra = tableColumns.stream().filter(column -> !registryFields.contains(column)).toList();
            boolean drift = !missing.isEmpty() || !extra.isEmpty();
            return new SchemaDrift(drift ? "DRIFT" : "OK", drift ? "The table and the registered schema differ" : null,
                    subject, missing, extra);
        }
    }

    record Report(String table, String day, Status status, List<String> issues, Totals totals, List<DayReport> days,
            VolumeCheck volume, PriceRule priceOutliers, List<String> informationalNulls, SchemaDrift schemaDrift) {}

    private DataQuality() {}

    /**
     * @param day the only day to report, or null for all of them; history before it still counts for volume
     * @param dated one entry per day that has rows, in any order
     * @param undated the rows without event_time, or null if there are none
     */
    static Report build(String table, LocalDate day, List<DayCounts> dated, DayCounts undated, List<String> columns,
            PriceRule priceRule, SchemaDrift drift, Settings settings) {
        boolean hasPrice = priceRule != null;
        TreeSet<String> types = new TreeSet<>();
        dated.forEach(counts -> types.addAll(counts.rowsByEventType().keySet()));
        List<DayCounts> filled = filled(day, dated, types, columns, hasPrice);

        List<VolumeDrop> drops = new ArrayList<>();
        for (int i = 0; i < filled.size(); i++) {
            DayCounts today = filled.get(i);
            if (day != null && !today.day().equals(day.toString())) {
                continue;
            }
            List<DayCounts> history = filled.subList(Math.max(0, i - settings.historyDays()), i);
            if (history.size() < settings.minHistoryDays()) {
                continue;
            }
            for (String type : types) {
                double median = median(history.stream().mapToLong(d -> d.rowsByEventType().getOrDefault(type, 0L)).toArray());
                long rows = today.rowsByEventType().getOrDefault(type, 0L);
                if (median > 0 && rows < settings.minVolumeRatio() * median) {
                    drops.add(new VolumeDrop(today.day(), type, rows, median, Math.round(rows / median * 1000) / 1000.0));
                }
            }
        }

        List<DayCounts> scope = filled.stream().filter(d -> day == null || d.day().equals(day.toString())).toList();
        List<DayReport> days = scope.stream().map(d -> report(d, dated, hasPrice)).toList();
        Totals totals = totals(scope, day == null ? undated : null, types, columns, hasPrice);

        List<String> informational = columns.stream().filter(settings.knownNulls()::contains).toList();
        List<String> issues = issues(totals, days, drops, informational, drift);
        return new Report(table, day == null ? null : day.toString(), issues.isEmpty() ? Status.OK : Status.ISSUES,
                issues, totals, days, new VolumeCheck(settings.minVolumeRatio(), settings.historyDays(),
                        settings.minHistoryDays(), drops), priceRule, informational, drift);
    }

    /** Every date from the first to the last, plus the day asked for: a day with no rows is an empty partition. */
    private static List<DayCounts> filled(LocalDate day, List<DayCounts> dated, Set<String> types, List<String> columns,
            boolean hasPrice) {
        TreeMap<LocalDate, DayCounts> byDate = new TreeMap<>();
        dated.forEach(counts -> byDate.put(LocalDate.parse(counts.day()), counts));
        if (day != null) {
            byDate.putIfAbsent(day, null);
        }
        List<DayCounts> out = new ArrayList<>();
        if (byDate.isEmpty()) {
            return out;
        }
        for (LocalDate date = byDate.firstKey(); !date.isAfter(byDate.lastKey()); date = date.plusDays(1)) {
            DayCounts counts = byDate.get(date);
            out.add(counts != null ? withAllKeys(counts, types, columns) : empty(date.toString(), types, columns, hasPrice));
        }
        return out;
    }

    private static DayCounts withAllKeys(DayCounts counts, Set<String> types, List<String> columns) {
        return new DayCounts(counts.day(), counts.rows(), zeros(types, counts.rowsByEventType()),
                zeros(columns, counts.nulls()), counts.duplicateCopies(), counts.price());
    }

    private static DayCounts empty(String day, Set<String> types, List<String> columns, boolean hasPrice) {
        return new DayCounts(day, 0, zeros(types, Map.of()), zeros(columns, Map.of()), 0, hasPrice ? PriceCounts.NONE : null);
    }

    private static Map<String, Long> zeros(Iterable<String> keys, Map<String, Long> values) {
        Map<String, Long> out = new LinkedHashMap<>();
        keys.forEach(key -> out.put(key, values.getOrDefault(key, 0L)));
        return out;
    }

    private static DayReport report(DayCounts d, List<DayCounts> dated, boolean hasPrice) {
        boolean empty = dated.stream().noneMatch(counts -> counts.day().equals(d.day()));
        PriceCounts price = d.price();
        return new DayReport(d.day(), empty, d.rows(), d.rowsByEventType(), d.nulls(), d.duplicateCopies(),
                hasPrice ? price.zero() : null, hasPrice ? price.negative() : null,
                hasPrice ? price.zero() + price.negative() : null, hasPrice ? price.highOutliers() : null);
    }

    private static Totals totals(List<DayCounts> scope, DayCounts undated, Set<String> types, List<String> columns,
            boolean hasPrice) {
        List<DayCounts> all = new ArrayList<>(scope);
        if (undated != null) {
            all.add(undated);
        }
        Map<String, Long> byType = zeros(types, Map.of());
        Map<String, Long> nulls = zeros(columns, Map.of());
        long rows = 0;
        long duplicates = 0;
        PriceCounts price = PriceCounts.NONE;
        for (DayCounts d : all) {
            rows += d.rows();
            duplicates += d.duplicateCopies();
            d.rowsByEventType().forEach((type, n) -> byType.merge(type, n, Long::sum));
            d.nulls().forEach((column, n) -> nulls.merge(column, n, Long::sum));
            if (d.price() != null) {
                price = price.plus(d.price());
            }
        }
        return new Totals(rows, undated == null ? 0 : undated.rows(), byType, nulls, duplicates,
                hasPrice ? price.zero() : null, hasPrice ? price.negative() : null,
                hasPrice ? price.zero() + price.negative() : null, hasPrice ? price.highOutliers() : null);
    }

    private static List<String> issues(Totals totals, List<DayReport> days, List<VolumeDrop> drops,
            List<String> informational, SchemaDrift drift) {
        List<String> issues = new ArrayList<>();
        days.stream().filter(DayReport::empty).forEach(d -> issues.add("Empty partition: no rows on " + d.day()));
        if (totals.rowsWithoutDay() > 0) {
            issues.add(totals.rowsWithoutDay() + " rows have no event_time, so they belong to no day");
        }
        totals.nulls().forEach((column, n) -> {
            if (n > 0 && !informational.contains(column)) {
                issues.add(n + " nulls in " + column);
            }
        });
        if (totals.duplicateCopies() > 0) {
            issues.add(totals.duplicateCopies() + " exact duplicate rows (extra copies)");
        }
        if (totals.priceOutOfRange() != null && totals.priceOutOfRange() > 0) {
            issues.add(totals.priceOutOfRange() + " rows with price <= 0 (" + totals.priceZero() + " zero, "
                    + totals.priceNegative() + " negative)");
        }
        if (totals.priceHighOutliers() != null && totals.priceHighOutliers() > 0) {
            issues.add(totals.priceHighOutliers() + " prices above the outlier fence");
        }
        drops.forEach(drop -> issues.add("Low volume: " + drop.rows() + " " + drop.eventType() + " rows on " + drop.day()
                + ", " + drop.ratio() + " of the median " + drop.median() + " of the days before"));
        if ("DRIFT".equals(drift.status())) {
            issues.add("Schema drift: missing in the table " + drift.missingInTable() + ", not in the registry "
                    + drift.extraInTable());
        }
        return issues;
    }

    static double median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }
}
