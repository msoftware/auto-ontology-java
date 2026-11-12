// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.semantic;

import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.llm.Embedder;
import de.jentsch.autoontology.store.SemanticRepository;
import de.jentsch.autoontology.vector.VectorStore;
import de.jentsch.autoontology.vector.VectorStore.VectorRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Embedding of the semantic layer (semantic/embed.py). Text formats are part of the retrieval
 * contract: {@code "Term: <name>. <description>"} and
 * {@code "ColumnAttribute: <attr> of Term <term> (<synonyms>). <description> Sample values: ..."}.
 */
public class SemanticEmbedder {
    static final int MAX_SAMPLE_LEN = 30;
    static final int MAX_JSON_SAMPLE_LEN = 60;

    private final Embedder embedder;
    private final VectorStore vectors;
    private final SemanticRepository semantic;

    public SemanticEmbedder(Embedder embedder, VectorStore vectors, SemanticRepository semantic) {
        this.embedder = embedder;
        this.vectors = vectors;
        this.semantic = semantic;
    }

    /** A prepared semantic row. */
    public record Row(String id, String label, String name, String text, Map<String, Object> meta) {}

    public static String termText(String name, String description) {
        return ("Term: " + name + ". " + (description == null ? "" : description)).strip();
    }

    public static String attributeText(String attr, String term, List<String> synonyms, String description,
            Object sampleValues, String dataType) {
        String syn = synonyms == null || synonyms.isEmpty() ? "" : " (" + String.join(", ", synonyms) + ")";
        return ("ColumnAttribute: " + attr + " of Term " + term + syn + ". " + (description == null ? "" : description)
                + samples(sampleValues, dataType)).strip();
    }

    static String samples(Object raw, String dataType) {
        int max = dataType != null && dataType.toLowerCase(Locale.ROOT).contains("json") ? MAX_JSON_SAMPLE_LEN
                : MAX_SAMPLE_LEN;
        List<Object> values = raw instanceof String s ? Json.readList(s) : raw instanceof List<?> l ? List.copyOf(l)
                : List.of();
        List<String> kept = new ArrayList<>();
        for (Object v : values) {
            String str = String.valueOf(v);
            if (v != null && str.length() <= max) {
                kept.add(str);
            }
        }
        return kept.isEmpty() ? "" : " Sample values: " + String.join(", ", kept) + ".";
    }

    /** Rows of one Term and its ColumnAttributes (attribute maps from attributesOfDatabase). */
    public List<Row> termRows(String databaseName, Map<String, Object> term, List<Map<String, Object>> attrs,
            boolean includeTerm) {
        List<Row> rows = new ArrayList<>();
        String termName = (String) term.get("name");
        @SuppressWarnings("unchecked")
        List<String> synonyms = term.get("synonyms") instanceof List<?> l ? (List<String>) l : List.of();
        if (includeTerm && termName != null) {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("label", "Term");
            meta.put("name", termName);
            meta.put("database_name", databaseName);
            meta.put("source_path", "semantic:term:" + termName);
            meta.put("id", term.get("id"));
            rows.add(new Row((String) term.get("id"), "Term", termName,
                    termText(termName, (String) term.get("description")), meta));
        }
        for (Map<String, Object> a : attrs) {
            String owner = a.get("term_name") != null ? (String) a.get("term_name") : termName;
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("label", "ColumnAttribute");
            meta.put("name", a.get("name"));
            meta.put("term_name", owner);
            meta.put("term_id", a.get("term_id"));
            meta.put("source_column", a.get("source_column"));
            meta.put("column_id", a.get("column_id"));
            meta.put("table_id", a.get("table_id"));
            meta.put("table_name", a.get("table_name"));
            meta.put("is_unique", a.get("is_unique"));
            meta.put("schema_name", a.get("schema_name"));
            meta.put("data_type", a.get("datatype"));
            meta.put("database_name", databaseName);
            meta.put("source_path", "semantic:attr:" + a.get("id"));
            meta.put("id", a.get("id"));
            rows.add(new Row((String) a.get("id"), "ColumnAttribute", (String) a.get("name"),
                    attributeText((String) a.get("name"), owner, synonyms, (String) a.get("description"),
                            a.get("sample_values"), (String) a.get("datatype")), meta));
        }
        return rows;
    }

    public static Row sqlAttributeRow(String databaseName, Map<String, Object> a) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("label", "SqlAttribute");
        meta.put("name", a.get("name"));
        meta.put("term_id", a.get("term_id"));
        meta.put("term_name", a.get("term_name"));
        meta.put("expression", a.get("expression"));
        meta.put("database_name", databaseName);
        meta.put("id", a.get("id"));
        String text = ("SqlAttribute: " + a.get("name") + " of Term " + a.get("term_name") + ". "
                + (a.get("description") == null ? "" : a.get("description")) + " Expression: " + a.get("expression"))
                .strip();
        return new Row((String) a.get("id"), "SqlAttribute", (String) a.get("name"), text, meta);
    }

    public static Row customAnalysisRow(String databaseName, Map<String, Object> a) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("label", "CustomAnalysis");
        meta.put("name", a.get("name"));
        meta.put("sql", a.get("sql"));
        meta.put("database_name", databaseName);
        meta.put("id", a.get("id"));
        String text = ("CustomAnalysis: " + a.get("name") + ". " + (a.get("description") == null ? "" : a.get("description")))
                .strip();
        return new Row((String) a.get("id"), "CustomAnalysis", (String) a.get("name"), text, meta);
    }

    public void write(String databaseName, List<Row> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<float[]> vecs = embedder.embedPassages(rows.stream().map(Row::text).toList());
        List<VectorRecord> recs = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            recs.add(new VectorRecord(r.id(), r.text(), r.label(), r.name(), databaseName, r.meta(), vecs.get(i)));
        }
        vectors.upsert(VectorStore.SEMANTIC_LAYER, recs);
    }

    /** Re-embeds a Term with its attributes (refresh_term_embeddings). */
    public void refreshTerm(String termId) {
        Map<String, Object> term = semantic.termById(termId).orElse(null);
        if (term == null) {
            return;
        }
        Map<String, List<Map<String, Object>>> byDb = new LinkedHashMap<>();
        for (Map<String, Object> t : semantic.tablesOfTerm(termId)) {
            String db = (String) t.get("database_name");
            if (byDb.containsKey(db)) {
                // a Term with several tables in one database: its attributes are already collected
                continue;
            }
            List<Map<String, Object>> attrs = byDb.computeIfAbsent(db, k -> new ArrayList<>());
            for (Map<String, Object> a : semantic.attributesOfDatabase((String) t.get("database_id"))) {
                if (termId.equals(a.get("term_id"))) {
                    attrs.add(a);
                }
            }
        }
        byDb.forEach((db, attrs) -> write(db, termRows(db, term, attrs, true)));
    }
}
