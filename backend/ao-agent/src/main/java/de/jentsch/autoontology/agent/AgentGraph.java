// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.agent;

import de.jentsch.autoontology.common.CancellationToken;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Small graph engine (instead of LangGraph's StateGraph): nodes, (conditional) edges, a
 * recursion limit per run, step events with the thought the node itself just recorded.
 */
public class AgentGraph {
    private static final Logger log = LoggerFactory.getLogger(AgentGraph.class);
    public static final String END = "__end__";

    /** A node: reads and writes the state. */
    public interface Node {
        void run(AgentState state);
    }

    /** Raised when the recursion limit is hit (GraphRecursionError). */
    public static class RecursionLimitException extends RuntimeException {
        public RecursionLimitException(int limit) {
            super("Recursion limit of " + limit + " reached without hitting a stop condition");
        }
    }

    /** Raised by a node; carries the node name. */
    public static class NodeException extends RuntimeException {
        private final String node;

        public NodeException(String node, Throwable cause) {
            super(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), cause);
            this.node = node;
        }

        public String node() {
            return node;
        }
    }

    private final Map<String, Node> nodes = new HashMap<>();
    private final Map<String, Function<AgentState, String>> routes = new HashMap<>();
    private final int recursionLimit;

    public AgentGraph(int recursionLimit) {
        this.recursionLimit = recursionLimit;
    }

    public AgentGraph node(String name, Node node) {
        nodes.put(name, node);
        return this;
    }

    public AgentGraph edge(String from, String to) {
        routes.put(from, s -> to);
        return this;
    }

    public AgentGraph route(String from, Function<AgentState, String> router) {
        routes.put(from, router);
        return this;
    }

    public boolean has(String node) {
        return nodes.containsKey(node);
    }

    /** Runs from {@code entry} to END, emitting step and sql events. */
    public void run(String entry, AgentState state, AgentEvents.Sink sink, CancellationToken cancel) {
        String current = entry;
        int visits = 0;
        while (!END.equals(current)) {
            if (cancel != null) {
                cancel.check();
            }
            if (++visits > recursionLimit) {
                throw new RecursionLimitException(recursionLimit);
            }
            Node node = nodes.get(current);
            if (node == null) {
                throw new IllegalStateException("Unknown graph node " + current);
            }
            int thoughtsBefore = state.thoughtsLog().size();
            String sqlBefore = state.get("validated_sql");
            sink.emit(AgentEvents.step(current, "start", null));
            long start = System.nanoTime();
            MDC.put("ao.node", current);
            try {
                node.run(state);
            } catch (RuntimeException e) {
                throw new NodeException(current, e);
            } finally {
                MDC.remove("ao.node");
            }
            log.info("node {} finished in {} ms", current, (System.nanoTime() - start) / 1_000_000);
            List<Map<String, Object>> thoughts = state.thoughtsLog();
            String thought = null;
            // only a thought this very node just wrote
            for (int i = thoughts.size() - 1; i >= thoughtsBefore; i--) {
                if (current.equals(thoughts.get(i).get("node"))) {
                    thought = (String) thoughts.get(i).get("thought");
                    break;
                }
            }
            sink.emit(AgentEvents.step(current, "end", thought));
            String sqlAfter = state.get("validated_sql");
            if (sqlAfter != null && !sqlAfter.equals(sqlBefore)) {
                sink.emit(AgentEvents.sql(current, sqlAfter));
            }
            Function<AgentState, String> r = routes.get(current);
            current = r == null ? END : r.apply(state);
        }
    }
}
