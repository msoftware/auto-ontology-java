// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.nlchart;

import de.jentsch.autoontology.agent.AgentRunner;
import de.jentsch.autoontology.agent.ChartAdvisor;
import de.jentsch.autoontology.common.AoProperties;
import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.CurrentUser;
import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.ingestion.ConnectionService;
import de.jentsch.autoontology.llm.Embedder;
import de.jentsch.autoontology.llm.LlmField;
import de.jentsch.autoontology.llm.LlmGateway;
import de.jentsch.autoontology.llm.LlmMessage;
import de.jentsch.autoontology.llm.ModelRole;
import de.jentsch.autoontology.store.AppRepository;
import de.jentsch.autoontology.vector.VectorStore;
import de.jentsch.autoontology.vector.VectorStore.SearchHit;
import de.jentsch.quasardashboard.model.Models.Dataset;
import de.jentsch.quasardashboard.model.Models.DatasetColumn;
import de.jentsch.quasardashboard.model.Models.DatasetMetric;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Charts from natural language (section 23.5). Mode A: dataset choice via the ontology, the LLM
 * produces dashboard ChartParams, validation (FR-NLC-11) with up to two repair rounds. Mode B:
 * when no dataset fits, the text-to-SQL agent answers and the rows become a QueryResult for the
 * controlled renderer (FR-NLC-14). Saving always goes through the dashboard API (POST /api/v1/chart/).
 */
public class NlChartService {
    static final int MAX_REPAIR_ROUNDS = 2;
    static final double MAX_DATASET_DISTANCE = 0.75;
    /**
     * Marker result of mode A: the suggestion stayed invalid and the {@code error} event is
     * already sent. Unlike {@code null} ("no dataset fits") it must not start mode B.
     */
    private static final Map<String, Object> FAILED = new java.util.HashMap<>();

    /** One alternative viz type with the reason. */
    public record Alternative(String viz_type, String reason) {}

    /** FR-NLC-07: structured LLM output. {@code params_json} carries the ChartParams as JSON text. */
    public record ChartSuggestion(
            @LlmField(value = "Id of the chosen dataset from the candidate list, or null when no single dataset can answer"
                    + " the question.", nullable = true) String dataset_id,
            @LlmField("Viz type: line, bar, pie, big_number, scatter, heatmap, pivot_table or table.") String viz_type,
            @LlmField("The chart params as a JSON object string: only data controls (plus customize controls the question"
                    + " names). Metrics are saved metric names (preferred) or SIMPLE ad-hoc objects.") String params_json,
            @LlmField("Chart name, at most 250 characters, in the language of the question.") String slice_name,
            @LlmField("Short chart description in the language of the question.") String description,
            @LlmField("How the question was interpreted.") String explanation,
            @LlmField("Assumptions made, e.g. 'Revenue = saved metric Total revenue'.") List<String> assumptions,
            @LlmField("Up to 2 other viz types with a reason.") List<Alternative> alternatives) {}

    /** Request of POST /suggest. */
    public record Request(String question, Long datasetId, String vizType, Map<String, Object> currentParams,
            String conversationId, Long dashboardId) {}

    private final DatasetSync sync;
    private final ChartValidator validator;
    private final LlmGateway llm;
    private final Embedder embedder;
    private final VectorStore vectors;
    private final AgentRunner agent;
    private final ChartAdvisor advisor;
    private final ConnectionService connections;
    private final AppRepository app;
    private final AoProperties props;
    private final CurrentUser user;
    private final AoTime time;

    public NlChartService(DatasetSync sync, ChartValidator validator, LlmGateway llm, Embedder embedder,
            VectorStore vectors, AgentRunner agent, ChartAdvisor advisor, ConnectionService connections,
            AppRepository app, AoProperties props, CurrentUser user, AoTime time) {
        this.sync = sync;
        this.validator = validator;
        this.llm = llm;
        this.embedder = embedder;
        this.vectors = vectors;
        this.agent = agent;
        this.advisor = advisor;
        this.connections = connections;
        this.app = app;
        this.props = props;
        this.user = user;
        this.time = time;
    }

    private static void step(Consumer<Map<String, Object>> sink, String node, String label, String phase, String thought) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "step");
        e.put("node", node);
        e.put("label", label);
        e.put("phase", phase);
        if (thought != null) {
            e.put("thought", thought);
        }
        sink.accept(e);
    }

    /** Runs the pipeline; emits step events and finally {@code chart_suggestion} or {@code error}. */
    public void suggest(Request req, Consumer<Map<String, Object>> sink) {
        String analyticsId = app.addAnalytics(user.id(), req.conversationId(), "nl-chart", req.question(), time.now());
        step(sink, "choose_dataset", "Choosing dataset", "start", null);
        List<Dataset> candidates = candidates(req);
        step(sink, "choose_dataset", "Choosing dataset", "end", candidates.isEmpty() ? "No matching dataset"
                : candidates.stream().map(Dataset::tableName).collect(Collectors.joining(", ")));
        Map<String, Object> result = candidates.isEmpty() ? null : modeA(req, candidates, sink);
        if (result == FAILED) {
            // reported as an error; running the SQL agent now would only add a second, unrelated answer
            return;
        }
        if (result == null) {
            result = modeB(req, sink);
        }
        if (result != null) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", "chart_suggestion");
            e.putAll(result);
            sink.accept(e);
            app.completeAnalytics(analyticsId, result.get("explanation") == null ? null
                    : String.valueOf(result.get("explanation")), null, Json.write(result.get("params")), time.now());
        }
    }

    private List<Dataset> candidates(Request req) {
        List<Dataset> all = sync.datasets();
        if (req.datasetId() != null) {
            return all.stream().filter(d -> d.id() == req.datasetId()).toList();
        }
        List<Dataset> out = new ArrayList<>();
        try {
            for (SearchHit h : vectors.search(VectorStore.SEMANTIC_LAYER, embedder.embedQuery(req.question()), 5,
                    new VectorStore.VectorFilter(List.of(), List.of("Dataset"), List.of()), MAX_DATASET_DISTANCE)) {
                // the payload number comes back as Long (memory, Qdrant integer) or Double (JSON round trip)
                Object raw = h.meta("dataset_id");
                String id = raw instanceof Number num ? String.valueOf(num.longValue()) : String.valueOf(raw);
                all.stream().filter(d -> String.valueOf(d.id()).equals(id)).findFirst()
                        .ifPresent(d -> {
                            if (!out.contains(d)) {
                                out.add(d);
                            }
                        });
            }
        } catch (RuntimeException e) {
            // no embeddings yet: every allowed dataset is a candidate
        }
        return out.isEmpty() ? all : out;
    }

    // ------------------------------------------------------------------ mode A

    private Map<String, Object> modeA(Request req, List<Dataset> candidates, Consumer<Map<String, Object>> sink) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(systemPrompt()));
        messages.add(LlmMessage.user(userPrompt(req, candidates)));
        step(sink, "build_chart", "Building chart", "start", null);
        ChartSuggestion s = llm.tryStructured(ModelRole.REASONING, messages, ChartSuggestion.class).orElse(null);
        step(sink, "build_chart", "Building chart", "end", s == null ? "No suggestion" : s.explanation());
        // one loop for the first answer and every repair round: the same checks, the same events
        for (int round = 0; s != null; round++) {
            if (s.dataset_id() == null || s.dataset_id().isBlank()) {
                // the model says no single dataset answers the question: mode B
                return null;
            }
            String datasetId = s.dataset_id().strip();
            Dataset d = candidates.stream().filter(x -> String.valueOf(x.id()).equals(datasetId)).findFirst().orElse(null);
            List<String> errors = new ArrayList<>();
            ChartValidator.Result r = null;
            if (d == null) {
                errors.add("dataset_id " + s.dataset_id() + " is not one of the candidates");
            } else if (s.viz_type() == null || s.viz_type().isBlank()) {
                errors.add("viz_type is required");
            } else {
                Map<String, Object> params = null;
                try {
                    params = Json.readMap(s.params_json());
                } catch (RuntimeException e) {
                    errors.add("params_json is not a JSON object: " + e.getMessage());
                }
                if (params != null) {
                    step(sink, "validate", "Validating", "start", null);
                    r = validator.validate(d, s.viz_type().strip(), params, true);
                    errors.addAll(r.errors());
                    step(sink, "validate", "Validating", "end", errors.isEmpty() ? "valid" : String.join("; ", errors));
                }
            }
            if (errors.isEmpty() && r != null) {
                return answer("semantic", d, s, r);
            }
            if (round >= MAX_REPAIR_ROUNDS) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("type", "error");
                e.put("message", "The chart could not be validated: " + String.join("; ", errors));
                e.put("node", "validate");
                sink.accept(e);
                return FAILED;
            }
            // repair round with the error messages
            messages.add(LlmMessage.assistant(Json.write(s)));
            messages.add(LlmMessage.user("The suggestion is invalid:\n- " + String.join("\n- ", errors)
                    + "\nFix only what the errors require and answer again. If a time range cannot be expressed, use"
                    + " 'No filter' and add an assumption."));
            step(sink, "repair", "Repairing chart", "start", null);
            s = llm.tryStructured(ModelRole.REASONING, messages, ChartSuggestion.class).orElse(null);
            step(sink, "repair", "Repairing chart", "end", s == null ? "No suggestion" : null);
        }
        return null;
    }

    private Map<String, Object> answer(String mode, Dataset d, ChartSuggestion s, ChartValidator.Result r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode);
        out.put("dataset_id", d.id());
        out.put("viz_type", s.viz_type().strip());
        out.put("params", r.params());
        out.put("slice_name", s.slice_name() == null ? "" : s.slice_name().substring(0, Math.min(250, s.slice_name().length())));
        out.put("description", s.description());
        out.put("explanation", s.explanation());
        List<String> assumptions = new ArrayList<>(s.assumptions() == null ? List.of() : s.assumptions());
        assumptions.addAll(r.assumptions());
        out.put("assumptions", assumptions);
        out.put("alternatives", s.alternatives() == null ? List.of() : s.alternatives().stream().limit(2).toList());
        out.put("query_context", r.queryContext());
        out.put("warnings", r.warnings());
        return out;
    }

    String systemPrompt() {
        LocalDate today = LocalDate.ofInstant(time.now(), ZoneOffset.UTC);
        return """
                You turn a natural-language question into ONE chart of a dashboard. You never write SQL: you choose a \
                registered dataset and fill the chart params of a dashboard viz type.

                Today's date is %s.

                Choosing the viz type (explicit wishes of the user win):
                - development over time -> line (x_axis = the temporal column, time_grain_sqla from daily/weekly/monthly:
                  P1D, P1W, P1M, P3M, P1Y)
                - comparison of categories -> bar (orientation horizontal for many or long categories)
                - share of a whole with an additive metric and at most about 8 categories -> pie (groupby, metric)
                - a single key figure -> big_number (metric; show_trend true and time_grain_sqla for
                  development/trend/compared to the previous period)
                - two measures per entity -> scatter (entity, x_metric, y_metric)
                - two dimensions and one measure -> heatmap (x_axis, y_axis, metric) or pivot_table (groupbyRows,
                  groupbyColumns, metrics) when a table/cross table is asked for
                - detail list / raw data -> table with query_mode raw and all_columns; an aggregated top-N list ->
                  table with query_mode aggregate, groupby, metrics, orderby [[metric, false]] and row_limit

                Metrics: saved metrics (by metric_name) have priority. Ad-hoc metrics only as
                {"expressionType": "SIMPLE", "aggregate": COUNT|COUNT_DISTINCT|SUM|AVG|MIN|MAX,
                 "column": {"column_name": ...} or null for COUNT(*), "label": ...}; SUM/AVG only on numeric columns.

                Time ranges use this grammar: 'No filter' (no time reference), 'Last week', 'Last month',
                'Last quarter', 'Last year', 'Last 30 days', '<YYYY>-01-01 : now' (year to date),
                '2026-06-01 : 2026-07-01' (a month, half-open). granularity_sqla is the temporal column.

                Filters go into adhoc_filters as {"col": ..., "op": ..., "val": ...} with op one of ==, !=, >, <, >=,
                <=, LIKE, NOT LIKE, ILIKE, NOT ILIKE, IS NULL, IS NOT NULL, IN, NOT IN, IS TRUE, IS FALSE. Boolean
                columns use IS TRUE / IS FALSE. Use the spelling of stored values; translate names (e.g. Deutschland ->
                Germany) and state that as an assumption.

                Only use columns and metrics of the chosen dataset. Return dataset_id null when no single candidate
                dataset can answer the question (e.g. it needs data of two datasets). Name and texts in the language
                of the question.
                """.formatted(today);
    }

    String userPrompt(Request req, List<Dataset> candidates) {
        StringBuilder sb = new StringBuilder("Candidate datasets:\n");
        for (Dataset d : candidates) {
            sb.append("\n[dataset_id ").append(d.id()).append("] ").append(d.tableName());
            if (d.description() != null) {
                sb.append(" — ").append(d.description());
            }
            sb.append("\n  main temporal column: ").append(d.mainDttmCol()).append("\n  columns:\n");
            for (DatasetColumn c : d.columns()) {
                String role = c.isDttm() ? "temporal" : switch (c.typeGeneric()) {
                    case 0 -> "numeric";
                    case 3 -> "boolean";
                    default -> "text";
                };
                sb.append("    - ").append(c.columnName()).append(" (").append(role)
                        .append(c.groupby() ? ", groupable" : "").append(c.filterable() ? ", filterable" : "").append(')');
                if (c.verboseName() != null) {
                    sb.append(" \"").append(c.verboseName()).append('"');
                }
                sb.append('\n');
            }
            sb.append("  saved metrics:\n");
            for (DatasetMetric m : d.metrics()) {
                sb.append("    - ").append(m.metricName());
                if (m.verboseName() != null) {
                    sb.append(" \"").append(m.verboseName()).append('"');
                }
                if (props.nlChart().exposeMetricSql()) {
                    sb.append(" = ").append(m.expression());
                }
                sb.append('\n');
            }
        }
        if (req.vizType() != null) {
            sb.append("\nThe user wants viz_type ").append(req.vizType()).append(".\n");
        }
        if (req.currentParams() != null && !req.currentParams().isEmpty()) {
            sb.append("\nCurrent chart (refine it as a diff: change only what the question asks; on a type change keep"
                    + " metrics, groupby, adhoc_filters and the time settings):\n").append(Json.write(req.currentParams()))
                    .append('\n');
        }
        sb.append("\nQuestion: ").append(req.question());
        return sb.toString();
    }

    // ------------------------------------------------------------------ mode B

    @SuppressWarnings("unchecked")
    private Map<String, Object> modeB(Request req, Consumer<Map<String, Object>> sink) {
        Map<String, Object> answer = agent.run(new AgentRunner.Request(req.question(), null, false, null, List.of(),
                connections.answeringConnectors()), e -> {
                    if ("step".equals(e.get("type")) || "error".equals(e.get("type"))) {
                        sink.accept(e);
                    }
                }, null);
        if (answer == null) {
            return null;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) answer.get("sql_response_from_db");
        String sql = (String) answer.get("sql_code");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", "sql");
        out.put("dataset_id", null);
        out.put("explanation", answer.get("response"));
        out.put("assumptions", List.of());
        out.put("alternatives", List.of());
        out.put("query_context", null);
        out.put("sql", sql);
        List<String> warnings = new ArrayList<>(List.of("not savable as a dashboard chart"));
        if (rows == null || rows.isEmpty()) {
            out.put("viz_type", null);
            out.put("params", Map.of());
            out.put("results", List.of());
            warnings.add("the query returned no rows");
            out.put("warnings", warnings);
            return out;
        }
        List<String> cols = (List<String>) answer.getOrDefault("sql_columns", List.of());
        List<Integer> types = (List<Integer>) answer.get("sql_column_types");
        Map<String, Object> chart = advisor.recommend(req.question(), sql, cols, types, rows).orElse(null);
        Map<String, Object> params = chart == null ? new LinkedHashMap<>(Map.of("query_mode", "raw", "all_columns", cols))
                : new LinkedHashMap<>((Map<String, Object>) chart.get("params"));
        String vizType = chart == null ? "table" : (String) chart.get("viz_type");
        params.put("viz_type", vizType);
        out.put("viz_type", vizType);
        out.put("params", params);
        out.put("slice_name", params.getOrDefault("slice_name", req.question()));
        out.put("results", List.of(SqlResults.toQueryResult(cols, types, rows, sql, time.now())));
        out.put("warnings", warnings);
        return out;
    }
}
