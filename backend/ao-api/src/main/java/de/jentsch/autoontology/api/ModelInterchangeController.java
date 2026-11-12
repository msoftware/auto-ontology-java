// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.api;

import de.jentsch.autoontology.common.ApiException;
import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.CurrentUser;
import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.semantic.SemanticEmbedder;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.store.GovernanceRepository;
import de.jentsch.autoontology.store.SemanticRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Model import/export in the native YAML format (FR-GOV-10, server/model_interchange). Matching on
 * import is by {@code imported_id OR id}; {@code replace} defaults to true. The Apache Ossie format
 * is not implemented in this version and answers with 422.
 */
@RestController
@RequestMapping("${ao.api-prefix:/api}/model")
public class ModelInterchangeController {
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private final CatalogRepository catalog;
    private final SemanticRepository semantic;
    private final GovernanceRepository gov;
    private final SemanticEmbedder embedder;
    private final CurrentUser user;
    private final AoTime time;

    public ModelInterchangeController(CatalogRepository catalog, SemanticRepository semantic, GovernanceRepository gov,
            SemanticEmbedder embedder, CurrentUser user, AoTime time) {
        this.catalog = catalog;
        this.semantic = semantic;
        this.gov = gov;
        this.embedder = embedder;
        this.user = user;
        this.time = time;
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/export")
    public ResponseEntity<String> export(@RequestBody Map<String, Object> body) {
        String format = String.valueOf(body.getOrDefault("format", "auto_ontology"));
        if (!"auto_ontology".equals(format)) {
            throw ApiException.unprocessable("Export format " + format + " is not supported (only auto_ontology)");
        }
        List<String> ids = (List<String>) body.getOrDefault("databases", List.of());
        List<Map<String, Object>> dbs = new ArrayList<>();
        List<Map<String, Object>> terms = new ArrayList<>();
        java.util.Set<String> seenTerms = new java.util.HashSet<>();
        for (Map<String, Object> d : catalog.databases()) {
            if (!ids.isEmpty() && !ids.contains(d.get("id"))) {
                continue;
            }
            String dbId = (String) d.get("id");
            List<Map<String, Object>> tables = new ArrayList<>();
            for (Map<String, Object> t : catalog.tablesOfDatabase(dbId)) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("id", t.get("id"));
                tm.put("schema", t.get("schema_name"));
                tm.put("name", t.get("name"));
                tm.put("description", t.get("description"));
                tm.put("description_certified", t.get("description_certified"));
                tm.put("owner_id", t.get("owner_id"));
                tm.put("columns", catalog.columnsOfTable((String) t.get("id")).stream().map(c -> {
                    Map<String, Object> cm = new LinkedHashMap<>();
                    cm.put("id", c.get("id"));
                    cm.put("name", c.get("name"));
                    cm.put("data_type", c.get("data_type"));
                    cm.put("description", c.get("description"));
                    cm.put("description_certified", c.get("description_certified"));
                    cm.put("sample_values", Json.readList((String) c.get("sample_values")));
                    return cm;
                }).toList());
                tables.add(tm);
                for (Map<String, Object> term : semantic.termsOfTable((String) t.get("id"))) {
                    String tid = (String) term.get("id");
                    if (seenTerms.add(tid)) {
                        Map<String, Object> x = new LinkedHashMap<>(term);
                        x.put("tables", semantic.tablesOfTerm(tid).stream().map(tb -> tb.get("database_name") + "."
                                + tb.get("schema_name") + "." + tb.get("name")).toList());
                        x.put("column_attributes", semantic.attributesOfTerm(tid).stream().map(a -> {
                            Map<String, Object> am = new LinkedHashMap<>();
                            am.put("id", a.get("id"));
                            am.put("name", a.get("name"));
                            am.put("description", a.get("description"));
                            am.put("certified", a.get("certified"));
                            am.put("column", semantic.columnsOfAttribute((String) a.get("id")).stream().findFirst()
                                    .map(c -> c.get("database_name") + "." + c.get("schema_name") + "." + c.get("table_name")
                                            + "." + c.get("name")).orElse(null));
                            return am;
                        }).toList());
                        x.put("sql_attributes", semantic.sqlAttributesOfTerm(tid).stream().map(a -> {
                            // LinkedHashMap: missing values are exported as YAML null, not as the text "null"
                            Map<String, Object> sm = new LinkedHashMap<>();
                            for (String k : List.of("id", "name", "description", "expression", "source", "certified")) {
                                sm.put(k, a.get(k));
                            }
                            return sm;
                        }).toList());
                        terms.add(x);
                    }
                }
            }
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("id", dbId);
            dm.put("name", d.get("name"));
            dm.put("dialect", d.get("dialect"));
            dm.put("description", d.get("description"));
            dm.put("tables", tables);
            dbs.add(dm);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("format", "auto_ontology");
        doc.put("version", 1);
        doc.put("databases", dbs);
        doc.put("terms", terms);
        doc.put("custom_analyses", semantic.customAnalyses());
        doc.put("groups", gov.groups());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/x-yaml"))
                .header("Content-Disposition", "attachment; filename=\"auto-ontology-model.yaml\"")
                .body(YAML.writeValueAsString(doc));
    }

    @SuppressWarnings("unchecked")
    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> importModel(@RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "true") boolean replace) throws IOException {
        Map<String, Object> doc;
        try {
            doc = YAML.readValue(file.getBytes(), Map.class);
        } catch (RuntimeException e) {
            throw ApiException.unprocessable("The file is not valid YAML");
        }
        if (doc == null) {
            throw ApiException.unprocessable("The file is empty");
        }
        if (doc.containsKey("semantic_model") || doc.containsKey("ossie")) {
            throw ApiException.unprocessable("The Ossie format is not supported in this version");
        }
        int terms = 0;
        int attributes = 0;
        int sqlAttributes = 0;
        for (Map<String, Object> t : list(doc.get("terms"))) {
            if (AppController.str(t.get("name")) == null) {
                throw ApiException.unprocessable("Every term needs a name");
            }
            String name = String.valueOf(t.get("name"));
            Optional<Map<String, Object>> existing = semantic.termById(String.valueOf(t.get("id")))
                    .or(() -> semantic.termByName(name));
            String termId;
            if (existing.isPresent()) {
                termId = (String) existing.get().get("id");
                if (replace) {
                    semantic.patchTerm(termId, Map.of("description", t.get("description") == null ? ""
                                    : String.valueOf(t.get("description")),
                            "name_certified", Boolean.TRUE.equals(t.get("name_certified")),
                            "description_certified", Boolean.TRUE.equals(t.get("description_certified"))));
                }
            } else {
                termId = semantic.mergeTerm(name, AppController.text(t.get("description")),
                        t.get("synonyms") instanceof List<?> syn ? syn.stream().map(String::valueOf).toList() : List.of(),
                        "semantic", time.now());
                if (t.get("id") != null) {
                    semantic.patchTerm(termId, Map.of("imported_id", String.valueOf(t.get("id"))));
                }
            }
            terms++;
            for (Object q : t.get("tables") instanceof List<?> l ? l : List.of()) {
                findTable(AppController.text(q)).ifPresent(tb -> semantic.linkTableTerm((String) tb.get("id"), termId));
            }
            for (Map<String, Object> a : list(t.get("column_attributes"))) {
                Optional<Map<String, Object>> col = findColumn(AppController.text(a.get("column")));
                if (col.isPresent() && AppController.str(a.get("name")) != null) {
                    semantic.mergeColumnAttribute(String.valueOf(a.get("name")), AppController.text(a.get("description")),
                            (String) col.get().get("data_type"), (String) col.get().get("name"), name, termId,
                            (String) col.get().get("table_id"), (String) col.get().get("id"), time.now());
                    attributes++;
                }
            }
            for (Map<String, Object> a : list(t.get("sql_attributes"))) {
                String attrName = AppController.str(a.get("name"));
                String expression = AppController.str(a.get("expression"));
                if (attrName == null || expression == null) {
                    // name and expression are NOT NULL in the store: an incomplete entry is skipped
                    continue;
                }
                Optional<Map<String, Object>> sa = semantic.sqlAttributeByName(attrName);
                if (sa.isPresent()) {
                    if (replace) {
                        semantic.patchSqlAttribute((String) sa.get().get("id"), Map.of("expression", expression,
                                "description", a.get("description") == null ? "" : String.valueOf(a.get("description"))));
                    }
                } else {
                    String source = AppController.text(a.get("source"));
                    semantic.insertSqlAttribute(attrName, AppController.text(a.get("description")), expression,
                            SemanticController.SQL_ATTRIBUTE_SOURCES.contains(source) ? source : "manual", termId, null,
                            user.id(), time.now());
                }
                sqlAttributes++;
            }
            embedder.refreshTerm(termId);
        }
        return Map.of("data", Map.of("terms", terms, "column_attributes", attributes, "sql_attributes", sqlAttributes,
                "replace", replace));
    }

    /** The maps of a YAML list; anything else (missing key, scalar, list of scalars) yields no entries. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
        }
        return out;
    }

    private Optional<Map<String, Object>> findTable(String qualified) {
        if (qualified == null) {
            return Optional.empty();
        }
        String[] p = qualified.split("\\.");
        if (p.length < 3) {
            return Optional.empty();
        }
        return catalog.databaseByName(p[0]).flatMap(d -> catalog.tablesOfDatabase((String) d.get("id")).stream()
                .filter(t -> p[1].equals(t.get("schema_name")) && p[2].equals(t.get("name"))).findFirst());
    }

    private Optional<Map<String, Object>> findColumn(String qualified) {
        if (qualified == null) {
            return Optional.empty();
        }
        int dot = qualified.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        return findTable(qualified.substring(0, dot)).flatMap(t -> catalog.columnsOfTable((String) t.get("id")).stream()
                .filter(c -> qualified.substring(dot + 1).equals(c.get("name"))).findFirst());
    }
}
