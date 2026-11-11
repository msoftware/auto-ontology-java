// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.agent;

import de.jentsch.autoontology.vector.VectorStore;
import de.jentsch.autoontology.vector.VectorStore.SearchHit;
import de.jentsch.autoontology.vector.VectorStore.VectorFilter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Candidate retrieval from the semantic layer (data_access/candidates.py, semantic_search.py):
 * per entity a vector search; duplicates keep the best distance.
 */
public final class Retrieval {
    static final List<String> SEMANTIC_LABELS = List.of("ColumnAttribute", "SqlAttribute", "CustomAnalysis", "Term");

    private Retrieval() {}

    /** A candidate with the entity it was found for. */
    public record Candidate(String id, String label, String name, String databaseName, double distance, String entity,
            Map<String, Object> meta, String text) {
        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("label", label);
            m.put("name", name);
            m.put("database_name", databaseName);
            m.put("distance", distance);
            m.put("entity", entity);
            m.put("text", text);
            m.put("metadata", meta);
            return m;
        }
    }

    public static List<Candidate> search(AgentServices s, String collection, String query, String entity, int k,
            String databaseName, List<String> labels, Double maxDistance) {
        float[] v = s.embedder().embedQuery(query);
        List<Candidate> out = new ArrayList<>();
        for (SearchHit h : s.vectors().search(collection, v, k, new VectorFilter(
                databaseName == null ? List.of() : List.of(databaseName), labels, List.of()), maxDistance)) {
            out.add(new Candidate(h.id(), h.record().label(), h.record().name(), h.record().databaseName(), h.distance(),
                    entity, h.record().metadata(), h.record().text()));
        }
        return out;
    }

    /** Keeps the best distance per id, ordered by distance. */
    public static List<Candidate> dedupe(List<Candidate> candidates) {
        Map<String, Candidate> best = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            Candidate prev = best.get(c.id());
            if (prev == null || c.distance() < prev.distance()) {
                best.put(c.id(), c);
            }
        }
        List<Candidate> out = new ArrayList<>(best.values());
        out.sort(Comparator.comparingDouble(Candidate::distance));
        return out;
    }

    /** Database with the highest entity coverage, ties broken by the best distance. */
    public static String chooseDatabase(List<Candidate> candidates, List<String> entities) {
        Map<String, java.util.Set<String>> covered = new LinkedHashMap<>();
        Map<String, Double> bestDistance = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            if (c.databaseName() == null) {
                continue;
            }
            covered.computeIfAbsent(c.databaseName(), k -> new java.util.HashSet<>()).add(c.entity());
            bestDistance.merge(c.databaseName(), c.distance(), Math::min);
        }
        return covered.keySet().stream().max(Comparator.<String>comparingInt(db -> covered.get(db).size())
                .thenComparing(db -> -bestDistance.get(db))).orElse(null);
    }

    static String collection(String label) {
        return "Table".equals(label) || "Column".equals(label) ? VectorStore.DATA_LAYER : VectorStore.SEMANTIC_LAYER;
    }
}
