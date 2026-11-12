// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.api;

import de.jentsch.autoontology.common.ApiException;
import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.CurrentUser;
import de.jentsch.autoontology.common.Ids;
import de.jentsch.autoontology.ingestion.RuleService;
import de.jentsch.autoontology.ingestion.SearchService;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.store.GovernanceRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Search (FR-GOV-05), tags (FR-GOV-06), rules (FR-GOV-07), zones (FR-GOV-08), groups/owners (FR-AUTH-07/08). */
@RestController
@RequestMapping("${ao.api-prefix:/api}")
public class GovernanceController {
    private final GovernanceRepository gov;
    private final CatalogRepository catalog;
    private final SearchService search;
    private final RuleService rules;
    private final CurrentUser user;
    private final AoTime time;

    public GovernanceController(GovernanceRepository gov, CatalogRepository catalog, SearchService search,
            RuleService rules, CurrentUser user, AoTime time) {
        this.gov = gov;
        this.catalog = catalog;
        this.search = search;
        this.rules = rules;
        this.user = user;
        this.time = time;
    }

    // ------------------------------------------------------------------ search

    @PostMapping("/search/global-search")
    public Map<String, Object> globalSearch(@RequestBody Map<String, Object> body) {
        List<Map<String, Object>> data = search.search(SearchService.request(body));
        return Map.of("data", data, "count", data.size());
    }

    @PostMapping("/search/global-search/count")
    public Map<String, Object> globalSearchCount(@RequestBody Map<String, Object> body) {
        return Map.of("data", search.count(SearchService.request(body)));
    }

    // ------------------------------------------------------------------ tags

    @GetMapping("/tags")
    public Map<String, Object> tags(@RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int skip, @RequestParam(defaultValue = "100") int limit) {
        List<Map<String, Object>> all = gov.tags().stream().filter(t -> query == null
                || String.valueOf(t.get("name")).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
        List<Map<String, Object>> page = all.stream().skip(Math.max(0, skip)).limit(Math.max(0, limit)).toList();
        return Map.of("data", page, "count", page.size(), "total", all.size());
    }

    @PostMapping("/tags")
    public ResponseEntity<Map<String, Object>> createTag(@RequestBody Map<String, Object> body) {
        String name = AppController.str(body.get("name"));
        if (name == null) {
            throw ApiException.unprocessable("name is required");
        }
        if (gov.tagByName(name).isPresent()) {
            throw ApiException.conflict("A tag with this name already exists");
        }
        String id = gov.addTag(name, user.id(), time.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", gov.tag(id).orElseThrow()));
    }

    private Map<String, Object> tag(String id) {
        if (!Ids.isUuid(id)) {
            throw ApiException.notFound("Tag not found");
        }
        return gov.tag(id).orElseThrow(() -> ApiException.notFound("Tag not found"));
    }

    @GetMapping("/tags/{id}")
    public Map<String, Object> getTag(@PathVariable String id) {
        return Map.of("data", tag(id));
    }

    @PatchMapping("/tags/{id}")
    public Map<String, Object> renameTag(@PathVariable String id, @RequestBody Map<String, Object> body) {
        tag(id);
        String name = AppController.str(body.get("name"));
        if (name == null) {
            throw ApiException.unprocessable("name is required");
        }
        gov.tagByName(name).filter(t -> !id.equals(t.get("id"))).ifPresent(t -> {
            throw ApiException.conflict("A tag with this name already exists");
        });
        gov.renameTag(id, name, user.id(), time.now());
        return Map.of("data", tag(id));
    }

    @DeleteMapping("/tags/{id}")
    public Map<String, Object> deleteTag(@PathVariable String id) {
        tag(id);
        gov.deleteTag(id);
        return Map.of("data", Map.of("id", id));
    }

    @GetMapping("/tags/{id}/targets")
    public Map<String, Object> tagTargets(@PathVariable String id, @RequestParam(defaultValue = "0") int skip,
            @RequestParam(defaultValue = "50") int limit) {
        tag(id);
        List<Map<String, Object>> page = gov.tagTargets(id, Math.max(0, skip), Math.max(0, limit)).stream()
                .map(this::targetView).toList();
        return Map.of("data", page, "count", page.size(), "total", gov.countTagTargets(id));
    }

    private Map<String, Object> targetView(Map<String, Object> t) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : GovernanceRepository.TAG_TARGET_COLUMNS.entrySet()) {
            Object item = t.get(e.getValue());
            if (item != null) {
                m.put("target_type", e.getKey());
                m.put("item_id", item);
            }
        }
        m.put("tagged", t.get("tagged"));
        m.put("tagged_by", t.get("tagged_by"));
        m.put("rule_id", t.get("rule_id"));
        return m;
    }

    @PostMapping("/tags/{id}/targets")
    public ResponseEntity<Map<String, Object>> addTarget(@PathVariable String id, @RequestBody Map<String, Object> body) {
        tag(id);
        String type = AppController.str(body.get("target_type"));
        String item = AppController.str(body.get("item_id"));
        if (type == null || item == null || !GovernanceRepository.TAG_TARGET_COLUMNS.containsKey(type)) {
            throw ApiException.unprocessable("target_type (term, table, column, column_attribute, sql_attribute) and"
                    + " item_id are required");
        }
        boolean created;
        try {
            created = gov.addTagTarget(id, type, item, user.id(), null, time.now());
        } catch (RuntimeException e) {
            throw ApiException.notFound("Target not found");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target_type", type);
        out.put("item_id", item);
        out.put("created", created);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", out));
    }

    @DeleteMapping("/tags/{id}/targets/{targetType}/{itemId}")
    public Map<String, Object> removeTarget(@PathVariable String id, @PathVariable String targetType,
            @PathVariable String itemId) {
        tag(id);
        if (!GovernanceRepository.TAG_TARGET_COLUMNS.containsKey(targetType)) {
            throw ApiException.unprocessable("Unknown target type");
        }
        if (gov.removeTagTarget(id, targetType, itemId) == 0) {
            throw ApiException.notFound("Tag is not attached to this item");
        }
        return Map.of("data", Map.of("id", id, "item_id", itemId));
    }

    // ------------------------------------------------------------------ rules

    @GetMapping("/rules")
    public Map<String, Object> rules(@RequestParam(required = false) String query,
            @RequestParam(defaultValue = "0") int skip, @RequestParam(defaultValue = "100") int limit) {
        List<Map<String, Object>> all = gov.rules().stream().filter(r -> query == null
                || String.valueOf(r.get("name")).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();
        List<Map<String, Object>> page = all.stream().skip(Math.max(0, skip)).limit(Math.max(0, limit)).toList();
        return Map.of("data", page, "count", page.size(), "total", all.size());
    }

    /** Applied at once to exactly the hits the search showed. */
    @SuppressWarnings("unchecked")
    @PostMapping("/rules")
    public ResponseEntity<Map<String, Object>> createRule(@RequestBody Map<String, Object> body) {
        String name = AppController.str(body.get("name"));
        String term = AppController.str(body.get("search_term"));
        if (name == null || term == null) {
            throw ApiException.unprocessable("name and search_term are required");
        }
        List<String> tagIds = new ArrayList<>();
        for (Object t : body.get("tags") instanceof List<?> l ? (List<Object>) l : List.of()) {
            String tid = t instanceof Map<?, ?> m ? String.valueOf(m.get("id")) : String.valueOf(t);
            tag(tid);
            tagIds.add(tid);
        }
        if (tagIds.isEmpty()) {
            throw ApiException.unprocessable("at least one tag is required");
        }
        search.search(SearchService.request(body));
        String id = gov.addRule(name, term, AppController.str(body.get("text_match_option")), body.get("filters"), tagIds,
                user.id(), time.now());
        rules.apply(gov.rule(id).orElseThrow());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", Map.of("id", id)));
    }

    /** Editing is renaming only. */
    @PatchMapping("/rules/{id}")
    public Map<String, Object> renameRule(@PathVariable String id, @RequestBody Map<String, Object> body) {
        String name = AppController.str(body.get("name"));
        if (name == null) {
            throw ApiException.unprocessable("name is required");
        }
        if (gov.renameRule(id, name, user.id(), time.now()) == 0) {
            throw ApiException.notFound("Rule not found");
        }
        return Map.of("data", gov.rule(id).orElseThrow());
    }

    @DeleteMapping("/rules/{id}")
    public Map<String, Object> deleteRule(@PathVariable String id,
            @RequestParam(name = "keep_labels", defaultValue = "false") boolean keepLabels) {
        if (gov.deleteRule(id, keepLabels) == 0) {
            throw ApiException.notFound("Rule not found");
        }
        return Map.of("data", Map.of("id", id));
    }

    // ------------------------------------------------------------------ zones (FR-GOV-08, FR-AUTH-09)

    @GetMapping("/zones")
    public Map<String, Object> zones() {
        List<Map<String, Object>> data = gov.zones().stream().map(this::zoneView).toList();
        return Map.of("data", data, "count", data.size());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> zoneView(Map<String, Object> z) {
        Map<String, Object> m = new LinkedHashMap<>(z);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> t : (List<Map<String, Object>>) z.get("targets")) {
            for (String col : List.of("database_id", "schema_id", "table_id")) {
                if (t.get(col) != null) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", t.get(col));
                    item.put("type", col.replace("_id", ""));
                    item.put("name", nameOf(col, (String) t.get(col)));
                    items.add(item);
                }
            }
        }
        m.remove("targets");
        m.put("items", items);
        m.put("label", z.get("name"));
        return m;
    }

    private String nameOf(String col, String id) {
        return switch (col) {
            case "database_id" -> catalog.databaseById(id).map(d -> (String) d.get("name")).orElse(id);
            case "schema_id" -> catalog.schemaById(id).map(d -> (String) d.get("name")).orElse(id);
            default -> catalog.tableById(id).map(t -> t.get("schema_name") + "." + t.get("name")).orElse(id);
        };
    }

    private Map<String, Object> zone(String id) {
        if (!Ids.isUuid(id)) {
            throw ApiException.notFound("Zone not found");
        }
        return gov.zone(id).orElseThrow(() -> ApiException.notFound("Zone not found"));
    }

    /** Name rule: case-insensitive, trimmed, per affected database. */
    private void checkZoneName(String name, String exceptId, List<Map<String, String>> items) {
        java.util.Set<String> dbs = new java.util.HashSet<>();
        items.forEach(i -> dbs.add(databaseOf(i)));
        for (Map<String, Object> z : gov.zones()) {
            if (z.get("id").equals(exceptId) || !String.valueOf(z.get("name")).strip().equalsIgnoreCase(name.strip())) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> targets = (List<Map<String, Object>>) z.get("targets");
            for (Map<String, Object> t : targets) {
                Map<String, String> i = new HashMap<>();
                for (String col : List.of("database_id", "schema_id", "table_id")) {
                    if (t.get(col) != null) {
                        i.put("type", col.replace("_id", ""));
                        i.put("id", (String) t.get(col));
                    }
                }
                if (dbs.contains(databaseOf(i))) {
                    throw ApiException.conflict("A zone named " + name + " already exists for this database");
                }
            }
        }
    }

    private String databaseOf(Map<String, String> item) {
        String id = item.get("id");
        return switch (String.valueOf(item.get("type"))) {
            case "database" -> id;
            case "schema" -> catalog.schemaById(id).map(s -> (String) s.get("database_id")).orElse(id);
            default -> catalog.tableById(id).map(t -> (String) t.get("database_id")).orElse(id);
        };
    }

    /**
     * Zone targets from the request: {@code {type, id}} objects or bare ids. Every target must exist
     * and have one of the three types; anything else is a 422 instead of a database error.
     */
    private List<Map<String, String>> items(Object raw) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Object o : raw instanceof List<?> l ? l : List.of()) {
            String id = String.valueOf(o instanceof Map<?, ?> m ? m.get("id") : o);
            String type = o instanceof Map<?, ?> m && m.get("type") != null ? String.valueOf(m.get("type"))
                    : catalog.databaseById(id).isPresent() ? "database"
                    : catalog.schemaById(id).isPresent() ? "schema" : "table";
            boolean exists = switch (type) {
                case "database" -> catalog.databaseById(id).isPresent();
                case "schema" -> catalog.schemaById(id).isPresent();
                case "table" -> catalog.tableById(id).isPresent();
                default -> throw ApiException.unprocessable("Unknown zone item type " + type
                        + " (database, schema or table)");
            };
            if (!exists) {
                throw ApiException.unprocessable("Zone item " + type + " " + id + " does not exist");
            }
            out.add(Map.of("type", type, "id", id));
        }
        return out;
    }

    @PostMapping("/zones")
    public ResponseEntity<Map<String, Object>> createZone(@RequestBody Map<String, Object> body) {
        String name = AppController.str(body.get("name"));
        if (name == null) {
            throw ApiException.unprocessable("name is required");
        }
        List<Map<String, String>> items = items(body.get("items"));
        checkZoneName(name, null, items);
        String id = gov.addZone(name, AppController.str(body.get("description")), AppController.str(body.get("color")),
                !Boolean.FALSE.equals(body.get("enabled")), user.id(), time.now());
        gov.replaceZoneTargets(id, items);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", zoneView(zone(id))));
    }

    @GetMapping("/zones/{id}")
    public Map<String, Object> getZone(@PathVariable String id) {
        return Map.of("data", zoneView(zone(id)));
    }

    @PatchMapping("/zones/{id}")
    public Map<String, Object> patchZone(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Map<String, Object> z = zone(id);
        Map<String, Object> values = new HashMap<>();
        for (String k : List.of("name", "description", "color")) {
            if (body.containsKey(k)) {
                values.put(k, AppController.text(body.get(k)));
            }
        }
        if (values.containsKey("name")) {
            String name = AppController.str(values.get("name"));
            if (name == null) {
                throw ApiException.unprocessable("name must not be empty");
            }
            values.put("name", name.strip());
        }
        if (body.containsKey("items") || values.containsKey("name")) {
            List<Map<String, String>> items = body.containsKey("items") ? items(body.get("items"))
                    : items(zoneView(z).get("items"));
            checkZoneName(String.valueOf(values.getOrDefault("name", z.get("name"))), id, items);
            if (body.containsKey("items")) {
                gov.replaceZoneTargets(id, items);
            }
        }
        gov.patchZone(id, values);
        return Map.of("data", zoneView(zone(id)));
    }

    @PatchMapping("/zones/{id}/status")
    public Map<String, Object> zoneStatus(@PathVariable String id, @RequestBody Map<String, Object> body) {
        zone(id);
        if (!(body.get("enabled") instanceof Boolean enabled)) {
            throw ApiException.unprocessable("enabled must be a boolean");
        }
        gov.patchZone(id, Map.of("enabled", enabled));
        return Map.of("data", zoneView(zone(id)));
    }

    @DeleteMapping("/zones/{id}")
    public Map<String, Object> deleteZone(@PathVariable String id) {
        zone(id);
        gov.deleteZone(id);
        return Map.of("data", Map.of("id", id));
    }

    // ------------------------------------------------------------------ groups and owners

    private Map<String, Object> groupView(Map<String, Object> g) {
        Map<String, Object> m = new LinkedHashMap<>(g);
        m.put("role", null);
        m.put("permissions", List.of());
        m.put("roles", List.of());
        m.put("users", List.of());
        return m;
    }

    @GetMapping("/groups")
    public Map<String, Object> groups() {
        List<Map<String, Object>> data = gov.groups().stream().map(this::groupView).toList();
        return Map.of("data", data, "count", data.size());
    }

    @PostMapping("/groups")
    public ResponseEntity<Map<String, Object>> createGroup(@RequestBody Map<String, Object> body) {
        String name = AppController.str(body.get("name"));
        if (name == null) {
            throw ApiException.unprocessable("name is required");
        }
        if (gov.groupNameExists(name, null)) {
            throw ApiException.conflict("A group with this name already exists");
        }
        // role and members of the original are accepted but have no effect (FR-AUTH-08)
        String id = gov.addGroup(name, AppController.str(body.get("description")), AppController.str(body.get("color")),
                AppController.str(body.get("role")), time.now());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("data", groupView(gov.group(id).orElseThrow())));
    }

    @GetMapping("/groups/{id}")
    public Map<String, Object> getGroup(@PathVariable String id) {
        return Map.of("data", groupView(gov.group(id).orElseThrow(() -> ApiException.notFound("Group not found"))));
    }

    @PatchMapping("/groups/{id}")
    public Map<String, Object> updateGroup(@PathVariable String id, @RequestBody Map<String, Object> body) {
        gov.group(id).orElseThrow(() -> ApiException.notFound("Group not found"));
        String name = AppController.str(body.get("name"));
        if (name != null && gov.groupNameExists(name, id)) {
            throw ApiException.conflict("A group with this name already exists");
        }
        gov.updateGroup(id, name, AppController.str(body.get("description")), AppController.str(body.get("color")));
        return getGroup(id);
    }

    @DeleteMapping("/groups/{id}")
    public Map<String, Object> deleteGroup(@PathVariable String id) {
        if (gov.deleteGroup(id) == 0) {
            throw ApiException.notFound("Group not found");
        }
        return Map.of("data", Map.of("id", id));
    }

    /** Groups plus the free owner names in use. */
    @GetMapping("/owners")
    public Map<String, Object> owners() {
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> groupIds = new java.util.HashSet<>();
        for (Map<String, Object> g : gov.groups()) {
            groupIds.add((String) g.get("id"));
            out.add(Map.of("id", g.get("id"), "name", g.get("name"), "kind", "group",
                    "color", g.get("color") == null ? "" : String.valueOf(g.get("color")), "member_ids", List.of(),
                    "permissions", List.of()));
        }
        for (String o : gov.usedOwnerIds()) {
            if (!groupIds.contains(o)) {
                out.add(Map.of("id", o, "name", o, "kind", "name", "color", "", "member_ids", List.of(),
                        "permissions", List.of()));
            }
        }
        return Map.of("data", out, "count", out.size());
    }
}
