// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.nlchart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Java port of {@code plugin.buildQuery} of the 8 built-in viz types of quasar-dashboard
 * (packages/dashboard/src/viz/&#42;/buildQuery.ts, viz/shared/query.ts, utils/pivot.ts) for
 * FR-NLC-11 step 3. Works on the JSON form of ChartParams and QueryObjects. Unknown viz types
 * (host plugins) return {@code null}: they are validated only on the client.
 */
public final class QueryContextBuilder {
    public static final List<String> BUILT_IN = List.of("line", "bar", "pie", "big_number", "scatter", "heatmap",
            "pivot_table", "table");

    private QueryContextBuilder() {}

    /** {@code buildQueryContext}: datasource, queries, result format/type and form_data. */
    public static Map<String, Object> context(long datasetId, Map<String, Object> params, String resultType) {
        List<Map<String, Object>> queries = queries(params);
        if (queries == null) {
            return null;
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("datasource", Map.of("id", datasetId, "type", "table"));
        ctx.put("queries", queries);
        ctx.put("result_format", "json");
        ctx.put("result_type", resultType);
        ctx.put("form_data", params);
        return ctx;
    }

    public static List<Map<String, Object>> queries(Map<String, Object> p) {
        return switch (String.valueOf(p.get("viz_type"))) {
            case "line", "bar" -> xy(p);
            case "pie" -> pie(p);
            case "big_number" -> bigNumber(p);
            case "scatter" -> scatter(p);
            case "heatmap" -> heatmap(p);
            case "pivot_table" -> pivot(p);
            case "table" -> table(p);
            default -> null;
        };
    }

    // ------------------------------------------------------------------ shared/query.ts

    @SuppressWarnings("unchecked")
    static Map<String, Object> base(Map<String, Object> p, boolean timeGrain) {
        Map<String, Object> b = new LinkedHashMap<>();
        List<Map<String, Object>> filters = new ArrayList<>();
        for (Object o : list(p.get("adhoc_filters"))) {
            if (o instanceof Map<?, ?> f && f.get("col") != null && f.get("op") != null) {
                Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) f);
                copy.remove("isExtra");
                filters.add(copy);
            }
        }
        b.put("filters", filters);
        b.put("granularity", p.get("granularity_sqla"));
        b.put("time_range", p.get("time_range") == null ? "No filter" : p.get("time_range"));
        b.put("time_grain", timeGrain ? p.get("time_grain_sqla") : null);
        return b;
    }

    static String metricLabel(Object m) {
        if (m instanceof Map<?, ?> map) {
            return String.valueOf(map.get("label"));
        }
        return String.valueOf(m);
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return o instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
    }

    static boolean present(Object o) {
        return o != null && !"".equals(o);
    }

    /** {@code orderByOrUndefined}: drops empty keys; null when nothing remains. */
    static List<Object> orderBy(Object raw) {
        List<Object> valid = new ArrayList<>();
        for (Object o : list(raw)) {
            if (o instanceof List<?> pair && !pair.isEmpty() && present(pair.get(0))) {
                valid.add(pair);
            }
        }
        return valid.isEmpty() ? null : valid;
    }

    /** {@code clean}: removes undefined (here: null) values except where null is meaningful. */
    static Map<String, Object> clean(Map<String, Object> q) {
        Map<String, Object> out = new LinkedHashMap<>();
        q.forEach((k, v) -> {
            if (v != null || k.equals("metrics") || k.equals("granularity") || k.equals("time_grain")) {
                out.put(k, v);
            }
        });
        return out;
    }

    private static Map<String, Object> query(List<Object> columns, Object metrics, Map<String, Object> base) {
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("columns", columns);
        q.put("metrics", metrics);
        q.putAll(base);
        return q;
    }

    // ------------------------------------------------------------------ per type

    static List<Map<String, Object>> xy(Map<String, Object> p) {
        Map<String, Object> base = base(p, true);
        List<Object> columns = new ArrayList<>();
        if (present(p.get("x_axis"))) {
            columns.add(p.get("x_axis"));
        }
        if (present(p.get("series"))) {
            columns.add(p.get("series"));
        }
        boolean timeseries = present(p.get("x_axis")) && Objects.equals(p.get("x_axis"), base.get("granularity"));
        List<Object> orderby = orderBy(p.get("orderby"));
        if (orderby == null && timeseries) {
            orderby = List.of(List.of(p.get("x_axis"), true));
        }
        Map<String, Object> q = query(columns, p.get("metrics"), base);
        q.put("orderby", orderby);
        q.put("row_limit", p.get("row_limit"));
        int seriesLimit = p.get("series_limit") instanceof Number n ? n.intValue() : 0;
        if (present(p.get("series")) && seriesLimit > 0) {
            q.put("series_limit", seriesLimit);
            if (p.get("series_limit_metric") != null) {
                q.put("series_limit_metric", p.get("series_limit_metric"));
            }
        }
        if (timeseries) {
            q.put("is_timeseries", true);
        }
        return List.of(clean(q));
    }

    static List<Map<String, Object>> pie(Map<String, Object> p) {
        Object metric = p.get("metric");
        List<Object> metrics = metric == null ? List.of() : List.of(metric);
        Map<String, Object> base = base(p, true);
        Map<String, Object> main = query(present(p.get("groupby")) ? List.of(p.get("groupby")) : List.of(), metrics, base);
        if (metric != null) {
            main.put("orderby", List.of(List.of(metricLabel(metric), !Boolean.TRUE.equals(p.get("sort_desc")))));
        }
        main.put("row_limit", p.get("row_limit"));
        if (metric == null || !present(p.get("groupby"))) {
            return List.of(clean(main));
        }
        Map<String, Object> total = query(List.of(), metrics, base);
        total.put("time_grain", null);
        total.put("row_limit", 1);
        return List.of(clean(main), clean(total));
    }

    static List<Map<String, Object>> bigNumber(Map<String, Object> p) {
        Object metric = p.get("metric");
        List<Object> metrics = metric == null ? List.of() : List.of(metric);
        Map<String, Object> base = base(p, true);
        if (!Boolean.TRUE.equals(p.get("show_trend")) || base.get("granularity") == null) {
            Map<String, Object> q = query(List.of(), metrics, base);
            q.put("time_grain", null);
            q.put("row_limit", 1);
            return List.of(clean(q));
        }
        Map<String, Object> q = query(List.of(base.get("granularity")), metrics, base);
        q.put("time_grain", base.get("time_grain") == null ? "P1D" : base.get("time_grain"));
        q.put("orderby", List.of(List.of(base.get("granularity"), false)));
        q.put("row_limit", p.get("row_limit"));
        q.put("is_timeseries", true);
        return List.of(clean(q));
    }

    static List<Map<String, Object>> scatter(Map<String, Object> p) {
        List<Object> metrics = new ArrayList<>();
        for (String k : List.of("x_metric", "y_metric", "size_metric")) {
            if (p.get(k) != null && !"".equals(p.get(k))) {
                metrics.add(p.get(k));
            }
        }
        List<Object> columns = new ArrayList<>();
        for (String k : List.of("entity", "series")) {
            if (present(p.get(k))) {
                columns.add(p.get(k));
            }
        }
        Map<String, Object> q = query(columns, metrics, base(p, false));
        q.put("row_limit", p.get("row_limit"));
        return List.of(clean(q));
    }

    static List<Map<String, Object>> heatmap(Map<String, Object> p) {
        List<Object> columns = new ArrayList<>();
        for (String k : List.of("x_axis", "y_axis")) {
            if (present(p.get(k))) {
                columns.add(p.get(k));
            }
        }
        Map<String, Object> q = query(columns, p.get("metric") == null ? List.of() : List.of(p.get("metric")), base(p, true));
        q.put("row_limit", p.get("row_limit"));
        return List.of(clean(q));
    }

    /** utils/pivot.ts::pivotGroupings. */
    static List<int[]> pivotGroupings(int n, int m, boolean rowSubtotals, boolean colSubtotals) {
        List<int[]> list = new ArrayList<>(List.of(new int[] {n, m}, new int[] {n, 0}, new int[] {0, m}, new int[] {0, 0}));
        List<Integer> rowLevels = new ArrayList<>(List.of(n));
        if (rowSubtotals) {
            for (int i = n - 1; i >= 1; i--) {
                rowLevels.add(i);
            }
        }
        rowLevels.add(0);
        List<Integer> colLevels = new ArrayList<>(List.of(m));
        if (colSubtotals) {
            for (int j = m - 1; j >= 1; j--) {
                colLevels.add(j);
            }
        }
        colLevels.add(0);
        for (int i : rowLevels) {
            for (int j : colLevels) {
                list.add(new int[] {i, j});
            }
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<int[]> out = new ArrayList<>();
        for (int[] g : list) {
            if (seen.add(g[0] + "|" + g[1])) {
                out.add(g);
            }
        }
        return out;
    }

    static List<Map<String, Object>> pivot(Map<String, Object> p) {
        List<Object> rows = list(p.get("groupbyRows"));
        List<Object> cols = list(p.get("groupbyColumns"));
        Map<String, Object> base = base(p, true);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int[] g : pivotGroupings(rows.size(), cols.size(), Boolean.TRUE.equals(p.get("row_subtotals")),
                Boolean.TRUE.equals(p.get("col_subtotals")))) {
            List<Object> columns = new ArrayList<>(rows.subList(0, g[0]));
            columns.addAll(cols.subList(0, g[1]));
            Map<String, Object> q = query(columns, p.get("metrics"), base);
            q.put("row_limit", p.get("row_limit"));
            out.add(clean(q));
        }
        return out;
    }

    static List<Map<String, Object>> table(Map<String, Object> p) {
        Map<String, Object> base = base(p, true);
        int pageLength = p.get("page_length") instanceof Number n ? n.intValue() : 0;
        boolean paging = Boolean.TRUE.equals(p.get("server_pagination")) && pageLength > 0;
        Object limit = paging ? (Object) (pageLength + 1) : p.get("row_limit");
        if ("raw".equals(p.get("query_mode"))) {
            List<Object> all = list(p.get("all_columns"));
            List<Object> orderby = new ArrayList<>();
            for (Object o : list(p.get("orderby"))) {
                if (o instanceof List<?> pair && !pair.isEmpty() && pair.get(0) instanceof String s && all.contains(s)) {
                    orderby.add(pair);
                }
            }
            Map<String, Object> q = query(all, null, base);
            q.put("time_grain", null);
            q.put("orderby", orderBy(orderby));
            q.put("row_limit", limit);
            if (paging) {
                q.put("row_offset", 0);
            }
            return List.of(clean(q));
        }
        Map<String, Object> main = query(list(p.get("groupby")), p.get("metrics"), base);
        main.put("orderby", orderBy(p.get("orderby")));
        main.put("row_limit", limit);
        if (paging) {
            main.put("row_offset", 0);
        }
        if (!Boolean.TRUE.equals(p.get("show_totals"))) {
            return List.of(clean(main));
        }
        Map<String, Object> totals = query(List.of(), p.get("metrics"), base);
        totals.put("time_grain", null);
        totals.put("row_limit", 1);
        return List.of(clean(main), clean(totals));
    }
}
