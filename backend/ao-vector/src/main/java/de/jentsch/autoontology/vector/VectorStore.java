// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.vector;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Vector store (FR-VEC-04). Distances are cosine distances (smaller is better) on every
 * implementation (FR-VEC-03).
 */
public interface VectorStore {
    /** Catalog tables and columns. */
    String DATA_LAYER = "data_objects_layer";

    /** Terms, ColumnAttributes, SqlAttributes, CustomAnalyses, Datasets. */
    String SEMANTIC_LAYER = "semantic_layer";

    /** Creates missing collections; fails when an existing one has another dimension (FR-VEC-02). */
    void ensureCollections();

    void upsert(String collection, List<VectorRecord> records);

    List<SearchHit> search(String collection, float[] vector, int k, VectorFilter filter, Double maxDistance);

    void deleteByIds(String collection, Collection<String> ids);

    /** Deletes by database and/or label; {@code null} means "any". */
    void deleteByFilter(String collection, String databaseName, String label);

    long count(String collection);

    /** Business IDs stored for a database (reconciliation, FR-VEC-06). */
    List<String> ids(String collection, String databaseName);

    /** Readiness: reachable and both collections present. */
    Map<String, Object> health();

    /** A stored point. */
    record VectorRecord(
            String id, String text, String label, String name, String databaseName, Map<String, Object> metadata,
            float[] vector) {}

    /** One result; {@code distance} is the cosine distance. */
    record SearchHit(VectorRecord record, double distance) {
        public String id() {
            return record.id();
        }

        public Object meta(String key) {
            return record.metadata() == null ? null : record.metadata().get(key);
        }
    }

    /** Filter on the payload indexes; empty lists mean "no restriction". */
    record VectorFilter(List<String> databaseNames, List<String> labels, List<String> ids) {
        public static VectorFilter none() {
            return new VectorFilter(List.of(), List.of(), List.of());
        }

        public static VectorFilter of(String databaseName, String... labels) {
            return new VectorFilter(databaseName == null ? List.of() : List.of(databaseName), List.of(labels), List.of());
        }

        public boolean matches(VectorRecord r) {
            return (databaseNames == null || databaseNames.isEmpty() || databaseNames.contains(r.databaseName()))
                    && (labels == null || labels.isEmpty() || labels.contains(r.label()))
                    && (ids == null || ids.isEmpty() || ids.contains(r.id()));
        }
    }
}
