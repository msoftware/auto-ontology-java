// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.agent;

import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.store.SemanticRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Join paths (dal/attributes.py::find_join_path / find_table_bridge, FR-AGT-10). Semantic FKs
 * (with {@code use_in_join_paths}) and declared FKs are projected to undirected table edges that
 * keep the exact join columns; BFS runs over tables. Two columns that reference the same
 * attribute are never joined to each other: every edge ends at the attribute's own table.
 */
public class JoinPathFinder {
    /** One hop with its join columns. */
    public record Hop(String fromTableId, String fromTable, String fromColumn, String toTableId, String toTable,
            String toColumn, String fromSchema, String toSchema) {
        public String condition() {
            return qualified(fromSchema, fromTable) + "." + fromColumn + " = " + qualified(toSchema, toTable) + "."
                    + toColumn;
        }

        Hop reversed() {
            return new Hop(toTableId, toTable, toColumn, fromTableId, fromTable, fromColumn, toSchema, fromSchema);
        }

        private static String qualified(String schema, String table) {
            return schema == null ? table : schema + "." + table;
        }
    }

    private final CatalogRepository catalog;
    private final SemanticRepository semantic;

    public JoinPathFinder(CatalogRepository catalog, SemanticRepository semantic) {
        this.catalog = catalog;
        this.semantic = semantic;
    }

    /** Undirected adjacency of a database. */
    Map<String, List<Hop>> graph(String databaseId) {
        Map<String, List<Hop>> adj = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (Map<String, Object> e : semantic.semanticFkEdges(databaseId)) {
            if (!Boolean.TRUE.equals(e.get("use_in_join_paths"))) {
                continue;
            }
            add(adj, seen, new Hop((String) e.get("source_table_id"), (String) e.get("source_table"),
                    (String) e.get("source_column"), (String) e.get("target_table_id"), (String) e.get("target_table"),
                    (String) e.get("target_column"), (String) e.get("source_schema"), (String) e.get("target_schema")));
        }
        for (Map<String, Object> f : catalog.foreignKeys(databaseId)) {
            add(adj, seen, new Hop((String) f.get("source_table_id"), (String) f.get("source_table"),
                    (String) f.get("source_column"), (String) f.get("target_table_id"), (String) f.get("target_table"),
                    (String) f.get("target_column"), (String) f.get("source_schema"), (String) f.get("target_schema")));
        }
        return adj;
    }

    private static void add(Map<String, List<Hop>> adj, Set<String> seen, Hop h) {
        if (h.fromTableId().equals(h.toTableId())) {
            return;
        }
        String key = h.fromTableId() + "." + h.fromColumn() + "|" + h.toTableId() + "." + h.toColumn();
        if (!seen.add(key)) {
            return;
        }
        adj.computeIfAbsent(h.fromTableId(), k -> new ArrayList<>()).add(h);
        adj.computeIfAbsent(h.toTableId(), k -> new ArrayList<>()).add(h.reversed());
    }

    /** Shortest hop list between two tables, or null when unreachable (find_table_bridge). */
    public List<Hop> bridge(String databaseId, String fromTableId, String toTableId) {
        return bfs(graph(databaseId), fromTableId, toTableId);
    }

    static List<Hop> bfs(Map<String, List<Hop>> adj, String from, String to) {
        if (from.equals(to)) {
            return List.of();
        }
        Map<String, Hop> prev = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(from);
        Set<String> visited = new HashSet<>(Set.of(from));
        while (!queue.isEmpty()) {
            String t = queue.poll();
            for (Hop h : adj.getOrDefault(t, List.of())) {
                if (visited.add(h.toTableId())) {
                    prev.put(h.toTableId(), h);
                    if (h.toTableId().equals(to)) {
                        List<Hop> path = new ArrayList<>();
                        String cur = to;
                        while (!cur.equals(from)) {
                            Hop p = prev.get(cur);
                            path.add(0, p);
                            cur = p.fromTableId();
                        }
                        return path;
                    }
                    queue.add(h.toTableId());
                }
            }
        }
        return null;
    }

    /**
     * Joins connecting all given tables: shortest paths from the first table to every other one,
     * merged. Intermediate (bridge/hub) tables are part of the result.
     */
    public Result connect(String databaseId, List<String> tableIds) {
        Map<String, List<Hop>> adj = graph(databaseId);
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(tableIds));
        LinkedHashMap<String, Hop> hops = new LinkedHashMap<>();
        Set<String> tables = new LinkedHashSet<>(ids);
        Set<String> unreachable = new LinkedHashSet<>();
        if (ids.size() > 1) {
            Set<String> connected = new LinkedHashSet<>(List.of(ids.get(0)));
            for (String target : ids.subList(1, ids.size())) {
                List<Hop> best = null;
                for (String src : connected) {
                    List<Hop> p = bfs(adj, src, target);
                    if (p != null && (best == null || p.size() < best.size())) {
                        best = p;
                    }
                }
                if (best == null) {
                    unreachable.add(target);
                    continue;
                }
                for (Hop h : best) {
                    hops.putIfAbsent(h.condition(), h);
                    connected.add(h.fromTableId());
                    connected.add(h.toTableId());
                    tables.add(h.fromTableId());
                    tables.add(h.toTableId());
                }
            }
        }
        return new Result(new ArrayList<>(hops.values()), new ArrayList<>(tables), new ArrayList<>(unreachable));
    }

    /** Hops, all involved tables (including bridges) and tables without a path. */
    public record Result(List<Hop> hops, List<String> tableIds, List<String> unreachable) {
        public String describe() {
            if (hops.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("SUGGESTED JOIN PATHS (verified column-level join conditions):\n");
            for (Hop h : hops) {
                sb.append("- ").append(h.condition()).append('\n');
            }
            return sb.toString();
        }
    }
}
