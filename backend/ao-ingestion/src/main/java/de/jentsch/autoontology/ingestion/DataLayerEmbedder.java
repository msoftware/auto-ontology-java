// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.ingestion;

import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.llm.Embedder;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.vector.VectorStore;
import de.jentsch.autoontology.vector.VectorStore.VectorRecord;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes tables and columns into {@code data_objects_layer} (FR-ING-01 steps 6 and 7). The old
 * points of a database are replaced only before the first successfully embedded chunk; if the
 * embedding fails earlier, the old points stay (comment in {@code run_ingest}).
 */
public class DataLayerEmbedder {
    private static final Logger log = LoggerFactory.getLogger(DataLayerEmbedder.class);
    private static final int CHUNK = 128;

    private final CatalogRepository catalog;
    private final Embedder embedder;
    private final VectorStore vectors;

    public DataLayerEmbedder(CatalogRepository catalog, Embedder embedder, VectorStore vectors) {
        this.catalog = catalog;
        this.embedder = embedder;
        this.vectors = vectors;
    }

    /** Text and metadata rows of a database (CatalogEmbeddingRowsOp). */
    public List<Row> rows(String databaseId, String databaseName) {
        List<Row> rows = new ArrayList<>();
        Map<String, List<Map<String, Object>>> byTable = new LinkedHashMap<>();
        for (Map<String, Object> c : catalog.columnsOfDatabase(databaseId)) {
            byTable.computeIfAbsent((String) c.get("table_id"), k -> new ArrayList<>()).add(c);
        }
        for (Map<String, Object> t : catalog.tablesOfDatabase(databaseId)) {
            List<Map<String, Object>> cols = byTable.getOrDefault((String) t.get("id"), List.of());
            String schema = (String) t.get("schema_name");
            String table = (String) t.get("name");
            List<EmbeddingTexts.ColumnText> ct = cols.stream().map(c -> new EmbeddingTexts.ColumnText(
                    (String) c.get("name"), (String) c.get("data_type"), (String) c.get("description"))).toList();
            rows.add(new Row((String) t.get("id"), "Table", table, schema,
                    EmbeddingTexts.tableText(databaseName, schema, table, (String) t.get("description"), ct)));
            for (Map<String, Object> c : cols) {
                rows.add(columnRow(databaseName, c));
            }
        }
        return rows;
    }

    private static Row columnRow(String databaseName, Map<String, Object> c) {
        List<Object> samples = Json.readList((String) c.get("sample_values"));
        String schema = (String) c.get("schema_name");
        return new Row((String) c.get("id"), "Column", (String) c.get("name"), schema,
                EmbeddingTexts.columnText(databaseName, schema, (String) c.get("table_name"), (String) c.get("name"),
                        (String) c.get("data_type"), (String) c.get("description"),
                        samples.size() > 5 ? samples.subList(0, 5) : samples));
    }

    /** One embedding row. */
    public record Row(String id, String label, String name, String schemaName, String text) {}

    /** Re-embeds a whole database; returns the number of written points. */
    public int embedDatabase(String databaseId, String databaseName) {
        List<Row> rows = rows(databaseId, databaseName);
        boolean replaced = false;
        int written = 0;
        for (int i = 0; i < rows.size(); i += CHUNK) {
            List<Row> chunk = rows.subList(i, Math.min(rows.size(), i + CHUNK));
            List<float[]> vecs = embedder.embedPassages(chunk.stream().map(Row::text).toList());
            if (!replaced) {
                vectors.deleteByFilter(VectorStore.DATA_LAYER, databaseName, null);
                replaced = true;
            }
            vectors.upsert(VectorStore.DATA_LAYER, records(databaseName, chunk, vecs));
            written += chunk.size();
        }
        if (rows.isEmpty()) {
            vectors.deleteByFilter(VectorStore.DATA_LAYER, databaseName, null);
        }
        log.info("Embedded {} data-layer rows of {}", written, databaseName);
        return written;
    }

    /** Re-embeds single columns (after edited sample values, FR-GOV-01). */
    public void embedColumns(Collection<String> columnIds) {
        List<Map<String, Object>> cols = catalog.columnsByIds(columnIds);
        Map<String, List<Row>> byDb = new LinkedHashMap<>();
        for (Map<String, Object> c : cols) {
            byDb.computeIfAbsent((String) c.get("database_name"), k -> new ArrayList<>())
                    .add(columnRow((String) c.get("database_name"), c));
        }
        byDb.forEach((db, rows) -> vectors.upsert(VectorStore.DATA_LAYER,
                records(db, rows, embedder.embedPassages(rows.stream().map(Row::text).toList()))));
    }

    private static List<VectorRecord> records(String databaseName, List<Row> rows, List<float[]> vecs) {
        List<VectorRecord> out = new ArrayList<>();
        for (int j = 0; j < rows.size(); j++) {
            Row r = rows.get(j);
            Map<String, Object> meta = EmbeddingTexts.metadata(r.id(), r.label(), r.name(), r.schemaName(), databaseName);
            out.add(new VectorRecord(r.id(), r.text(), r.label(), r.name(), databaseName, meta, vecs.get(j)));
        }
        return out;
    }
}
