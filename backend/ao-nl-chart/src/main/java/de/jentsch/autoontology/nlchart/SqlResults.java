// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.nlchart;

import de.jentsch.autoontology.agent.ChartAdvisor;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mode B (FR-NLC-14): agent rows → QueryResult. coltypes from JDBC types (0 numeric, 1 text, 2
 * temporal, 3 boolean), temporal values as epoch milliseconds UTC, decimals with 15 significant
 * digits (BE-11, BE-12).
 */
public final class SqlResults {
    private SqlResults() {}

    public static Map<String, Object> toQueryResult(List<String> cols, List<Integer> jdbcTypes,
            List<Map<String, Object>> rows, String sql, Instant now) {
        List<Integer> coltypes = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) {
            coltypes.add(jdbcTypes == null || jdbcTypes.size() <= i ? 1 : ChartAdvisor.coltype(jdbcTypes.get(i)));
        }
        List<Map<String, Object>> data = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (int i = 0; i < cols.size(); i++) {
                out.put(cols.get(i), value(row.get(cols.get(i)), coltypes.get(i)));
            }
            data.add(out);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("colnames", cols);
        r.put("coltypes", coltypes);
        r.put("rowcount", data.size());
        r.put("data", data);
        r.put("applied_filters", List.of());
        r.put("rejected_filters", List.of());
        r.put("from_dttm", null);
        r.put("to_dttm", null);
        r.put("query", sql);
        r.put("is_cached", false);
        r.put("queried_dttm", now.toString());
        return r;
    }

    static Object value(Object v, int coltype) {
        if (v == null) {
            return null;
        }
        return switch (coltype) {
            case 0 -> v instanceof Number n ? round(n) : parseNumber(String.valueOf(v));
            case 2 -> epoch(String.valueOf(v));
            case 3 -> v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
            default -> String.valueOf(v);
        };
    }

    static Object round(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short) {
            return n;
        }
        BigDecimal bd = new BigDecimal(n.toString()).round(new MathContext(15));
        return bd.scale() <= 0 ? (Object) bd.longValue() : bd.doubleValue();
    }

    static Object parseNumber(String s) {
        try {
            return round(new BigDecimal(s));
        } catch (NumberFormatException e) {
            return s;
        }
    }

    static Object epoch(String s) {
        try {
            if (s.length() == 10) {
                return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            }
            if (s.endsWith("Z") || s.matches(".*[+-]\\d{2}:\\d{2}$")) {
                return OffsetDateTime.parse(s.replace(' ', 'T')).toInstant().toEpochMilli();
            }
            return LocalDateTime.parse(s.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException e) {
            return s;
        }
    }
}
