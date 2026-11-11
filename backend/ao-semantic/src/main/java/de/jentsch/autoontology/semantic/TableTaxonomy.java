// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.semantic;

import de.jentsch.autoontology.llm.LlmGateway;
import de.jentsch.autoontology.llm.LlmMessage;
import de.jentsch.autoontology.llm.ModelRole;
import de.jentsch.autoontology.llm.Prompts;
import de.jentsch.autoontology.semantic.Profiler.ColumnProfile;
import de.jentsch.autoontology.semantic.SemanticModels.ColumnDescriptionResult;
import de.jentsch.autoontology.semantic.SemanticModels.FkAndPkResult;
import de.jentsch.autoontology.semantic.SemanticModels.PotentialFkSuggestion;
import de.jentsch.autoontology.semantic.SemanticModels.RawTableTermsResult;
import de.jentsch.autoontology.semantic.SemanticModels.RawTermProposal;
import de.jentsch.autoontology.semantic.SemanticModels.SynonymExtraction;
import de.jentsch.autoontology.semantic.SemanticModels.TermColumnRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LLM steps of {@code process_table} (FR-SEM-02 steps 2–4): FK suggestions and junction decision
 * (fk_suggester.py), attribute candidates with descriptions (deterministic.py), Term extraction and
 * synonyms (term_extractor.py), including the sanitizing of the LLM output.
 */
public class TableTaxonomy {
    private static final Set<String> INVALID = Set.of("unnamed", "unknown", "none", "");
    private static final Pattern LABEL = Pattern.compile("^[A-Z][A-Za-z0-9]*(?: [A-Za-z][A-Za-z0-9]*)*$");
    private static final String FORMAT_MARKER = "format:";
    private static final int DESCRIPTION_BATCH = 15;

    private final LlmGateway llm;

    public TableTaxonomy(LlmGateway llm) {
        this.llm = llm;
    }

    /** Attribute candidate (ColumnAttributeSpec). */
    public record Spec(String sourceColumn, String name, String datatype, String description, String columnId) {}

    /** Sanitized Term with column assignments (TermProposal). */
    public record TermProposal(String name, String description, List<String> synonyms, Map<String, String> attributes) {}

    /** FK decision of one table (PotentialFkResult). */
    public record FkResult(List<PotentialFkSuggestion> suggestions, boolean junction, String rationale) {}

    // ------------------------------------------------------------------ naming helpers (deterministic.py)

    public static String toTermName(String tableName) {
        String s = tableName.strip().replaceAll("(?<=[A-Z])(?=[A-Z][a-z])", " ")
                .replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ");
        List<String> out = new ArrayList<>();
        for (String part : s.split("[^A-Za-z0-9]+")) {
            if (part.isEmpty()) {
                continue;
            }
            out.add(part.equals(part.toUpperCase(Locale.ROOT)) && part.chars().anyMatch(Character::isLetter)
                    ? part : capitalize(part));
        }
        return String.join(" ", out);
    }

    private static String capitalize(String p) {
        return p.isEmpty() ? p : Character.toUpperCase(p.charAt(0)) + p.substring(1).toLowerCase(Locale.ROOT);
    }

    /** {@code _column_to_attr_name}: snake → lowerCamel. */
    public static String columnToAttrName(String column) {
        String[] parts = column.strip().split("[_\\s]+");
        if (parts.length == 0) {
            return column;
        }
        StringBuilder sb = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(capitalize(parts[i]));
            }
        }
        return sb.toString();
    }

    static String normalizeLabel(String name, String fallback) {
        String cleaned = name == null ? "" : name.strip();
        if (INVALID.contains(cleaned.toLowerCase(Locale.ROOT)) || !LABEL.matcher(cleaned).matches()) {
            return fallback;
        }
        return toTermName(cleaned);
    }

    // ------------------------------------------------------------------ FK suggestions

    public FkResult suggestForeignKeys(Map<String, Object> table, List<Map<String, Object>> columns,
            List<Map<String, Object>> declaredFks, Map<String, ColumnProfile> profiles, Set<String> fkTargetColumns) {
        @SuppressWarnings("unchecked")
        Set<String> pk = new HashSet<>((List<String>) table.getOrDefault("pk", List.of()));
        Set<String> knownFk = declaredFks.stream().map(f -> (String) f.get("source_column")).collect(Collectors.toSet());
        Set<String> excluded = new HashSet<>(pk);
        excluded.addAll(knownFk);
        List<Map<String, Object>> candidates = columns.stream()
                .filter(c -> !excluded.contains(c.get("name")) && !fkTargetColumns.contains(c.get("name"))).toList();
        if (candidates.isEmpty() && columns.size() <= 2) {
            return new FkResult(List.of(), false, "");
        }
        String header = table.get("schema_name") == null ? (String) table.get("name")
                : table.get("schema_name") + "." + table.get("name");
        String allLines = columns.stream().map(c -> columnLine(c, profiles.get(c.get("name"))))
                .collect(Collectors.joining("\n"));
        String candLines = candidates.isEmpty() ? "  (none)" : candidates.stream()
                .map(c -> columnLine(c, profiles.get(c.get("name")))).collect(Collectors.joining("\n"));
        String fkLines = declaredFks.isEmpty() ? "  (none)" : declaredFks.stream()
                .map(f -> "  - " + f.get("source_column") + " -> " + f.get("target_table")).collect(Collectors.joining("\n"));
        String prompt = "Table: " + header + "\n"
                + "Description: " + nz(table.get("description")) + "\n"
                + "Primary key columns (exclude from suggestions): " + (pk.isEmpty() ? "(none)"
                        : pk.stream().sorted().collect(Collectors.joining(", "))) + "\n"
                + "Known foreign key columns (exclude from suggestions): " + (knownFk.isEmpty() ? "(none)"
                        : knownFk.stream().sorted().collect(Collectors.joining(", "))) + "\n"
                + "Known foreign key roles:\n" + fkLines + "\n"
                + "All table columns:\n" + allLines + "\n"
                + "Candidate columns:\n" + candLines + "\n";
        FkAndPkResult r = llm.tryStructured(ModelRole.REASONING, List.of(
                LlmMessage.system(Prompts.get("fk_suggester__system")), LlmMessage.user(prompt)), FkAndPkResult.class)
                .orElse(null);
        if (r == null) {
            return new FkResult(List.of(), false, "");
        }
        Set<String> llmPk = new HashSet<>();
        for (String n : r.pk_column_names()) {
            ColumnProfile p = profiles.get(n.strip());
            if (p == null || !Boolean.FALSE.equals(p.isUnique())) {
                llmPk.add(n.strip());
            }
        }
        Set<String> allowed = candidates.stream().map(c -> (String) c.get("name")).collect(Collectors.toSet());
        List<PotentialFkSuggestion> filtered = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PotentialFkSuggestion s : r.fk_suggestions()) {
            String n = s.column_name().strip();
            if (!n.isEmpty() && allowed.contains(n) && seen.add(n)) {
                filtered.add(new PotentialFkSuggestion(n, nz(s.rationale()).strip()));
            }
        }
        for (Map<String, Object> c : candidates) {
            String n = (String) c.get("name");
            ColumnProfile p = profiles.get(n);
            if (!seen.contains(n) && "uuid".equalsIgnoreCase(String.valueOf(c.get("data_type"))) && !llmPk.contains(n)
                    && p != null && Boolean.FALSE.equals(p.isUnique())) {
                seen.add(n);
                filtered.add(new PotentialFkSuggestion(n,
                        "uuid type, not a declared or inferred primary key — almost certainly references another entity"));
            }
        }
        return new FkResult(filtered, r.is_junction_table(), nz(r.junction_table_rationale()).strip());
    }

    private static String columnLine(Map<String, Object> c, ColumnProfile p) {
        StringBuilder suffix = new StringBuilder();
        if (c.get("description") != null && !String.valueOf(c.get("description")).isBlank()) {
            suffix.append(" — ").append(c.get("description"));
        }
        if (p != null && !p.sampleValues().isEmpty()) {
            List<String> vals = p.sampleValues().stream().map(String::valueOf).filter(v -> v.length() <= 30).toList();
            if (!vals.isEmpty()) {
                suffix.append(" [samples: ").append(String.join(", ", vals)).append("]");
            }
        }
        if (p != null && p.isUnique() != null) {
            suffix.append(" [is_unique: ").append(p.isUnique()).append("]");
        }
        return "  - " + c.get("name") + " (" + nz(c.get("data_type")) + ")" + suffix;
    }

    // ------------------------------------------------------------------ attribute specs

    /** Non-FK columns 1:1 as candidates; descriptions from the LLM where none exist (column_attribute_specs). */
    public List<Spec> attributeSpecs(List<Map<String, Object>> columns, Set<String> fkColumns,
            Map<String, ColumnProfile> profiles, boolean samplesToLlm) {
        List<Map<String, Object>> candidates = columns.stream().filter(c -> !fkColumns.contains(c.get("name"))).toList();
        List<Map<String, Object>> without = candidates.stream()
                .filter(c -> c.get("description") == null || String.valueOf(c.get("description")).isBlank()).toList();
        Map<String, String> llmDescriptions = describe(without, profiles, samplesToLlm);
        List<Spec> specs = new ArrayList<>();
        for (Map<String, Object> c : candidates) {
            String name = (String) c.get("name");
            String desc = (String) c.get("description");
            boolean has = desc != null && !desc.isBlank();
            String text = has ? addSamples(desc, samplesFor(c, profiles, true)) : llmDescriptions.get(name);
            specs.add(new Spec(name, columnToAttrName(name), nz(c.get("data_type")),
                    enrich(text, profiles.get(name), (String) c.get("format")), (String) c.get("id")));
        }
        return specs;
    }

    private static List<String> samplesFor(Map<String, Object> c, Map<String, ColumnProfile> profiles, boolean allowed) {
        if (!allowed || DateFormats.isDateType((String) c.get("data_type"))) {
            return List.of();
        }
        ColumnProfile p = profiles.get(c.get("name"));
        return p == null ? List.of() : p.sampleValues().stream().map(String::valueOf).toList();
    }

    private static String addSamples(String text, List<String> samples) {
        return samples.isEmpty() ? text : text + " — samples: " + String.join(", ", samples);
    }

    private static String enrich(String text, ColumnProfile p, String storedFormat) {
        String notation = p != null && p.format() != null ? p.format() : storedFormat;
        String t = text == null ? "" : text;
        if (notation != null && !t.contains(FORMAT_MARKER)) {
            t = t.isEmpty() ? FORMAT_MARKER + " " + notation : t + " — " + FORMAT_MARKER + " " + notation;
        }
        return t.isEmpty() ? null : t;
    }

    private Map<String, String> describe(List<Map<String, Object>> columns, Map<String, ColumnProfile> profiles,
            boolean samplesToLlm) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i < columns.size(); i += DESCRIPTION_BATCH) {
            List<Map<String, Object>> batch = columns.subList(i, Math.min(columns.size(), i + DESCRIPTION_BATCH));
            String lines = batch.stream().map(c -> addSamples("  - " + c.get("name") + " ("
                    + (c.get("data_type") == null ? "unknown" : c.get("data_type")) + ")",
                    samplesFor(c, profiles, samplesToLlm))).collect(Collectors.joining("\n"));
            ColumnDescriptionResult r = llm.tryStructured(ModelRole.REASONING, List.of(
                    LlmMessage.system(Prompts.get("deterministic__description_system")),
                    LlmMessage.user("Columns:\n" + lines)), ColumnDescriptionResult.class).orElse(null);
            if (r == null) {
                continue;
            }
            List<String> requested = batch.stream().map(c -> (String) c.get("name")).toList();
            for (var d : r.descriptions()) {
                String raw = nz(d.column_name());
                String desc = nz(d.description()).strip();
                if (desc.isEmpty()) {
                    continue;
                }
                if (requested.contains(raw)) {
                    out.putIfAbsent(raw, desc);
                } else {
                    requested.stream().filter(raw::startsWith).max((a, b) -> Integer.compare(a.length(), b.length()))
                            .ifPresent(n -> out.putIfAbsent(n, desc));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ term extraction

    public List<TermProposal> extractTerms(Map<String, Object> table, List<Spec> specs, String domainBlock) {
        if (specs.isEmpty()) {
            return fallback(table, specs);
        }
        String description = nz(table.get("description"));
        String specLines = specs.stream().limit(40).map(s -> "  - source_column=" + s.sourceColumn() + " ("
                + s.datatype() + ")" + (s.description() != null ? " — " + s.description() : ""))
                .collect(Collectors.joining("\n"));
        String prompt = "Table: " + table.get("name") + "\n"
                + "Description: " + description + "\n"
                + "Candidate columns (assign each to exactly one Term with a display_name):\n"
                + specLines + "\n"
                + (domainBlock == null ? "" : domainBlock);
        RawTableTermsResult r = llm.tryStructured(ModelRole.REASONING, List.of(
                LlmMessage.system(Prompts.get("term_extractor__system")), LlmMessage.user(prompt)),
                RawTableTermsResult.class).orElse(null);
        List<TermProposal> result = r == null ? fallback(table, specs) : sanitize(r, table, specs);
        List<String> synonyms = synonyms(description);
        if (!synonyms.isEmpty()) {
            result = result.stream().map(t -> new TermProposal(t.name(), t.description(), synonyms, t.attributes()))
                    .toList();
        }
        return result;
    }

    private List<String> synonyms(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        return llm.tryStructured(ModelRole.REASONING, List.of(
                LlmMessage.system(Prompts.get("term_extractor__synonym_system")),
                LlmMessage.user("Description: " + description)), SynonymExtraction.class)
                .map(s -> s.synonyms().stream().map(String::strip).filter(x -> !x.isEmpty()).toList())
                .orElse(List.of());
    }

    static List<TermProposal> fallback(Map<String, Object> table, List<Spec> specs) {
        Map<String, String> attrs = new LinkedHashMap<>();
        specs.forEach(s -> attrs.put(s.sourceColumn(), s.name()));
        return List.of(new TermProposal(toTermName((String) table.get("name")),
                "Business entity represented by table " + table.get("name"), List.of(), attrs));
    }

    static List<TermProposal> sanitize(RawTableTermsResult raw, Map<String, Object> table, List<Spec> specs) {
        Map<String, Spec> byColumn = new LinkedHashMap<>();
        specs.forEach(s -> byColumn.put(s.sourceColumn(), s));
        String defaultTerm = toTermName((String) table.get("name"));
        List<TermProposal> terms = new ArrayList<>();
        Set<String> seenTerms = new HashSet<>();
        Set<String> assigned = new HashSet<>();
        for (RawTermProposal t : raw.terms()) {
            String name = normalizeLabel(t.name(), defaultTerm);
            if (!seenTerms.add(name)) {
                continue;
            }
            Map<String, String> attrs = new LinkedHashMap<>();
            for (TermColumnRef a : t.attributes() == null ? List.<TermColumnRef>of() : t.attributes()) {
                String col = nz(a.source_column()).strip();
                if (col.isEmpty() || !byColumn.containsKey(col) || assigned.contains(col)) {
                    continue;
                }
                attrs.put(col, normalizeLabel(a.display_name(), byColumn.get(col).name()));
                assigned.add(col);
            }
            terms.add(new TermProposal(name, nz(t.description()).strip(), List.of(), attrs));
        }
        if (terms.isEmpty()) {
            return fallback(table, specs);
        }
        // unassigned columns fall back to the first Term
        for (Spec s : specs) {
            if (!assigned.contains(s.sourceColumn())) {
                terms.get(0).attributes().put(s.sourceColumn(), s.name());
            }
        }
        return terms;
    }

    static String nz(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
