// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.api;

import de.jentsch.autoontology.agent.EntityCoverageService;
import de.jentsch.autoontology.common.ApiException;
import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.CurrentUser;
import de.jentsch.autoontology.common.Ids;
import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.connectors.ConnectionSpec;
import de.jentsch.autoontology.connectors.DbErrors;
import de.jentsch.autoontology.connectors.SourceDatabase;
import de.jentsch.autoontology.ingestion.ConnectionService;
import de.jentsch.autoontology.ingestion.DataLayerEmbedder;
import de.jentsch.autoontology.ingestion.IngestService;
import de.jentsch.autoontology.llm.LlmGateway;
import de.jentsch.autoontology.semantic.SemanticCompilationService;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.store.GovernanceRepository;
import de.jentsch.autoontology.store.SemanticRepository;
import de.jentsch.autoontology.store.StoreJdbc;
import de.jentsch.autoontology.vector.VectorStore;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/** Health, connections, catalog tree, semantic compilation, answerability (sections 5, 11, 13.2). */
@RestController
@RequestMapping("${ao.api-prefix:/api}")
public class CatalogController {
    private static final Logger log = LoggerFactory.getLogger(CatalogController.class);

    private final CatalogRepository catalog;
    private final SemanticRepository semantic;
    private final GovernanceRepository governance;
    private final ConnectionService connections;
    private final IngestService ingest;
    private final SemanticCompilationService compilation;
    private final EntityCoverageService coverage;
    private final DataLayerEmbedder dataEmbedder;
    private final VectorStore vectors;
    private final LlmGateway llm;
    private final StoreJdbc db;
    private final CurrentUser user;
    private final AoTime time;

    public CatalogController(CatalogRepository catalog, SemanticRepository semantic, GovernanceRepository governance,
            ConnectionService connections, IngestService ingest, SemanticCompilationService compilation,
            EntityCoverageService coverage, DataLayerEmbedder dataEmbedder, VectorStore vectors, LlmGateway llm,
            StoreJdbc db, CurrentUser user, AoTime time) {
        this.catalog = catalog;
        this.semantic = semantic;
        this.governance = governance;
        this.connections = connections;
        this.ingest = ingest;
        this.compilation = compilation;
        this.coverage = coverage;
        this.dataEmbedder = dataEmbedder;
        this.vectors = vectors;
        this.llm = llm;
        this.db = db;
        this.user = user;
        this.time = time;
    }

    // ------------------------------------------------------------------ health

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> h = new LinkedHashMap<>();
        boolean store;
        try {
            db.count("SELECT COUNT(*) FROM catalog_database", Map.of());
            store = true;
        } catch (RuntimeException e) {
            store = false;
        }
        Map<String, Object> vec = vectors.health();
        h.put("status", store && Boolean.TRUE.equals(vec.get("ok")) ? "ok" : "degraded");
        h.put("store", store);
        h.put("vector_store", vec);
        h.put("llm_configured", llm.configured());
        return h;
    }

    // ------------------------------------------------------------------ connections (FR-CON-04/06)

    @GetMapping("/connections")
    public Map<String, Object> connections() {
        List<Map<String, Object>> data = connections.publicConnections();
        return Map.of("data", data, "count", data.size());
    }

    @GetMapping("/connections/source")
    public boolean source() {
        return connections.isEnvSource();
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/connections")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Map<String, Object> c = (Map<String, Object>) body.get("connection");
        if (c == null) {
            throw ApiException.unprocessable("connection is required");
        }
        ConnectionSpec spec = connections.fromJson(null, c);
        if (catalog.databaseByName(spec.name()).map(r -> r.get("connection") != null).orElse(false)) {
            throw ApiException.conflict("A connection for " + spec.name() + " already exists");
        }
        test(spec);
        Map<String, Object> stored = new LinkedHashMap<>(c);
        stored.put("database_name", spec.name());
        connections.save(spec, stored);
        // FR-ING-03: a new connection triggers a non-blocking ingest, then rule replay
        Thread.ofVirtual().name("ao-ingest-new").start(() -> {
            try {
                ingest.run(spec);
            } catch (RuntimeException e) {
                log.warn("Ingest of new connection {} failed: {}", spec.name(), e.getMessage());
            }
        });
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data",
                ConnectionService.publicConnection(spec.name(), stored)));
    }

    @SuppressWarnings("unchecked")
    @PutMapping("/connections/{databaseName}")
    public Map<String, Object> update(@PathVariable String databaseName, @RequestBody Map<String, Object> body) {
        Map<String, Object> existing = catalog.databaseByName(databaseName).map(r -> (String) r.get("connection"))
                .filter(s -> s != null).map(Json::readMap)
                .orElseThrow(() -> ApiException.notFound("Connection " + databaseName + " not found"));
        Map<String, Object> merged = new LinkedHashMap<>(existing);
        merged.putAll((Map<String, Object>) body.getOrDefault("connection", Map.of()));
        merged.put("database_name", databaseName);
        ConnectionSpec spec = connections.fromJson(databaseName, merged);
        test(spec);
        connections.save(spec, merged);
        Thread.ofVirtual().start(() -> {
            try {
                ingest.run(spec);
            } catch (RuntimeException e) {
                log.warn("Ingest of updated connection {} failed: {}", databaseName, e.getMessage());
            }
        });
        return Map.of("data", ConnectionService.publicConnection(databaseName, merged));
    }

    @DeleteMapping("/connections/{databaseName}")
    public Map<String, Object> delete(@PathVariable String databaseName) {
        String name = ingest.deleteDatabase(databaseName)
                .orElseThrow(() -> ApiException.notFound("Connection " + databaseName + " not found"));
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("database_name", name);
        d.put("connection", null);
        return Map.of("data", d);
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/connections/test")
    public Map<String, Object> testConnection(@RequestBody Map<String, Object> body) {
        Map<String, Object> c = (Map<String, Object>) body.get("connection");
        if (c == null) {
            throw ApiException.unprocessable("connection is required");
        }
        String replacing = AppController.str(body.get("replacing"));
        Map<String, Object> merged = new LinkedHashMap<>(c);
        if (replacing != null && merged.get("password") == null) {
            catalog.databaseByName(replacing).map(r -> (String) r.get("connection")).filter(s -> s != null)
                    .map(Json::readMap).ifPresent(old -> merged.put("password", old.get("password")));
        }
        List<String> schemas = test(connections.fromJson(replacing, merged));
        return Map.of("success", true, "schemas", schemas);
    }

    private List<String> test(ConnectionSpec spec) {
        try (SourceDatabase sdb = connections.openConnector(spec)) {
            sdb.execute("SELECT 1" + ("oracle".equals(sdb.dialect()) ? " FROM DUAL" : ""), List.of(), 15, 1);
            return sdb.listSchemas();
        } catch (DbErrors.SourceQueryException e) {
            throw ApiException.badRequest("Connection failed (" + e.kind() + "): " + e.getMessage());
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Connection failed: " + DbErrors.shortMessage(e));
        }
    }

    // ------------------------------------------------------------------ catalog tree (FR-GOV-04)

    @GetMapping("/datasources/dbs")
    public Map<String, Object> databases() {
        List<Map<String, Object>> data = catalog.databases().stream().map(d -> {
            List<Map<String, Object>> schemas = schemaSummaries((String) d.get("id"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.get("id"));
            m.put("name", d.get("name"));
            m.put("description", d.get("description"));
            m.put("description_certified", d.get("description_certified"));
            m.put("owner_id", d.get("owner_id"));
            m.put("dialect", d.get("dialect"));
            m.put("pulled", d.get("pulled"));
            m.put("num_of_schemas", schemas.size());
            m.put("schemas", schemas);
            return m;
        }).toList();
        return Map.of("data", data, "count", data.size());
    }

    private List<Map<String, Object>> schemaSummaries(String dbId) {
        return catalog.schemas(dbId).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.get("id"));
            m.put("schema_name", s.get("name"));
            m.put("description", s.get("description"));
            m.put("owner_id", s.get("owner_id"));
            m.put("tables_count", catalog.tablesOfSchema((String) s.get("id")).size());
            return m;
        }).toList();
    }

    @GetMapping("/schemas/{dbId}")
    public Map<String, Object> schemas(@PathVariable String dbId) {
        List<Map<String, Object>> schemas = Ids.isUuid(dbId) ? schemaSummaries(dbId) : List.of();
        return Map.of("schemas", schemas, "schemas_count", schemas.size());
    }

    @GetMapping("/tables/{schemaId}")
    public Map<String, Object> tables(@PathVariable String schemaId) {
        Map<String, List<String>> tags = governance.tagNamesFor("table");
        List<Map<String, Object>> data = (Ids.isUuid(schemaId) ? catalog.tablesOfSchema(schemaId) : List.<Map<String, Object>>of())
                .stream().map(t -> {
                    String id = (String) t.get("id");
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", id);
                    m.put("name", t.get("name"));
                    m.put("schema_name", t.get("schema_name"));
                    m.put("database_name", t.get("database_name"));
                    m.put("description", t.get("description"));
                    m.put("description_certified", t.get("description_certified"));
                    m.put("owner_id", t.get("owner_id"));
                    m.put("table_type", t.get("table_type"));
                    m.put("is_junction_table", t.get("is_junction_table"));
                    m.put("columns_count", catalog.columnsOfTable(id).size());
                    m.put("terms_count", semantic.termsOfTable(id).size());
                    m.put("sql_count", catalog.sqlForTables(List.of(id), 1000).size());
                    m.put("tags", chips(tags.getOrDefault(id, List.of())));
                    return m;
                }).toList();
        return Map.of("data", data, "count", data.size());
    }

    @GetMapping("/columns/{tableId}")
    public Map<String, Object> columns(@PathVariable String tableId, @RequestParam(defaultValue = "0") int skip,
            @RequestParam(defaultValue = "500") int limit) {
        Optional<Map<String, Object>> table = Ids.isUuid(tableId) ? catalog.tableById(tableId) : Optional.empty();
        if (table.isEmpty()) {
            throw ApiException.notFound("Table not found");
        }
        Map<String, List<String>> tags = governance.tagNamesFor("column");
        List<Map<String, Object>> all = catalog.columnsOfTable(tableId);
        List<Map<String, Object>> page = all.stream().skip(skip).limit(limit).map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.get("id"));
            m.put("column_name", c.get("name"));
            m.put("data_type", c.get("data_type"));
            m.put("description", c.get("description"));
            m.put("description_certified", c.get("description_certified"));
            m.put("ordinal_position", c.get("ordinal_position"));
            m.put("is_nullable", c.get("is_nullable"));
            m.put("is_unique", c.get("is_unique"));
            m.put("format", c.get("format"));
            m.put("sample_values", Json.readList((String) c.get("sample_values")).stream().map(String::valueOf).toList());
            m.put("tags", chips(tags.getOrDefault((String) c.get("id"), List.of())));
            return m;
        }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("columns", page);
        data.put("columns_count", all.size());
        data.put("database_name", table.get().get("database_name"));
        data.put("schema_name", table.get().get("schema_name"));
        data.put("table_name", table.get().get("name"));
        data.put("table_type", table.get().get("table_type"));
        return Map.of("data", data, "count", page.size(), "total", all.size());
    }

    static List<Map<String, Object>> chips(List<String> names) {
        return names.stream().map(n -> Map.<String, Object>of("id", n, "name", n)).toList();
    }

    /**
     * PATCH /api/nodes/{node_id}: description, certification, owner, sample values. A change that
     * cannot be stored is an error, never a silent 200 (FR-GOV-04).
     */
    @SuppressWarnings("unchecked")
    @PatchMapping("/nodes/{nodeId}")
    public Map<String, Object> patchNode(@PathVariable String nodeId, @RequestBody Map<String, Object> body) {
        if (!Ids.isUuid(nodeId)) {
            throw ApiException.notFound("Node not found");
        }
        String table = null;
        for (String t : List.of("catalog_database", "catalog_schema", "catalog_table", "catalog_column")) {
            if (db.count("SELECT COUNT(*) FROM " + t + " WHERE id = :id", Map.of("id", nodeId)) > 0) {
                table = t;
                break;
            }
        }
        if (table == null) {
            throw ApiException.notFound("Node not found");
        }
        Map<String, Object> values = new HashMap<>();
        if (body.containsKey("description")) {
            values.put("description", body.get("description"));
        }
        if (body.containsKey("description_certified")) {
            values.put("description_certified", Boolean.TRUE.equals(body.get("description_certified")));
        }
        if (body.containsKey("owner_id")) {
            if ("catalog_column".equals(table)) {
                throw ApiException.unprocessable("Columns inherit their owner from the table");
            }
            values.put("owner_id", body.get("owner_id"));
        }
        if (body.containsKey("sample_values")) {
            if (!"catalog_column".equals(table)) {
                throw ApiException.unprocessable("Only columns have sample values");
            }
            String type = String.valueOf(catalog.columnById(nodeId).map(c -> c.get("data_type")).orElse(""));
            if (!editableSamples(type)) {
                throw ApiException.unprocessable("Sample values are only editable for text, JSON and XML columns");
            }
            values.put("sample_values", Json.write(body.get("sample_values")));
        }
        if (values.isEmpty()) {
            throw ApiException.unprocessable("Nothing to update");
        }
        // a human edit: modified* is set (compilation writes never count as a change)
        values.put("modified", java.sql.Timestamp.from(time.now()));
        values.put("modified_by", user.id());
        int n = catalog.patchNode(table, nodeId, values);
        if (n != 1) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "The change could not be stored");
        }
        if ("catalog_column".equals(table) && (values.containsKey("description") || values.containsKey("sample_values"))) {
            dataEmbedder.embedColumns(List.of(nodeId));
        }
        Map<String, Object> row = db.one("SELECT * FROM " + table + " WHERE id = :id", Map.of("id", nodeId)).orElseThrow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", nodeId);
        out.put("description", row.get("description"));
        out.put("description_certified", row.get("description_certified"));
        out.put("owner_id", row.get("owner_id"));
        out.put("sample_values", row.containsKey("sample_values")
                ? Json.readList((String) row.get("sample_values")).stream().map(String::valueOf).toList() : null);
        return out;
    }

    /** FR-GOV-03 (utils/column_types.py): text, JSON and XML types only. */
    static boolean editableSamples(String type) {
        String t = type.toLowerCase(java.util.Locale.ROOT);
        return t.contains("char") || t.contains("text") || t.contains("string") || t.contains("json") || t.contains("xml")
                || t.contains("clob");
    }

    // ------------------------------------------------------------------ semantic compilation (FR-SEM-07/08)

    @GetMapping("/semantic-compilation/status")
    public Map<String, Object> status() {
        return compilation.status();
    }

    /** Destructive and asynchronous: 202 does not mean finished. */
    @PostMapping("/semantic-compilation/reset")
    public ResponseEntity<Map<String, Object>> reset(@RequestParam(name = "database_name", required = false) String dbName) {
        Thread.ofVirtual().name("ao-semantic-reset").start(() -> compilation.reset(dbName));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "accepted"));
    }

    /** Documented extension: the backend-only trigger as a public endpoint (FR-SEM-07). */
    @PostMapping("/semantic-compilation/trigger")
    public ResponseEntity<Map<String, Object>> trigger() {
        compilation.trigger();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "accepted"));
    }

    // ------------------------------------------------------------------ answerability (FR-AGT-09)

    @PostMapping("/question-entity-coverage")
    public Map<String, Object> coverage(@RequestBody Map<String, Object> body) {
        String q = AppController.str(body.get("question"));
        if (q == null) {
            throw ApiException.unprocessable("question is required");
        }
        Double max = body.get("max_distance") instanceof Number n ? n.doubleValue() : null;
        return Map.of("data", coverage.coverage(q, AppController.str(body.get("target_db")), max,
                Boolean.TRUE.equals(body.get("return_uncovered_entities"))));
    }
}
