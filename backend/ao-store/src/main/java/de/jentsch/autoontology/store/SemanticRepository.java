// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.store;

import de.jentsch.autoontology.common.Ids;
import de.jentsch.autoontology.common.Json;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Semantic layer: Terms, ColumnAttributes, SqlAttributes, semantic FKs, Custom and PQL analyses.
 * Derived from auto_ontology/dal/terms.py, attributes.py, sql_attributes.py of NVIDIA Auto-Ontology.
 */
public class SemanticRepository {
    private static final String TERM_COLS = "t.id, t.name, t.description, t.source, t.synonyms, t.name_certified,"
            + " t.description_certified, t.owner_id, t.created, t.modified, t.created_by, t.modified_by";

    private final StoreJdbc db;

    public SemanticRepository(StoreJdbc db) {
        this.db = db;
    }

    // ---------------------------------------------------------------- terms

    public long countTerms() {
        return db.count("SELECT COUNT(*) FROM term", Map.of());
    }

    public List<Map<String, Object>> terms(String search, int offset, int limit) {
        Map<String, Object> p = new HashMap<>();
        String where = "";
        if (search != null && !search.isBlank()) {
            where = " WHERE LOWER(t.name) LIKE :q OR LOWER(COALESCE(t.description, '')) LIKE :q";
            p.put("q", "%" + search.toLowerCase(java.util.Locale.ROOT) + "%");
        }
        return synonyms(db.list("SELECT " + TERM_COLS + " FROM term t" + where
                + " ORDER BY LOWER(t.name), t.id OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY", p));
    }

    public long countTerms(String search) {
        if (search == null || search.isBlank()) {
            return countTerms();
        }
        return db.count("SELECT COUNT(*) FROM term t WHERE LOWER(t.name) LIKE :q"
                + " OR LOWER(COALESCE(t.description, '')) LIKE :q",
                Map.of("q", "%" + search.toLowerCase(java.util.Locale.ROOT) + "%"));
    }

    public List<Map<String, Object>> allTerms() {
        return synonyms(db.list("SELECT " + TERM_COLS + " FROM term t ORDER BY t.name"));
    }

    public Optional<Map<String, Object>> termById(String id) {
        return db.one("SELECT " + TERM_COLS + " FROM term t WHERE t.id = :id", Map.of("id", id))
                .map(SemanticRepository::synonyms);
    }

    public Optional<Map<String, Object>> termByName(String name) {
        return db.one("SELECT " + TERM_COLS + " FROM term t WHERE t.name = :n ORDER BY t.source", Map.of("n", name))
                .map(SemanticRepository::synonyms);
    }

    public List<Map<String, Object>> termsByIds(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return synonyms(db.list("SELECT " + TERM_COLS + " FROM term t WHERE t.id IN (:ids)", Map.of("ids", ids)));
    }

    /** Merge by (name, source); writes of the compilation never touch audit columns (FR-DB-02). */
    public String mergeTerm(String name, String description, List<String> synonyms, String source, Instant now) {
        Optional<Map<String, Object>> row = db.one("SELECT id, synonyms FROM term WHERE name = :n AND source = :s",
                Map.of("n", name, "s", source));
        if (row.isPresent()) {
            String id = (String) row.get().get("id");
            List<String> merged = new ArrayList<>(Json.readStringList((String) row.get().get("synonyms")));
            for (String s : synonyms) {
                if (!merged.contains(s)) {
                    merged.add(s);
                }
            }
            Map<String, Object> p = new HashMap<>();
            p.put("id", id);
            p.put("syn", Json.write(merged));
            p.put("desc", description);
            db.update("UPDATE term SET synonyms = :syn,"
                    + " description = CASE WHEN description IS NULL OR description = '' THEN :desc ELSE description END"
                    + " WHERE id = :id", p);
            return id;
        }
        String id = Ids.newId();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("n", name);
        p.put("d", description);
        p.put("s", source);
        p.put("syn", Json.write(synonyms));
        p.put("now", now);
        db.update("INSERT INTO term (id, name, description, source, synonyms, created)"
                + " VALUES (:id, :n, :d, :s, :syn, :now)", p);
        return id;
    }

    public void linkTableTerm(String tableId, String termId) {
        if (db.count("SELECT COUNT(*) FROM table__term WHERE table_id = :t AND term_id = :term",
                Map.of("t", tableId, "term", termId)) == 0) {
            db.update("INSERT INTO table__term (table_id, term_id) VALUES (:t, :term)",
                    Map.of("t", tableId, "term", termId));
        }
    }

    public List<Map<String, Object>> tablesOfTerm(String termId) {
        return db.list(CatalogRepository.TABLE_SELECT + " JOIN table__term tt ON tt.table_id = t.id"
                + " WHERE tt.term_id = :id ORDER BY t.name", Map.of("id", termId));
    }

    public List<Map<String, Object>> termsOfTable(String tableId) {
        return synonyms(db.list("SELECT " + TERM_COLS + " FROM term t JOIN table__term tt ON tt.term_id = t.id"
                + " WHERE tt.table_id = :id ORDER BY t.name", Map.of("id", tableId)));
    }

    /** Per-card counters of the terms list (FR-GOV-01). */
    public Map<String, Object> termCounters(String termId) {
        Map<String, Object> m = new HashMap<>();
        m.put("table_count", db.count("SELECT COUNT(*) FROM table__term WHERE term_id = :id", Map.of("id", termId)));
        m.put("column_attribute_count", db.count(
                "SELECT COUNT(*) FROM column_attribute__term WHERE term_id = :id", Map.of("id", termId)));
        m.put("sql_attribute_count", db.count(
                "SELECT COUNT(*) FROM sql_attribute__term WHERE term_id = :id", Map.of("id", termId)));
        return m;
    }

    // ---------------------------------------------------------------- column attributes

    public static final String ATTR_SELECT = """
            SELECT a.id, a.name, a.description, a.datatype, a.source, a.source_column, a.term_name, a.table_id,
                   a.certified, a.description_suggestion, a.pii_processed, at.term_id
            FROM column_attribute a
            LEFT JOIN column_attribute__term at ON at.attribute_id = a.id
            """;

    /**
     * Merge key (name, source_column, term_name, table_id, source); creates PROPERTY_OF and
     * HAS_ATTRIBUTE edges. Returns the attribute id.
     */
    public String mergeColumnAttribute(String name, String description, String datatype, String sourceColumn,
            String termName, String termId, String tableId, String columnId, Instant now) {
        String source = "semantic";
        Map<String, Object> key = new HashMap<>();
        key.put("n", name);
        key.put("sc", sourceColumn == null ? "" : sourceColumn);
        key.put("tn", termName == null ? "" : termName);
        key.put("tid", tableId == null ? "" : tableId);
        key.put("s", source);
        Optional<Map<String, Object>> row = db.one("SELECT id FROM column_attribute WHERE name = :n"
                + " AND source_column = :sc AND term_name = :tn AND table_id = :tid AND source = :s", key);
        String id;
        if (row.isPresent()) {
            id = (String) row.get().get("id");
        } else {
            id = Ids.newId();
            Map<String, Object> p = new HashMap<>(key);
            p.put("id", id);
            p.put("d", description);
            p.put("dt", datatype);
            p.put("now", now);
            db.update("INSERT INTO column_attribute (id, name, description, datatype, source, source_column, term_name,"
                    + " table_id, created) VALUES (:id, :n, :d, :dt, :s, :sc, :tn, :tid, :now)", p);
        }
        if (termId != null && db.count("SELECT COUNT(*) FROM column_attribute__term WHERE attribute_id = :a",
                Map.of("a", id)) == 0) {
            db.update("INSERT INTO column_attribute__term (attribute_id, term_id) VALUES (:a, :t)",
                    Map.of("a", id, "t", termId));
        }
        if (columnId != null && db.count("SELECT COUNT(*) FROM column__has_attribute WHERE column_id = :c"
                + " AND attribute_id = :a", Map.of("c", columnId, "a", id)) == 0) {
            db.update("INSERT INTO column__has_attribute (column_id, attribute_id) VALUES (:c, :a)",
                    Map.of("c", columnId, "a", id));
        }
        return id;
    }

    public List<Map<String, Object>> attributesOfTerm(String termId) {
        return db.list(ATTR_SELECT + " WHERE at.term_id = :id ORDER BY a.name", Map.of("id", termId));
    }

    public Optional<Map<String, Object>> attributeById(String id) {
        return db.one(ATTR_SELECT + " WHERE a.id = :id", Map.of("id", id));
    }

    public List<Map<String, Object>> attributesByIds(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return db.list(ATTR_SELECT + " WHERE a.id IN (:ids)", Map.of("ids", ids));
    }

    /** The primary column of an attribute (HAS_ATTRIBUTE). */
    public List<Map<String, Object>> columnsOfAttribute(String attributeId) {
        return db.list(CatalogRepository.COLUMN_SELECT + " JOIN column__has_attribute ha ON ha.column_id = c.id"
                + " WHERE ha.attribute_id = :id", Map.of("id", attributeId));
    }

    /** Columns that reference the attribute (SEMANTIC_FK). */
    public List<Map<String, Object>> referencingColumns(String attributeId) {
        return db.list(CatalogRepository.COLUMN_SELECT + " JOIN column__semantic_fk sf ON sf.column_id = c.id"
                + " WHERE sf.attribute_id = :id", Map.of("id", attributeId));
    }

    /** Attributes of all columns of a database with term and column info (embedding, retrieval). */
    public List<Map<String, Object>> attributesOfDatabase(String databaseId) {
        return db.list("""
                SELECT a.id, a.name, a.description, a.datatype, a.source_column, a.term_name, a.table_id,
                       a.certified, at.term_id, c.id AS column_id, c.sample_values, c.is_unique, t.name AS table_name,
                       s.name AS schema_name
                FROM column_attribute a
                JOIN column__has_attribute ha ON ha.attribute_id = a.id
                JOIN catalog_column c ON c.id = ha.column_id
                JOIN catalog_table t ON t.id = c.table_id
                JOIN catalog_schema s ON s.id = t.schema_id
                LEFT JOIN column_attribute__term at ON at.attribute_id = a.id
                WHERE s.database_id = :db
                ORDER BY t.name, c.ordinal_position
                """, Map.of("db", databaseId));
    }

    public int patchAttribute(String id, Map<String, Object> values) {
        return patch("column_attribute", id, values);
    }

    // ---------------------------------------------------------------- semantic FKs

    public boolean columnHasSemanticEdge(String columnId) {
        return db.count("SELECT COUNT(*) FROM column__semantic_fk WHERE column_id = :c", Map.of("c", columnId)) > 0
                || db.count("SELECT COUNT(*) FROM column__has_attribute WHERE column_id = :c", Map.of("c", columnId)) > 0;
    }

    public void addSemanticFk(String columnId, String attributeId) {
        if (db.count("SELECT COUNT(*) FROM column__semantic_fk WHERE column_id = :c AND attribute_id = :a",
                Map.of("c", columnId, "a", attributeId)) == 0) {
            db.update("INSERT INTO column__semantic_fk (column_id, attribute_id, use_in_join_paths)"
                    + " VALUES (:c, :a, TRUE)", Map.of("c", columnId, "a", attributeId));
        }
    }

    public int deleteSemanticFk(String columnId, String attributeId) {
        return db.update("DELETE FROM column__semantic_fk WHERE column_id = :c AND attribute_id = :a",
                Map.of("c", columnId, "a", attributeId));
    }

    public void setJoinDefault(String columnId, String attributeId, boolean value) {
        db.update("UPDATE column__semantic_fk SET use_in_join_paths = :v WHERE column_id = :c AND attribute_id = :a",
                Map.of("c", columnId, "a", attributeId, "v", value));
    }

    /**
     * Semantic-FK edges of a database projected to tables: the referencing column and table, the
     * referenced attribute's column and table (basis of the join path search, FR-AGT-10).
     */
    public List<Map<String, Object>> semanticFkEdges(String databaseId) {
        return db.list("""
                SELECT sf.column_id AS source_column_id, sc.name AS source_column, st.id AS source_table_id,
                       st.name AS source_table, ss.name AS source_schema, sf.attribute_id, sf.use_in_join_paths,
                       tc.id AS target_column_id, tc.name AS target_column, tt.id AS target_table_id,
                       tt.name AS target_table, ts.name AS target_schema
                FROM column__semantic_fk sf
                JOIN catalog_column sc ON sc.id = sf.column_id
                JOIN catalog_table st ON st.id = sc.table_id
                JOIN catalog_schema ss ON ss.id = st.schema_id
                JOIN column__has_attribute ha ON ha.attribute_id = sf.attribute_id
                JOIN catalog_column tc ON tc.id = ha.column_id
                JOIN catalog_table tt ON tt.id = tc.table_id
                JOIN catalog_schema ts ON ts.id = tt.schema_id
                WHERE ss.database_id = :db
                """, Map.of("db", databaseId));
    }

    public List<Map<String, Object>> allSemanticFkEdges() {
        return db.list("""
                SELECT sf.column_id AS source_column_id, sc.table_id AS source_table_id, sf.attribute_id,
                       ha.column_id AS target_column_id, tc.table_id AS target_table_id, sf.use_in_join_paths
                FROM column__semantic_fk sf
                JOIN catalog_column sc ON sc.id = sf.column_id
                JOIN column__has_attribute ha ON ha.attribute_id = sf.attribute_id
                JOIN catalog_column tc ON tc.id = ha.column_id
                """);
    }

    // ---------------------------------------------------------------- SQL attributes

    public static final String SQL_ATTR_SELECT = """
            SELECT a.id, a.name, a.description, a.expression, a.source, a.certified, a.owner_id,
                   a.description_suggestion, a.pii_processed, a.created, a.modified, at.term_id, t.name AS term_name
            FROM sql_attribute a
            LEFT JOIN sql_attribute__term at ON at.attribute_id = a.id
            LEFT JOIN term t ON t.id = at.term_id
            """;

    public List<Map<String, Object>> sqlAttributes() {
        return db.list(SQL_ATTR_SELECT + " ORDER BY a.name");
    }

    public List<Map<String, Object>> sqlAttributesOfTerm(String termId) {
        return db.list(SQL_ATTR_SELECT + " WHERE at.term_id = :id ORDER BY a.name", Map.of("id", termId));
    }

    public Optional<Map<String, Object>> sqlAttributeById(String id) {
        return db.one(SQL_ATTR_SELECT + " WHERE a.id = :id", Map.of("id", id));
    }

    public Optional<Map<String, Object>> sqlAttributeByName(String name) {
        return db.one(SQL_ATTR_SELECT + " WHERE a.name = :n", Map.of("n", name));
    }

    public List<Map<String, Object>> sqlAttributesByIds(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return db.list(SQL_ATTR_SELECT + " WHERE a.id IN (:ids)", Map.of("ids", ids));
    }

    public String insertSqlAttribute(String name, String description, String expression, String source,
            String termId, String ownerId, String user, Instant now) {
        String id = Ids.newId();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("n", name);
        p.put("d", description);
        p.put("e", expression);
        p.put("s", source);
        p.put("o", ownerId);
        p.put("u", user);
        p.put("now", now);
        db.update("INSERT INTO sql_attribute (id, name, description, expression, source, owner_id, created, created_by)"
                + " VALUES (:id, :n, :d, :e, :s, :o, :now, :u)", p);
        if (termId != null) {
            db.update("INSERT INTO sql_attribute__term (attribute_id, term_id) VALUES (:a, :t)",
                    Map.of("a", id, "t", termId));
        }
        return id;
    }

    public void linkSqlAttributeSql(String attributeId, String sqlId) {
        if (db.count("SELECT COUNT(*) FROM sql_attribute__sql WHERE attribute_id = :a AND sql_query_id = :q",
                Map.of("a", attributeId, "q", sqlId)) == 0) {
            db.update("INSERT INTO sql_attribute__sql (attribute_id, sql_query_id) VALUES (:a, :q)",
                    Map.of("a", attributeId, "q", sqlId));
        }
    }

    public int patchSqlAttribute(String id, Map<String, Object> values) {
        return patch("sql_attribute", id, values);
    }

    public int deleteSqlAttribute(String id) {
        return db.update("DELETE FROM sql_attribute WHERE id = :id", Map.of("id", id));
    }

    public void invalidateSuggestionsOfTerm(String termId) {
        db.update("UPDATE sql_attribute SET description_suggestion = NULL WHERE id IN"
                + " (SELECT attribute_id FROM sql_attribute__term WHERE term_id = :t)", Map.of("t", termId));
    }

    // ---------------------------------------------------------------- custom analyses / PQL

    public List<Map<String, Object>> customAnalyses() {
        return db.list("SELECT id, name, description, sql, database_id, created, modified FROM custom_analysis"
                + " ORDER BY name");
    }

    public Optional<Map<String, Object>> customAnalysisById(String id) {
        return db.one("SELECT id, name, description, sql, database_id, created, modified FROM custom_analysis"
                + " WHERE id = :id", Map.of("id", id));
    }

    public List<Map<String, Object>> customAnalysesByIds(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return db.list("SELECT id, name, description, sql, database_id FROM custom_analysis WHERE id IN (:ids)",
                Map.of("ids", ids));
    }

    public String insertCustomAnalysis(String name, String description, String sql, String databaseId, String user,
            Instant now) {
        String id = Ids.newId();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("n", name);
        p.put("d", description);
        p.put("s", sql);
        p.put("db", databaseId);
        p.put("u", user);
        p.put("now", now);
        db.update("INSERT INTO custom_analysis (id, name, description, sql, database_id, created, created_by)"
                + " VALUES (:id, :n, :d, :s, :db, :now, :u)", p);
        return id;
    }

    public int updateCustomAnalysis(String id, String name, String description, String sql, String user,
            Instant now) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("n", name);
        p.put("d", description);
        p.put("s", sql);
        p.put("u", user);
        p.put("now", now);
        return db.update("UPDATE custom_analysis SET name = :n, description = :d, sql = :s, modified = :now,"
                + " modified_by = :u WHERE id = :id", p);
    }

    public int deleteCustomAnalysis(String id) {
        return db.update("DELETE FROM custom_analysis WHERE id = :id", Map.of("id", id));
    }

    public List<Map<String, Object>> pqlAnalyses() {
        return db.list("SELECT id, name, description, pql, created, modified FROM pql_analysis ORDER BY name");
    }

    public String insertPql(String name, String description, String pql, Instant now) {
        String id = Ids.newId();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("n", name);
        p.put("d", description);
        p.put("q", pql);
        p.put("now", now);
        db.update("INSERT INTO pql_analysis (id, name, description, pql, created) VALUES (:id, :n, :d, :q, :now)", p);
        return id;
    }

    public int updatePql(String id, String name, String description, String pql, Instant now) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("n", name);
        p.put("d", description);
        p.put("q", pql);
        p.put("now", now);
        return db.update("UPDATE pql_analysis SET name = :n, description = :d, pql = :q, modified = :now"
                + " WHERE id = :id", p);
    }

    public int deletePql(String id) {
        return db.update("DELETE FROM pql_analysis WHERE id = :id", Map.of("id", id));
    }

    // ---------------------------------------------------------------- reset (dal/reset.py)

    /**
     * Deletes the semantic layer of one database (or all when {@code databaseId} is null).
     * PqlAnalysis survives a single-database reset (FR-SEM-08). Returns the deleted term ids.
     */
    public List<String> deleteSemanticLayer(String databaseId) {
        if (databaseId == null) {
            List<String> ids = db.column("SELECT id FROM term", Map.of(), String.class);
            db.update("DELETE FROM sql_attribute", Map.of());
            db.update("DELETE FROM column_attribute", Map.of());
            db.update("DELETE FROM term", Map.of());
            db.update("DELETE FROM custom_analysis", Map.of());
            db.update("DELETE FROM pql_analysis", Map.of());
            db.update("UPDATE catalog_table SET is_junction_table = FALSE", Map.of());
            return ids;
        }
        Map<String, Object> p = Map.of("db", databaseId);
        String tablesOfDb = "SELECT t.id FROM catalog_table t JOIN catalog_schema s ON s.id = t.schema_id"
                + " WHERE s.database_id = :db";
        List<String> termIds = db.column("SELECT DISTINCT term_id FROM table__term WHERE table_id IN (" + tablesOfDb
                + ")", p, String.class);
        db.update("DELETE FROM column__semantic_fk WHERE column_id IN (SELECT c.id FROM catalog_column c WHERE"
                + " c.table_id IN (" + tablesOfDb + "))", p);
        db.update("DELETE FROM column_attribute WHERE table_id IN (" + tablesOfDb + ")", p);
        if (!termIds.isEmpty()) {
            Map<String, Object> tp = Map.of("ids", termIds);
            db.update("DELETE FROM sql_attribute WHERE id IN (SELECT attribute_id FROM sql_attribute__term"
                    + " WHERE term_id IN (:ids))", tp);
            db.update("DELETE FROM term WHERE id IN (:ids)", tp);
        }
        db.update("DELETE FROM custom_analysis WHERE database_id = :db", p);
        return termIds;
    }

    // ---------------------------------------------------------------- helpers

    private int patch(String table, String id, Map<String, Object> values) {
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

    public int patchTerm(String id, Map<String, Object> values) {
        return patch("term", id, values);
    }

    private static List<Map<String, Object>> synonyms(List<Map<String, Object>> rows) {
        rows.forEach(SemanticRepository::synonyms);
        return rows;
    }

    private static Map<String, Object> synonyms(Map<String, Object> row) {
        Object s = row.get("synonyms");
        row.put("synonyms", s instanceof String str ? Json.readStringList(str) : List.of());
        return row;
    }
}
