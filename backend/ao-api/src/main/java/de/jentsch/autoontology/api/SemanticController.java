// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.api;

import de.jentsch.autoontology.common.ApiException;
import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.CurrentUser;
import de.jentsch.autoontology.common.Ids;
import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.connectors.ReadOnlySql;
import de.jentsch.autoontology.connectors.SourceDatabase;
import de.jentsch.autoontology.ingestion.ConnectionService;
import de.jentsch.autoontology.ingestion.DataLayerEmbedder;
import de.jentsch.autoontology.llm.LlmGateway;
import de.jentsch.autoontology.llm.LlmMessage;
import de.jentsch.autoontology.llm.ModelRole;
import de.jentsch.autoontology.semantic.SemanticEmbedder;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.store.GovernanceRepository;
import de.jentsch.autoontology.store.SemanticRepository;
import de.jentsch.autoontology.vector.VectorStore;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Glossary (section 13.1), SQL attributes (FR-GOV-02), custom/PQL analyses (FR-GOV-11). */
@RestController
@RequestMapping("${ao.api-prefix:/api}")
public class SemanticController {
    private final SemanticRepository semantic;
    private final CatalogRepository catalog;
    private final GovernanceRepository governance;
    private final SemanticEmbedder embedder;
    private final DataLayerEmbedder dataEmbedder;
    private final VectorStore vectors;
    private final ConnectionService connections;
    private final LlmGateway llm;
    private final CurrentUser user;
    private final AoTime time;

    public SemanticController(SemanticRepository semantic, CatalogRepository catalog, GovernanceRepository governance,
            SemanticEmbedder embedder, DataLayerEmbedder dataEmbedder, VectorStore vectors, ConnectionService connections,
            LlmGateway llm, CurrentUser user, AoTime time) {
        this.semantic = semantic;
        this.catalog = catalog;
        this.governance = governance;
        this.embedder = embedder;
        this.dataEmbedder = dataEmbedder;
        this.vectors = vectors;
        this.connections = connections;
        this.llm = llm;
        this.user = user;
        this.time = time;
    }

    // ------------------------------------------------------------------ certification (FR-GOV-01a)

    /** certified: all flags set; pending: none; partial: otherwise (dal/terms.py::_certification). */
    String certification(Map<String, Object> term) {
        String id = (String) term.get("id");
        List<Boolean> flags = new ArrayList<>();
        flags.add(Boolean.TRUE.equals(term.get("name_certified")));
        flags.add(Boolean.TRUE.equals(term.get("description_certified")));
        semantic.attributesOfTerm(id).forEach(a -> flags.add(Boolean.TRUE.equals(a.get("certified"))));
        semantic.sqlAttributesOfTerm(id).forEach(a -> flags.add(Boolean.TRUE.equals(a.get("certified"))));
        long set = flags.stream().filter(b -> b).count();
        return set == flags.size() ? "certified" : set == 0 ? "pending" : "partial";
    }

    private Map<String, Object> termItem(Map<String, Object> t, Map<String, List<String>> tags) {
        Map<String, Object> m = new LinkedHashMap<>();
        String id = (String) t.get("id");
        m.put("id", id);
        m.put("name", t.get("name"));
        m.put("description", t.get("description"));
        m.put("name_certified", t.get("name_certified"));
        m.put("description_certified", t.get("description_certified"));
        m.put("owner_id", t.get("owner_id"));
        m.put("synonyms", t.get("synonyms"));
        m.put("certification", certification(t));
        m.put("schema_names", semantic.tablesOfTerm(id).stream().map(x -> (String) x.get("schema_name")).distinct().toList());
        m.put("tags", CatalogController.chips(tags.getOrDefault(id, List.of())));
        m.put("zones", List.of());
        return m;
    }

    // ------------------------------------------------------------------ terms

    @GetMapping("/terms")
    public Map<String, Object> terms(@RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int skip, @RequestParam(defaultValue = "50") int limit) {
        Map<String, List<String>> tags = governance.tagNamesFor("term");
        List<Map<String, Object>> terms = semantic.terms(query, skip, limit);
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> caCounts = new ArrayList<>();
        List<Map<String, Object>> saCounts = new ArrayList<>();
        List<Map<String, Object>> relCounts = new ArrayList<>();
        for (Map<String, Object> t : terms) {
            items.add(termItem(t, tags));
            Map<String, Object> c = semantic.termCounters((String) t.get("id"));
            caCounts.add(Map.of("term_id", t.get("id"), "count", c.get("column_attribute_count")));
            saCounts.add(Map.of("term_id", t.get("id"), "count", c.get("sql_attribute_count")));
            relCounts.add(Map.of("term_id", t.get("id"), "count", c.get("table_count")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("terms", items);
        out.put("total", semantic.countTerms(query));
        out.put("column_attribute_counts", caCounts);
        out.put("sql_attribute_counts", saCounts);
        out.put("related_counts", relCounts);
        return out;
    }

    private Map<String, Object> term(String id) {
        if (!Ids.isUuid(id)) {
            throw ApiException.notFound("Term not found");
        }
        return semantic.termById(id).orElseThrow(() -> ApiException.notFound("Term not found"));
    }

    @GetMapping("/terms/{termId}")
    public Map<String, Object> termDetail(@PathVariable String termId) {
        Map<String, Object> t = term(termId);
        Map<String, Object> d = termItem(t, governance.tagNamesFor("term"));
        d.put("created", t.get("created"));
        d.put("created_by", t.get("created_by"));
        d.put("modified", t.get("modified"));
        d.put("modified_by", t.get("modified_by"));
        List<Map<String, Object>> tables = semantic.tablesOfTerm(termId).stream().map(x -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", x.get("id"));
            m.put("name", x.get("name"));
            m.put("schema_id", x.get("schema_id"));
            m.put("schema_name", x.get("schema_name"));
            m.put("db_id", x.get("database_id"));
            m.put("database_name", x.get("database_name"));
            return m;
        }).toList();
        d.put("tables", tables);
        d.put("table_count", tables.size());
        d.put("related_terms", relatedTerms(termId));
        return Map.of("data", d);
    }

    /** Terms connected through semantic FKs between their tables. */
    private List<Map<String, Object>> relatedTerms(String termId) {
        Set<String> myTables = new LinkedHashSet<>();
        semantic.tablesOfTerm(termId).forEach(t -> myTables.add((String) t.get("id")));
        Set<String> otherTables = new LinkedHashSet<>();
        for (Map<String, Object> e : semantic.allSemanticFkEdges()) {
            if (myTables.contains(e.get("source_table_id"))) {
                otherTables.add((String) e.get("target_table_id"));
            }
            if (myTables.contains(e.get("target_table_id"))) {
                otherTables.add((String) e.get("source_table_id"));
            }
        }
        otherTables.removeAll(myTables);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (String t : otherTables) {
            for (Map<String, Object> term : semantic.termsOfTable(t)) {
                out.putIfAbsent((String) term.get("id"), Map.of("id", term.get("id"), "name", term.get("name"),
                        "description", String.valueOf(term.getOrDefault("description", ""))));
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Only name, description, name_certified, description_certified and owner_id (TermUpdate). */
    @PatchMapping("/terms/{termId}")
    public Map<String, Object> patchTerm(@PathVariable String termId, @RequestBody Map<String, Object> body) {
        Map<String, Object> before = term(termId);
        Set<String> allowed = Set.of("name", "description", "name_certified", "description_certified", "owner_id");
        for (String k : body.keySet()) {
            if (!allowed.contains(k)) {
                throw ApiException.unprocessable("Field " + k + " cannot be changed (synonyms are not editable)");
            }
        }
        Map<String, Object> values = new HashMap<>(body);
        boolean textChange = values.containsKey("name") || values.containsKey("description");
        values.put("modified", Timestamp.from(time.now()));
        values.put("modified_by", user.id());
        if (semantic.patchTerm(termId, values) != 1) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "The change could not be stored");
        }
        if (values.containsKey("name") && !String.valueOf(values.get("name")).equals(before.get("name"))) {
            // a renamed Term invalidates the cached description suggestions of its SqlAttributes
            semantic.invalidateSuggestionsOfTerm(termId);
        }
        if (textChange) {
            embedder.refreshTerm(termId);
        }
        Map<String, Object> t = term(termId);
        Map<String, Object> d = termItem(t, governance.tagNamesFor("term"));
        d.put("term_certification", d.get("certification"));
        return Map.of("data", d);
    }

    // ------------------------------------------------------------------ column attributes

    private Map<String, Object> attributeView(Map<String, Object> a, Map<String, List<String>> tags) {
        String id = (String) a.get("id");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", a.get("name"));
        m.put("description", a.get("description"));
        m.put("datatype", a.get("datatype"));
        m.put("source_column", a.get("source_column"));
        m.put("term_name", a.get("term_name"));
        m.put("table_id", a.get("table_id"));
        m.put("certified", a.get("certified"));
        List<Map<String, Object>> primary = semantic.columnsOfAttribute(id);
        m.put("primary_column", primary.isEmpty() ? null : columnRef(primary.get(0)));
        m.put("referenced_columns", semantic.referencingColumns(id).stream().map(SemanticController::columnRef).toList());
        m.put("sample_values", primary.isEmpty() ? List.of() : Json.readList((String) primary.get(0).get("sample_values"))
                .stream().map(String::valueOf).toList());
        m.put("tags", CatalogController.chips(tags.getOrDefault(id, List.of())));
        m.put("zones", List.of());
        return m;
    }

    static Map<String, Object> columnRef(Map<String, Object> c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.get("id"));
        m.put("column_name", c.get("name"));
        m.put("table_id", c.get("table_id"));
        m.put("table_name", c.get("table_name"));
        m.put("schema_id", c.get("schema_id"));
        m.put("db_id", c.get("database_id"));
        return m;
    }

    @GetMapping("/terms/{termId}/column-attributes")
    public Map<String, Object> termAttributes(@PathVariable String termId, @RequestParam(defaultValue = "0") int skip,
            @RequestParam(defaultValue = "200") int limit) {
        term(termId);
        Map<String, List<String>> tags = governance.tagNamesFor("column_attribute");
        List<Map<String, Object>> all = semantic.attributesOfTerm(termId);
        List<Map<String, Object>> page = all.stream().skip(skip).limit(limit).map(a -> attributeView(a, tags)).toList();
        return Map.of("data", page, "count", page.size(), "total", all.size());
    }

    @PatchMapping("/terms/{termId}/column-attributes/{attrId}")
    public Map<String, Object> patchAttribute(@PathVariable String termId, @PathVariable String attrId,
            @RequestBody Map<String, Object> body) {
        term(termId);
        Map<String, Object> a = semantic.attributeById(attrId).filter(x -> termId.equals(x.get("term_id")))
                .orElseThrow(() -> ApiException.notFound("Column attribute not found"));
        Map<String, Object> values = new HashMap<>();
        for (String k : List.of("name", "description", "certified")) {
            if (body.containsKey(k)) {
                values.put(k, body.get(k));
            }
        }
        boolean samples = body.containsKey("sample_values");
        if (values.isEmpty() && !samples) {
            throw ApiException.unprocessable("Nothing to update");
        }
        if (!values.isEmpty()) {
            values.put("modified", Timestamp.from(time.now()));
            values.put("modified_by", user.id());
            semantic.patchAttribute(attrId, values);
        }
        List<Map<String, Object>> cols = semantic.columnsOfAttribute(attrId);
        if (samples && !cols.isEmpty()) {
            // edited sample values are written to the column; then both layers are re-embedded
            catalog.updateSampleValues((String) cols.get(0).get("id"), Json.write(body.get("sample_values")));
        }
        if (samples || values.containsKey("name") || values.containsKey("description")) {
            if (!cols.isEmpty()) {
                dataEmbedder.embedColumns(List.of((String) cols.get(0).get("id")));
            }
            embedder.refreshTerm(termId);
        }
        Map<String, Object> view = attributeView(semantic.attributeById(attrId).orElse(a),
                governance.tagNamesFor("column_attribute"));
        view.put("term_certification", certification(term(termId)));
        return Map.of("data", view);
    }

    // ------------------------------------------------------------------ SQL attributes (FR-API-04)

    private Map<String, Object> sqlAttrView(Map<String, Object> a) {
        Map<String, Object> m = new LinkedHashMap<>(a);
        m.put("tags", CatalogController.chips(governance.tagNamesFor("sql_attribute").getOrDefault((String) a.get("id"),
                List.of())));
        m.put("zones", List.of());
        return m;
    }

    @GetMapping("/terms/{termId}/sql-attributes")
    public Map<String, Object> termSqlAttributes(@PathVariable String termId) {
        term(termId);
        List<Map<String, Object>> data = semantic.sqlAttributesOfTerm(termId).stream().map(this::sqlAttrView).toList();
        return Map.of("data", data, "count", data.size(), "total", data.size());
    }

    @GetMapping("/sql-attributes")
    public Map<String, Object> sqlAttributes(@RequestParam(required = false) String query) {
        List<Map<String, Object>> data = semantic.sqlAttributes().stream()
                .filter(a -> query == null || String.valueOf(a.get("name")).toLowerCase().contains(query.toLowerCase()))
                .map(this::sqlAttrView).toList();
        return Map.of("data", data, "count", data.size(), "total", data.size());
    }

    private Map<String, Object> sqlAttr(String id) {
        if (!Ids.isUuid(id)) {
            throw ApiException.notFound("SQL attribute not found");
        }
        return semantic.sqlAttributeById(id).orElseThrow(() -> ApiException.notFound("SQL attribute not found"));
    }

    @GetMapping("/sql-attributes/{id}")
    public Map<String, Object> sqlAttribute(@PathVariable String id) {
        return Map.of("data", sqlAttrView(sqlAttr(id)));
    }

    /** Parse errors are 422, not {@code valid: false}. */
    @PostMapping("/sql-attributes/validate")
    public Map<String, Object> validateSqlAttribute(@RequestBody Map<String, Object> body) {
        String expr = AppController.str(body.get("expression"));
        if (expr == null) {
            throw ApiException.unprocessable("expression is required");
        }
        parseExpression(expr);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", true);
        out.put("expression", expr);
        return Map.of("data", out);
    }

    static void parseExpression(String expr) {
        try {
            if (expr.strip().toUpperCase().startsWith("SELECT") || expr.strip().toUpperCase().startsWith("WITH")) {
                String v = ReadOnlySql.violation(expr);
                if (v != null) {
                    throw new IllegalArgumentException(v);
                }
            } else {
                CCJSqlParserUtil.parseExpression(expr);
            }
        } catch (Exception e) {
            throw ApiException.unprocessable("Expression could not be parsed: "
                    + String.valueOf(e.getMessage()).lines().findFirst().orElse(""));
        }
    }

    @PostMapping("/sql-attributes")
    public ResponseEntity<Map<String, Object>> createSqlAttribute(@RequestBody Map<String, Object> body) {
        String name = AppController.str(body.get("name"));
        String description = AppController.str(body.get("description"));
        String expression = AppController.str(body.get("expression"));
        String termId = AppController.str(body.get("term_id"));
        if (name == null || description == null || expression == null || termId == null) {
            throw ApiException.unprocessable("name, description, expression and term_id are required");
        }
        term(termId);
        parseExpression(expression);
        if (semantic.sqlAttributeByName(name).isPresent()) {
            throw ApiException.conflict("A SQL attribute with this name already exists");
        }
        String source = body.get("source") == null ? "manual" : String.valueOf(body.get("source"));
        String id = semantic.insertSqlAttribute(name, description, expression, source, termId,
                AppController.str(body.get("owner_id")), user.id(), time.now());
        embedSqlAttribute(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", sqlAttrView(sqlAttr(id))));
    }

    /** PUT replaces the expression, re-parses and re-embeds. */
    @PutMapping("/sql-attributes/{id}")
    public Map<String, Object> replaceSqlAttribute(@PathVariable String id, @RequestBody Map<String, Object> body) {
        sqlAttr(id);
        String expression = AppController.str(body.get("expression"));
        if (expression == null) {
            throw ApiException.unprocessable("expression is required");
        }
        parseExpression(expression);
        Map<String, Object> values = new HashMap<>();
        values.put("expression", expression);
        for (String k : List.of("name", "description")) {
            if (body.get(k) != null) {
                values.put(k, body.get(k));
            }
        }
        values.put("description_suggestion", null);
        values.put("modified", Timestamp.from(time.now()));
        values.put("modified_by", user.id());
        semantic.patchSqlAttribute(id, values);
        embedSqlAttribute(id);
        return Map.of("data", sqlAttrView(sqlAttr(id)));
    }

    /** PATCH changes only metadata, never the expression. */
    @PatchMapping("/sql-attributes/{id}")
    public Map<String, Object> patchSqlAttribute(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Map<String, Object> a = sqlAttr(id);
        if (body.containsKey("expression")) {
            throw ApiException.unprocessable("PATCH does not change the expression; use PUT");
        }
        Map<String, Object> values = new HashMap<>();
        for (String k : List.of("name", "description", "certified", "owner_id")) {
            if (body.containsKey(k)) {
                values.put(k, body.get(k));
            }
        }
        if (values.isEmpty()) {
            throw ApiException.unprocessable("Nothing to update");
        }
        values.put("modified", Timestamp.from(time.now()));
        values.put("modified_by", user.id());
        semantic.patchSqlAttribute(id, values);
        if (values.containsKey("name") || values.containsKey("description")) {
            embedSqlAttribute(id);
        }
        Map<String, Object> view = sqlAttrView(sqlAttr(id));
        if (a.get("term_id") != null) {
            view.put("term_certification", certification(term((String) a.get("term_id"))));
        }
        return Map.of("data", view);
    }

    /** Deletes the attribute with its edges and the embedding. */
    @DeleteMapping("/sql-attributes/{id}")
    public Map<String, Object> deleteSqlAttribute(@PathVariable String id) {
        sqlAttr(id);
        semantic.deleteSqlAttribute(id);
        vectors.deleteByIds(VectorStore.SEMANTIC_LAYER, List.of(id));
        return Map.of("data", Map.of("id", id));
    }

    @GetMapping("/sql-attributes/{id}/description-suggestion")
    public Map<String, Object> describe(@PathVariable String id) {
        Map<String, Object> a = sqlAttr(id);
        String cached = (String) a.get("description_suggestion");
        if (cached == null || cached.isBlank()) {
            cached = llm.text(ModelRole.NON_REASONING, List.of(LlmMessage.user(
                    "Write one or two sentences in business language describing what this SQL expression of the business"
                            + " term '" + a.get("term_name") + "' calculates or filters. Return only the description.\n\n"
                            + "Name: " + a.get("name") + "\nExpression: " + a.get("expression")))).strip();
            semantic.patchSqlAttribute(id, Map.of("description_suggestion", cached));
        }
        return Map.of("data", Map.of("description_suggestion", cached));
    }

    private void embedSqlAttribute(String id) {
        Map<String, Object> a = sqlAttr(id);
        String dbName = databaseOfTerm((String) a.get("term_id")).orElse(null);
        embedder.write(dbName, List.of(SemanticEmbedder.sqlAttributeRow(dbName, a)));
    }

    private Optional<String> databaseOfTerm(String termId) {
        if (termId == null) {
            return Optional.empty();
        }
        return semantic.tablesOfTerm(termId).stream().map(t -> (String) t.get("database_name")).findFirst();
    }

    // ------------------------------------------------------------------ custom analyses (FR-GOV-11)

    @GetMapping("/custom-analyses")
    public Map<String, Object> customAnalyses(@RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int skip, @RequestParam(defaultValue = "100") int limit) {
        List<Map<String, Object>> all = semantic.customAnalyses().stream().filter(a -> query == null
                || String.valueOf(a.get("name")).toLowerCase().contains(query.toLowerCase())).toList();
        List<Map<String, Object>> page = all.stream().skip(skip).limit(limit).toList();
        return Map.of("data", page, "count", page.size(), "total", all.size());
    }

    /** Parse or resolution errors are 422. */
    @PostMapping("/custom-analyses/validate")
    public Map<String, Object> validateAnalysis(@RequestBody Map<String, Object> body) {
        String sql = AppController.str(body.get("sql"));
        if (sql == null) {
            throw ApiException.unprocessable("sql is required");
        }
        String v = ReadOnlySql.violation(sql);
        if (v != null) {
            throw ApiException.unprocessable(v);
        }
        String dbName = resolveDatabase(sql);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", true);
        out.put("database_name", dbName);
        return Map.of("data", out);
    }

    /** The database whose catalog contains the referenced tables; 422 when none resolves. */
    private String resolveDatabase(String sql) {
        Set<String> tables = new LinkedHashSet<>();
        try {
            new net.sf.jsqlparser.util.TablesNamesFinder<>().getTables(CCJSqlParserUtil.parse(
                    ReadOnlySql.stripTrailingSemicolons(sql))).forEach(t -> tables.add(t.replace("\"", "").toLowerCase()));
        } catch (Exception e) {
            throw ApiException.unprocessable("SQL could not be parsed");
        }
        for (Map<String, Object> d : catalog.databases()) {
            Set<String> known = new java.util.HashSet<>();
            catalog.tablesOfDatabase((String) d.get("id")).forEach(t -> {
                known.add((t.get("schema_name") + "." + t.get("name")).toLowerCase());
                known.add(String.valueOf(t.get("name")).toLowerCase());
            });
            if (known.containsAll(tables)) {
                return (String) d.get("name");
            }
        }
        throw ApiException.unprocessable("Tables of the SQL could not be resolved in the catalog: " + tables);
    }

    @PostMapping("/custom-analyses")
    public ResponseEntity<Map<String, Object>> createAnalysis(@RequestBody Map<String, Object> body) {
        String name = AppController.str(body.get("name"));
        String sql = AppController.str(body.get("sql"));
        if (name == null || sql == null) {
            throw ApiException.unprocessable("name and sql are required");
        }
        if (semantic.customAnalyses().stream().anyMatch(a -> name.equalsIgnoreCase(String.valueOf(a.get("name")))
                || sql.strip().equals(String.valueOf(a.get("sql")).strip()))) {
            throw ApiException.conflict("A custom analysis with this name or SQL already exists");
        }
        validateAnalysis(body);
        String dbName = resolveDatabase(sql);
        String dbId = catalog.databaseByName(dbName).map(d -> (String) d.get("id")).orElse(null);
        String id = semantic.insertCustomAnalysis(name, AppController.str(body.get("description")), sql, dbId, user.id(),
                time.now());
        Map<String, Object> a = semantic.customAnalysisById(id).orElseThrow();
        embedder.write(dbName, List.of(SemanticEmbedder.customAnalysisRow(dbName, a)));
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", a));
    }

    @PutMapping("/custom-analyses/{id}")
    public Map<String, Object> updateAnalysis(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Map<String, Object> existing = semantic.customAnalysisById(id)
                .orElseThrow(() -> ApiException.notFound("Custom analysis not found"));
        String name = AppController.str(body.getOrDefault("name", existing.get("name")));
        String sql = AppController.str(body.getOrDefault("sql", existing.get("sql")));
        if (semantic.customAnalyses().stream().anyMatch(a -> !id.equals(a.get("id"))
                && (String.valueOf(a.get("name")).equalsIgnoreCase(name) || String.valueOf(a.get("sql")).strip()
                        .equals(sql == null ? "" : sql.strip())))) {
            throw ApiException.conflict("A custom analysis with this name or SQL already exists");
        }
        String dbName = resolveDatabase(sql);
        semantic.updateCustomAnalysis(id, name, AppController.str(body.getOrDefault("description",
                existing.get("description"))), sql, user.id(), time.now());
        Map<String, Object> a = semantic.customAnalysisById(id).orElseThrow();
        embedder.write(dbName, List.of(SemanticEmbedder.customAnalysisRow(dbName, a)));
        return Map.of("data", a);
    }

    @DeleteMapping("/custom-analyses/{id}")
    public Map<String, Object> deleteAnalysis(@PathVariable String id) {
        if (semantic.deleteCustomAnalysis(id) == 0) {
            throw ApiException.notFound("Custom analysis not found");
        }
        vectors.deleteByIds(VectorStore.SEMANTIC_LAYER, List.of(id));
        return Map.of("data", Map.of("id", id));
    }

    // ------------------------------------------------------------------ PQL analyses

    @GetMapping("/pql-analyses")
    public Map<String, Object> pqlAnalyses() {
        List<Map<String, Object>> data = semantic.pqlAnalyses();
        return Map.of("data", data, "count", data.size(), "total", data.size());
    }

    @PostMapping("/pql-analyses")
    public ResponseEntity<Map<String, Object>> createPql(@RequestBody Map<String, Object> body) {
        String name = AppController.str(body.get("name"));
        if (name == null) {
            throw ApiException.unprocessable("name is required");
        }
        String id = semantic.insertPql(name, AppController.str(body.get("description")), AppController.str(body.get("pql")),
                time.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", semantic.pqlAnalyses().stream()
                .filter(p -> id.equals(p.get("id"))).findFirst().orElseThrow()));
    }

    @PutMapping("/pql-analyses/{id}")
    public Map<String, Object> updatePql(@PathVariable String id, @RequestBody Map<String, Object> body) {
        if (semantic.updatePql(id, AppController.str(body.get("name")), AppController.str(body.get("description")),
                AppController.str(body.get("pql")), time.now()) == 0) {
            throw ApiException.notFound("PQL analysis not found");
        }
        return Map.of("data", semantic.pqlAnalyses().stream().filter(p -> id.equals(p.get("id"))).findFirst().orElseThrow());
    }

    @DeleteMapping("/pql-analyses/{id}")
    public Map<String, Object> deletePql(@PathVariable String id) {
        if (semantic.deletePql(id) == 0) {
            throw ApiException.notFound("PQL analysis not found");
        }
        return Map.of("data", Map.of("id", id));
    }

    /** Validation helper used by the custom analysis test execution. */
    Optional<SourceDatabase> connector(String dbName) {
        return connections.answeringConnectors().stream().filter(c -> c.databaseName().equals(dbName)).findFirst();
    }
}
