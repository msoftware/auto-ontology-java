// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** Streaming events of {@code stream_agent_response} (FR-AGT-07, docs/conversation-api.md). */
public final class AgentEvents {
    private AgentEvents() {}

    /** Receives events of a run. */
    public interface Sink {
        void emit(Map<String, Object> event);
    }

    public static Map<String, Object> step(String node, String phase, String thought) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "step");
        e.put("node", node);
        e.put("label", NodeLabels.label(node));
        e.put("phase", phase);
        if (thought != null) {
            e.put("thought", thought);
        }
        return e;
    }

    public static Map<String, Object> sql(String node, String sql) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "sql");
        e.put("node", node);
        e.put("sql", sql);
        return e;
    }

    public static Map<String, Object> result(Map<String, Object> answer) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "result");
        e.put("answer", answer);
        return e;
    }

    public static Map<String, Object> charts(Object content, Object sqlResponse) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "charts");
        e.put("content", content);
        e.put("sql_response", sqlResponse);
        return e;
    }

    public static Map<String, Object> error(String message, String node, String errorType, Object partialAnswer) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "error");
        e.put("message", message);
        e.put("node", node);
        e.put("error_type", errorType);
        e.put("partial_answer", partialAnswer);
        return e;
    }
}
