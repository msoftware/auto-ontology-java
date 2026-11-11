// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.api;

import de.jentsch.autoontology.agent.JoinPathFinder;
import de.jentsch.autoontology.common.ApiException;
import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.store.GovernanceRepository;
import de.jentsch.autoontology.store.SemanticRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Graph exploration (FR-GOV-12, server/exploration): data graph, semantic graph, details, term paths, zones. */
@RestController
@RequestMapping("${ao.api-prefix:/api}/exploration")
public class ExplorationController {
    private final CatalogRepository catalog;
    private final SemanticRepository semantic;
    private final GovernanceRepository gov;
    private final JoinPathFinder joins;

    public ExplorationController(CatalogRepository catalog, SemanticRepository semantic, GovernanceRepository gov,
            JoinPathFinder joins) {
        this.catalog = catalog;
        this.semantic = semantic;
        this.gov = gov;
        this.joins = joins;
    }

    private static Map<String, Object> node(String id, String type, String name, String parent, Map<String, Object> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("type", type);
        m.put("name", name);
        m.put("parent_id", parent);
        if (extra != null) {
            m.putAll(extra);
        }
        return m;
    }

    private static Map<String, Object> link(String source, String target, String type, String label) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", source);
        m.put("target", target);
        m.put("type", type);
        m.put("label", label);
        return m;
    }

    /** Database → Schema → Table/View (+ columns when requested), FK, join and SQL edges. */
    @GetMapping("/graph")
    public Map<String, Object> graph(@RequestParam(name = "database_id", required = false) String databaseId,
            @RequestParam(name = "include_columns", defaultValue = "false") boolean includeColumns) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> links = new ArrayList<>();
        for (Map<String, Object> d : catalog.databases()) {
            String dbId = (String) d.get("id");
            if (databaseId != null && !databaseId.equals(dbId)) {
                continue;
            }
            nodes.add(node(dbId, "Database", (String) d.get("name"), null, null));
            for (Map<String, Object> s : catalog.schemas(dbId)) {
                nodes.add(node((String) s.get("id"), "Schema", (String) s.get("name"), dbId, null));
                links.add(link(dbId, (String) s.get("id"), "CONTAINS", null));
            }
            for (Map<String, Object> t : catalog.tablesOfDatabase(dbId)) {
                String type = "base table".equals(t.get("table_type")) ? "Table" : "View";
                nodes.add(node((String) t.get("id"), type, (String) t.get("name"), (String) t.get("schema_id"),
                        Map.of("schema_name", t.get("schema_name"), "is_junction_table", t.get("is_junction_table"))));
                links.add(link((String) t.get("schema_id"), (String) t.get("id"), "CONTAINS", null));
                if (includeColumns) {
                    for (Map<String, Object> c : catalog.columnsOfTable((String) t.get("id"))) {
                        nodes.add(node((String) c.get("id"), "Column", (String) c.get("name"), (String) t.get("id"),
                                Map.of("data_type", String.valueOf(c.get("data_type")))));
                        links.add(link((String) t.get("id"), (String) c.get("id"), "CONTAINS", null));
                    }
                }
            }
            for (Map<String, Object> f : catalog.foreignKeys(dbId)) {
                links.add(link((String) f.get("source_table_id"), (String) f.get("target_table_id"), "FOREIGN_KEY",
                        f.get("source_column") + " → " + f.get("target_column")));
            }
            for (Map<String, Object> j : catalog.observedJoins(dbId)) {
                var a = catalog.columnById((String) j.get("source_column_id"));
                var b = catalog.columnById((String) j.get("target_column_id"));
                if (a.isPresent() && b.isPresent()) {
                    links.add(link((String) a.get().get("table_id"), (String) b.get().get("table_id"), "JOIN",
                            a.get().get("name") + " = " + b.get().get("name")));
                }
            }
        }
        return Map.of("data", Map.of("nodes", nodes, "links", links));
    }

    /** Terms with counts; links are semantic relationships between their tables. */
    @GetMapping("/semantic-graph")
    public Map<String, Object> semanticGraph() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        Map<String, Set<String>> termsOfTable = new HashMap<>();
        for (Map<String, Object> t : semantic.allTerms()) {
            String id = (String) t.get("id");
            Map<String, Object> c = semantic.termCounters(id);
            Map<String, Object> n = node(id, "Term", (String) t.get("name"), null, null);
            n.put("description", t.get("description"));
            n.put("synonyms", t.get("synonyms"));
            n.put("column_attributes_count", c.get("column_attribute_count"));
            n.put("sql_attributes_count", c.get("sql_attribute_count"));
            n.put("zones", List.of());
            nodes.add(n);
            semantic.tablesOfTerm(id).forEach(tb -> termsOfTable.computeIfAbsent((String) tb.get("id"), k -> new LinkedHashSet<>())
                    .add(id));
        }
        Map<String, Map<String, Object>> links = new LinkedHashMap<>();
        Map<String, Integer> relCount = new HashMap<>();
        for (Map<String, Object> e : semantic.allSemanticFkEdges()) {
            for (String a : termsOfTable.getOrDefault((String) e.get("source_table_id"), Set.of())) {
                for (String b : termsOfTable.getOrDefault((String) e.get("target_table_id"), Set.of())) {
                    if (!a.equals(b) && links.putIfAbsent(a + "|" + b, link(a, b, "RELATED_TO", null)) == null) {
                        relCount.merge(a, 1, Integer::sum);
                        relCount.merge(b, 1, Integer::sum);
                    }
                }
            }
        }
        nodes.forEach(n -> n.put("relationship_count", relCount.getOrDefault((String) n.get("id"), 0)));
        return Map.of("data", Map.of("nodes", nodes, "links", new ArrayList<>(links.values())));
    }

    @GetMapping("/edges")
    public Map<String, Object> edges(@RequestParam(name = "database_id", required = false) String databaseId) {
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) graph(databaseId, false).get("data");
        return Map.of("data", g.get("links"));
    }

    @GetMapping("/nodes/{id}/relationships")
    public Map<String, Object> relationships(@PathVariable String id) {
        List<Map<String, Object>> out = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> links = (List<Map<String, Object>>) ((Map<String, Object>) graph(null, true).get("data"))
                .get("links");
        links.stream().filter(l -> id.equals(l.get("source")) || id.equals(l.get("target"))).forEach(out::add);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sem = (List<Map<String, Object>>) ((Map<String, Object>) semanticGraph().get("data"))
                .get("links");
        sem.stream().filter(l -> id.equals(l.get("source")) || id.equals(l.get("target"))).forEach(out::add);
        return Map.of("data", out);
    }

    @GetMapping({"/tables/{id}/details", "/columns/{id}/details", "/terms/{id}/details",
        "/column-attributes/{id}/details", "/sql-attributes/{id}/details", "/sql/{id}/details"})
    public Map<String, Object> details(@PathVariable String id, jakarta.servlet.http.HttpServletRequest request) {
        String uri = request.getRequestURI();
        String kind = uri.substring(0, uri.lastIndexOf("/" + id + "/details"));
        kind = kind.substring(kind.lastIndexOf('/') + 1);
        return details(kind, id);
    }

    Map<String, Object> details(String kind, String id) {
        Map<String, Object> d = switch (kind) {
            case "tables" -> {
                Map<String, Object> t = new LinkedHashMap<>(catalog.tableById(id)
                        .orElseThrow(() -> ApiException.notFound("Table not found")));
                t.put("columns", catalog.columnsOfTable(id).stream().map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>(c);
                    m.put("sample_values", Json.readList((String) c.get("sample_values")));
                    return m;
                }).toList());
                t.put("foreign_keys", catalog.foreignKeysOfTable(id));
                t.put("terms", semantic.termsOfTable(id));
                t.put("sql", catalog.sqlForTables(List.of(id), 20));
                t.put("tags", gov.tagNamesFor("table").getOrDefault(id, List.of()));
                yield t;
            }
            case "columns" -> new LinkedHashMap<>(catalog.columnById(id)
                    .orElseThrow(() -> ApiException.notFound("Column not found")));
            case "terms" -> {
                Map<String, Object> t = new LinkedHashMap<>(semantic.termById(id)
                        .orElseThrow(() -> ApiException.notFound("Term not found")));
                t.put("tables", semantic.tablesOfTerm(id));
                t.put("column_attributes", semantic.attributesOfTerm(id));
                t.put("sql_attributes", semantic.sqlAttributesOfTerm(id));
                yield t;
            }
            case "column-attributes" -> {
                Map<String, Object> a = new LinkedHashMap<>(semantic.attributeById(id)
                        .orElseThrow(() -> ApiException.notFound("Column attribute not found")));
                a.put("columns", semantic.columnsOfAttribute(id));
                a.put("referencing_columns", semantic.referencingColumns(id));
                yield a;
            }
            case "sql-attributes" -> new LinkedHashMap<>(semantic.sqlAttributeById(id)
                    .orElseThrow(() -> ApiException.notFound("SQL attribute not found")));
            case "sql" -> new LinkedHashMap<>(catalog.sqlById(id).orElseThrow(() -> ApiException.notFound("SQL not found")));
            default -> throw ApiException.notFound("Unknown node kind " + kind);
        };
        return Map.of("data", d);
    }

    /** Join path between the tables of two Terms. */
    @GetMapping("/terms/{id}/path/{otherId}")
    public Map<String, Object> termPath(@PathVariable String id, @PathVariable String otherId) {
        List<Map<String, Object>> a = semantic.tablesOfTerm(id);
        List<Map<String, Object>> b = semantic.tablesOfTerm(otherId);
        List<JoinPathFinder.Hop> best = null;
        for (Map<String, Object> ta : a) {
            for (Map<String, Object> tb : b) {
                if (!ta.get("database_id").equals(tb.get("database_id"))) {
                    continue;
                }
                List<JoinPathFinder.Hop> p = joins.bridge((String) ta.get("database_id"), (String) ta.get("id"),
                        (String) tb.get("id"));
                if (p != null && (best == null || p.size() < best.size())) {
                    best = p;
                }
            }
        }
        List<Map<String, Object>> hops = best == null ? List.of() : best.stream().map(h -> Map.<String, Object>of(
                "from_table_id", h.fromTableId(), "from_table", h.fromTable(), "from_column", h.fromColumn(),
                "to_table_id", h.toTableId(), "to_table", h.toTable(), "to_column", h.toColumn(),
                "condition", h.condition())).toList();
        return Map.of("data", Map.of("found", best != null, "hops", hops));
    }

    /**
     * Zones per table. {@code zone_ids} absent means no restriction; an empty list means no hits
     * (dal/users.py::resolve_accessible_catalog_ids, FR-AUTH-09).
     */
    @SuppressWarnings("unchecked")
    @GetMapping("/tables/zones")
    public Map<String, Object> tableZones(@RequestParam(name = "zone_ids", required = false) List<String> zoneIds) {
        Map<String, List<Map<String, Object>>> byTable = new LinkedHashMap<>();
        if (zoneIds != null && zoneIds.isEmpty()) {
            return Map.of("data", byTable);
        }
        for (Map<String, Object> z : gov.zones()) {
            if (zoneIds != null && !zoneIds.contains(z.get("id"))) {
                continue;
            }
            Map<String, Object> chip = Map.of("id", z.get("id"), "name", z.get("name"),
                    "color", String.valueOf(z.getOrDefault("color", "")), "enabled", z.get("enabled"));
            for (Map<String, Object> t : (List<Map<String, Object>>) z.get("targets")) {
                List<Map<String, Object>> tables = t.get("table_id") != null
                        ? catalog.tableById((String) t.get("table_id")).map(List::of).orElse(List.of())
                        : t.get("schema_id") != null ? catalog.tablesOfSchema((String) t.get("schema_id"))
                        : catalog.tablesOfDatabase((String) t.get("database_id"));
                tables.forEach(tb -> byTable.computeIfAbsent((String) tb.get("id"), k -> new ArrayList<>()).add(chip));
            }
        }
        return Map.of("data", byTable);
    }
}
