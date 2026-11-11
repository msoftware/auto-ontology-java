// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.vector;

import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.store.StoreJdbc;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force cosine store for the Docker-free build and the demo (FR-VEC-04). Optionally persisted
 * into the store table {@code vector_record} and loaded at start (FR-DEMO-01).
 */
public class InMemoryVectorStore implements VectorStore {
    private final String prefix;
    private final int dimensions;
    private final StoreJdbc persistence;
    private final Map<String, Map<String, VectorRecord>> collections = new ConcurrentHashMap<>();

    public InMemoryVectorStore(String prefix, int dimensions, StoreJdbc persistence) {
        this.prefix = prefix == null ? "" : prefix;
        this.dimensions = dimensions;
        this.persistence = persistence;
    }

    private String name(String collection) {
        return prefix + collection;
    }

    private Map<String, VectorRecord> col(String collection) {
        return collections.computeIfAbsent(name(collection), k -> new ConcurrentHashMap<>());
    }

    @Override
    public void ensureCollections() {
        col(DATA_LAYER);
        col(SEMANTIC_LAYER);
        if (persistence != null) {
            for (Map<String, Object> row : persistence.list("SELECT * FROM vector_record")) {
                String c = (String) row.get("collection_name");
                float[] v = decode((String) row.get("vector"));
                if (v.length != dimensions) {
                    throw new IllegalStateException("Stored vector of " + c + " has dimension " + v.length
                            + " but ao.vector.dimensions is " + dimensions + "; reset the vector store");
                }
                VectorRecord r = new VectorRecord((String) row.get("id"), (String) row.get("text_content"),
                        (String) row.get("label"), null, (String) row.get("database_name"),
                        Json.readMap((String) row.get("metadata")), v);
                Map<String, Object> meta = r.metadata();
                collections.computeIfAbsent(c, k -> new ConcurrentHashMap<>()).put(r.id(),
                        new VectorRecord(r.id(), r.text(), r.label(), (String) meta.get("name"), r.databaseName(),
                                meta, v));
            }
        }
    }

    @Override
    public synchronized void upsert(String collection, List<VectorRecord> records) {
        Map<String, VectorRecord> c = col(collection);
        for (VectorRecord r : records) {
            if (r.vector().length != dimensions) {
                throw new IllegalStateException("Embedding dimension " + r.vector().length
                        + " does not match ao.vector.dimensions=" + dimensions);
            }
            c.put(r.id(), r);
            if (persistence != null) {
                Map<String, Object> p = new HashMap<>();
                p.put("c", name(collection));
                p.put("id", r.id());
                persistence.update("DELETE FROM vector_record WHERE collection_name = :c AND id = :id", p);
                p.put("db", r.databaseName());
                p.put("label", r.label());
                p.put("text", r.text());
                p.put("meta", Json.write(r.metadata()));
                p.put("vec", encode(r.vector()));
                persistence.update("INSERT INTO vector_record (collection_name, id, database_name, label, text_content,"
                        + " metadata, vector) VALUES (:c, :id, :db, :label, :text, :meta, :vec)", p);
            }
        }
    }

    @Override
    public List<SearchHit> search(String collection, float[] vector, int k, VectorFilter filter, Double maxDistance) {
        VectorFilter f = filter == null ? VectorFilter.none() : filter;
        List<SearchHit> hits = new ArrayList<>();
        for (VectorRecord r : col(collection).values()) {
            if (!f.matches(r)) {
                continue;
            }
            double d = 1.0 - cosine(vector, r.vector());
            if (maxDistance == null || d <= maxDistance) {
                hits.add(new SearchHit(r, d));
            }
        }
        hits.sort(Comparator.comparingDouble(SearchHit::distance).thenComparing(SearchHit::id));
        return hits.size() > k ? new ArrayList<>(hits.subList(0, k)) : hits;
    }

    @Override
    public synchronized void deleteByIds(String collection, Collection<String> ids) {
        Map<String, VectorRecord> c = col(collection);
        ids.forEach(c::remove);
        if (persistence != null && !ids.isEmpty()) {
            persistence.update("DELETE FROM vector_record WHERE collection_name = :c AND id IN (:ids)",
                    Map.of("c", name(collection), "ids", ids));
        }
    }

    @Override
    public synchronized void deleteByFilter(String collection, String databaseName, String label) {
        Map<String, VectorRecord> c = col(collection);
        c.values().removeIf(r -> (databaseName == null || databaseName.equals(r.databaseName()))
                && (label == null || label.equals(r.label())));
        if (persistence != null) {
            Map<String, Object> p = new HashMap<>();
            p.put("c", name(collection));
            StringBuilder sql = new StringBuilder("DELETE FROM vector_record WHERE collection_name = :c");
            if (databaseName != null) {
                sql.append(" AND database_name = :db");
                p.put("db", databaseName);
            }
            if (label != null) {
                sql.append(" AND label = :label");
                p.put("label", label);
            }
            persistence.update(sql.toString(), p);
        }
    }

    @Override
    public long count(String collection) {
        return col(collection).size();
    }

    @Override
    public List<String> ids(String collection, String databaseName) {
        return col(collection).values().stream()
                .filter(r -> databaseName == null || databaseName.equals(r.databaseName()))
                .map(VectorRecord::id)
                .sorted()
                .toList();
    }

    @Override
    public Map<String, Object> health() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("store", "memory");
        h.put("ok", true);
        h.put(DATA_LAYER, count(DATA_LAYER));
        h.put(SEMANTIC_LAYER, count(SEMANTIC_LAYER));
        return h;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String encode(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 10);
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.toString();
    }

    private static float[] decode(String s) {
        String[] parts = s.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i]);
        }
        return v;
    }
}
