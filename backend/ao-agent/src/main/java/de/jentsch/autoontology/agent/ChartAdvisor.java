// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.agent;

import de.jentsch.autoontology.agent.AgentModels.VisualizationRecommendation;
import de.jentsch.autoontology.llm.LlmGateway;
import de.jentsch.autoontology.llm.LlmMessage;
import de.jentsch.autoontology.llm.ModelRole;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Chat visualization (FR-AGT-12) in the format of quasar-dashboard (NFR-QD-08): the LLM decides
 * table vs. chart and recommends one chart; the result is {@code {viz_type, params, mode: "sql"}}
 * whose metrics are result column names (mode B, FR-NLC-14). Only single-query configurations are
 * produced (bar, line, scatter, heatmap, big_number, table without totals).
 */
public class ChartAdvisor {
    static final List<String> ALLOWED = List.of("bar", "line", "scatter", "heatmap", "big_number", "table");

    private final LlmGateway llm;

    public ChartAdvisor(LlmGateway llm) {
        this.llm = llm;
    }

    public Optional<Map<String, Object>> recommend(String question, String sql, List<String> columns,
            List<Integer> jdbcTypes, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty() || columns == null || columns.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder cols = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            int t = jdbcTypes == null || jdbcTypes.size() <= i ? java.sql.Types.VARCHAR : jdbcTypes.get(i);
            cols.append(i > 0 ? ", " : "").append(columns.get(i)).append(" (").append(kind(t)).append(')');
        }
        String prompt = "I have a dataset resulting from the following SQL query:\n" + sql + "\n\n"
                + "The natural language question corresponding to this query is:\n" + question + "\n\n"
                + "The dataset has the following structure:\nSize: " + rows.size() + " rows\nColumns: " + cols + "\n\n"
                + "Decide whether a chart answers the question better than a table, and if so propose the SINGLE best"
                + " chart for a dashboard renderer.\n"
                + "Allowed viz_type values: bar (categories), line (time series), scatter (two measures per entity),"
                + " heatmap (two dimensions and one measure), big_number (a single value), table.\n"
                + "Rules:\n- Stick strictly to the original question; if it names a chart type, use it.\n"
                + "- Column names in x, metrics and series MUST be exact matches from Columns above; metrics must be"
                + " numeric columns.\n- A single scalar result is big_number with that column as the only metric.\n"
                + "- Time series: line with the temporal column as x. Pure categories: bar.\n"
                + "- scatter needs exactly two metrics; heatmap needs x, series (the second dimension) and one metric.\n"
                + "- If the result is a detail list or a chart would not help, set use_chart to false.";
        VisualizationRecommendation r = llm.tryStructured(ModelRole.NON_REASONING, List.of(LlmMessage.user(prompt)),
                VisualizationRecommendation.class).orElse(null);
        if (r == null || !r.use_chart() || r.viz_type() == null || !ALLOWED.contains(r.viz_type())) {
            return Optional.empty();
        }
        List<String> metrics = r.metrics().stream().filter(columns::contains).toList();
        String x = r.x() != null && columns.contains(r.x()) ? r.x() : null;
        String series = r.series() != null && columns.contains(r.series()) ? r.series() : null;
        Map<String, Object> params = new LinkedHashMap<>();
        switch (r.viz_type()) {
            case "bar", "line" -> {
                if (x == null || metrics.isEmpty()) {
                    return Optional.empty();
                }
                params.put("x_axis", x);
                params.put("metrics", metrics);
                if (series != null) {
                    params.put("series", series);
                }
            }
            case "scatter" -> {
                if (metrics.size() < 2) {
                    return Optional.empty();
                }
                params.put("entity", x);
                params.put("x_metric", metrics.get(0));
                params.put("y_metric", metrics.get(1));
            }
            case "heatmap" -> {
                if (x == null || series == null || metrics.isEmpty()) {
                    return Optional.empty();
                }
                params.put("x_axis", x);
                params.put("y_axis", series);
                params.put("metric", metrics.get(0));
            }
            case "big_number" -> {
                if (metrics.isEmpty()) {
                    return Optional.empty();
                }
                params.put("metric", metrics.get(0));
                params.put("show_trend", false);
            }
            default -> {
                params.put("query_mode", "raw");
                params.put("all_columns", new ArrayList<>(columns));
            }
        }
        params.put("row_limit", Math.max(rows.size(), 1));
        params.put("slice_name", r.title());
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("viz_type", r.viz_type());
        block.put("params", params);
        block.put("mode", "sql");
        return Optional.of(block);
    }

    /** QueryResult coltypes: 0 numeric, 1 text, 2 temporal, 3 boolean (BE-11/12). */
    public static int coltype(int jdbcType) {
        return switch (jdbcType) {
            case java.sql.Types.TINYINT, java.sql.Types.SMALLINT, java.sql.Types.INTEGER, java.sql.Types.BIGINT,
                    java.sql.Types.FLOAT, java.sql.Types.REAL, java.sql.Types.DOUBLE, java.sql.Types.NUMERIC,
                    java.sql.Types.DECIMAL -> 0;
            case java.sql.Types.DATE, java.sql.Types.TIME, java.sql.Types.TIMESTAMP,
                    java.sql.Types.TIMESTAMP_WITH_TIMEZONE, java.sql.Types.TIME_WITH_TIMEZONE -> 2;
            case java.sql.Types.BOOLEAN, java.sql.Types.BIT -> 3;
            default -> 1;
        };
    }

    private static String kind(int jdbcType) {
        return switch (coltype(jdbcType)) {
            case 0 -> "numeric";
            case 2 -> "temporal";
            case 3 -> "boolean";
            default -> "text";
        };
    }
}
