// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.agent;

import de.jentsch.autoontology.agent.AgentModels.CalculationSubtype;
import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.llm.Prompts;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Prompt builders of retrieval/text_to_sql/prompts.py and agents/question_intent.py. Static texts
 * are loaded verbatim from {@code prompts/*.txt}; the Python formatting functions are ported here.
 */
public final class AgentPrompts {
    private static final Set<String> SCHEMALESS = Set.of("sqlite", "duckdb");
    static final Map<String, Map<String, String>> SUBTYPES = loadSubtypes();

    private AgentPrompts() {}

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> loadSubtypes() {
        try (InputStream in = AgentPrompts.class.getClassLoader().getResourceAsStream("prompts/calculation_subtypes.json")) {
            List<Object> raw = Json.readList(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            Map<String, Map<String, String>> out = new LinkedHashMap<>();
            for (Object o : raw) {
                Map<String, String> m = (Map<String, String>) o;
                out.put(m.get("type"), Map.of("description", m.get("description").strip(),
                        "sql_template", m.get("sql_template").strip()));
            }
            Set<String> expected = new LinkedHashSet<>();
            for (CalculationSubtype s : CalculationSubtype.values()) {
                expected.add(s.name().toLowerCase(Locale.ROOT));
            }
            if (!out.keySet().equals(expected)) {
                throw new IllegalStateException("calculation_subtypes.json does not match CalculationSubtype");
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** main_system_prompt_template: today's date plus the custom prompts. */
    public static String mainSystemPrompt(LocalDate date, String customPrompts) {
        return "Today's date is: { 'Year': " + date.getYear() + ", 'Month': " + date.getMonthValue() + ", 'Day': "
                + date.getDayOfMonth() + " }.\n\n" + (customPrompts == null ? "" : customPrompts);
    }

    /** format_dialect_rules, extended by Oracle, T-SQL and H2 (FR-AGT-03). */
    public static String dialectRules(String dialect) {
        String d = dialect == null ? "" : dialect.strip().toLowerCase(Locale.ROOT);
        String name = switch (d) {
            case "sqlite" -> "dialect_sqlite";
            case "snowflake" -> "dialect_snowflake";
            case "postgres", "postgresql" -> "dialect_postgres";
            case "mysql" -> "dialect_mysql";
            case "oracle" -> "dialect_oracle";
            case "tsql", "mssql", "sqlserver" -> "dialect_tsql";
            case "h2" -> "dialect_h2";
            default -> null;
        };
        if (name == null) {
            return "";
        }
        String text = Prompts.get(name);
        return text.endsWith("\n\n") ? text : text.endsWith("\n") ? text + "\n" : text + "\n\n";
    }

    public static String projectionRules(boolean shorten) {
        return shorten ? Prompts.get("projection_rules") + Prompts.get("projection_rules_short")
                : Prompts.get("projection_rules");
    }

    /** create_sql_from_candidates_prompt: table naming is gated on the dialect. */
    public static String sqlFromCandidatesSystem(String dialect, boolean hasSqlExamples) {
        boolean bare = SCHEMALESS.contains(dialect == null ? "" : dialect.toLowerCase(Locale.ROOT));
        return Prompts.get("sql_from_candidates_" + (bare ? "bare" : "schema") + (hasSqlExamples ? "_examples" : ""));
    }

    public static String authoritativeEvidence(String evidence, boolean evidenceFirst) {
        if (evidence == null || evidence.isBlank()) {
            return "";
        }
        return Prompts.render(evidenceFirst ? "authoritative_evidence_first" : "authoritative_evidence",
                Map.of("evidence", evidence));
    }

    /** format_dual_question_block. */
    public static String dualQuestionBlock(String original, String sanitized, String processing) {
        String o = original == null ? "" : original.strip();
        String n = sanitized == null ? "" : sanitized.strip();
        String p = processing == null ? "" : processing.strip();
        if (o.equals(n)) {
            return sanitized;
        }
        String processingBlock = !p.isEmpty() && !p.equals(o) && !p.equals(n)
                ? "Standalone processing question:\n" + p + "\n\n" : "";
        return "Original user request:\n" + o + "\n\n" + processingBlock + "Normalized retrieval question:\n" + n;
    }

    /** format_custom_analyses_section. */
    public static String customAnalysesSection(List<Map<String, Object>> analyses) {
        if (analyses == null || analyses.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> a : analyses) {
            StringBuilder line = new StringBuilder("- ").append(a.getOrDefault("name", "(unnamed)"));
            String desc = a.get("description") == null ? "" : String.valueOf(a.get("description")).strip();
            if (!desc.isEmpty()) {
                line.append(": ").append(desc);
            }
            String sql = a.get("sql") == null ? "" : String.valueOf(a.get("sql")).strip();
            if (!sql.isEmpty()) {
                line.append("\n  SQL: ").append(sql);
            }
            lines.add(line.toString());
        }
        return "DOMAIN-SPECIFIC CUSTOM ANALYSES (use their SQL patterns as guidance):\n" + String.join("\n", lines)
                + "\n\n";
    }

    /** create_question_intent_prompt (agents/question_intent.py). */
    public static String questionIntent(String question, Boolean predictionOverride, boolean calculationOnly,
            List<Map<String, String>> glossary, String existingEvidence, List<String> databases,
            List<Map<String, Object>> sqlExamples) {
        String override = "";
        if (calculationOnly) {
            override = "\nThis request is calculation-only. Do not select a top-level question type. Choose its calculation"
                    + " subtype and still perform rewriting and evidence extraction.\n";
        } else if (Boolean.TRUE.equals(predictionOverride)) {
            override = "\nThe caller explicitly selected prediction mode. Return question_type \"prediction\", but still"
                    + " perform rewriting and evidence extraction.\n";
        } else if (Boolean.FALSE.equals(predictionOverride)) {
            override = "\nThe caller explicitly disabled prediction mode. Do not return question_type \"prediction\";"
                    + " classify the request as information or calculation.\n";
        }
        List<String> glossaryEntries = new ArrayList<>();
        for (Map<String, String> g : glossary == null ? List.<Map<String, String>>of() : glossary) {
            String name = g.get("name") == null ? "" : g.get("name").strip();
            if (!name.isEmpty()) {
                glossaryEntries.add("- " + name + ": " + (g.get("description") == null ? "" : g.get("description").strip()));
            }
        }
        String glossarySection = glossaryEntries.isEmpty() ? "" : "\n## Glossary context\nUse these definitions to"
                + " recognize internal terms and synonym usage. Do not copy them into extracted_evidence unless the"
                + " question itself contains the instruction.\n" + String.join("\n", glossaryEntries) + "\n";
        String evidenceSection = existingEvidence == null || existingEvidence.isBlank() ? ""
                : "\n## Existing evidence\nThis evidence is already stored separately. Use it for interpretation but do"
                        + " not repeat it in extracted_evidence.\n" + existingEvidence.strip() + "\n";
        List<String> dbs = new ArrayList<>(new LinkedHashSet<>(databases == null ? List.of() : databases));
        String databaseSection = "\n## Available databases\n" + (dbs.isEmpty() ? "(none)"
                : dbs.stream().map(d -> "- " + d).collect(Collectors.joining("\n"))) + "\n\n"
                + "Set target_db to an exact name from this list only when the question explicitly asks to use that"
                + " database. Correct an obvious typo or very close variant to the exact listed name. If no listed name is"
                + " close, return null. A database selection is routing metadata: remove its phrase from"
                + " rewritten_question and do not put it in extracted_evidence. Do not infer a database merely because its"
                + " name resembles a business entity.\n";
        List<String> blocks = new ArrayList<>();
        int number = 0;
        for (Map<String, Object> ex : sqlExamples == null ? List.<Map<String, Object>>of() : sqlExamples) {
            number++;
            String q = String.join(" ", String.valueOf(ex.getOrDefault("question", "")).split("\\s+")).strip();
            String ev = String.join(" ", String.valueOf(ex.getOrDefault("evidence", "")).split("\\s+")).strip();
            String sql = String.valueOf(ex.getOrDefault("sql", "")).strip();
            if (q.isEmpty() || sql.isEmpty()) {
                continue;
            }
            List<String> lines = new ArrayList<>(List.of(number + ". Question: " + q, "   SQL: " + sql));
            if (!ev.isEmpty()) {
                lines.add(1, "   Evidence: " + ev);
            }
            blocks.add(String.join("\n", lines));
        }
        String sqlExamplesSection = "\n## Candidate SQL examples\n" + (blocks.isEmpty() ? "(none)" : String.join("\n\n", blocks))
                + "\n\n"
                + "Return in relevant_sql_example_numbers only examples whose SQL structure fits the executable request:"
                + " the same requested output shape, aggregation or calculation pattern, grouping grain, ranking/limit"
                + " behavior, and result cardinality. Different business subjects may still share a useful pattern."
                + " Reject examples with a different aggregation, projection shape, grouping, ranking structure, or kind of"
                + " result. Do not retain an example merely because its wording or entities are similar. Return an empty"
                + " list when no example is structurally relevant. Use the one-based numbers shown above.\n";
        StringBuilder subtypes = new StringBuilder();
        for (CalculationSubtype s : CalculationSubtype.values()) {
            String key = s.name().toLowerCase(Locale.ROOT);
            if (!subtypes.isEmpty()) {
                subtypes.append('\n');
            }
            subtypes.append("- ").append(key).append(": ").append(SUBTYPES.get(key).get("description"))
                    .append("\n  SQL shape example:\n");
            subtypes.append(SUBTYPES.get(key).get("sql_template").lines().map(l -> "    " + l)
                    .collect(Collectors.joining("\n")));
        }
        String tasks = calculationOnly ? """
                Perform four tasks in one structured response:
                1. Choose exactly one calculation subtype.
                2. Rewrite the executable question without supporting inline instructions.
                3. Return any removed supporting instruction as extracted_evidence.
                4. Filter the candidate SQL examples by structural relevance.""" : """
                Perform five tasks in one structured response:
                1. Classify the question as information, prediction, or calculation.
                2. For calculation only, choose exactly one calculation subtype.
                3. Rewrite the executable question without supporting inline instructions.
                4. Return any removed supporting instruction as extracted_evidence.
                5. Filter the candidate SQL examples by structural relevance.""";
        String questionTypes = calculationOnly ? "" : """

                ## Question types
                - information: asks about catalog or semantic metadata, including what a dataset,
                  table, or column means, contains, or connects to, and how an existing measure is
                  defined or calculated. "How is revenue calculated/defined?" is information because
                  it asks for the stored definition rather than requesting a result.
                - prediction: asks to predict, forecast, estimate, project, or assess the likelihood
                  of a future or currently unknown outcome. A query about existing historical rows
                  is not prediction merely because it contains a date.
                - calculation: asks the data system to match/filter, rank, compare, count, aggregate,
                  compute, or interpret values. "Calculate revenue for 2026" is calculation because
                  it requests a derived value. If the type is uncertain, choose calculation.
                """;
        return "You prepare a question for a data agent.\n" + tasks + "\n" + override + "\n" + questionTypes + "\n"
                + "## Calculation subtypes\n"
                + "Use each SQL example only to understand the subtype's structural pattern. The\n"
                + "identifiers and literal values belong to the example and are not instructions for\n"
                + "the user's question.\n" + subtypes + "\n\n"
                + "## Evidence separation\n"
                + "Move text to extracted_evidence only when it instructs the agent how or where to\n"
                + "interpret/calculate the answer, such as a table hint, formula, definition, mapping,\n"
                + "or domain rule. A configured database hint belongs only in target_db, never in\n"
                + "extracted_evidence. Keep actual business filters, dates, entities, requested measures,\n"
                + "grouping, ranking, and output constraints in rewritten_question.\n\n"
                + "Do not invent evidence. If no evidence is embedded in the question, return an empty\n"
                + "string. The rewritten question must remain understandable on its own.\n\n"
                + "Examples:\n"
                + "- \"find revenue in test_dataset dataset\"\n"
                + "  → rewritten_question: \"find revenue\"\n"
                + "  → extracted_evidence: \"\"\n"
                + "  → target_db: \"test_dataset\" (only when it appears in Available databases)\n"
                + "- \"What is revenue by region, where revenue means gross_sales minus refunds?\"\n"
                + "  → rewritten_question: \"What is revenue by region?\"\n"
                + "  → extracted_evidence: \"Revenue means gross_sales minus refunds.\"\n"
                + "- \"List the top 5 products by revenue in 2025\"\n"
                + "  → rewritten_question: unchanged\n"
                + "  → extracted_evidence: \"\"\n"
                + databaseSection + glossarySection + evidenceSection + sqlExamplesSection + "\n"
                + "Question:\n" + question;
    }

    /** create_intent_validation_prompt. */
    public static String intentValidation(String original, String processing, String sanitized, String sql,
            String customAnalyses, String joinPaths, boolean joinsValidatedElsewhere, boolean hasEvidence,
            String usedSchemaContext, boolean shortenAnswer) {
        String questionBlock = dualQuestionBlock(original, sanitized, processing);
        String caBlock = customAnalyses != null && !customAnalyses.isBlank() ? "\n" + customAnalyses : "";
        String joinPathsBlock;
        String joinCriterion;
        String authoritativeNote;
        if (joinsValidatedElsewhere) {
            joinPathsBlock = "";
            joinCriterion = "1. Every join in this query is already known to be real — do not question whether it exists."
                    + " Flag it only if either (a) it is self-evidently broken regardless of any alternative (a"
                    + " tautological condition, a table joined to itself, columns of clearly unrelated meaning being"
                    + " equated), or (b) it clearly reaches the wrong entity for the question and you can name a specific,"
                    + " better-fitting real relationship instead. Do not flag a join just because it merely looks"
                    + " unfamiliar (different fields/roles for the same concept, e.g. customer vs supplier delivery city"
                    + " for a region filter, are OK).";
            authoritativeNote = "";
        } else {
            joinPathsBlock = joinPaths != null && !joinPaths.isBlank() ? "\n" + joinPaths : "";
            joinCriterion = "1. Are any joins nonsensical or clearly broken for the question? Alternate but plausible join"
                    + " paths that could still answer it are OK — including different fields/roles for the same concept"
                    + " (e.g. customer vs supplier delivery city for a region filter). Do NOT fail for those.";
            authoritativeNote = "\nIf AUTHORITATIVE JOIN PATHS are listed above, do not flag a generated join that follows"
                    + " one of those verified paths.";
        }
        String evidenceCriterion = hasEvidence ? "\n3. Does the SQL follow EVERY instruction in the Authoritative"
                + " Evidence exactly? Evidence compliance is strict, not lenient. Flag any missing or substituted"
                + " evidence-defined value, column/table mapping, filter, operator, or formula as a critical evidence"
                + " issue." : "";
        String projectionCriterion = shortenAnswer ? "\n4. STRICT PROJECTION CHECK (this overrides all leniency"
                + " instructions): Compare the outermost SELECT output with the exact output fields requested by the"
                + " question and Authoritative Evidence. The SQL must return every requested field, exactly once, in the"
                + " requested shape, and NO additional fields. Aggregates and transformed values count as output fields."
                + " Never accept extra context as helpful, and never remove a requested field. Record every extra,"
                + " missing, duplicated, reordered, or reshaped field in projection_issues and mark the SQL invalid." : "";
        String parsedUsage = "\nUSED SQL OBJECTS (catalog metadata only for tables and columns referenced by the query):\n"
                + usedSchemaContext + "\n";
        return "User's Question:\n" + questionBlock + "\n" + caBlock + "\n" + joinPathsBlock + "\n" + parsedUsage
                + "\nGenerated SQL Query:\n```sql\n" + sql + "\n```\n\n"
                + "Check for CRITICAL issues ONLY (be lenient):\n" + joinCriterion + "\n"
                + "2. Are aggregations CLEARLY WRONG for the question? (e.g., COUNT when explicitly asking for SUM)"
                + " (Variations are OK)\n" + evidenceCriterion + "\n" + projectionCriterion + "\n\n"
                + "Only mark as invalid if there are SERIOUS problems. If the SQL could reasonably work, mark it as VALID.\n"
                + "If DOMAIN-SPECIFIC CUSTOM ANALYSES are listed above, treat their SQL as intentional domain definitions"
                + " — do not flag the generated query as invalid merely for following those patterns." + authoritativeNote
                + "\n\nProvide your analysis.";
    }
}
