// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.ingestion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Embedding texts of the data layer. They are part of the retrieval contract: the format follows
 * {@code build_table_text} / {@code build_column_text} in utils/embedding_rows.py exactly.
 */
public final class EmbeddingTexts {
    private EmbeddingTexts() {}

    /** Minimal column view for the table text. */
    public record ColumnText(String name, String dataType, String description) {}

    public static String tableText(String databaseName, String schemaName, String tableName, String tableDescription,
            List<ColumnText> columns) {
        StringBuilder text = new StringBuilder()
                .append("database_name: ").append(databaseName)
                .append(", schema_name: ").append(schemaName)
                .append(", table_name: ").append(tableName);
        if (tableDescription != null && !tableDescription.isBlank()) {
            text.append(", table_description: ").append(tableDescription.strip());
        }
        List<String> pieces = new ArrayList<>();
        for (ColumnText c : columns) {
            StringBuilder piece = new StringBuilder("{name: ").append(c.name()).append(", data_type: ")
                    .append(c.dataType() == null ? "" : c.dataType().strip());
            if (c.description() != null && !c.description().isBlank()) {
                piece.append(", description: ").append(c.description().strip());
            }
            pieces.add(piece.append('}').toString());
        }
        text.append(", columns: ").append(String.join(",", pieces));
        return text.toString().strip();
    }

    public static String columnText(String databaseName, String schemaName, String tableName, String columnName,
            String dataType, String columnDescription, List<?> sampleValues) {
        StringBuilder text = new StringBuilder()
                .append("database_name: ").append(databaseName)
                .append(", schema_name: ").append(schemaName)
                .append(", table_name: ").append(tableName)
                .append(", column_name: ").append(columnName)
                .append(", data_type: ").append(dataType == null ? "" : dataType.strip());
        if (columnDescription != null && !columnDescription.isBlank()) {
            text.append(", column_description: ").append(columnDescription.strip());
        }
        if (sampleValues != null && !sampleValues.isEmpty()) {
            text.append(", sample_values: ").append(sampleValues.stream().limit(5).map(String::valueOf)
                    .collect(Collectors.joining(", ")));
        }
        return text.toString().strip();
    }

    /** Metadata of a data-layer row (build_embed_row). */
    public static Map<String, Object> metadata(String id, String label, String name, String schemaName,
            String databaseName) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", id);
        fields.put("label", label);
        fields.put("name", name);
        fields.put("source_path", "auto_ontology:" + (id == null ? "unknown" : id));
        fields.put("schema_name", schemaName);
        fields.put("database_name", databaseName);
        return fields;
    }
}
