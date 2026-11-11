// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.store;

import de.jentsch.autoontology.common.Ids;
import de.jentsch.autoontology.common.Json;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Catalog tables (Database, Schema, Table, Column, FKs, observed joins). Derived from
 * auto_ontology/dal/datasources.py and catalog/write.py of NVIDIA Auto-Ontology.
 */
public class CatalogRepository {
    /** Columns of a table row joined with its schema and database names. */
    public static final String TABLE_SELECT = """
            SELECT t.id, t.name, t.description, t.description_certified, t.owner_id, t.pk, t.table_type,
                   t.is_junction_table, t.schema_id, s.name AS schema_name, d.id AS database_id,
                   d.name AS database_name, d.dialect
            FROM catalog_table t
            JOIN catalog_schema s ON s.id = t.schema_id
            JOIN catalog_database d ON d.id = s.database_id
            """;

    /** Columns of a column row joined with table, schema and database names. */
    public static final String COLUMN_SELECT = """
            SELECT c.id, c.name, c.description, c.description_certified, c.data_type, c.sample_values, c.format,
                   c.is_unique, c.is_nullable, c.ordinal_position, c.pii_processed, c.table_id,
                   t.name AS table_name, s.name AS schema_name, s.id AS schema_id, d.name AS database_name,
                   d.id AS database_id
            FROM catalog_column c
            JOIN catalog_table t ON t.id = c.table_id
            JOIN catalog_schema s ON s.id = t.schema_id
            JOIN catalog_database d ON d.id = s.database_id
            """;

    private final StoreJdbc db;

    public CatalogRepository(StoreJdbc db) {
        this.db = db;
    }

    // ---------------------------------------------------------------- databases

    public List<Map<String, Object>> databases() {
        return db.list("SELECT id, name, dialect, description, description_certified, owner_id, created, pulled"
                + " FROM catalog_database ORDER BY name");
    }

    public Optional<Map<String, Object>> databaseByName(String name) {
        return db.one("SELECT * FROM catalog_database WHERE name = :name", Map.of("name", name));
    }

    public Optional<Map<String, Object>> databaseById(String id) {
        return db.one("SELECT * FROM catalog_database WHERE id = :id", Map.of("id", id));
    }

    /** Creates or updates a database row; returns its id. */
    public String upsertDatabase(String name, String dialect, Map<String, Object> connection, Instant now) {
        Optional<Map<String, Object>> existing = databaseByName(name);
        String conn = connection == null ? null : Json.write(connection);
        if (existing.isPresent()) {
            String id = (String) existing.get().get("id");
            Map<String, Object> p = new HashMap<>();
            p.put("id", id);
            p.put("dialect", dialect);
            p.put("conn", conn);
            db.update("UPDATE catalog_database SET dialect = :dialect,"
                    + " connection = COALESCE(:conn, connection) WHERE id = :id", p);
            return id;
        }
        String id = Ids.newId();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("name", name);
        p.put("dialect", dialect);
        p.put("conn", conn);
        p.put("now", now);
        db.update("INSERT INTO catalog_database (id, name, dialect, connection, created)"
                + " VALUES (:id, :name, :dialect, :conn, :now)", p);
        return id;
    }

    public void setConnection(String databaseId, Map<String, Object> connection) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", databaseId);
        p.put("conn", connection == null ? null : Json.write(connection));
        db.update("UPDATE catalog_database SET connection = :conn WHERE id = :id", p);
    }

    public void markPulled(String databaseId, Instant now) {
        db.update("UPDATE catalog_database SET pulled = :now WHERE id = :id", Map.of("id", databaseId, "now", now));
    }

    public int deleteDatabase(String id) {
        return db.update("DELETE FROM catalog_database WHERE id = :id", Map.of("id", id));
    }

    // ---------------------------------------------------------------- schemas

    public List<Map<String, Object>> schemas(String databaseId) {
        return db.list("SELECT id, name, description, description_certified, owner_id, database_id"
                + " FROM catalog_schema WHERE database_id = :id ORDER BY name", Map.of("id", databaseId));
    }

    public Optional<Map<String, Object>> schemaById(String id) {
        return db.one("SELECT * FROM catalog_schema WHERE id = :id", Map.of("id", id));
    }

    public String upsertSchema(String databaseId, String name) {
        Optional<Map<String, Object>> row = db.one(
                "SELECT id FROM catalog_schema WHERE database_id = :db AND name = :name",
                Map.of("db", databaseId, "name", name));
        if (row.isPresent()) {
            return (String) row.get().get("id");
        }
        String id = Ids.newId();
        db.update("INSERT INTO catalog_schema (id, database_id, name) VALUES (:id, :db, :name)",
                Map.of("id", id, "db", databaseId, "name", name));
        return id;
    }

    public int deleteSchema(String id) {
        return db.update("DELETE FROM catalog_schema WHERE id = :id", Map.of("id", id));
    }

    // ---------------------------------------------------------------- tables

    public List<Map<String, Object>> tablesOfSchema(String schemaId) {
        return withPk(db.list(TABLE_SELECT + " WHERE t.schema_id = :id ORDER BY t.name", Map.of("id", schemaId)));
    }

    public List<Map<String, Object>> tablesOfDatabase(String databaseId) {
        return withPk(db.list(TABLE_SELECT + " WHERE d.id = :id ORDER BY s.name, t.name", Map.of("id", databaseId)));
    }

    public List<Map<String, Object>> allTables() {
        return withPk(db.list(TABLE_SELECT + " ORDER BY d.name, s.name, t.name"));
    }

    public Optional<Map<String, Object>> tableById(String id) {
        return db.one(TABLE_SELECT + " WHERE t.id = :id", Map.of("id", id)).map(CatalogRepository::pk);
    }

    public List<Map<String, Object>> tablesByIds(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return withPk(db.list(TABLE_SELECT + " WHERE t.id IN (:ids)", Map.of("ids", ids)));
    }

    public String upsertTable(String schemaId, String name, String tableType, List<String> pk, String comment) {
        Optional<Map<String, Object>> row = db.one(
                "SELECT id FROM catalog_table WHERE schema_id = :s AND name = :name",
                Map.of("s", schemaId, "name", name));
        Map<String, Object> p = new HashMap<>();
        p.put("s", schemaId);
        p.put("name", name);
        p.put("type", tableType);
        p.put("pk", Json.write(pk == null ? List.of() : pk));
        p.put("comment", comment);
        if (row.isPresent()) {
            String id = (String) row.get().get("id");
            p.put("id", id);
            // a comment only seeds an empty description, never overwrites edits (getColumnComments, P2)
            db.update("UPDATE catalog_table SET table_type = :type, pk = :pk,"
                    + " description = COALESCE(description, :comment) WHERE id = :id", p);
            return id;
        }
        String id = Ids.newId();
        p.put("id", id);
        db.update("INSERT INTO catalog_table (id, schema_id, name, table_type, pk, description)"
                + " VALUES (:id, :s, :name, :type, :pk, :comment)", p);
        return id;
    }

    public int deleteTable(String id) {
        return db.update("DELETE FROM catalog_table WHERE id = :id", Map.of("id", id));
    }

    /** FR-SEM-02: is_junction_table is only ever set by the compilation, never cleared. */
    public void markJunction(String tableId) {
        db.update("UPDATE catalog_table SET is_junction_table = TRUE WHERE id = :id", Map.of("id", tableId));
    }

    public List<Map<String, Object>> tablesWithoutTerm(String databaseId) {
        return withPk(db.list(TABLE_SELECT
                + " WHERE d.id = :id AND NOT EXISTS (SELECT 1 FROM table__term tt WHERE tt.table_id = t.id)"
                + " ORDER BY s.name, t.name", Map.of("id", databaseId)));
    }

    // ---------------------------------------------------------------- columns

    public List<Map<String, Object>> columnsOfTable(String tableId) {
        return db.list(COLUMN_SELECT + " WHERE c.table_id = :id ORDER BY c.ordinal_position, c.name",
                Map.of("id", tableId));
    }

    public List<Map<String, Object>> columnsOfTables(Collection<String> tableIds) {
        if (tableIds.isEmpty()) {
            return List.of();
        }
        return db.list(COLUMN_SELECT + " WHERE c.table_id IN (:ids) ORDER BY t.name, c.ordinal_position",
                Map.of("ids", tableIds));
    }

    public List<Map<String, Object>> columnsOfDatabase(String databaseId) {
        return db.list(COLUMN_SELECT + " WHERE d.id = :id ORDER BY s.name, t.name, c.ordinal_position",
                Map.of("id", databaseId));
    }

    public Optional<Map<String, Object>> columnById(String id) {
        return db.one(COLUMN_SELECT + " WHERE c.id = :id", Map.of("id", id));
    }

    public List<Map<String, Object>> columnsByIds(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return db.list(COLUMN_SELECT + " WHERE c.id IN (:ids)", Map.of("ids", ids));
    }

    public String upsertColumn(String tableId, String name, String dataType, boolean nullable, int ordinal,
            String comment) {
        Optional<Map<String, Object>> row = db.one(
                "SELECT id FROM catalog_column WHERE table_id = :t AND name = :name",
                Map.of("t", tableId, "name", name));
        Map<String, Object> p = new HashMap<>();
        p.put("t", tableId);
        p.put("name", name);
        p.put("type", dataType);
        p.put("nullable", nullable);
        p.put("ord", ordinal);
        p.put("comment", comment);
        if (row.isPresent()) {
            String id = (String) row.get().get("id");
            p.put("id", id);
            db.update("UPDATE catalog_column SET data_type = :type, is_nullable = :nullable,"
                    + " ordinal_position = :ord, description = COALESCE(description, :comment) WHERE id = :id", p);
            return id;
        }
        String id = Ids.newId();
        p.put("id", id);
        db.update("INSERT INTO catalog_column (id, table_id, name, data_type, is_nullable, ordinal_position,"
                + " description) VALUES (:id, :t, :name, :type, :nullable, :ord, :comment)", p);
        return id;
    }

    public int deleteColumn(String id) {
        return db.update("DELETE FROM catalog_column WHERE id = :id", Map.of("id", id));
    }

    /** Profiling result (FR-SEM-02 step 1). */
    public void updateProfile(String columnId, String sampleValuesJson, Boolean isUnique, String format) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", columnId);
        p.put("samples", sampleValuesJson);
        p.put("uniq", isUnique);
        p.put("format", format);
        db.update("UPDATE catalog_column SET sample_values = :samples, is_unique = :uniq, format = :format"
                + " WHERE id = :id", p);
    }

    public void updateSampleValues(String columnId, String sampleValuesJson) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", columnId);
        p.put("samples", sampleValuesJson);
        db.update("UPDATE catalog_column SET sample_values = :samples WHERE id = :id", p);
    }

    public void setPiiProcessed(String columnId) {
        db.update("UPDATE catalog_column SET pii_processed = TRUE WHERE id = :id", Map.of("id", columnId));
    }

    // ---------------------------------------------------------------- foreign keys and joins

    public void upsertForeignKey(String sourceColumnId, String targetColumnId, Instant seen) {
        int n = db.update("UPDATE column__foreign_key SET last_seen = :seen"
                + " WHERE source_column_id = :s AND target_column_id = :t",
                Map.of("s", sourceColumnId, "t", targetColumnId, "seen", seen));
        if (n == 0) {
            db.update("INSERT INTO column__foreign_key (source_column_id, target_column_id, last_seen)"
                    + " VALUES (:s, :t, :seen)", Map.of("s", sourceColumnId, "t", targetColumnId, "seen", seen));
        }
    }

    /** Removes FKs of a database that were not seen in the current ingest (FR-ING-01 step 3). */
    public int deleteStaleForeignKeys(String databaseId, Instant seen) {
        return db.update("""
                DELETE FROM column__foreign_key WHERE (last_seen IS NULL OR last_seen < :seen)
                AND source_column_id IN (SELECT c.id FROM catalog_column c JOIN catalog_table t ON t.id = c.table_id
                    JOIN catalog_schema s ON s.id = t.schema_id WHERE s.database_id = :db)
                """, Map.of("db", databaseId, "seen", seen));
    }

    /** Declared FKs of a database with source and target names. */
    public List<Map<String, Object>> foreignKeys(String databaseId) {
        return db.list("""
                SELECT fk.source_column_id, fk.target_column_id, sc.name AS source_column, st.id AS source_table_id,
                       st.name AS source_table, ss.name AS source_schema, tc.name AS target_column,
                       tt.id AS target_table_id, tt.name AS target_table, ts.name AS target_schema
                FROM column__foreign_key fk
                JOIN catalog_column sc ON sc.id = fk.source_column_id
                JOIN catalog_table st ON st.id = sc.table_id
                JOIN catalog_schema ss ON ss.id = st.schema_id
                JOIN catalog_column tc ON tc.id = fk.target_column_id
                JOIN catalog_table tt ON tt.id = tc.table_id
                JOIN catalog_schema ts ON ts.id = tt.schema_id
                WHERE ss.database_id = :db
                """, Map.of("db", databaseId));
    }

    public List<Map<String, Object>> foreignKeysOfTable(String tableId) {
        return db.list("""
                SELECT fk.source_column_id, fk.target_column_id, sc.name AS source_column, tc.name AS target_column,
                       tt.id AS target_table_id, tt.name AS target_table, ts.name AS target_schema
                FROM column__foreign_key fk
                JOIN catalog_column sc ON sc.id = fk.source_column_id
                JOIN catalog_column tc ON tc.id = fk.target_column_id
                JOIN catalog_table tt ON tt.id = tc.table_id
                JOIN catalog_schema ts ON ts.id = tt.schema_id
                WHERE sc.table_id = :t
                """, Map.of("t", tableId));
    }

    /** Records a join observed in the query history; refs are {@code "<sql id>|<condition>"}. */
    public void addObservedJoin(String table, String sourceColumnId, String targetColumnId, String ref) {
        String t = "union".equals(table) ? "column__union" : "column__join";
        Optional<Map<String, Object>> row = db.one("SELECT refs FROM " + t
                + " WHERE source_column_id = :s AND target_column_id = :t",
                Map.of("s", sourceColumnId, "t", targetColumnId));
        if (row.isPresent()) {
            List<String> refs = new java.util.ArrayList<>(Json.readStringList((String) row.get().get("refs")));
            if (!refs.contains(ref)) {
                refs.add(ref);
                db.update("UPDATE " + t + " SET refs = :refs WHERE source_column_id = :s AND target_column_id = :t",
                        Map.of("s", sourceColumnId, "t", targetColumnId, "refs", Json.write(refs)));
            }
        } else {
            db.update("INSERT INTO " + t + " (source_column_id, target_column_id, refs) VALUES (:s, :t, :refs)",
                    Map.of("s", sourceColumnId, "t", targetColumnId, "refs", Json.write(List.of(ref))));
        }
    }

    public List<Map<String, Object>> observedJoins(String databaseId) {
        return db.list("""
                SELECT j.source_column_id, j.target_column_id, j.refs FROM column__join j
                JOIN catalog_column c ON c.id = j.source_column_id JOIN catalog_table t ON t.id = c.table_id
                JOIN catalog_schema s ON s.id = t.schema_id WHERE s.database_id = :db
                """, Map.of("db", databaseId));
    }

    // ---------------------------------------------------------------- SQL history

    /** Upserts an observed statement by text hash; returns its id. Counters grow across runs. */
    public String upsertSqlQuery(String databaseId, String hash, String sql, int count, Instant lastQuery,
            int nodes, int joins, int unions, Instant now) {
        Optional<Map<String, Object>> row = db.one("SELECT id FROM sql_query WHERE query_hash = :h", Map.of("h", hash));
        Map<String, Object> p = new HashMap<>();
        p.put("h", hash);
        p.put("db", databaseId);
        p.put("sql", sql);
        p.put("cnt", count);
        p.put("lq", lastQuery);
        p.put("nodes", nodes);
        p.put("joins", joins);
        p.put("unions", unions);
        p.put("now", now);
        if (row.isPresent()) {
            String id = (String) row.get().get("id");
            p.put("id", id);
            db.update("UPDATE sql_query SET query_count = :cnt, total_counter = total_counter + :cnt,"
                    + " last_seen = :now, last_query_timestamp = :lq WHERE id = :id", p);
            return id;
        }
        String id = Ids.newId();
        p.put("id", id);
        db.update("INSERT INTO sql_query (id, query_hash, database_id, sql_full_query, query_count, total_counter,"
                + " created, last_seen, last_query_timestamp, nodes_count, join_count, union_count)"
                + " VALUES (:id, :h, :db, :sql, :cnt, :cnt, :now, :now, :lq, :nodes, :joins, :unions)", p);
        return id;
    }

    public void linkSqlTable(String sqlId, String tableId) {
        if (db.count("SELECT COUNT(*) FROM sql_query__table WHERE sql_query_id = :q AND table_id = :t",
                Map.of("q", sqlId, "t", tableId)) == 0) {
            db.update("INSERT INTO sql_query__table (sql_query_id, table_id) VALUES (:q, :t)",
                    Map.of("q", sqlId, "t", tableId));
        }
    }

    public void linkSqlColumn(String sqlId, String columnId) {
        if (db.count("SELECT COUNT(*) FROM sql_query__column WHERE sql_query_id = :q AND column_id = :c",
                Map.of("q", sqlId, "c", columnId)) == 0) {
            db.update("INSERT INTO sql_query__column (sql_query_id, column_id) VALUES (:q, :c)",
                    Map.of("q", sqlId, "c", columnId));
        }
    }

    /** Statements that reference any of the given tables, most used first. */
    public List<Map<String, Object>> sqlForTables(Collection<String> tableIds, int limit) {
        if (tableIds.isEmpty()) {
            return List.of();
        }
        return db.list("SELECT DISTINCT q.id, q.sql_full_query, q.total_counter, q.query_count FROM sql_query q"
                + " JOIN sql_query__table qt ON qt.sql_query_id = q.id WHERE qt.table_id IN (:ids)"
                + " ORDER BY q.total_counter DESC, q.id FETCH FIRST " + limit + " ROWS ONLY",
                Map.of("ids", tableIds));
    }

    public Optional<Map<String, Object>> sqlById(String id) {
        return db.one("SELECT * FROM sql_query WHERE id = :id", Map.of("id", id));
    }

    // ---------------------------------------------------------------- generic node patch

    /** Updates whitelisted columns of a catalog node; returns the number of rows changed. */
    public int patchNode(String table, String id, Map<String, Object> values) {
        if (values.isEmpty()) {
            return 0;
        }
        StringBuilder sql = new StringBuilder("UPDATE ").append(table).append(" SET ");
        Map<String, Object> p = new HashMap<>(values);
        int i = 0;
        for (String key : values.keySet()) {
            if (i++ > 0) {
                sql.append(", ");
            }
            sql.append(key).append(" = :").append(key);
        }
        sql.append(" WHERE id = :__id");
        p.put("__id", id);
        return db.update(sql.toString(), p);
    }

    private static List<Map<String, Object>> withPk(List<Map<String, Object>> rows) {
        rows.forEach(CatalogRepository::pk);
        return rows;
    }

    private static Map<String, Object> pk(Map<String, Object> row) {
        Object pk = row.get("pk");
        if (pk instanceof String s) {
            row.put("pk", Json.readStringList(s));
        }
        return row;
    }
}
