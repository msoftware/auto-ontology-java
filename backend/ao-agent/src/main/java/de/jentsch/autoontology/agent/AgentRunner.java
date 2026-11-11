// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.agent;

import de.jentsch.autoontology.agent.AgentModels.FollowUpResolutionModel;
import de.jentsch.autoontology.common.CancellationToken;
import de.jentsch.autoontology.common.Ids;
import de.jentsch.autoontology.connectors.SourceDatabase;
import de.jentsch.autoontology.llm.LlmMessage;
import de.jentsch.autoontology.llm.ModelRole;
import de.jentsch.autoontology.llm.Prompts;
import de.jentsch.autoontology.store.AppRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Builds and runs the text-to-SQL graph (text_to_sql_graph.py, main.py::stream_agent_response,
 * FR-AGT-01..08).
 */
public class AgentRunner {
    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
    static final int MAX_FULL_PIPELINE_ATTEMPTS = 2;

    private final AgentServices s;
    private final Nodes nodes;
    private final AppRepository app;
    private final AgentGraph graph;

    public AgentRunner(AgentServices services, AppRepository app) {
        this.s = services;
        this.nodes = new Nodes(services);
        this.app = app;
        this.graph = buildGraph();
    }

    /** A request to the agent. */
    public record Request(String question, String evidence, Boolean prediction, String targetDb,
            List<Map<String, String>> history, List<SourceDatabase> connectors) {}

    private AgentGraph buildGraph() {
        int maxAttempts = s.props().agent().maxReconstructionAttempts();
        AgentGraph g = new AgentGraph(s.props().agent().recursionLimit());
        g.node("question_intent", nodes::questionIntent)
                .node("question_extraction", nodes::questionExtraction)
                .node("retrieve_candidates", nodes::retrieveCandidates)
                .node("information_agent", nodes::informationAgent)
                .node("prepare_candidates", nodes::prepareCandidates)
                .node("construct_sql_from_candidates", nodes::constructSql)
                .node("validate_sql_query", nodes::validateSql)
                .node("execute_sql_query", nodes::executeSql)
                .node("check_empty_like_result", nodes::checkEmptyLikeResult)
                .node("check_value_repair", nodes::checkValueRepair)
                .node("reconstruct_sql", nodes::reconstructSql)
                .node("format_and_respond", nodes::formatAndRespond)
                .node("unconstructable_sql_response", nodes::unconstructable);
        g.edge("question_intent", "question_extraction");
        g.edge("question_extraction", "retrieve_candidates");
        g.route("retrieve_candidates", st -> Nodes.isInformation(st) ? "information_agent" : "prepare_candidates");
        g.edge("information_agent", AgentGraph.END);
        g.edge("prepare_candidates", "construct_sql_from_candidates");
        g.route("construct_sql_from_candidates", st -> st.get("sql_code") == null ? "unconstructable_sql_response"
                : "validate_sql_query");
        g.route("validate_sql_query", st -> st.get("error") == null ? "execute_sql_query" : retryOr(st, maxAttempts));
        g.route("execute_sql_query", st -> st.get("error") == null ? "check_empty_like_result" : retryOr(st, maxAttempts));
        // soft checks lead to reconstruct_sql but respect the same attempt limit
        g.route("check_empty_like_result", st -> Boolean.TRUE.equals(st.get("empty_like_flagged"))
                ? retryOr(st, maxAttempts) : "check_value_repair");
        g.route("check_value_repair", st -> switch (String.valueOf(st.<Object>get("value_repair"))) {
            case "repaired" -> "validate_sql_query";
            case "regenerate" -> st.failedAttempts() < maxAttempts ? "reconstruct_sql" : "format_and_respond";
            default -> "format_and_respond";
        });
        g.route("reconstruct_sql", st -> st.get("sql_code") == null ? "unconstructable_sql_response" : "validate_sql_query");
        g.edge("format_and_respond", AgentGraph.END);
        g.edge("unconstructable_sql_response", AgentGraph.END);
        return g;
    }

    private static String retryOr(AgentState st, int max) {
        if (st.failedAttempts() >= max) {
            st.put("unconstructable_explanation", "The SQL could not be repaired after " + max + " attempts. Last error: "
                    + st.get("error"));
            return "unconstructable_sql_response";
        }
        return "reconstruct_sql";
    }

    /** Runs the agent; events go to {@code sink}. Returns the final answer (or null after an error). */
    public Map<String, Object> run(Request req, AgentEvents.Sink sink, CancellationToken cancel) {
        MDC.put("ao.run", Ids.newId().substring(0, 8));
        try {
            AgentState st = initialState(req);
            Map<String, Object> answer = null;
            for (int attempt = 1; attempt <= MAX_FULL_PIPELINE_ATTEMPTS; attempt++) {
                st.fullPipelineAttempt = attempt;
                // _entry_router: a resumed run would jump to reconstruct_sql
                String entry = st.get("resume") != null ? "reconstruct_sql" : "question_intent";
                graph.run(entry, st, sink, cancel);
                answer = st.get("final_response");
                boolean unconstructable = Boolean.TRUE.equals(st.get("unconstructable"));
                if (!unconstructable || attempt == MAX_FULL_PIPELINE_ATTEMPTS) {
                    break;
                }
                log.info("Run ended unconstructable without SQL; restarting the whole pipeline once");
                st.restore();
            }
            if (Boolean.TRUE.equals(st.get("unconstructable")) && st.get("last_intent_rejected_sql") != null) {
                answer = deterministicFallback(st, sink, cancel);
            }
            Map<String, Object> out = new LinkedHashMap<>(answer == null ? Map.of() : answer);
            out.put("thoughts", st.thoughtsLog().stream().map(t -> {
                Map<String, Object> m = new LinkedHashMap<>(t);
                m.put("label", NodeLabels.label((String) t.get("node")));
                return m;
            }).toList());
            out.put("question_type", st.get("question_type"));
            out.put("target_db", st.get("target_database"));
            out.put("standalone_question", st.question());
            out.put("sql_column_types", st.get("sql_column_types"));
            sink.emit(AgentEvents.result(out));
            return out;
        } catch (AgentGraph.NodeException e) {
            log.warn("Agent node {} failed: {}", e.node(), e.getMessage());
            sink.emit(AgentEvents.error(e.getMessage(), e.node(), e.getCause().getClass().getSimpleName(), null));
            return null;
        } catch (AgentGraph.RecursionLimitException e) {
            sink.emit(AgentEvents.error(e.getMessage(), null, "GraphRecursionError", null));
            return null;
        } finally {
            MDC.remove("ao.run");
        }
    }

    /** Last SQL that passed the static checks and was only rejected for intent (returned_deterministic_fallback). */
    private Map<String, Object> deterministicFallback(AgentState st, AgentEvents.Sink sink, CancellationToken cancel) {
        String sql = st.get("last_intent_rejected_sql");
        st.put("validated_sql", sql);
        sink.emit(AgentEvents.sql("execute_sql_query", sql));
        nodes.executeSql(st);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("response", "This answer uses the closest valid SQL found; its intent could not be fully confirmed.");
        r.put("sql_code", sql);
        r.put("sql_columns", st.pathState.getOrDefault("sql_columns", List.of()));
        r.put("custom_analyses_used", List.of());
        r.put("sql_response_from_db", st.get("error") == null ? st.get("sql_response_from_db") : null);
        r.put("returned_deterministic_fallback", true);
        return r;
    }

    AgentState initialState(Request req) {
        AgentState st = new AgentState();
        st.initialQuestion = req.question();
        st.evidence = req.evidence() == null ? "" : req.evidence();
        st.predictionOverride = req.prediction();
        st.validateSqlValues = s.props().agent().validateSqlValues();
        st.connectors = new ArrayList<>(req.connectors());
        if (st.connectors.isEmpty()) {
            throw new IllegalStateException("No database connectors are configured");
        }
        // FR-AGT-06: acronyms and custom prompts per request
        List<Map<String, Object>> acronyms = app.acronyms();
        st.glossary = acronyms.stream().map(a -> Map.of("name", String.valueOf(a.get("name")),
                "description", String.valueOf(a.getOrDefault("description", "")))).toList();
        st.domainRules = acronyms.stream().map(a -> "- " + a.get("name") + ": " + a.get("description"))
                .collect(Collectors.joining("\n"));
        String customPrompts = app.prompts().stream().map(p -> String.valueOf(p.get("content")))
                .collect(Collectors.joining("\n\n"));
        st.messages.add(LlmMessage.system(AgentPrompts.mainSystemPrompt(s.time().today(), customPrompts)
                + "\nAnswer in the language of the user's question."));
        // target_db: UUID or name; a single connector is selected automatically
        String target = req.targetDb();
        if (target != null && Ids.isUuid(target)) {
            target = s.catalog().databaseById(target).map(d -> (String) d.get("name")).orElse(target);
        }
        if (target == null && st.connectors.size() == 1) {
            target = st.connectors.get(0).databaseName();
        }
        if (target != null) {
            st.put("target_db", target);
        }
        if (req.history() != null && !req.history().isEmpty()) {
            st.conversationHistory = historyText(req.history());
            st.standaloneQuestion = resolveFollowUp(req.question(), st.conversationHistory);
        }
        st.snapshot();
        return st;
    }

    /** Last five completed rounds, at most 12,000 characters, question/answer/SQL only (FR-AGT-08). */
    static String historyText(List<Map<String, String>> history) {
        List<String> rounds = new ArrayList<>();
        int from = Math.max(0, history.size() - 5);
        for (Map<String, String> h : history.subList(from, history.size())) {
            StringBuilder r = new StringBuilder("User: ").append(h.getOrDefault("question", "")).append('\n');
            r.append("Assistant: ").append(h.getOrDefault("answer", "")).append('\n');
            if (h.get("sql") != null && !h.get("sql").isBlank()) {
                r.append("SQL: ").append(h.get("sql")).append('\n');
            }
            rounds.add(r.toString());
        }
        String text = String.join("\n", rounds);
        return text.length() > 12_000 ? text.substring(text.length() - 12_000) : text;
    }

    /** follow_up.py::resolve_follow_up; on LLM errors the original question is used. */
    String resolveFollowUp(String question, String history) {
        String prompt = Prompts.render("follow_up_resolution", Map.of("question", question, "conversation_history", history));
        return s.llm().tryStructured(ModelRole.NON_REASONING, List.of(LlmMessage.user(prompt)),
                FollowUpResolutionModel.class)
                .map(r -> r.is_follow_up() ? r.standalone_question().strip() : question)
                .orElse(question);
    }
}
