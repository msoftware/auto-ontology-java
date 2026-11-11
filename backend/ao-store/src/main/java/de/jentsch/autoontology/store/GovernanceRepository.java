// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.store;

import de.jentsch.autoontology.common.Ids;
import de.jentsch.autoontology.common.Json;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Tags, rules, zones and groups (section 13.4, 13.5, FR-AUTH-08). */
public class GovernanceRepository {
    /** Polymorphic tag target columns by API target type. */
    public static final Map<String, String> TAG_TARGET_COLUMNS = Map.of(
            "term", "term_id",
            "table", "table_id",
            "column", "column_id",
            "column_attribute", "column_attribute_id",
            "sql_attribute", "sql_attribute_id");

    private static final Set<String> ZONE_TARGET_COLUMNS = Set.of("database_id", "schema_id", "table_id");

    private final StoreJdbc db;

    public GovernanceRepository(StoreJdbc db) {
        this.db = db;
    }

    // ---------------------------------------------------------------- tags

    public List<Map<String, Object>> tags() {
        return db.list("""
                SELECT g.id, g.name, g.created, g.modified, g.created_by, g.modified_by,
                       (SELECT COUNT(*) FROM tag_target tt WHERE tt.tag_id = g.id) AS target_count
                FROM tag g ORDER BY LOWER(g.name)
                """);
    }

    public Optional<Map<String, Object>> tag(String id) {
        return db.one("SELECT id, name, created, modified, created_by, modified_by FROM tag WHERE id = :id",
                Map.of("id", id));
    }

    public Optional<Map<String, Object>> tagByName(String name) {
        return db.one("SELECT id, name FROM tag WHERE name_lower = :n",
                Map.of("n", name.trim().toLowerCase(Locale.ROOT)));
    }

    public String addTag(String name, String user, Instant now) {
        String id = Ids.newId();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("n", name.trim());
        p.put("nl", name.trim().toLowerCase(Locale.ROOT));
        p.put("u", user);
        p.put("now", now);
        db.update("INSERT INTO tag (id, name, name_lower, created, created_by) VALUES (:id, :n, :nl, :now, :u)", p);
        return id;
    }

    public int renameTag(String id, String name, String user, Instant now) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("n", name.trim());
        p.put("nl", name.trim().toLowerCase(Locale.ROOT));
        p.put("u", user);
        p.put("now", now);
        return db.update("UPDATE tag SET name = :n, name_lower = :nl, modified = :now, modified_by = :u"
                + " WHERE id = :id", p);
    }

    public int deleteTag(String id) {
        return db.update("DELETE FROM tag WHERE id = :id", Map.of("id", id));
    }

    public List<Map<String, Object>> tagTargets(String tagId, int offset, int limit) {
        return db.list("SELECT id, tag_id, term_id, table_id, column_id, column_attribute_id, sql_attribute_id,"
                + " tagged, tagged_by, rule_id FROM tag_target WHERE tag_id = :id ORDER BY tagged DESC, id"
                + " OFFSET " + offset + " ROWS FETCH NEXT " + limit + " ROWS ONLY", Map.of("id", tagId));
    }

    public long countTagTargets(String tagId) {
        return db.count("SELECT COUNT(*) FROM tag_target WHERE tag_id = :id", Map.of("id", tagId));
    }

    /** Attaches a tag; returns false when it already existed. */
    public boolean addTagTarget(String tagId, String targetType, String itemId, String user, String ruleId,
            Instant now) {
        String col = column(targetType);
        if (db.count("SELECT COUNT(*) FROM tag_target WHERE tag_id = :t AND " + col + " = :i",
                Map.of("t", tagId, "i", itemId)) > 0) {
            return false;
        }
        Map<String, Object> p = new HashMap<>();
        p.put("id", Ids.newId());
        p.put("t", tagId);
        p.put("i", itemId);
        p.put("u", user);
        p.put("r", ruleId);
        p.put("now", now);
        db.update("INSERT INTO tag_target (id, tag_id, " + col + ", tagged, tagged_by, rule_id)"
                + " VALUES (:id, :t, :i, :now, :u, :r)", p);
        return true;
    }

    public int removeTagTarget(String tagId, String targetType, String itemId) {
        return db.update("DELETE FROM tag_target WHERE tag_id = :t AND " + column(targetType) + " = :i",
                Map.of("t", tagId, "i", itemId));
    }

    public boolean hasTag(String tagId, String targetType, String itemId) {
        return db.count("SELECT COUNT(*) FROM tag_target WHERE tag_id = :t AND " + column(targetType) + " = :i",
                Map.of("t", tagId, "i", itemId)) > 0;
    }

    /** Tag names per item of one target type. */
    public Map<String, List<String>> tagNamesFor(String targetType) {
        String col = column(targetType);
        Map<String, List<String>> out = new HashMap<>();
        for (Map<String, Object> r : db.list("SELECT tt." + col + " AS item_id, g.name FROM tag_target tt"
                + " JOIN tag g ON g.id = tt.tag_id WHERE tt." + col + " IS NOT NULL ORDER BY g.name")) {
            out.computeIfAbsent((String) r.get("item_id"), k -> new java.util.ArrayList<>()).add((String) r.get("name"));
        }
        return out;
    }

    private static String column(String targetType) {
        String col = TAG_TARGET_COLUMNS.get(targetType);
        if (col == null) {
            throw new IllegalArgumentException("Unknown target type: " + targetType);
        }
        return col;
    }

    // ---------------------------------------------------------------- rules

    public List<Map<String, Object>> rules() {
        List<Map<String, Object>> rules = db.list("SELECT id, name, search_term, text_match_option, filters, created,"
                + " modified, created_by, modified_by FROM rule ORDER BY created, id");
        for (Map<String, Object> r : rules) {
            r.put("filters", Json.readAny((String) r.get("filters")));
            r.put("tags", db.list("SELECT g.id, g.name FROM rule__tag rt JOIN tag g ON g.id = rt.tag_id"
                    + " WHERE rt.rule_id = :id ORDER BY rt.position", Map.of("id", r.get("id"))));
        }
        return rules;
    }

    public Optional<Map<String, Object>> rule(String id) {
        return rules().stream().filter(r -> id.equals(r.get("id"))).findFirst();
    }

    public String addRule(String name, String searchTerm, String matchOption, Object filters, List<String> tagIds,
            String user, Instant now) {
        String id = Ids.newId();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("n", name);
        p.put("s", searchTerm);
        p.put("m", matchOption == null ? "contains" : matchOption);
        p.put("f", Json.write(filters));
        p.put("u", user);
        p.put("now", now);
        db.update("INSERT INTO rule (id, name, search_term, text_match_option, filters, created, created_by)"
                + " VALUES (:id, :n, :s, :m, :f, :now, :u)", p);
        int pos = 0;
        for (String tagId : tagIds) {
            db.update("INSERT INTO rule__tag (rule_id, tag_id, position) VALUES (:r, :t, :p)",
                    Map.of("r", id, "t", tagId, "p", pos++));
        }
        return id;
    }

    public int renameRule(String id, String name, String user, Instant now) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("n", name);
        p.put("u", user);
        p.put("now", now);
        return db.update("UPDATE rule SET name = :n, modified = :now, modified_by = :u WHERE id = :id", p);
    }

    /** Deletes the rule; its labels are removed, or kept and handed over to the rule author. */
    public int deleteRule(String id, boolean keepLabels) {
        if (keepLabels) {
            db.update("UPDATE tag_target SET rule_id = NULL, tagged_by = (SELECT created_by FROM rule WHERE id = :id)"
                    + " WHERE rule_id = :id", Map.of("id", id));
        }
        return db.update("DELETE FROM rule WHERE id = :id", Map.of("id", id));
    }

    // ---------------------------------------------------------------- zones

    public List<Map<String, Object>> zones() {
        List<Map<String, Object>> zones = db.list("SELECT id, name, description, color, enabled, created, modified"
                + " FROM zone ORDER BY LOWER(name)");
        zones.forEach(z -> z.put("targets", zoneTargets((String) z.get("id"))));
        return zones;
    }

    public Optional<Map<String, Object>> zone(String id) {
        Optional<Map<String, Object>> z = db.one("SELECT id, name, description, color, enabled, created, modified"
                + " FROM zone WHERE id = :id", Map.of("id", id));
        z.ifPresent(m -> m.put("targets", zoneTargets(id)));
        return z;
    }

    public List<Map<String, Object>> zoneTargets(String zoneId) {
        return db.list("SELECT id, database_id, schema_id, table_id FROM zone_target WHERE zone_id = :id",
                Map.of("id", zoneId));
    }

    public String addZone(String name, String description, String color, boolean enabled, String user, Instant now) {
        String id = Ids.newId();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("n", name.trim());
        p.put("d", description);
        p.put("c", color);
        p.put("e", enabled);
        p.put("u", user);
        p.put("now", now);
        db.update("INSERT INTO zone (id, name, description, color, enabled, created, created_by)"
                + " VALUES (:id, :n, :d, :c, :e, :now, :u)", p);
        return id;
    }

    public int patchZone(String id, Map<String, Object> values) {
        if (values.isEmpty()) {
            return db.count("SELECT COUNT(*) FROM zone WHERE id = :id", Map.of("id", id)) > 0 ? 1 : 0;
        }
        StringBuilder sql = new StringBuilder("UPDATE zone SET ");
        int i = 0;
        for (String k : values.keySet()) {
            sql.append(i++ > 0 ? ", " : "").append(k).append(" = :").append(k);
        }
        Map<String, Object> p = new HashMap<>(values);
        p.put("__id", id);
        return db.update(sql.append(" WHERE id = :__id").toString(), p);
    }

    public void replaceZoneTargets(String zoneId, List<Map<String, String>> targets) {
        db.update("DELETE FROM zone_target WHERE zone_id = :id", Map.of("id", zoneId));
        for (Map<String, String> t : targets) {
            String col = t.get("type") + "_id";
            if (!ZONE_TARGET_COLUMNS.contains(col)) {
                throw new IllegalArgumentException("Unknown zone target type: " + t.get("type"));
            }
            db.update("INSERT INTO zone_target (id, zone_id, " + col + ") VALUES (:id, :z, :t)",
                    Map.of("id", Ids.newId(), "z", zoneId, "t", t.get("id")));
        }
    }

    public int deleteZone(String id) {
        return db.update("DELETE FROM zone WHERE id = :id", Map.of("id", id));
    }

    // ---------------------------------------------------------------- groups

    public List<Map<String, Object>> groups() {
        return db.list("SELECT id, name, description, color, created FROM user_group ORDER BY LOWER(name)");
    }

    public Optional<Map<String, Object>> group(String id) {
        return db.one("SELECT id, name, description, color, created FROM user_group WHERE id = :id", Map.of("id", id));
    }

    public boolean groupNameExists(String name, String exceptId) {
        Map<String, Object> p = new HashMap<>();
        p.put("n", name.trim().toLowerCase(Locale.ROOT));
        p.put("x", exceptId == null ? "" : exceptId);
        return db.count("SELECT COUNT(*) FROM user_group WHERE name_lower = :n AND id <> :x", p) > 0;
    }

    public String addGroup(String name, String description, String color, String role, Instant now) {
        String id = Ids.newId();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("n", name.trim());
        p.put("nl", name.trim().toLowerCase(Locale.ROOT));
        p.put("d", description);
        p.put("c", color);
        p.put("r", role);
        p.put("now", now);
        db.update("INSERT INTO user_group (id, name, name_lower, description, color, role, created)"
                + " VALUES (:id, :n, :nl, :d, :c, :r, :now)", p);
        return id;
    }

    public int updateGroup(String id, String name, String description, String color) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("n", name == null ? null : name.trim());
        p.put("nl", name == null ? null : name.trim().toLowerCase(Locale.ROOT));
        p.put("d", description);
        p.put("c", color);
        return db.update("UPDATE user_group SET name = COALESCE(:n, name), name_lower = COALESCE(:nl, name_lower),"
                + " description = COALESCE(:d, description), color = COALESCE(:c, color) WHERE id = :id", p);
    }

    public int deleteGroup(String id) {
        return db.update("DELETE FROM user_group WHERE id = :id", Map.of("id", id));
    }

    /** Free owner names used on catalog and semantic objects (FR-AUTH-07). */
    public List<String> usedOwnerIds() {
        return db.column("""
                SELECT owner_id FROM catalog_database WHERE owner_id IS NOT NULL
                UNION SELECT owner_id FROM catalog_schema WHERE owner_id IS NOT NULL
                UNION SELECT owner_id FROM catalog_table WHERE owner_id IS NOT NULL
                UNION SELECT owner_id FROM term WHERE owner_id IS NOT NULL
                """, Map.of(), String.class);
    }
}
