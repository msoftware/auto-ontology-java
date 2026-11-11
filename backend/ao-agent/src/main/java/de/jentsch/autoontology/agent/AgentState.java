// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.agent;

import de.jentsch.autoontology.connectors.SourceDatabase;
import de.jentsch.autoontology.llm.LlmMessage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed agent state (retrieval/text_to_sql/state.py, FR-AGT-02). Run-scoped values live in
 * {@link #pathState} under the key names of the original.
 */
public class AgentState {
    public String initialQuestion;
    /** Standalone question after follow-up resolution. */
    public String standaloneQuestion;
    public String evidence = "";
    public List<Map<String, Object>> sqlExamples = new ArrayList<>();
    public List<Map<String, Object>> valueAnchors = new ArrayList<>();
    public boolean calculationOnly;
    public boolean shortenAnswer;
    public boolean validateSqlValues;
    public boolean skipEvidenceRefinement;
    public Boolean predictionOverride;
    public List<LlmMessage> messages = new ArrayList<>();
    public String decision;
    public List<SourceDatabase> connectors = new ArrayList<>();
    public String domainRules = "";
    public List<Map<String, String>> glossary = new ArrayList<>();
    public int fullPipelineAttempt;
    public String conversationHistory = "";
    public Map<String, Object> pathState = new LinkedHashMap<>();
    /** Immutable start state for the full-pipeline retry (restore_from). */
    Map<String, Object> restoreFrom;

    public String question() {
        return standaloneQuestion != null && !standaloneQuestion.isBlank() ? standaloneQuestion : initialQuestion;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) pathState.get(key);
    }

    public void put(String key, Object value) {
        pathState.put(key, value);
    }

    public int failedAttempts() {
        Object v = pathState.get("failed_attempts");
        return v instanceof List<?> l ? l.size() : 0;
    }

    @SuppressWarnings("unchecked")
    public void addFailedAttempt(String sql, String error) {
        List<Map<String, Object>> list = (List<Map<String, Object>>) pathState.computeIfAbsent("failed_attempts",
                k -> new ArrayList<>());
        Map<String, Object> a = new HashMap<>();
        a.put("sql", sql);
        a.put("error", error);
        list.add(a);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> thoughtsLog() {
        return (List<Map<String, Object>>) pathState.computeIfAbsent("thoughts_log", k -> new ArrayList<>());
    }

    /** {@code BaseAgent.record_thought}. */
    public void recordThought(String node, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("node", node);
        t.put("thought", text.strip());
        thoughtsLog().add(t);
    }

    /** Snapshot for the full-pipeline restart (_FULL_RETRY_PATH_KEYS_TO_CLEAR are dropped). */
    void snapshot() {
        Map<String, Object> m = new HashMap<>();
        m.put("evidence", evidence);
        m.put("messages", new ArrayList<>(messages));
        m.put("sql_examples", new ArrayList<>(sqlExamples));
        m.put("value_anchors", new ArrayList<>(valueAnchors));
        m.put("glossary", new ArrayList<>(glossary));
        Map<String, Object> ps = new LinkedHashMap<>();
        if (pathState.containsKey("target_db")) {
            ps.put("target_db", pathState.get("target_db"));
        }
        m.put("path_state", ps);
        restoreFrom = m;
    }

    @SuppressWarnings("unchecked")
    void restore() {
        evidence = (String) restoreFrom.get("evidence");
        messages = new ArrayList<>((List<LlmMessage>) restoreFrom.get("messages"));
        sqlExamples = new ArrayList<>((List<Map<String, Object>>) restoreFrom.get("sql_examples"));
        valueAnchors = new ArrayList<>((List<Map<String, Object>>) restoreFrom.get("value_anchors"));
        glossary = new ArrayList<>((List<Map<String, String>>) restoreFrom.get("glossary"));
        Map<String, Object> keep = new LinkedHashMap<>((Map<String, Object>) restoreFrom.get("path_state"));
        Object lastIntentRejected = pathState.get("last_intent_rejected_sql");
        Object thoughts = pathState.get("thoughts_log");
        pathState = keep;
        if (lastIntentRejected != null) {
            pathState.put("last_intent_rejected_sql", lastIntentRejected);
        }
        if (thoughts != null) {
            pathState.put("thoughts_log", thoughts);
        }
    }
}
