// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.ingestion;

import de.jentsch.autoontology.ingestion.CatalogExtractor.Catalog;
import de.jentsch.autoontology.ingestion.CatalogExtractor.Column;
import de.jentsch.autoontology.ingestion.CatalogExtractor.ForeignKey;
import de.jentsch.autoontology.ingestion.CatalogExtractor.Table;
import de.jentsch.autoontology.store.CatalogRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Normalize and diff against the stored catalog (catalog/diff.py, catalog/write.py): new and
 * changed objects are upserted under stable IDs, removed ones deleted, FKs cleaned via
 * {@code last_seen}.
 */
public class CatalogWriter {
    private final CatalogRepository catalog;
    private final TransactionTemplate tx;

    public CatalogWriter(CatalogRepository catalog, TransactionTemplate tx) {
        this.catalog = catalog;
        this.tx = tx;
    }

    /** Result of a write: ids by key and the diff counters. */
    public record WriteResult(String databaseId, Map<String, String> tableIds, Map<String, String> columnIds,
            int added, int removed, List<String> removedTableIds, List<String> removedColumnIds) {}

    public WriteResult write(Catalog cat, String dialect, Map<String, Object> connection, Instant now) {
        return tx.execute(status -> doWrite(cat, dialect, connection, now));
    }

    private WriteResult doWrite(Catalog cat, String dialect, Map<String, Object> connection, Instant now) {
        String dbId = catalog.upsertDatabase(cat.databaseName(), dialect, connection, now);
        Map<String, String> existingTables = new HashMap<>();
        for (Map<String, Object> t : catalog.tablesOfDatabase(dbId)) {
            existingTables.put(t.get("schema_name") + "." + t.get("name"), (String) t.get("id"));
        }
        Map<String, String> existingColumns = new HashMap<>();
        for (Map<String, Object> c : catalog.columnsOfDatabase(dbId)) {
            existingColumns.put(c.get("schema_name") + "." + c.get("table_name") + "." + c.get("name"), (String) c.get("id"));
        }

        Map<String, String> schemaIds = new HashMap<>();
        Map<String, String> tableIds = new HashMap<>();
        Map<String, String> columnIds = new HashMap<>();
        int added = 0;
        for (Table t : cat.tables()) {
            String schemaId = schemaIds.computeIfAbsent(t.schema(), s -> catalog.upsertSchema(dbId, s));
            if (!existingTables.containsKey(t.key())) {
                added++;
            }
            String tableId = catalog.upsertTable(schemaId, t.name(), t.type(), t.pk(), t.comment());
            tableIds.put(t.key(), tableId);
            for (Column c : t.columns()) {
                String key = t.key() + "." + c.name();
                columnIds.put(key, catalog.upsertColumn(tableId, c.name(), c.dataType(), c.nullable(), c.ordinal(),
                        c.comment()));
            }
        }
        List<String> removedTables = new ArrayList<>();
        for (Map.Entry<String, String> e : existingTables.entrySet()) {
            if (!tableIds.containsKey(e.getKey())) {
                catalog.deleteTable(e.getValue());
                removedTables.add(e.getValue());
            }
        }
        List<String> removedColumns = new ArrayList<>();
        Set<String> removedTableSet = new HashSet<>(removedTables);
        for (Map.Entry<String, String> e : existingColumns.entrySet()) {
            if (!columnIds.containsKey(e.getKey())) {
                String tableKey = e.getKey().substring(0, e.getKey().lastIndexOf('.'));
                if (!removedTableSet.contains(existingTables.get(tableKey))) {
                    catalog.deleteColumn(e.getValue());
                }
                removedColumns.add(e.getValue());
            }
        }
        for (Map<String, Object> s : catalog.schemas(dbId)) {
            if (!schemaIds.containsKey((String) s.get("name"))) {
                catalog.deleteSchema((String) s.get("id"));
            }
        }
        for (ForeignKey fk : cat.fks()) {
            String src = columnIds.get(fk.schema() + "." + fk.table() + "." + fk.column());
            String dst = columnIds.get(fk.refSchema() + "." + fk.refTable() + "." + fk.refColumn());
            if (src != null && dst != null) {
                catalog.upsertForeignKey(src, dst, now);
            }
        }
        catalog.deleteStaleForeignKeys(dbId, now);
        catalog.markPulled(dbId, now);
        return new WriteResult(dbId, tableIds, columnIds, added, removedTables.size(), removedTables, removedColumns);
    }
}
