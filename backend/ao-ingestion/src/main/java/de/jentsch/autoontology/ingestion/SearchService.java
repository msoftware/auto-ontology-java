// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.ingestion;

import de.jentsch.autoontology.common.ApiException;
import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.store.GovernanceRepository;
import de.jentsch.autoontology.store.StoreJdbc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Global substring search (FR-GOV-05, server/search, dal/search.py): {@code contains}, at least two
 * characters, filters by type, tag (including the untagged sentinel) and data scope, optional
 * description search; ranking: name contains every token, then alias (synonym) matched as a whole
 * word, then shorter names. Capped at 200; the count endpoint has no cap.
 */
public class SearchService {
    public static final int CAP = 200;
    public static final String UNTAGGED = "(blanks)";
    public static final List<String> TYPES = List.of("Database", "Schema", "Table", "View", "Column", "CustomAnalysis",
            "Term", "ColumnAttribute", "SqlAttribute", "PqlAnalysis", "Tag");
    private static final Map<String, String> TAG_TARGET = Map.of("Term", "term", "Table", "table", "View", "table",
            "Column", "column", "ColumnAttribute", "column_attribute", "SqlAttribute", "sql_attribute");

    private final StoreJdbc db;
    private final GovernanceRepository governance;

    public SearchService(StoreJdbc db, GovernanceRepository governance) {
        this.db = db;
        this.governance = governance;
    }

    /** Search request (GlobalSearchRequest). */
    public record Request(String searchTerm, String textMatchOption, List<String> objects, List<String> tags,
            List<String> data, boolean description, boolean synonyms) {}

    public static Request request(Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        Map<String, Object> f = body.get("filters") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        // an explicit JSON null must not become the search text "null"
        Object term = body.get("search_term");
        Object option = body.get("text_match_option");
        return new Request(term == null ? "" : String.valueOf(term), option == null ? "contains" : String.valueOf(option),
                strings(f.get("objects")), strings(f.get("tags")), strings(f.get("data")),
                Boolean.TRUE.equals(f.get("description")), !Boolean.FALSE.equals(f.get("synonyms")));
    }

    private static List<String> strings(Object o) {
        return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : null;
    }

    public List<Map<String, Object>> search(Request r) {
        List<Map<String, Object>> all = candidates(r);
        all.sort(ranking(r));
        return all.size() > CAP ? new ArrayList<>(all.subList(0, CAP)) : all;
    }

    public Map<String, Integer> count(Request r) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map<String, Object> hit : candidates(r)) {
            out.merge((String) hit.get("type"), 1, Integer::sum);
        }
        return out;
    }

    private List<Map<String, Object>> candidates(Request r) {
        String term = r.searchTerm() == null ? "" : r.searchTerm().trim();
        if (term.length() < 2) {
            throw ApiException.unprocessable("search_term must contain at least two characters");
        }
        if (r.textMatchOption() != null && !"contains".equals(r.textMatchOption())) {
            throw ApiException.unprocessable("text_match_option must be 'contains'");
        }
        String q = "%" + term.toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
        Set<String> types = r.objects() == null || r.objects().isEmpty() ? Set.copyOf(TYPES) : Set.copyOf(r.objects());
        if (r.data() != null && !r.data().isEmpty()) {
            types = new java.util.HashSet<>(types);
            types.retainAll(Set.of("Table", "View", "Column"));
        }
        String nameOrDesc = r.description()
                ? "(LOWER(x.name) LIKE :q ESCAPE '\\' OR LOWER(COALESCE(x.description, '')) LIKE :q ESCAPE '\\')"
                : "LOWER(x.name) LIKE :q ESCAPE '\\'";
        Map<String, Object> p = Map.of("q", q);
        List<Map<String, Object>> hits = new ArrayList<>();
        if (types.contains("Database")) {
            add(hits, "Database", db.list("SELECT x.id, x.name, x.description, x.description_certified AS certified,"
                    + " NULL AS parent_id FROM catalog_database x WHERE " + nameOrDesc, p));
        }
        if (types.contains("Schema")) {
            add(hits, "Schema", db.list("SELECT x.id, x.name, x.description, x.description_certified AS certified,"
                    + " x.database_id AS parent_id, d.name AS database_name FROM catalog_schema x"
                    + " JOIN catalog_database d ON d.id = x.database_id WHERE " + nameOrDesc, p));
        }
        if (types.contains("Table") || types.contains("View")) {
            for (Map<String, Object> t : db.list("SELECT x.id, x.name, x.description, x.description_certified AS"
                    + " certified, x.schema_id AS parent_id, x.table_type, s.name AS schema_name, s.id AS schema_id,"
                    + " d.name AS database_name, d.id AS database_id FROM catalog_table x"
                    + " JOIN catalog_schema s ON s.id = x.schema_id JOIN catalog_database d ON d.id = s.database_id"
                    + " WHERE " + nameOrDesc, p)) {
                String type = "base table".equals(t.get("table_type")) ? "Table" : "View";
                if (types.contains(type) && inScope(r, t)) {
                    add(hits, type, List.of(t));
                }
            }
        }
        if (types.contains("Column")) {
            for (Map<String, Object> c : db.list("SELECT x.id, x.name, x.description, x.description_certified AS"
                    + " certified, x.table_id AS parent_id, t.name AS table_name, t.id AS table_id,"
                    + " s.name AS schema_name, s.id AS schema_id, d.name AS database_name, d.id AS database_id"
                    + " FROM catalog_column x JOIN catalog_table t ON t.id = x.table_id"
                    + " JOIN catalog_schema s ON s.id = t.schema_id JOIN catalog_database d ON d.id = s.database_id"
                    + " WHERE " + nameOrDesc, p)) {
                if (inScope(r, c)) {
                    add(hits, "Column", List.of(c));
                }
            }
        }
        if (types.contains("CustomAnalysis")) {
            add(hits, "CustomAnalysis", db.list("SELECT x.id, x.name, x.description, NULL AS certified,"
                    + " NULL AS parent_id FROM custom_analysis x WHERE " + nameOrDesc, p));
        }
        if (types.contains("Term")) {
            String synonymClause = r.synonyms() ? " OR LOWER(COALESCE(x.synonyms, '')) LIKE :q ESCAPE '\\'" : "";
            List<Map<String, Object>> terms = db.list("SELECT x.id, x.name, x.description, x.synonyms,"
                    + " x.name_certified, x.description_certified, NULL AS parent_id FROM term x WHERE (" + nameOrDesc
                    + synonymClause + ")", p);
            String needle = term.toLowerCase(Locale.ROOT);
            List<Map<String, Object>> matched = new ArrayList<>();
            for (Map<String, Object> t : terms) {
                List<String> syn = Json.readStringList((String) t.remove("synonyms"));
                List<String> hitSynonyms = r.synonyms() ? syn.stream()
                        .filter(s -> s.toLowerCase(Locale.ROOT).contains(needle)).toList() : List.of();
                t.put("synonyms", hitSynonyms);
                t.put("certified", Boolean.TRUE.equals(t.remove("name_certified"))
                        && Boolean.TRUE.equals(t.remove("description_certified")));
                // the SQL filter matched the JSON text of the synonyms, i.e. also brackets and quotes
                boolean nameOrDescription = String.valueOf(t.get("name")).toLowerCase(Locale.ROOT).contains(needle)
                        || (r.description() && t.get("description") != null
                                && String.valueOf(t.get("description")).toLowerCase(Locale.ROOT).contains(needle));
                if (nameOrDescription || !hitSynonyms.isEmpty()) {
                    matched.add(t);
                }
            }
            add(hits, "Term", matched);
        }
        if (types.contains("ColumnAttribute")) {
            add(hits, "ColumnAttribute", db.list("SELECT x.id, x.name, x.description, x.certified,"
                    + " at.term_id AS parent_id, x.term_name FROM column_attribute x"
                    + " LEFT JOIN column_attribute__term at ON at.attribute_id = x.id WHERE " + nameOrDesc, p));
        }
        if (types.contains("SqlAttribute")) {
            add(hits, "SqlAttribute", db.list("SELECT x.id, x.name, x.description, x.certified, at.term_id AS"
                    + " parent_id FROM sql_attribute x LEFT JOIN sql_attribute__term at ON at.attribute_id = x.id"
                    + " WHERE " + nameOrDesc, p));
        }
        if (types.contains("PqlAnalysis")) {
            add(hits, "PqlAnalysis", db.list("SELECT x.id, x.name, x.description, NULL AS certified, NULL AS parent_id"
                    + " FROM pql_analysis x WHERE " + nameOrDesc, p));
        }
        if (types.contains("Tag")) {
            add(hits, "Tag", db.list("SELECT x.id, x.name, NULL AS description, NULL AS certified, NULL AS parent_id"
                    + " FROM tag x WHERE LOWER(x.name) LIKE :q ESCAPE '\\'", p));
        }
        return filterTags(r, hits);
    }

    private static boolean inScope(Request r, Map<String, Object> row) {
        if (r.data() == null || r.data().isEmpty()) {
            return true;
        }
        return r.data().contains(row.get("database_id")) || r.data().contains(row.get("schema_id"));
    }

    private static void add(List<Map<String, Object>> hits, String type, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("id", row.get("id"));
            h.put("type", type);
            h.put("name", row.get("name"));
            h.put("description", row.get("description"));
            h.put("certified", row.get("certified"));
            h.put("parent_id", row.get("parent_id"));
            h.put("table_type", row.get("table_type"));
            Object syn = row.get("synonyms");
            h.put("synonyms", syn instanceof List<?> ? syn : List.of());
            List<Map<String, Object>> crumbs = new ArrayList<>();
            crumb(crumbs, "Database", row.get("database_id"), row.get("database_name"));
            crumb(crumbs, "Schema", row.get("schema_id"), row.get("schema_name"));
            crumb(crumbs, "Table", row.get("table_id"), row.get("table_name"));
            crumb(crumbs, "Term", "ColumnAttribute".equals(type) ? row.get("parent_id") : null, row.get("term_name"));
            h.put("breadcrumbs", crumbs);
            hits.add(h);
        }
    }

    private static void crumb(List<Map<String, Object>> crumbs, String type, Object id, Object name) {
        if (name != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", id);
            c.put("name", name);
            c.put("type", type);
            crumbs.add(c);
        }
    }

    private List<Map<String, Object>> filterTags(Request r, List<Map<String, Object>> hits) {
        if (r.tags() == null || r.tags().isEmpty()) {
            return hits;
        }
        boolean untagged = r.tags().contains(UNTAGGED);
        Map<String, Map<String, List<String>>> byTarget = new HashMap<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> h : hits) {
            String target = TAG_TARGET.get((String) h.get("type"));
            if (target == null) {
                continue;
            }
            List<String> names = byTarget.computeIfAbsent(target, governance::tagNamesFor)
                    .getOrDefault((String) h.get("id"), List.of());
            boolean match = names.isEmpty() ? untagged : names.stream().anyMatch(n -> r.tags().stream()
                    .anyMatch(t -> t.equalsIgnoreCase(n)));
            if (match) {
                out.add(h);
            }
        }
        return out;
    }

    private static Comparator<Map<String, Object>> ranking(Request r) {
        String[] tokens = r.searchTerm().trim().toLowerCase(Locale.ROOT).split("\\s+");
        Pattern whole = Pattern.compile("\\b" + Pattern.quote(r.searchTerm().trim().toLowerCase(Locale.ROOT)) + "\\b");
        return Comparator.<Map<String, Object>>comparingInt(h -> {
            String name = String.valueOf(h.get("name")).toLowerCase(Locale.ROOT);
            for (String t : tokens) {
                if (!name.contains(t)) {
                    return 1;
                }
            }
            return 0;
        }).thenComparingInt(h -> {
            @SuppressWarnings("unchecked")
            List<String> syn = (List<String>) h.get("synonyms");
            return syn.stream().anyMatch(s -> whole.matcher(s.toLowerCase(Locale.ROOT)).find()) ? 0 : 1;
        }).thenComparingInt(h -> String.valueOf(h.get("name")).length())
                .thenComparing(h -> String.valueOf(h.get("name")))
                .thenComparing(h -> String.valueOf(h.get("id")));
    }

    /** Target type of the tag API for a search type, or null when not taggable. */
    public static String tagTarget(String type) {
        return TAG_TARGET.get(type);
    }
}
