// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.nlchart;

import de.jentsch.autoontology.common.AoProperties;
import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.CurrentUser;
import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.semantic.SemanticEmbedder;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.store.SemanticRepository;
import de.jentsch.autoontology.store.StoreJdbc;
import de.jentsch.autoontology.vector.VectorStore;
import de.jentsch.quasardashboard.metadata.DatasetRepository;
import de.jentsch.quasardashboard.model.Models.Dataset;
import de.jentsch.quasardashboard.model.Models.DatasetColumn;
import de.jentsch.quasardashboard.model.Models.DatasetMetric;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridge between ontology and dashboard datasets (FR-NLC-01..04): links each {@code qd_dataset}
 * to its catalog table ({@code ao_dataset_link}), stores column roles in
 * {@code ao_dataset_column_link}, adopts descriptions where nothing is certified, mirrors saved
 * metrics as SqlAttributes ({@code source = 'dataset'}) and embeds one {@code Dataset} record per
 * dataset in {@code semantic_layer}.
 */
public class DatasetSync {
    private static final Logger log = LoggerFactory.getLogger(DatasetSync.class);

    private final DatasetRepository datasets;
    private final StoreJdbc db;
    private final CatalogRepository catalog;
    private final SemanticRepository semantic;
    private final SemanticEmbedder embedder;
    private final VectorStore vectors;
    private final AoProperties props;
    private final CurrentUser user;
    private final AoTime time;
    private volatile Map<String, Object> lastStatus = Map.of();

    public DatasetSync(DatasetRepository datasets, StoreJdbc db, CatalogRepository catalog, SemanticRepository semantic,
            SemanticEmbedder embedder, VectorStore vectors, AoProperties props, CurrentUser user, AoTime time) {
        this.datasets = datasets;
        this.db = db;
        this.catalog = catalog;
        this.semantic = semantic;
        this.embedder = embedder;
        this.vectors = vectors;
        this.props = props;
        this.user = user;
        this.time = time;
    }

    public Map<String, Object> lastStatus() {
        return lastStatus;
    }

    /** All registered datasets, filtered by the optional allowlist (FR-NLC-04). */
    public List<Dataset> datasets() {
        List<Dataset> out = new ArrayList<>();
        for (Long id : db.column("SELECT id FROM qd_dataset ORDER BY id", Map.of(), Long.class)) {
            datasets.find(id).filter(this::allowed).ifPresent(out::add);
        }
        return out;
    }

    public boolean allowed(Dataset d) {
        List<String> allow = props.nlChart().datasetAllowlist();
        return allow == null || allow.isEmpty() || allow.contains(String.valueOf(d.id())) || allow.contains(d.tableName());
    }

    /** AO catalog database of a dashboard database (n:1 mapping, FR-NLC-01). */
    String aoDatabase(Dataset d) {
        Map<String, String> mapping = props.dashboard().databaseMapping();
        if (mapping != null && mapping.containsKey(d.database().databaseName())) {
            return mapping.get(d.database().databaseName());
        }
        List<Map<String, Object>> dbs = catalog.databases();
        if (dbs.stream().anyMatch(x -> d.database().databaseName().equals(x.get("name")))) {
            return d.database().databaseName();
        }
        return dbs.size() == 1 ? (String) dbs.get(0).get("name") : null;
    }

    public synchronized Map<String, Object> sync() {
        List<String> warnings = new ArrayList<>();
        int linked = 0;
        int metrics = 0;
        Instant now = time.now();
        for (Dataset d : datasets()) {
            String dbName = aoDatabase(d);
            Optional<Map<String, Object>> table = dbName == null ? Optional.empty() : findTable(dbName, d);
            String key = String.valueOf(d.id());
            db.update("DELETE FROM ao_dataset_column_link WHERE dataset_id = :d", Map.of("d", key));
            db.update("DELETE FROM ao_dataset_link WHERE dataset_id = :d", Map.of("d", key));
            if (table.isEmpty()) {
                warnings.add("Dataset " + d.tableName() + " (" + d.id() + ") has no matching catalog table");
                Map<String, Object> p = new HashMap<>();
                p.put("d", key);
                p.put("now", now);
                p.put("w", Json.write(List.of("no catalog table")));
                db.update("INSERT INTO ao_dataset_link (dataset_id, table_id, synced_at, warnings) VALUES (:d, NULL, :now, :w)", p);
                continue;
            }
            String tableId = (String) table.get().get("id");
            linked++;
            List<String> dsWarnings = new ArrayList<>();
            Map<String, Map<String, Object>> cols = new HashMap<>();
            catalog.columnsOfTable(tableId).forEach(c -> cols.put(String.valueOf(c.get("name")).toLowerCase(Locale.ROOT), c));
            // descriptions are adopted only where nothing is certified
            if (d.description() != null && !d.description().isBlank()
                    && !Boolean.TRUE.equals(table.get().get("description_certified"))) {
                catalog.patchNode("catalog_table", tableId, Map.of("description", d.description()));
            }
            for (DatasetColumn c : d.columns()) {
                Map<String, Object> col = cols.get(c.columnName().toLowerCase(Locale.ROOT));
                if (col == null) {
                    dsWarnings.add("column " + c.columnName() + " is not in the catalog");
                }
                Map<String, Object> p = new HashMap<>();
                p.put("d", key);
                p.put("n", c.columnName());
                p.put("c", col == null ? null : col.get("id"));
                p.put("t", c.typeGeneric());
                p.put("dt", c.isDttm());
                p.put("g", c.groupby());
                p.put("f", c.filterable());
                db.update("INSERT INTO ao_dataset_column_link (dataset_id, column_name, column_id, type_generic, is_dttm,"
                        + " groupby, filterable) VALUES (:d, :n, :c, :t, :dt, :g, :f)", p);
                String desc = c.description() != null && !c.description().isBlank() ? c.description() : c.verboseName();
                if (col != null && desc != null && !desc.isBlank() && !Boolean.TRUE.equals(col.get("description_certified"))
                        && (col.get("description") == null || String.valueOf(col.get("description")).isBlank())) {
                    catalog.patchNode("catalog_column", (String) col.get("id"), Map.of("description", desc));
                }
            }
            List<Map<String, Object>> terms = semantic.termsOfTable(tableId);
            if (terms.isEmpty()) {
                dsWarnings.add("table has no Term yet; metrics are not mirrored");
            } else {
                String termId = (String) terms.get(0).get("id");
                for (DatasetMetric m : d.metrics()) {
                    if (m.expression() == null || m.expression().isBlank()) {
                        dsWarnings.add("metric " + m.metricName() + " has no expression");
                        continue;
                    }
                    String name = m.verboseName() != null && !m.verboseName().isBlank() ? m.verboseName() : m.metricName();
                    String marker = " (dataset " + d.tableName() + ", metric " + m.metricName() + ")";
                    String desc = (m.description() == null || m.description().isBlank() ? name : m.description()) + marker;
                    Optional<Map<String, Object>> existing = semantic.sqlAttributeByName(name);
                    if (existing.isPresent() && !mirrorOf(existing.get(), marker, termId)) {
                        // sql_attribute.name is unique in the whole store, and "count" exists in nearly every
                        // dataset: without an own name the datasets would overwrite each other on every sync
                        name = name + " (" + d.tableName() + ")";
                        existing = semantic.sqlAttributeByName(name);
                    }
                    String attributeId = null;
                    if (existing.isEmpty()) {
                        attributeId = semantic.insertSqlAttribute(name, desc, m.expression(), "dataset", termId, null,
                                user.id(), now);
                    } else if (mirrorOf(existing.get(), marker, termId) && !Boolean.TRUE.equals(existing.get().get("certified"))
                            && !(m.expression().equals(existing.get().get("expression"))
                                    && desc.equals(existing.get().get("description")))) {
                        attributeId = (String) existing.get().get("id");
                        semantic.patchSqlAttribute(attributeId, Map.of("expression", m.expression(), "description", desc));
                    }
                    if (attributeId != null) {
                        // new or changed: the embedding follows; a failing endpoint must not stop the sync
                        try {
                            embedder.write(dbName, List.of(SemanticEmbedder.sqlAttributeRow(dbName,
                                    semantic.sqlAttributeById(attributeId).orElseThrow())));
                        } catch (RuntimeException e) {
                            dsWarnings.add("metric " + m.metricName() + " is not embedded: " + e.getMessage());
                        }
                    }
                    metrics++;
                }
            }
            Map<String, Object> p = new HashMap<>();
            p.put("d", key);
            p.put("t", tableId);
            p.put("now", now);
            p.put("w", Json.write(dsWarnings));
            db.update("INSERT INTO ao_dataset_link (dataset_id, table_id, synced_at, warnings) VALUES (:d, :t, :now, :w)", p);
            dsWarnings.forEach(w -> warnings.add(d.tableName() + ": " + w));
            embed(dbName, d, terms);
        }
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("synced_at", now.toString());
        status.put("datasets_linked", linked);
        status.put("metrics", metrics);
        status.put("warnings", warnings);
        lastStatus = status;
        log.info("Dataset sync: {} linked, {} metrics, {} warning(s)", linked, metrics, warnings.size());
        return status;
    }

    /** Whether a SqlAttribute is the mirror of this very dataset metric (and not a namesake). */
    private static boolean mirrorOf(Map<String, Object> attribute, String marker, String termId) {
        return "dataset".equals(attribute.get("source"))
                && (String.valueOf(attribute.get("description")).endsWith(marker) || termId.equals(attribute.get("term_id")));
    }

    private Optional<Map<String, Object>> findTable(String dbName, Dataset d) {
        return catalog.databaseByName(dbName).flatMap(x -> catalog.tablesOfDatabase((String) x.get("id")).stream()
                .filter(t -> String.valueOf(t.get("name")).equalsIgnoreCase(d.tableName())
                        && (d.schema() == null || String.valueOf(t.get("schema_name")).equalsIgnoreCase(d.schema())))
                .findFirst());
    }

    /** FR-NLC-03: dataset name, description, terms with synonyms, columns (label, type, role), metrics. */
    static String datasetText(Dataset d, List<Map<String, Object>> terms) {
        StringBuilder sb = new StringBuilder("Dataset: ").append(d.tableName()).append('.');
        if (d.description() != null) {
            sb.append(' ').append(d.description());
        }
        for (Map<String, Object> t : terms) {
            sb.append(" Term ").append(t.get("name"));
            @SuppressWarnings("unchecked")
            List<String> syn = (List<String>) t.getOrDefault("synonyms", List.of());
            if (!syn.isEmpty()) {
                sb.append(" (").append(String.join(", ", syn)).append(')');
            }
            sb.append('.');
        }
        sb.append(" Columns: ");
        List<String> cols = new ArrayList<>();
        for (DatasetColumn c : d.columns()) {
            String role = c.isDttm() ? "time" : c.typeGeneric() == 0 ? "measure" : "dimension";
            cols.add((c.verboseName() == null ? c.columnName() : c.verboseName()) + " [" + c.columnName() + ", " + role + "]");
        }
        sb.append(String.join(", ", cols)).append(". Metrics: ");
        sb.append(String.join(", ", d.metrics().stream().map(m -> (m.verboseName() == null ? m.metricName()
                : m.verboseName()) + (m.description() == null ? "" : " - " + m.description())).toList())).append('.');
        return sb.toString();
    }

    private void embed(String dbName, Dataset d, List<Map<String, Object>> terms) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("label", "Dataset");
        meta.put("name", d.tableName());
        meta.put("dataset_id", d.id());
        meta.put("database_name", dbName);
        meta.put("id", "dataset:" + d.id());
        try {
            embedder.write(dbName, List.of(new SemanticEmbedder.Row("dataset:" + d.id(), "Dataset", d.tableName(),
                    datasetText(d, terms), meta)));
        } catch (RuntimeException e) {
            log.warn("Embedding of dataset {} failed: {}", d.tableName(), e.getMessage());
        }
    }

    /** Coverage of a dataset by the ontology for GET /datasets. */
    public Map<String, Object> coverage(Dataset d) {
        Map<String, Object> link = db.one("SELECT table_id, synced_at, warnings FROM ao_dataset_link WHERE dataset_id = :d",
                Map.of("d", String.valueOf(d.id()))).orElse(Map.of());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.id());
        m.put("table_name", d.tableName());
        m.put("schema", d.schema());
        m.put("database", d.database().databaseName());
        m.put("description", d.description());
        m.put("table_id", link.get("table_id"));
        m.put("synced_at", link.get("synced_at"));
        m.put("warnings", Json.readList((String) link.get("warnings")));
        List<Map<String, Object>> terms = link.get("table_id") == null ? List.of()
                : semantic.termsOfTable((String) link.get("table_id"));
        m.put("terms", terms.stream().map(t -> t.get("name")).toList());
        m.put("metrics", d.metrics().size());
        m.put("vector_indexed", vectors.ids(VectorStore.SEMANTIC_LAYER, aoDatabase(d)).contains("dataset:" + d.id()));
        return m;
    }
}
