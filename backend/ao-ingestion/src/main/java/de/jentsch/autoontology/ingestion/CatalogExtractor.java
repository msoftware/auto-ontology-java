// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.ingestion;

import de.jentsch.autoontology.connectors.SourceDatabase;
import de.jentsch.autoontology.connectors.TableFilter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalog extraction (catalog/extract.py): tables, columns, views, PKs, FKs, comments and query
 * history, filtered centrally (FR-CON-03), with the completeness check of
 * {@code _validate_relation_column_coverage}.
 */
public final class CatalogExtractor {
    private CatalogExtractor() {}

    /** One column of the extracted catalog. */
    public record Column(String name, String dataType, boolean nullable, int ordinal, String comment) {}

    /** One table or view. */
    public record Table(String schema, String name, String type, List<String> pk, String comment, List<Column> columns) {
        public String key() {
            return schema + "." + name;
        }
    }

    /** A declared foreign key. */
    public record ForeignKey(String schema, String table, String column, String refSchema, String refTable,
            String refColumn) {}

    /** The extracted catalog of one database. */
    public record Catalog(String databaseName, String dialect, List<Table> tables, List<ForeignKey> fks,
            List<Map<String, Object>> queries) {}

    /** Raised when a listed relation has no columns (IncompleteCatalogExtractionError). */
    public static class IncompleteCatalogExtractionException extends RuntimeException {
        public IncompleteCatalogExtractionException(String message) {
            super(message);
        }
    }

    public static Catalog extract(SourceDatabase db, TableFilter filter) {
        Map<String, List<Column>> columns = new LinkedHashMap<>();
        for (Map<String, Object> c : db.getColumns()) {
            String key = c.get("table_schema") + "." + c.get("table_name");
            columns.computeIfAbsent(key, k -> new ArrayList<>()).add(new Column(
                    (String) c.get("column_name"), (String) c.get("data_type"), Boolean.TRUE.equals(c.get("is_nullable")),
                    ((Number) c.get("ordinal_position")).intValue(), null));
        }
        Map<String, String> tableComments = new LinkedHashMap<>();
        Map<String, String> columnComments = new LinkedHashMap<>();
        for (Map<String, Object> c : db.getColumnComments()) {
            String key = c.get("table_schema") + "." + c.get("table_name");
            if (c.get("column_name") == null) {
                tableComments.put(key, (String) c.get("comment"));
            } else {
                columnComments.put(key + "." + c.get("column_name"), (String) c.get("comment"));
            }
        }
        Map<String, List<String>> pks = new LinkedHashMap<>();
        db.getPks().stream()
                .sorted(Comparator.comparingInt(p -> ((Number) p.get("ordinal_position")).intValue()))
                .forEach(p -> pks.computeIfAbsent(p.get("table_schema") + "." + p.get("table_name"), k -> new ArrayList<>())
                        .add((String) p.get("column_name")));
        // views are extracted and filtered but not evaluated further, like the original
        db.getViews();

        List<Table> tables = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> t : db.getTables()) {
            String schema = (String) t.get("table_schema");
            String name = (String) t.get("table_name");
            if (!filter.allows(schema, name)) {
                continue;
            }
            String key = schema + "." + name;
            List<Column> cols = columns.getOrDefault(key, List.of()).stream()
                    .sorted(Comparator.comparingInt(Column::ordinal))
                    .map(c -> new Column(c.name(), c.dataType(), c.nullable(), c.ordinal(),
                            columnComments.get(key + "." + c.name())))
                    .toList();
            if (cols.isEmpty()) {
                missing.add(key);
                continue;
            }
            tables.add(new Table(schema, name, (String) t.get("table_type"), pks.getOrDefault(key, List.of()),
                    tableComments.get(key), cols));
        }
        if (!missing.isEmpty()) {
            throw new IncompleteCatalogExtractionException("Catalog extraction of " + db.databaseName()
                    + " is incomplete: no columns for " + missing);
        }
        List<ForeignKey> fks = new ArrayList<>();
        for (Map<String, Object> f : db.getFks()) {
            String schema = (String) f.get("table_schema");
            String table = (String) f.get("table_name");
            String refSchema = (String) f.get("referenced_schema");
            String refTable = (String) f.get("referenced_table");
            if (filter.allows(schema, table) && filter.allows(refSchema, refTable)) {
                fks.add(new ForeignKey(schema, table, (String) f.get("column_name"), refSchema, refTable,
                        (String) f.get("referenced_column")));
            }
        }
        // getQueries is called without argument: the 24 h default (create_dataframe)
        return new Catalog(db.databaseName(), db.dialect(), tables, fks, db.getQueries());
    }
}
