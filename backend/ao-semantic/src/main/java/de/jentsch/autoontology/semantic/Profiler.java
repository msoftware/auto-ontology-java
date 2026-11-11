// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.semantic;

import de.jentsch.autoontology.connectors.SourceDatabase;
import de.jentsch.autoontology.connectors.TabularResult;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Column profiling (visit_enter.py::calculate_columns_profiling, FR-SEM-02 step 1): sample of up to
 * 1000 rows (120 s), is_unique, top 5 values, a DISTINCT probe for low-cardinality text columns
 * (≤ 25 values, switchable), no samples for date/time/uuid types or strings over 30 characters,
 * date notation, and a sampling breaker after 3 consecutive failures per database.
 */
public class Profiler {
    private static final Logger log = LoggerFactory.getLogger(Profiler.class);
    static final int SAMPLE_LIMIT = 1000;
    static final int TIMEOUT_SECONDS = 120;
    static final int MAX_CONSECUTIVE_FAILURES = 3;
    static final int TOP_N = 5;
    static final int MAX_SAMPLE_LEN = 30;
    static final int LOW_CARDINALITY_MAX = 25;
    static final List<String> EXCLUDED_TYPES = List.of("date", "time", "timestamp", "datetime", "uuid");
    static final List<String> TEXT_TYPES = List.of("char", "text", "string", "clob", "enum");

    private final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

    /** Profile of one column. */
    public record ColumnProfile(List<Object> sampleValues, Boolean isUnique, String format) {}

    /** The breaker is reset at the start of each compilation pass. */
    public void resetBreaker(String databaseName) {
        failures.remove(databaseName);
    }

    public boolean breakerOpen(String databaseName) {
        return failures.getOrDefault(databaseName, new AtomicInteger()).get() >= MAX_CONSECUTIVE_FAILURES;
    }

    static boolean excludedType(String dataType) {
        String t = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT);
        return EXCLUDED_TYPES.stream().anyMatch(t::contains);
    }

    static boolean textType(String dataType) {
        String t = dataType == null ? "" : dataType.toLowerCase(Locale.ROOT);
        return TEXT_TYPES.stream().anyMatch(t::contains);
    }

    /**
     * @return profiles by column name; empty when sampling is impossible (breaker open or failure)
     */
    public Map<String, ColumnProfile> profile(SourceDatabase db, String schema, String table,
            List<Map<String, Object>> columns, boolean distinctProbing) {
        if (breakerOpen(db.databaseName())) {
            return Map.of();
        }
        TabularResult sample;
        try {
            sample = db.execute(db.sampleQuery(schema, table, SAMPLE_LIMIT), List.of(), TIMEOUT_SECONDS, SAMPLE_LIMIT);
            failures.computeIfAbsent(db.databaseName(), k -> new AtomicInteger()).set(0);
        } catch (RuntimeException e) {
            int n = failures.computeIfAbsent(db.databaseName(), k -> new AtomicInteger()).incrementAndGet();
            log.warn("Profiling sample of {}.{} failed ({} in a row): {}", schema, table, n, e.getMessage());
            if (n >= MAX_CONSECUTIVE_FAILURES) {
                log.warn("Sampling breaker open for {}: no more samples in this pass", db.databaseName());
            }
            return Map.of();
        }
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < sample.columns().size(); i++) {
            index.put(sample.columns().get(i).toLowerCase(Locale.ROOT), i);
        }
        Map<String, ColumnProfile> out = new LinkedHashMap<>();
        for (Map<String, Object> col : columns) {
            String name = (String) col.get("name");
            String type = (String) col.get("data_type");
            Integer idx = index.get(name.toLowerCase(Locale.ROOT));
            if (idx == null) {
                continue;
            }
            List<Object> values = new ArrayList<>();
            for (List<Object> row : sample.rows()) {
                values.add(row.get(idx));
            }
            List<Object> nonNull = values.stream().filter(v -> v != null).toList();
            Boolean unique = nonNull.isEmpty() ? null : new HashSet<>(nonNull).size() == nonNull.size();
            String format = DateFormats.isDateType(type) ? null : textType(type) ? DateFormats.infer(nonNull) : null;
            List<Object> samples = List.of();
            if (!excludedType(type)) {
                samples = topValues(nonNull);
                if (distinctProbing && Boolean.FALSE.equals(unique) && textType(type)) {
                    List<Object> distinct = distinctIfLowCardinality(db, schema, table, name);
                    if (distinct != null) {
                        samples = merge(samples, distinct);
                    }
                }
            }
            out.put(name, new ColumnProfile(samples, unique, format));
        }
        return out;
    }

    /** The 5 most frequent values, ties in first-seen order, without long strings. */
    static List<Object> topValues(List<Object> values) {
        Map<Object, Integer> counts = new LinkedHashMap<>();
        for (Object v : values) {
            if (v instanceof String s && s.length() > MAX_SAMPLE_LEN) {
                continue;
            }
            counts.merge(v, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(TOP_N)
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<Object> distinctIfLowCardinality(SourceDatabase db, String schema, String table, String column) {
        String sql = db.limit("SELECT DISTINCT " + db.quote(column) + " FROM " + db.qualify(schema, table) + " WHERE "
                + db.quote(column) + " IS NOT NULL", LOW_CARDINALITY_MAX + 1);
        try {
            TabularResult r = db.execute(sql, List.of(), TIMEOUT_SECONDS, LOW_CARDINALITY_MAX + 1);
            if (r.rows().size() > LOW_CARDINALITY_MAX) {
                return null;
            }
            return r.column(0).stream().filter(v -> !(v instanceof String s && s.length() > MAX_SAMPLE_LEN)).toList();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<Object> merge(List<Object> top, List<Object> distinct) {
        Set<String> seen = new HashSet<>();
        List<Object> out = new ArrayList<>();
        for (Object v : top) {
            if (seen.add(String.valueOf(v))) {
                out.add(v);
            }
        }
        distinct.stream().sorted((a, b) -> String.valueOf(a).compareTo(String.valueOf(b))).forEach(v -> {
            if (seen.add(String.valueOf(v))) {
                out.add(v);
            }
        });
        return out;
    }
}
