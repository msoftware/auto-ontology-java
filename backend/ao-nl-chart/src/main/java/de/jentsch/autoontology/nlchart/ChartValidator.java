// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.nlchart;

import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.Json;
import de.jentsch.quasardashboard.error.ApiException;
import de.jentsch.quasardashboard.error.QueryValidationException;
import de.jentsch.quasardashboard.model.Models.Dataset;
import de.jentsch.quasardashboard.model.Models.DatasetColumn;
import de.jentsch.quasardashboard.query.QueryResult;
import de.jentsch.quasardashboard.service.ChartDataService;
import de.jentsch.quasardashboard.time.TimeRange;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validation before answering (FR-NLC-11, in this order): (1) viz catalog, (2) columns, metrics and
 * roles against the dataset, (3) query_context with the ported buildQuery, (4) compilation by
 * the dashboard engine with {@code result_type: "query"}. Filter literals are checked against the
 * real values (FR-NLC-10).
 */
public class ChartValidator {
    static final Set<String> OPERATORS = Set.of("==", "!=", ">", "<", ">=", "<=", "LIKE", "NOT LIKE", "ILIKE",
            "NOT ILIKE", "IS NULL", "IS NOT NULL", "IN", "NOT IN", "IS TRUE", "IS FALSE", "TEMPORAL_RANGE");
    static final Set<String> AGGREGATES = Set.of("COUNT", "COUNT_DISTINCT", "SUM", "AVG", "MIN", "MAX");
    private static final Set<String> COLUMN_CONTROLS = Set.of("x_axis", "series", "groupby", "entity", "y_axis",
            "granularity_sqla");
    private static final Set<String> COLUMN_LIST_CONTROLS = Set.of("groupby", "all_columns", "groupbyRows", "groupbyColumns");
    private static final Set<String> METRIC_CONTROLS = Set.of("metric", "x_metric", "y_metric", "size_metric",
            "series_limit_metric");

    private final ChartDataService chartData;
    private final AoTime time;
    private final Map<String, Object> catalog;

    public ChartValidator(ChartDataService chartData, AoTime time) {
        this.chartData = chartData;
        this.time = time;
        this.catalog = loadCatalog();
    }

    static Map<String, Object> loadCatalog() {
        try (InputStream in = ChartValidator.class.getClassLoader().getResourceAsStream("nl-chart/viz-catalog.json")) {
            return Json.readMap(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Map<String, Object> catalog() {
        return catalog;
    }

    /** Result: errors (empty = valid), the completed params, query_context, warnings and assumptions. */
    public record Result(List<String> errors, Map<String, Object> params, Map<String, Object> queryContext,
            List<String> warnings, List<String> assumptions) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> typeSpec(String vizType) {
        return (Map<String, Object>) ((Map<String, Object>) catalog.get("types")).get(vizType);
    }

    /** Defaults of the plugin plus the common controls (useExploreStore.init). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> withDefaults(Dataset d, String vizType, Map<String, Object> params) {
        Map<String, Object> p = new LinkedHashMap<>();
        Map<String, Object> spec = typeSpec(vizType);
        if (spec != null) {
            p.putAll((Map<String, Object>) spec.getOrDefault("defaults", Map.of()));
        }
        p.put("adhoc_filters", new ArrayList<>());
        p.put("time_range", "No filter");
        p.put("granularity_sqla", d.mainDttmCol());
        p.put("time_grain_sqla", null);
        p.putIfAbsent("row_limit", 10000);
        params.forEach((k, v) -> {
            if (v != null) {
                p.put(k, v);
            }
        });
        p.put("viz_type", vizType);
        p.put("datasource", d.id() + "__table");
        return p;
    }

    @SuppressWarnings("unchecked")
    public Result validate(Dataset d, String vizType, Map<String, Object> rawParams, boolean checkValues) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> assumptions = new ArrayList<>();
        Map<String, Object> spec = typeSpec(vizType);
        Map<String, Object> p = withDefaults(d, vizType, rawParams);
        if (spec == null) {
            // host plugin: only client-side validation (FR-NLC-13)
            warnings.add("viz_type " + vizType + " is not built in; validated on the client only");
            return new Result(errors, p, null, warnings, assumptions);
        }
        // (1) catalog: required controls and enum values
        Map<String, Object> controls = (Map<String, Object>) spec.get("controls");
        controls.forEach((name, c) -> {
            Map<String, Object> ctl = (Map<String, Object>) c;
            Object v = p.get(name);
            boolean empty = v == null || "".equals(v) || (v instanceof List<?> l && l.isEmpty());
            if (Boolean.TRUE.equals(ctl.get("required")) && empty
                    && !("table".equals(vizType) && List.of("metrics", "groupby", "all_columns").contains(name))) {
                errors.add("Control " + name + " is required for " + vizType);
            }
            if (!empty && ctl.get("values") instanceof List<?> values && !values.contains(v)) {
                errors.add("Control " + name + " must be one of " + values);
            }
        });
        if ("table".equals(vizType)) {
            boolean raw = "raw".equals(p.get("query_mode"));
            if (raw && QueryContextBuilder.list(p.get("all_columns")).isEmpty()) {
                errors.add("Control all_columns is required in raw mode");
            }
            if (!raw && QueryContextBuilder.list(p.get("metrics")).isEmpty()
                    && QueryContextBuilder.list(p.get("groupby")).isEmpty()) {
                errors.add("Controls metrics or groupby are required in aggregate mode");
            }
        }
        // (2) columns, metrics, roles
        for (String k : COLUMN_CONTROLS) {
            Object v = p.get(k);
            if (v instanceof String s && !s.isEmpty()) {
                checkColumn(d, k, s, errors, "granularity_sqla".equals(k));
            }
        }
        for (String k : COLUMN_LIST_CONTROLS) {
            if (p.get(k) instanceof List<?> l) {
                for (Object c : l) {
                    checkColumn(d, k, String.valueOf(c), errors, false);
                }
            }
        }
        for (String k : METRIC_CONTROLS) {
            if (p.get(k) != null) {
                checkMetric(d, k, p.get(k), errors);
            }
        }
        if (p.get("metrics") instanceof List<?> l) {
            for (Object m : l) {
                checkMetric(d, "metrics", m, errors);
            }
        }
        String timeRange = String.valueOf(p.get("time_range"));
        try {
            TimeRange.parse(timeRange, time.now());
        } catch (RuntimeException e) {
            errors.add("INVALID_TIME_RANGE_ERROR: time_range '" + timeRange + "' is invalid: " + e.getMessage());
        }
        List<Object> filters = new ArrayList<>();
        if (p.get("adhoc_filters") instanceof List<?> rawFilters) {
            filters.addAll(rawFilters);
        } else if (p.get("adhoc_filters") != null) {
            errors.add("Control adhoc_filters must be a list of {col, op, val} objects");
        }
        for (Object f : filters) {
            if (!(f instanceof Map<?, ?>)) {
                errors.add("Every entry of adhoc_filters must be an object {col, op, val}");
                continue;
            }
            Map<String, Object> filter = (Map<String, Object>) f;
            String op = String.valueOf(filter.get("op"));
            if (!OPERATORS.contains(op)) {
                errors.add("Filter operator " + op + " is not allowed");
            }
            checkColumn(d, "adhoc_filters", String.valueOf(filter.get("col")), errors, false);
        }
        if (!errors.isEmpty()) {
            return new Result(errors, p, null, warnings, assumptions);
        }
        if (checkValues) {
            repairFilterValues(d, filters, warnings, assumptions);
        }
        // (3) query_context with the same logic as plugin.buildQuery, (4) engine compilation
        Map<String, Object> ctx = QueryContextBuilder.context(d.id(), p, "query");
        try {
            chartData.run(Json.MAPPER.valueToTree(ctx), null);
        } catch (QueryValidationException e) {
            errors.add(e.errorType() + ": " + e.getMessage());
        } catch (ApiException e) {
            errors.add("INVALID_QUERY: " + e.getMessage());
        }
        Map<String, Object> full = QueryContextBuilder.context(d.id(), p, "full");
        return new Result(errors, p, full, warnings, assumptions);
    }

    private static void checkColumn(Dataset d, String control, String name, List<String> errors, boolean temporal) {
        Optional<DatasetColumn> c = d.column(name);
        if (c.isEmpty()) {
            errors.add("INVALID_COLUMN_ERROR: column " + name + " (" + control + ") does not exist in dataset "
                    + d.tableName());
        } else if (temporal && !c.get().isDttm()) {
            errors.add("INVALID_COLUMN_ERROR: column " + name + " is not temporal");
        } else if (List.of("x_axis", "series", "groupby", "entity", "y_axis", "groupbyRows", "groupbyColumns")
                .contains(control) && !c.get().groupby() && !c.get().isDttm()) {
            errors.add("INVALID_COLUMN_ERROR: column " + name + " is not groupable");
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkMetric(Dataset d, String control, Object m, List<String> errors) {
        if (m instanceof String s) {
            if (d.metrics().stream().noneMatch(x -> x.metricName().equals(s))) {
                errors.add("INVALID_METRIC_ERROR: saved metric " + s + " (" + control + ") does not exist in dataset "
                        + d.tableName());
            }
            return;
        }
        if (!(m instanceof Map<?, ?>)) {
            errors.add("INVALID_METRIC_ERROR: metric of " + control + " has an invalid form");
            return;
        }
        Map<String, Object> adhoc = (Map<String, Object>) m;
        String agg = String.valueOf(adhoc.get("aggregate")).toUpperCase(Locale.ROOT);
        if (!"SIMPLE".equals(adhoc.get("expressionType")) || !AGGREGATES.contains(agg)) {
            errors.add("INVALID_METRIC_ERROR: ad-hoc metrics must be SIMPLE with one of " + AGGREGATES);
            return;
        }
        Object column = adhoc.get("column");
        String col = column instanceof Map<?, ?> cm ? String.valueOf(cm.get("column_name")) : null;
        if (col == null) {
            if (!"COUNT".equals(agg)) {
                errors.add("INVALID_METRIC_ERROR: only COUNT may have column null");
            }
            return;
        }
        Optional<DatasetColumn> c = d.column(col);
        if (c.isEmpty()) {
            errors.add("INVALID_METRIC_ERROR: column " + col + " of the ad-hoc metric does not exist");
        } else if ((agg.equals("SUM") || agg.equals("AVG")) && c.get().typeGeneric() != 0) {
            errors.add("INVALID_METRIC_ERROR: " + agg + " needs a numeric column, " + col + " is not numeric");
        }
    }

    /** FR-NLC-10: literals of ==, !=, IN, NOT IN on text columns are matched against the real values. */
    @SuppressWarnings("unchecked")
    private void repairFilterValues(Dataset d, List<Object> filters, List<String> warnings, List<String> assumptions) {
        for (Object f : filters) {
            Map<String, Object> filter = (Map<String, Object>) f;
            String op = String.valueOf(filter.get("op"));
            if (!List.of("==", "!=", "IN", "NOT IN").contains(op)) {
                continue;
            }
            String col = String.valueOf(filter.get("col"));
            Optional<DatasetColumn> c = d.column(col);
            if (c.isEmpty() || c.get().typeGeneric() != 1) {
                continue;
            }
            List<String> values = values(d, col);
            if (values.isEmpty()) {
                continue;
            }
            Object val = filter.get("val");
            if (val instanceof List<?> list) {
                List<Object> fixed = new ArrayList<>();
                for (Object v : list) {
                    fixed.add(fix(col, String.valueOf(v), values, warnings, assumptions));
                }
                filter.put("val", fixed);
            } else if (val != null) {
                filter.put("val", fix(col, String.valueOf(val), values, warnings, assumptions));
            }
        }
    }

    private static String fix(String col, String v, List<String> values, List<String> warnings, List<String> assumptions) {
        if (values.contains(v)) {
            return v;
        }
        for (String x : values) {
            if (x.equalsIgnoreCase(v.strip())) {
                assumptions.add(col + ": '" + v + "' → '" + x + "'");
                return x;
            }
        }
        String best = null;
        double bestScore = 0.8;
        for (String x : values) {
            double s = similarity(v.toLowerCase(Locale.ROOT), x.toLowerCase(Locale.ROOT));
            if (s > bestScore) {
                bestScore = s;
                best = x;
            }
        }
        if (best != null) {
            assumptions.add(col + ": '" + v + "' → '" + best + "'");
            return best;
        }
        warnings.add("Value '" + v + "' does not occur in column " + col + " (known values: "
                + String.join(", ", values.subList(0, Math.min(10, values.size()))) + ")");
        return v;
    }

    static double similarity(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                dp[i][j] = a.charAt(i - 1) == b.charAt(j - 1) ? dp[i - 1][j - 1] + 1 : Math.max(dp[i - 1][j], dp[i][j - 1]);
            }
        }
        return a.isEmpty() && b.isEmpty() ? 1 : 2.0 * dp[a.length()][b.length()] / (a.length() + b.length());
    }

    /** Distinct values of a column through the dashboard engine. */
    public List<String> values(Dataset d, String column) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("columns", List.of(column));
        Map<String, Object> count = new LinkedHashMap<>();
        count.put("expressionType", "SIMPLE");
        count.put("aggregate", "COUNT");
        count.put("column", null);
        count.put("label", "n");
        q.put("metrics", List.of(count));
        q.put("filters", List.of());
        q.put("time_range", "No filter");
        q.put("row_limit", 500);
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("datasource", Map.of("id", d.id(), "type", "table"));
        ctx.put("queries", List.of(q));
        ctx.put("result_format", "json");
        ctx.put("result_type", "full");
        try {
            ChartDataService.Outcome out = chartData.run(Json.MAPPER.valueToTree(ctx), null);
            if (out instanceof ChartDataService.JsonOutcome json && !json.result().isEmpty()) {
                QueryResult r = json.result().get(0);
                return r.data().stream().map(row -> row.get(column)).filter(v -> v != null).map(String::valueOf).toList();
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        return List.of();
    }
}
