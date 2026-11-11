// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.nlchart;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Port of buildQuery (FR-NLC-11) and the mode-B rule of FR-NLC-14: every allowed SQL-mode
 * configuration yields exactly one query; pie, pivot_table and table with totals do not.
 */
class QueryContextBuilderTest {
    @Test
    void timeseriesLineIsOrderedByTheTimeColumn() {
        Map<String, Object> p = Map.of("viz_type", "line", "x_axis", "order_date", "granularity_sqla", "order_date",
                "metrics", List.of("total_revenue"), "time_grain_sqla", "P1M", "row_limit", 100);
        Map<String, Object> q = QueryContextBuilder.queries(p).get(0);
        assertThat(q).containsEntry("columns", List.of("order_date")).containsEntry("is_timeseries", true)
                .containsEntry("orderby", List.of(List.of("order_date", true))).containsEntry("time_range", "No filter");
    }

    @Test
    void singleQueryConfigurations() {
        assertThat(QueryContextBuilder.queries(Map.of("viz_type", "bar", "x_axis", "region", "metrics", List.of("m"))))
                .hasSize(1);
        assertThat(QueryContextBuilder.queries(Map.of("viz_type", "scatter", "entity", "country", "x_metric", "a",
                "y_metric", "b"))).hasSize(1);
        assertThat(QueryContextBuilder.queries(Map.of("viz_type", "heatmap", "x_axis", "a", "y_axis", "b", "metric", "m")))
                .hasSize(1);
        assertThat(QueryContextBuilder.queries(Map.of("viz_type", "big_number", "metric", "m"))).hasSize(1);
        assertThat(QueryContextBuilder.queries(Map.of("viz_type", "table", "query_mode", "raw",
                "all_columns", List.of("a")))).hasSize(1);
        // not allowed in SQL mode
        assertThat(QueryContextBuilder.queries(Map.of("viz_type", "pie", "groupby", "a", "metric", "m"))).hasSize(2);
        assertThat(QueryContextBuilder.queries(Map.of("viz_type", "table", "query_mode", "aggregate",
                "groupby", List.of("a"), "metrics", List.of("m"), "show_totals", true))).hasSize(2);
        assertThat(QueryContextBuilder.queries(Map.of("viz_type", "pivot_table", "groupbyRows", List.of("a"),
                "groupbyColumns", List.of("b"), "metrics", List.of("m")))).hasSize(4);
        assertThat(QueryContextBuilder.queries(Map.of("viz_type", "custom_host_type"))).isNull();
    }

    @Test
    void sqlRowsBecomeAQueryResult() {
        Map<String, Object> r = SqlResults.toQueryResult(List.of("d", "n"), List.of(java.sql.Types.DATE, java.sql.Types.DECIMAL),
                List.of(Map.of("d", "2025-06-01", "n", new java.math.BigDecimal("1.123456789012345678"))), "SELECT 1",
                java.time.Instant.parse("2026-06-30T00:00:00Z"));
        assertThat(r.get("coltypes")).isEqualTo(List.of(2, 0));
        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) r.get("data")).get(0);
        assertThat(row.get("d")).isEqualTo(1748736000000L);
        assertThat(row.get("n")).isEqualTo(1.12345678901235);
    }
}
