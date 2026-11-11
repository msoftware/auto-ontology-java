// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.connectors;

import java.util.List;
import java.util.Map;

/**
 * Source database connector, modelled on {@code SQLDatabase} in auto_ontology/connectors/base.py
 * (FR-CON-01). Metadata methods return rows with the column names of the original contract.
 */
public interface SourceDatabase extends AutoCloseable {

    /** Executes one statement with timeout (seconds) and a row cap; cancellable via the bound token. */
    TabularResult execute(String sql, List<Object> params, int timeoutSeconds, int maxRows);

    /** {@code table_schema, table_name, table_type} with canonical types. */
    List<Map<String, Object>> getTables();

    /** {@code table_schema, table_name, column_name, data_type, is_nullable (Boolean), ordinal_position}. */
    List<Map<String, Object>> getColumns();

    /** {@code table_schema, table_name, view_definition}. */
    List<Map<String, Object>> getViews();

    /** {@code table_schema, table_name, column_name, ordinal_position}. */
    List<Map<String, Object>> getPks();

    /** {@code table_schema, table_name, column_name, referenced_schema, referenced_table, referenced_column}. */
    List<Map<String, Object>> getFks();

    /** {@code end_time, query_text} of the last {@code hours} hours; empty without a history. */
    List<Map<String, Object>> getQueries(int hours);

    default List<Map<String, Object>> getQueries() {
        return getQueries(24);
    }

    /** {@code table_schema, table_name, column_name, comment}; column_name null for table comments. */
    List<Map<String, Object>> getColumnComments();

    /** {@code postgres}, {@code oracle}, {@code tsql}, {@code h2}. */
    String dialect();

    String databaseName();

    /** Correctly quoted qualified name. */
    String qualify(String schema, String table);

    /** Quotes one identifier when needed. */
    String quote(String identifier);

    /** Dialect-correct sample statement (Oracle and T-SQL have no LIMIT, FR-CON-02). */
    String sampleQuery(String schema, String table, int n);

    /** Dialect-correct row cap appended to/wrapped around a SELECT (FR-AGT-04). */
    String limit(String selectSql, int n);

    /** Schemas visible to the connection (connection test, FR-CON-06). */
    List<String> listSchemas();

    @Override
    void close();
}
