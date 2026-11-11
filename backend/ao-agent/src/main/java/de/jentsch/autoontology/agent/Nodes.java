// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.agent;

import de.jentsch.autoontology.agent.AgentModels.CombinedCandidateFilterModel;
import de.jentsch.autoontology.agent.AgentModels.EmptyLikeCheckModel;
import de.jentsch.autoontology.agent.AgentModels.ErrorAnalysis;
import de.jentsch.autoontology.agent.AgentModels.ErrorType;
import de.jentsch.autoontology.agent.AgentModels.InformationAgentAction;
import de.jentsch.autoontology.agent.AgentModels.IntentValidationModel;
import de.jentsch.autoontology.agent.AgentModels.QuestionExtractionModel;
import de.jentsch.autoontology.agent.AgentModels.QuestionIntentModel;
import de.jentsch.autoontology.agent.AgentModels.QuestionType;
import de.jentsch.autoontology.agent.AgentModels.SQLGenerationModel;
import de.jentsch.autoontology.agent.AgentModels.TableRelevanceModel;
import de.jentsch.autoontology.agent.AgentModels.TableRemovalModel;
import de.jentsch.autoontology.agent.Retrieval.Candidate;
import de.jentsch.autoontology.common.Json;
import de.jentsch.autoontology.connectors.DbErrors;
import de.jentsch.autoontology.connectors.SourceDatabase;
import de.jentsch.autoontology.connectors.TabularResult;
import de.jentsch.autoontology.llm.LlmMessage;
import de.jentsch.autoontology.llm.ModelRole;
import de.jentsch.autoontology.llm.Prompts;
import de.jentsch.autoontology.vector.VectorStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Agent nodes (retrieval/text_to_sql/agents/*.py). Each method is one node of the graph; the
 * key names in {@code path_state} follow the original.
 */
public class Nodes {
    static final int MAX_ENTITY_HITS = 6;
    static final double MAX_DISTANCE = 0.75;
    static final int MAX_TABLES = 12;
    static final int MAX_INFORMATION_STEPS = 6;
    private static final Pattern LITERAL_EQ = Pattern.compile(
            "(?i)((?:\"?[A-Za-z_][A-Za-z0-9_]*\"?\\.)?\"?([A-Za-z_][A-Za-z0-9_]*)\"?)\\s*(=|<>|!=|IN\\s*\\(|LIKE)\\s*'([^']*)'");

    private final AgentServices s;

    public Nodes(AgentServices services) {
        this.s = services;
    }

    // ================================================================== question_intent

    public void questionIntent(AgentState st) {
        List<String> dbs = st.connectors.stream().map(SourceDatabase::databaseName).distinct().toList();
        String prompt = AgentPrompts.questionIntent(st.question(), st.predictionOverride, st.calculationOnly, st.glossary,
                st.evidence, dbs, st.sqlExamples);
        QuestionIntentModel intent = s.llm().tryStructured(ModelRole.NON_REASONING, List.of(LlmMessage.system(prompt)),
                QuestionIntentModel.class).orElse(null);
        String type;
        String subtype;
        String rewritten = st.question();
        String extracted = "";
        if (intent == null) {
            type = Boolean.TRUE.equals(st.predictionOverride) ? "prediction" : "calculation";
            subtype = "prediction".equals(type) ? null : "match_based";
        } else {
            type = st.calculationOnly ? "calculation" : intent.question_type().name().toLowerCase(Locale.ROOT);
            subtype = intent.calculation_subtype() == null ? null : intent.calculation_subtype().name().toLowerCase(Locale.ROOT);
            rewritten = intent.rewritten_question() == null || intent.rewritten_question().isBlank() ? st.question()
                    : intent.rewritten_question().strip();
            extracted = intent.extracted_evidence() == null ? "" : intent.extracted_evidence().strip();
            List<Integer> keep = intent.relevant_sql_example_numbers() == null ? List.of()
                    : intent.relevant_sql_example_numbers();
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (int i = 0; i < st.sqlExamples.size(); i++) {
                if (keep.contains(i + 1)) {
                    filtered.add(st.sqlExamples.get(i));
                }
            }
            st.sqlExamples = filtered;
            if (Boolean.FALSE.equals(st.predictionOverride) && "prediction".equals(type)) {
                type = "calculation";
                subtype = "numeric_computation";
            }
            if (intent.target_db() != null && !intent.target_db().isBlank()) {
                String resolved = resolveDatabase(intent.target_db(), dbs);
                if (resolved != null) {
                    Object existing = st.get("target_db");
                    if (existing != null && !resolved.equalsIgnoreCase(String.valueOf(existing))) {
                        throw new IllegalArgumentException("Database selected in the question conflicts with the"
                                + " caller-selected target_db '" + existing + "': '" + resolved + "' was requested.");
                    }
                    st.put("target_db", resolved);
                }
            }
        }
        if (Boolean.TRUE.equals(st.predictionOverride)) {
            type = "prediction";
            subtype = null;
        }
        st.put("question_type", type);
        st.put("calculation_subtype", subtype);
        st.put("sql_template", subtype == null ? null : AgentPrompts.SUBTYPES.get(subtype).get("sql_template"));
        st.put("extracted_evidence", extracted);
        st.put("normalized_question", rewritten);
        st.evidence = mergeEvidence(st.evidence, extracted);
        st.recordThought("question_intent", "Question type: " + type + (subtype == null ? "" : " (" + subtype + ")")
                + (extracted.isEmpty() ? "" : ". Evidence: " + extracted));
    }

    static String mergeEvidence(String existing, String extracted) {
        String e = existing == null ? "" : existing.strip();
        String x = extracted == null ? "" : extracted.strip();
        if (x.isEmpty()) {
            return e;
        }
        if (e.isEmpty()) {
            return x;
        }
        return e.toLowerCase(Locale.ROOT).contains(x.toLowerCase(Locale.ROOT)) ? e : e + "\n" + x;
    }

    /** Exact (case-insensitive) or close match (similarity ≥ 0.8, difflib-like). */
    static String resolveDatabase(String requested, List<String> available) {
        String r = requested.strip();
        for (String d : available) {
            if (d.equalsIgnoreCase(r)) {
                return d;
            }
        }
        String best = null;
        double bestScore = 0.8;
        for (String d : available) {
            double score = similarity(r.toLowerCase(Locale.ROOT), d.toLowerCase(Locale.ROOT));
            if (score >= bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    static double similarity(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                dp[i][j] = a.charAt(i - 1) == b.charAt(j - 1) ? dp[i - 1][j - 1] + 1 : Math.max(dp[i - 1][j], dp[i][j - 1]);
            }
        }
        return a.isEmpty() && b.isEmpty() ? 1 : 2.0 * dp[a.length()][b.length()] / (a.length() + b.length());
    }

    // ================================================================== question_extraction

    public void questionExtraction(AgentState st) {
        String question = st.get("normalized_question") == null ? st.question() : st.get("normalized_question");
        String prompt = Prompts.render("entity_extraction", Map.of("question", question,
                "max_entities", s.props().agent().sqlGenMaxEntities()));
        StringBuilder full = new StringBuilder(prompt);
        if (!st.glossary.isEmpty()) {
            full.append("\nGlossary:\n");
            st.glossary.forEach(g -> full.append("- ").append(g.get("name")).append(": ").append(g.get("description"))
                    .append('\n'));
        }
        if (st.evidence != null && !st.evidence.isBlank()) {
            full.append("\nSupporting context (do not extract entities from it):\n").append(st.evidence).append('\n');
        }
        QuestionExtractionModel m = s.llm().tryStructured(ModelRole.NON_REASONING, List.of(LlmMessage.user(full.toString())),
                QuestionExtractionModel.class).orElse(null);
        List<String> entities;
        String sanitized = question;
        String subject = null;
        if (m == null) {
            entities = List.of(question);
        } else {
            sanitized = m.sanitized_question() == null || m.sanitized_question().isBlank() ? question : m.sanitized_question();
            entities = m.required_entity_name().stream().map(String::strip).filter(e -> !e.isEmpty()).distinct()
                    .limit(s.props().agent().sqlGenMaxEntities()).toList();
            subject = m.subject();
            if (entities.isEmpty()) {
                entities = List.of(sanitized);
            }
        }
        st.put("sanitized_question", sanitized);
        st.put("entities", entities);
        st.put("subject", subject);
        st.recordThought("question_extraction", "Entities: " + String.join(", ", entities));
    }

    // ================================================================== retrieve_candidates

    public void retrieveCandidates(AgentState st) {
        @SuppressWarnings("unchecked")
        List<String> entities = (List<String>) st.pathState.getOrDefault("entities", List.of(st.question()));
        String target = st.get("target_db");
        List<Candidate> all = new ArrayList<>();
        for (String entity : entities) {
            all.addAll(Retrieval.search(s, VectorStore.SEMANTIC_LAYER, entity, entity, MAX_ENTITY_HITS, target,
                    Retrieval.SEMANTIC_LABELS, MAX_DISTANCE));
        }
        String subject = st.get("subject");
        if (subject != null && !subject.isBlank()) {
            all.addAll(Retrieval.search(s, VectorStore.SEMANTIC_LAYER, subject, subject, 3, target, List.of("Term"),
                    MAX_DISTANCE));
        }
        String sanitized = st.get("sanitized_question");
        String whole = (sanitized == null ? st.question() : sanitized) + (st.evidence.isBlank() ? "" : "\n" + st.evidence);
        all.addAll(Retrieval.search(s, VectorStore.SEMANTIC_LAYER, whole, "__question__", MAX_ENTITY_HITS, target,
                List.of("SqlAttribute", "CustomAnalysis"), MAX_DISTANCE));
        // data layer: tables and columns, needed when the semantic layer is (still) empty
        List<Candidate> data = new ArrayList<>();
        for (String entity : entities) {
            data.addAll(Retrieval.search(s, VectorStore.DATA_LAYER, entity, entity, MAX_ENTITY_HITS, target,
                    List.of("Table", "Column"), null));
        }
        data.addAll(Retrieval.search(s, VectorStore.DATA_LAYER, whole, "__question__", MAX_ENTITY_HITS, target,
                List.of("Table"), null));
        List<String> connectorDbs = st.connectors.stream().map(SourceDatabase::databaseName).toList();
        String db = target;
        if (db == null) {
            db = Retrieval.chooseDatabase(all.stream().filter(c -> connectorDbs.contains(c.databaseName())).toList(), entities);
            if (db == null) {
                db = Retrieval.chooseDatabase(data.stream().filter(c -> connectorDbs.contains(c.databaseName())).toList(),
                        entities);
            }
            if (db == null && st.connectors.size() == 1) {
                db = st.connectors.get(0).databaseName();
            }
        }
        final String chosen = db;
        List<Candidate> semantic = Retrieval.dedupe(all.stream().filter(c -> chosen == null || chosen.equals(c.databaseName()))
                .toList());
        List<Candidate> dataHits = Retrieval.dedupe(data.stream().filter(c -> chosen == null || chosen.equals(c.databaseName()))
                .toList());
        // LLM relevance filter for custom analyses and SQL attributes in one call
        List<Candidate> ca = semantic.stream().filter(c -> "CustomAnalysis".equals(c.label())).toList();
        List<Candidate> sa = semantic.stream().filter(c -> "SqlAttribute".equals(c.label())).toList();
        if (!ca.isEmpty() || !sa.isEmpty()) {
            String prompt = Prompts.render("candidates_retrieval__combined_filter_prompt", Map.of(
                    "question", st.question(),
                    "custom_block", ca.isEmpty() ? "(none)" : ca.stream().map(c -> "- id=" + c.id() + " | " + c.text())
                            .collect(Collectors.joining("\n")),
                    "sql_attr_block", sa.isEmpty() ? "(none)" : sa.stream().map(c -> "- id=" + c.id() + " | " + c.text())
                            .collect(Collectors.joining("\n"))));
            CombinedCandidateFilterModel f = s.llm().tryStructured(ModelRole.NON_REASONING,
                    List.of(LlmMessage.user(prompt)), CombinedCandidateFilterModel.class).orElse(null);
            if (f != null) {
                Set<String> keep = new java.util.HashSet<>(f.custom_analysis_ids());
                keep.addAll(f.sql_attribute_ids());
                semantic = semantic.stream().filter(c -> !("CustomAnalysis".equals(c.label())
                        || "SqlAttribute".equals(c.label())) || keep.contains(c.id())).toList();
            }
        }
        st.put("target_database", chosen);
        st.put("candidates", semantic.stream().map(Candidate::asMap).toList());
        st.put("data_candidates", dataHits.stream().map(Candidate::asMap).toList());
        st.recordThought("retrieve_candidates", semantic.size() + " semantic candidates" + (chosen == null ? ""
                : " in database " + chosen) + ": " + semantic.stream().limit(8).map(Candidate::name)
                .collect(Collectors.joining(", ")));
    }

    // ================================================================== information_agent

    public void informationAgent(AgentState st) {
        List<String> observations = new ArrayList<>();
        String answer = null;
        String tools = """
                Available read-only tools (call one per step via `tool` and `argument`):
                - list_databases: no argument
                - list_tables: argument = database name (optional)
                - list_columns: argument = table name or id
                - list_terms: argument = optional search text
                - get_term: argument = term name or id
                - search_semantic_layer: argument = search text
                Set `final_answer` (Markdown) and leave `tool` null once you can answer.""";
        for (int step = 0; step < MAX_INFORMATION_STEPS && answer == null; step++) {
            String prompt = "You answer questions about the metadata of the data catalog and the semantic layer"
                    + " (databases, tables, columns, business terms, attributes, measures). Answer in the language of"
                    + " the question.\n\n" + tools + "\n\nQuestion: " + st.question() + "\n\nObservations so far:\n"
                    + (observations.isEmpty() ? "(none)" : String.join("\n\n", observations));
            InformationAgentAction a = s.llm().tryStructured(ModelRole.REASONING, List.of(LlmMessage.user(prompt)),
                    InformationAgentAction.class).orElse(null);
            if (a == null) {
                break;
            }
            st.recordThought("information_agent", a.thought());
            if (a.tool() == null || a.tool().isBlank()) {
                answer = a.final_answer();
                break;
            }
            observations.add("[" + a.tool() + "(" + (a.argument() == null ? "" : a.argument()) + ")]\n"
                    + truncate(tool(a.tool(), a.argument()), 4000));
        }
        if (answer == null || answer.isBlank()) {
            answer = observations.isEmpty() ? "I could not find metadata for this question."
                    : "Here is what I found:\n\n" + String.join("\n\n", observations);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("response", answer);
        response.put("sql_code", "");
        response.put("sql_columns", List.of());
        response.put("custom_analyses_used", List.of());
        response.put("sql_response_from_db", null);
        st.put("final_response", response);
    }

    private String tool(String name, String arg) {
        try {
            return switch (name) {
                case "list_databases" -> s.catalog().databases().stream()
                        .map(d -> "- " + d.get("name") + (d.get("description") == null ? "" : ": " + d.get("description")))
                        .collect(Collectors.joining("\n"));
                case "list_tables" -> s.catalog().allTables().stream()
                        .filter(t -> arg == null || arg.isBlank() || arg.equalsIgnoreCase((String) t.get("database_name")))
                        .map(t -> "- " + t.get("database_name") + "." + t.get("schema_name") + "." + t.get("name")
                                + " (id " + t.get("id") + ")" + (t.get("description") == null ? "" : ": " + t.get("description")))
                        .collect(Collectors.joining("\n"));
                case "list_columns" -> s.catalog().allTables().stream()
                        .filter(t -> String.valueOf(arg).equalsIgnoreCase((String) t.get("name")) || String.valueOf(arg).equals(t.get("id")))
                        .flatMap(t -> s.catalog().columnsOfTable((String) t.get("id")).stream())
                        .map(c -> "- " + c.get("table_name") + "." + c.get("name") + " (" + c.get("data_type") + ")"
                                + (c.get("description") == null ? "" : ": " + c.get("description")))
                        .collect(Collectors.joining("\n"));
                case "list_terms" -> s.semantic().terms(arg, 0, 100).stream()
                        .map(t -> "- " + t.get("name") + ": " + t.get("description")).collect(Collectors.joining("\n"));
                case "get_term" -> {
                    var term = s.semantic().termByName(arg).or(() -> s.semantic().termById(arg)).orElse(null);
                    if (term == null) {
                        yield "Term not found";
                    }
                    String id = (String) term.get("id");
                    yield "Term " + term.get("name") + ": " + term.get("description") + "\nSynonyms: " + term.get("synonyms")
                            + "\nTables: " + s.semantic().tablesOfTerm(id).stream().map(t -> t.get("schema_name") + "."
                                    + t.get("name")).collect(Collectors.joining(", "))
                            + "\nAttributes:\n" + s.semantic().attributesOfTerm(id).stream().map(a -> "- " + a.get("name")
                                    + " (" + a.get("source_column") + "): " + a.get("description")).collect(Collectors.joining("\n"))
                            + "\nSQL attributes:\n" + s.semantic().sqlAttributesOfTerm(id).stream().map(a -> "- "
                                    + a.get("name") + ": " + a.get("expression")).collect(Collectors.joining("\n"));
                }
                case "search_semantic_layer" -> Retrieval.search(s, VectorStore.SEMANTIC_LAYER, String.valueOf(arg), arg, 8,
                        null, List.of(), null).stream().map(c -> "- [" + c.label() + "] " + c.text())
                        .collect(Collectors.joining("\n"));
                default -> "Unknown tool " + name;
            };
        } catch (RuntimeException e) {
            return "Tool error: " + e.getMessage();
        }
    }

    // ================================================================== prepare_candidates

    @SuppressWarnings("unchecked")
    public void prepareCandidates(AgentState st) {
        String dbName = st.get("target_database");
        Map<String, Object> db = dbName == null ? null : s.catalog().databaseByName(dbName).orElse(null);
        if (db == null) {
            st.put("relevant_tables", List.of());
            st.put("unconstructable_explanation", "No database with matching data was found for this question.");
            return;
        }
        String dbId = (String) db.get("id");
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) st.pathState.getOrDefault("candidates", List.of());
        List<Map<String, Object>> dataCandidates = (List<Map<String, Object>>) st.pathState.getOrDefault("data_candidates",
                List.of());
        LinkedHashSet<String> tableIds = new LinkedHashSet<>();
        List<Map<String, Object>> customAnalyses = new ArrayList<>();
        List<Map<String, Object>> sqlAttributes = new ArrayList<>();
        for (Map<String, Object> c : candidates) {
            Map<String, Object> meta = (Map<String, Object>) c.get("metadata");
            switch (String.valueOf(c.get("label"))) {
                case "ColumnAttribute" -> {
                    if (meta.get("table_id") != null) {
                        tableIds.add((String) meta.get("table_id"));
                    }
                }
                case "Term" -> s.semantic().tablesOfTerm((String) c.get("id")).forEach(t -> tableIds.add((String) t.get("id")));
                case "SqlAttribute" -> s.semantic().sqlAttributeById((String) c.get("id")).ifPresent(a -> {
                    sqlAttributes.add(a);
                    if (a.get("term_id") != null) {
                        s.semantic().tablesOfTerm((String) a.get("term_id")).forEach(t -> tableIds.add((String) t.get("id")));
                    }
                });
                case "CustomAnalysis" -> s.semantic().customAnalysisById((String) c.get("id")).ifPresent(customAnalyses::add);
                default -> { }
            }
        }
        Map<String, Map<String, Object>> tablesOfDb = new LinkedHashMap<>();
        s.catalog().tablesOfDatabase(dbId).forEach(t -> tablesOfDb.put((String) t.get("id"), t));
        Map<String, String> columnTable = new HashMap<>();
        for (Map<String, Object> c : dataCandidates) {
            if ("Table".equals(c.get("label"))) {
                tableIds.add((String) c.get("id"));
            } else {
                columnTable.computeIfAbsent((String) c.get("id"), id -> s.catalog().columnById(id)
                        .map(col -> (String) col.get("table_id")).orElse(null));
                String t = columnTable.get(c.get("id"));
                if (t != null) {
                    tableIds.add(t);
                }
            }
        }
        tableIds.retainAll(tablesOfDb.keySet());
        List<String> ids = new ArrayList<>(tableIds);
        if (ids.size() > MAX_TABLES) {
            ids = new ArrayList<>(ids.subList(0, MAX_TABLES));
        }
        JoinPathFinder.Result joins = s.joins().connect(dbId, ids);
        // relevance filter (TABLE_RELEVANCE_FILTER_PROMPT): removal only when both checks hold
        if (joins.tableIds().size() > 2) {
            ids = relevanceFilter(st, joins, tablesOfDb, customAnalyses);
            joins = s.joins().connect(dbId, ids);
        }
        List<Map<String, Object>> relevant = new ArrayList<>();
        List<Map<String, Object>> allColumns = new ArrayList<>();
        for (String id : joins.tableIds()) {
            Map<String, Object> t = new LinkedHashMap<>(tablesOfDb.get(id));
            List<Map<String, Object>> cols = s.catalog().columnsOfTable(id);
            t.put("columns", cols);
            t.put("foreign_keys", s.catalog().foreignKeysOfTable(id));
            relevant.add(t);
            allColumns.addAll(cols);
        }
        st.put("database_id", dbId);
        st.put("dialect", db.get("dialect"));
        st.put("relevant_tables", relevant);
        st.put("relevant_columns", allColumns);
        st.put("join_paths", joins.describe());
        st.put("custom_analyses", customAnalyses);
        st.put("sql_attributes", sqlAttributes);
        st.put("example_queries", s.catalog().sqlForTables(joins.tableIds(), 5));
        st.recordThought("prepare_candidates", "Relevant tables: " + relevant.stream()
                .map(t -> t.get("schema_name") + "." + t.get("name")).collect(Collectors.joining(", "))
                + (joins.hops().isEmpty() ? "" : "; joins: " + joins.hops().stream().map(JoinPathFinder.Hop::condition)
                        .collect(Collectors.joining("; "))));
    }

    private List<String> relevanceFilter(AgentState st, JoinPathFinder.Result joins,
            Map<String, Map<String, Object>> tables, List<Map<String, Object>> customAnalyses) {
        Map<String, String> nameToId = new LinkedHashMap<>();
        StringBuilder summary = new StringBuilder();
        for (String id : joins.tableIds()) {
            Map<String, Object> t = tables.get(id);
            String name = t.get("schema_name") + "." + t.get("name");
            nameToId.put(name.toLowerCase(Locale.ROOT), id);
            nameToId.put(String.valueOf(t.get("name")).toLowerCase(Locale.ROOT), id);
            summary.append("- ").append(name).append(t.get("description") == null ? "" : ": " + t.get("description"))
                    .append(" | columns: ").append(s.catalog().columnsOfTable(id).stream().map(c -> (String) c.get("name"))
                            .collect(Collectors.joining(", "))).append('\n');
        }
        String prompt = Prompts.render("table_relevance_filter", Map.of(
                "domain_rules", st.domainRules == null || st.domainRules.isBlank() ? "" : st.domainRules + "\n\n",
                "custom_analyses", AgentPrompts.customAnalysesSection(customAnalyses),
                "join_paths", joins.describe().isEmpty() ? "" : joins.describe() + "\n",
                "enriched_question", "",
                "question", st.question(),
                "tables_summary", summary.toString()));
        TableRelevanceModel r = s.llm().tryStructured(ModelRole.NON_REASONING, List.of(LlmMessage.user(prompt)),
                TableRelevanceModel.class).orElse(null);
        List<String> keep = new ArrayList<>(joins.tableIds());
        if (r != null) {
            for (TableRemovalModel rm : r.tables_to_remove()) {
                // candidates_preparation enforces both flags instead of trusting the prose
                if (rm.supplies_no_needed_column() && rm.cannot_change_qualifying_rows()) {
                    String id = nameToId.get(String.valueOf(rm.table()).toLowerCase(Locale.ROOT));
                    if (id != null && keep.size() > 1) {
                        keep.remove(id);
                    }
                }
            }
        }
        return keep;
    }

    // ================================================================== construct_sql_from_candidates

    @SuppressWarnings("unchecked")
    public void constructSql(AgentState st) {
        List<Map<String, Object>> tables = (List<Map<String, Object>>) st.pathState.getOrDefault("relevant_tables", List.of());
        if (tables.isEmpty()) {
            if (st.get("unconstructable_explanation") == null) {
                st.put("unconstructable_explanation", "SQL can't be constructed from the data: no relevant tables found.");
            }
            return;
        }
        String dialect = st.get("dialect");
        boolean examples = !st.sqlExamples.isEmpty();
        List<LlmMessage> messages = new ArrayList<>(st.messages);
        messages.add(LlmMessage.system(AgentPrompts.sqlFromCandidatesSystem(dialect, examples)));
        String user = Prompts.render("sql_user_prompt", sqlUserValues(st, tables, dialect));
        if (examples) {
            user += "\n\n" + sqlExamplesSection(st.sqlExamples);
        }
        messages.add(LlmMessage.user(user));
        SQLGenerationModel gen = s.llm().structured(ModelRole.REASONING, messages, SQLGenerationModel.class);
        st.recordThought("construct_sql_from_candidates", gen.thought());
        if (gen.sql_code() == null || gen.sql_code().isBlank()) {
            st.put("unconstructable_explanation", gen.response());
            return;
        }
        st.put("sql_generation_result", gen);
        st.put("sql_code", gen.sql_code().strip());
        st.put("sql_construction_messages", messages);
    }

    private Map<String, Object> sqlUserValues(AgentState st, List<Map<String, Object>> tables, String dialect) {
        Map<String, Object> v = new HashMap<>();
        v.put("dialect", dialect == null ? "ANSI" : dialect);
        v.put("main_question", AgentPrompts.dualQuestionBlock(st.initialQuestion,
                (String) st.pathState.getOrDefault("normalized_question", st.question()), st.question()));
        StringBuilder obs = new StringBuilder();
        if (st.evidence != null && !st.evidence.isBlank()) {
            obs.append("\n").append(AgentPrompts.authoritativeEvidence(st.evidence, false)).append("\n");
        }
        String template = st.get("sql_template");
        if (template != null) {
            obs.append("\n## Calculation Pattern\nThe question is a ").append(st.<String>get("calculation_subtype"))
                    .append(" request. Structural SQL shape (identifiers belong to the example):\n").append(template)
                    .append('\n');
        }
        v.put("observation_block", obs.toString());
        v.put("tables", tablesText(tables, (String) st.get("target_database")));
        v.put("important_columns", importantColumns(st));
        v.put("queries", queriesText(st));
        v.put("qa_from_conversations", st.conversationHistory == null || st.conversationHistory.isBlank() ? "(none)"
                : st.conversationHistory);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ca = (List<Map<String, Object>>) st.pathState.getOrDefault("custom_analyses", List.of());
        StringBuilder caText = new StringBuilder(AgentPrompts.customAnalysesSection(ca));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sa = (List<Map<String, Object>>) st.pathState.getOrDefault("sql_attributes", List.of());
        if (!sa.isEmpty()) {
            caText.append("SQL ATTRIBUTES (reusable expressions):\n");
            sa.forEach(a -> caText.append("- ").append(a.get("name")).append(": ").append(a.get("description"))
                    .append("\n  SQL: ").append(a.get("expression")).append('\n'));
            caText.append('\n');
        }
        if (st.domainRules != null && !st.domainRules.isBlank()) {
            caText.append("DOMAIN RULES AND GLOSSARY:\n").append(st.domainRules).append("\n\n");
        }
        v.put("custom_analyses", caText.toString());
        v.put("join_paths", st.pathState.getOrDefault("join_paths", ""));
        v.put("dialect_rules", AgentPrompts.dialectRules(dialect));
        v.put("projection_rules", AgentPrompts.projectionRules(st.shortenAnswer));
        return v;
    }

    @SuppressWarnings("unchecked")
    String tablesText(List<Map<String, Object>> tables, String databaseName) {
        StringBuilder sb = new StringBuilder("AVAILABLE TABLES:\n");
        for (Map<String, Object> t : tables) {
            sb.append("\nTable: ").append(t.get("schema_name")).append('.').append(t.get("name"));
            if (t.get("description") != null) {
                sb.append(" — ").append(t.get("description"));
            }
            sb.append("\nColumns:\n");
            Map<String, String> fkTarget = new HashMap<>();
            for (Map<String, Object> f : (List<Map<String, Object>>) t.getOrDefault("foreign_keys", List.of())) {
                fkTarget.put((String) f.get("source_column"), f.get("target_schema") + "." + f.get("target_table") + "."
                        + f.get("target_column"));
            }
            for (Map<String, Object> c : (List<Map<String, Object>>) t.get("columns")) {
                sb.append("  - ").append(c.get("name")).append(" (").append(c.get("data_type")).append(')');
                if (Boolean.TRUE.equals(c.get("is_nullable"))) {
                    sb.append(" is_nullable: true");
                }
                if (fkTarget.containsKey(c.get("name"))) {
                    sb.append(" FK → ").append(fkTarget.get(c.get("name")));
                }
                if (c.get("format") != null) {
                    sb.append(" format: ").append(c.get("format"));
                }
                if (c.get("description") != null && !String.valueOf(c.get("description")).isBlank()) {
                    sb.append(" — ").append(c.get("description"));
                }
                String samples = sampleText(c);
                if (!samples.isEmpty()) {
                    sb.append(" [samples: ").append(samples).append(']');
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** NFR-SEC-04: no samples of PII columns to external LLMs unless allowed. */
    private String sampleText(Map<String, Object> c) {
        if (!s.props().security().piiSamplesToLlm() && s.pii().isPii((String) c.get("id"))) {
            return "";
        }
        List<Object> samples = Json.readList((String) c.get("sample_values"));
        return samples.stream().map(String::valueOf).filter(v -> v.length() <= 60 && !instructionLike(v)).limit(10)
                .collect(Collectors.joining(", "));
    }

    /** Values that look like instructions never reach a prompt (column_reference.py). */
    static boolean instructionLike(String v) {
        String l = v.toLowerCase(Locale.ROOT);
        return l.contains("ignore previous") || l.contains("ignore all") || l.contains("system prompt")
                || l.contains("you are ") || l.contains("instruction");
    }

    @SuppressWarnings("unchecked")
    private String importantColumns(AgentState st) {
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) st.pathState.getOrDefault("candidates", List.of());
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> c : candidates) {
            if (!"ColumnAttribute".equals(c.get("label"))) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) c.get("metadata");
            sb.append("- ").append(m.get("schema_name")).append('.').append(m.get("table_name")).append('.')
                    .append(m.get("source_column")).append(" (").append(c.get("name")).append(" of Term ")
                    .append(m.get("term_name")).append(") matched \"").append(c.get("entity")).append("\"\n");
        }
        return sb.isEmpty() ? "(none)" : sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String queriesText(AgentState st) {
        List<Map<String, Object>> q = (List<Map<String, Object>>) st.pathState.getOrDefault("example_queries", List.of());
        List<Map<String, Object>> ca = (List<Map<String, Object>>) st.pathState.getOrDefault("custom_analyses", List.of());
        List<String> lines = new ArrayList<>();
        ca.forEach(a -> lines.add("- " + a.get("sql")));
        q.forEach(x -> lines.add("- " + x.get("sql_full_query")));
        return lines.isEmpty() ? "(none)" : String.join("\n", lines);
    }

    private static String sqlExamplesSection(List<Map<String, Object>> examples) {
        List<String> blocks = new ArrayList<>();
        for (Map<String, Object> e : examples) {
            String sql = String.join(" ", String.valueOf(e.getOrDefault("sql", "")).split("\\s+")).strip();
            if (sql.isEmpty()) {
                continue;
            }
            StringBuilder b = new StringBuilder().append(blocks.size() + 1).append('.');
            if (e.get("db") != null) {
                b.append(" [source database: ").append(e.get("db")).append(']');
            }
            if (e.get("question") != null) {
                b.append("\n   Question: ").append(e.get("question"));
            }
            if (e.get("evidence") != null) {
                b.append("\n   Evidence: ").append(e.get("evidence"));
            }
            b.append("\n   SQL: ").append(sql);
            blocks.add(b.toString());
        }
        return blocks.isEmpty() ? "" : "## Reference Query Patterns\n" + String.join("\n\n", blocks);
    }

    // ================================================================== validate_sql_query

    @SuppressWarnings("unchecked")
    public void validateSql(AgentState st) {
        String sql = st.get("sql_code");
        st.put("error", null);
        List<Map<String, Object>> cols = (List<Map<String, Object>>) st.pathState.getOrDefault("relevant_columns", List.of());
        String dbId = st.get("database_id");
        List<Map<String, Object>> all = dbId == null ? cols : s.catalog().columnsOfDatabase(dbId);
        SqlStaticChecks.Result r = SqlStaticChecks.check(sql, SqlStaticChecks.view(all, st.get("dialect")));
        if (!r.valid()) {
            fail(st, "Static validation failed:\n- " + String.join("\n- ", r.errors()));
            return;
        }
        String used = all.stream().filter(c -> r.columns().contains(SqlStaticChecks.CatalogView.key(
                (String) c.get("schema_name"), (String) c.get("table_name")) + "." + c.get("name")))
                .map(c -> "- " + c.get("schema_name") + "." + c.get("table_name") + "." + c.get("name") + " ("
                        + c.get("data_type") + ")" + (c.get("description") == null ? "" : ": " + c.get("description")))
                .collect(Collectors.joining("\n"));
        boolean elsewhere = s.props().agent().intentValidationJoinsValidatedElsewhere();
        String user = AgentPrompts.intentValidation(st.initialQuestion, st.question(),
                (String) st.pathState.getOrDefault("sanitized_question", st.question()), sql,
                AgentPrompts.customAnalysesSection((List<Map<String, Object>>) st.pathState.getOrDefault("custom_analyses",
                        List.of())),
                String.valueOf(st.pathState.getOrDefault("join_paths", "")).replace("SUGGESTED JOIN PATHS",
                        "AUTHORITATIVE JOIN PATHS"),
                elsewhere, st.evidence != null && !st.evidence.isBlank(), used.isEmpty() ? "(none)" : used,
                st.shortenAnswer);
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(LlmMessage.system(Prompts.get(elsewhere ? "intent_validation_system_joins_elsewhere"
                : "intent_validation_system")));
        if (st.evidence != null && !st.evidence.isBlank()) {
            messages.add(LlmMessage.system(AgentPrompts.authoritativeEvidence(st.evidence, false)));
        }
        messages.add(LlmMessage.user(user));
        IntentValidationModel v = s.llm().tryStructured(ModelRole.NON_REASONING, messages, IntentValidationModel.class)
                .orElse(null);
        if (v != null && !v.is_valid()) {
            List<String> issues = new ArrayList<>();
            issues.addAll(v.join_issues());
            issues.addAll(v.aggregation_issues());
            issues.addAll(v.evidence_issues());
            issues.addAll(v.projection_issues());
            // deterministic fallback: SQL that passed the static checks and was only rejected for intent
            st.put("last_intent_rejected_sql", sql);
            fail(st, "Intent validation failed: " + v.reasoning() + (issues.isEmpty() ? "" : "\n- "
                    + String.join("\n- ", issues)));
            return;
        }
        st.put("validated_sql", sql);
        st.recordThought("validate_sql_query", v == null ? "Static checks passed." : "SQL is valid: " + v.reasoning());
    }

    private static void fail(AgentState st, String error) {
        st.put("error", error);
        st.addFailedAttempt(st.get("sql_code"), error);
        st.recordThought("validate_sql_query", error);
    }

    // ================================================================== execute_sql_query

    public void executeSql(AgentState st) {
        String sql = st.get("validated_sql");
        String dbName = st.get("target_database");
        SourceDatabase connector = st.connectors.stream().filter(c -> c.databaseName().equals(dbName)).findFirst()
                .orElse(st.connectors.isEmpty() ? null : st.connectors.get(0));
        if (connector == null) {
            st.put("error", "No connector available for database " + dbName);
            return;
        }
        String clean = de.jentsch.autoontology.connectors.ReadOnlySql.stripTrailingSemicolons(sql);
        try {
            TabularResult r = connector.execute(clean, List.of(), s.settings().sqlQueryTimeoutSeconds(),
                    s.props().agent().maxResultRows());
            st.put("error", null);
            st.put("sql_columns", r.columns());
            st.put("sql_response_from_db", r.asMaps());
            st.put("sql_column_types", r.jdbcTypes());
            st.put("row_count", r.rows().size());
            st.recordThought("execute_sql_query", "Query returned " + r.rows().size() + " row(s)"
                    + (r.truncated() ? " (truncated)" : ""));
        } catch (DbErrors.SourceQueryException e) {
            String error = "Execution failed (" + e.kind().name().toLowerCase(Locale.ROOT) + "): " + e.getMessage();
            st.put("error", error);
            st.addFailedAttempt(sql, error);
            st.recordThought("execute_sql_query", error);
        }
    }

    // ================================================================== check_empty_like_result

    public void checkEmptyLikeResult(AgentState st) {
        st.put("empty_like_flagged", false);
        Integer rows = st.get("row_count");
        String sql = st.get("validated_sql");
        if (rows == null || rows > 0 || sql == null || !sql.toUpperCase(Locale.ROOT).contains("LIKE")
                || Boolean.TRUE.equals(st.get("empty_like_checked"))) {
            return;
        }
        st.put("empty_like_checked", true);
        String prompt = Prompts.render("empty_like_check", Map.of("question_block", st.question(), "sql_code", sql));
        EmptyLikeCheckModel m = s.llm().tryStructured(ModelRole.NON_REASONING, List.of(LlmMessage.user(prompt)),
                EmptyLikeCheckModel.class).orElse(null);
        if (m != null && !m.non_essential_like_predicates().isEmpty()) {
            String error = "The query returned no rows. Remove these non-essential LIKE predicates and keep everything"
                    + " else: " + String.join("; ", m.non_essential_like_predicates());
            st.put("error", error);
            st.put("sql_code", sql);
            st.addFailedAttempt(sql, error);
            st.put("empty_like_flagged", true);
            st.recordThought("check_empty_like_result", error);
        }
    }

    // ================================================================== check_value_repair

    /** Near-miss repair of literals against distinct values (empty_result_value_repair.py). */
    public void checkValueRepair(AgentState st) {
        st.put("value_repair", "none");
        Integer rows = st.get("row_count");
        String sql = st.get("validated_sql");
        if (rows == null || rows > 0 || sql == null) {
            return;
        }
        String dbName = st.get("target_database");
        SourceDatabase connector = st.connectors.stream().filter(c -> c.databaseName().equals(dbName)).findFirst().orElse(null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) st.pathState.getOrDefault("relevant_columns", List.of());
        String repaired = sql;
        List<String> notes = new ArrayList<>();
        if (connector != null) {
            Matcher m = LITERAL_EQ.matcher(sql);
            while (m.find()) {
                String column = m.group(2);
                String literal = m.group(4);
                if (m.group(3).toUpperCase(Locale.ROOT).startsWith("LIKE")) {
                    continue;
                }
                for (Map<String, Object> c : cols) {
                    if (!column.equalsIgnoreCase((String) c.get("name"))) {
                        continue;
                    }
                    String match = nearMiss(connector, c, literal);
                    if (match != null && !match.equals(literal)) {
                        repaired = repaired.replace("'" + literal + "'", "'" + match.replace("'", "''") + "'");
                        notes.add("'" + literal + "' → '" + match + "' (" + c.get("table_name") + "." + c.get("name") + ")");
                        break;
                    }
                }
            }
        }
        if (!notes.isEmpty() && !repaired.equals(sql)) {
            st.put("sql_code", repaired);
            st.put("value_repair", "repaired");
            st.recordThought("check_value_repair", "Empty result: corrected filter values " + String.join(", ", notes));
            return;
        }
        if (!Boolean.TRUE.equals(st.get("undiagnosed_regeneration_done"))) {
            st.put("undiagnosed_regeneration_done", true);
            String error = "The query executed successfully but returned no rows. Re-check filter values, joins and the"
                    + " time window against the sample values; keep the user's intent.";
            st.put("error", error);
            st.put("sql_code", sql);
            st.addFailedAttempt(sql, error);
            st.put("value_repair", "regenerate");
            st.recordThought("check_value_repair", error);
        }
    }

    private String nearMiss(SourceDatabase db, Map<String, Object> col, String literal) {
        String sql = db.limit("SELECT DISTINCT " + db.quote((String) col.get("name")) + " FROM "
                + db.qualify((String) col.get("schema_name"), (String) col.get("table_name")) + " WHERE "
                + db.quote((String) col.get("name")) + " IS NOT NULL", 500);
        List<String> values;
        try {
            values = db.execute(sql, List.of(), s.settings().sqlQueryTimeoutSeconds(), 500).column(0).stream()
                    .map(String::valueOf).toList();
        } catch (RuntimeException e) {
            return null;
        }
        String l = literal.strip().toLowerCase(Locale.ROOT);
        for (String v : values) {
            if (v.strip().toLowerCase(Locale.ROOT).equals(l)) {
                return v;
            }
        }
        String best = null;
        double bestScore = 0.85;
        for (String v : values) {
            double score = similarity(l, v.toLowerCase(Locale.ROOT));
            if (score > bestScore) {
                bestScore = score;
                best = v;
            }
        }
        return best;
    }

    // ================================================================== reconstruct_sql

    @SuppressWarnings("unchecked")
    public void reconstructSql(AgentState st) {
        String sql = st.get("sql_code");
        String error = st.get("error");
        List<Map<String, Object>> tables = (List<Map<String, Object>>) st.pathState.getOrDefault("relevant_tables", List.of());
        // first attempt: classify FIXABLE / MISSING_DATA and load more tables for missing data
        if (st.failedAttempts() <= 1 && !Boolean.TRUE.equals(st.get("error_classified"))) {
            st.put("error_classified", true);
            String analysis = Prompts.render("sql_reconstruction__analysis_prompt_template", Map.of(
                    "question", st.question(),
                    "table_summary", tables.stream().map(t -> "- " + t.get("schema_name") + "." + t.get("name"))
                            .collect(Collectors.joining("\n")),
                    "known_columns_section", "",
                    "error_context", "SQL: " + sql + "\nError: " + error));
            ErrorAnalysis ea = s.llm().tryStructured(ModelRole.NON_REASONING, List.of(LlmMessage.user(analysis)),
                    ErrorAnalysis.class).orElse(null);
            if (ea != null && ea.error_type() == ErrorType.MISSING_DATA && !ea.search_queries().isEmpty()) {
                addTables(st, ea.search_queries());
                tables = (List<Map<String, Object>>) st.pathState.getOrDefault("relevant_tables", List.of());
                st.recordThought("reconstruct_sql", "Missing data: " + ea.explanation());
            }
        }
        StringBuilder history = new StringBuilder();
        List<Map<String, Object>> attempts = (List<Map<String, Object>>) st.pathState.getOrDefault("failed_attempts", List.of());
        if (attempts.size() > 1) {
            history.append("PREVIOUS FAILED ATTEMPTS:\n");
            for (int i = 0; i < attempts.size() - 1; i++) {
                history.append(i + 1).append(". SQL: ").append(attempts.get(i).get("sql")).append("\n   Error: ")
                        .append(attempts.get(i).get("error")).append('\n');
            }
            history.append('\n');
        }
        String interpretation = st.thoughtsLog().stream().filter(t -> "construct_sql_from_candidates".equals(t.get("node"))
                || "reconstruct_sql".equals(t.get("node"))).map(t -> (String) t.get("thought")).limit(5)
                .collect(Collectors.joining("\n"));
        String prompt = "The following SQL contains an ERROR:\n\n```sql\n" + sql + "\n```\n\n"
                + "Validation failed with the following message:\n" + error + "\n\n" + history
                + (interpretation.isEmpty() ? "" : "\nINTERPRETATION HISTORY:\n" + interpretation + "\n\nKeep every valid"
                        + " assumption above. Change one only when required by the question, schema, or error, and state"
                        + " the change explicitly.\n\n")
                + "Please correct the SQL. Do not return the same SQL — it is invalid.\n"
                + "Fix what the error requires while preserving every still-valid user-intent assumption. Do not silently"
                + " reinterpret an ambiguous term merely because you're rewriting the query. In `thought`, restate the"
                + " assumptions that remain valid and clearly state any assumption that had to change because it"
                + " conflicted with the user's question, the available schema, or the validation error.\n"
                + "Do not explain how you corrected the sql, like you were never wrong.\n"
                + tablesText(tables, st.get("target_database")) + "\n"
                + "The user's question was:\n" + st.question() + "\n"
                + "You must include corrected sql in your final answer.\n"
                + "Follow the rules defined in the previous messages for writing the final answer.";
        List<LlmMessage> messages = new ArrayList<>((List<LlmMessage>) st.pathState.getOrDefault("sql_construction_messages",
                st.messages));
        if (st.evidence != null && !st.evidence.isBlank()) {
            messages.add(LlmMessage.system(AgentPrompts.authoritativeEvidence(st.evidence, false)));
        }
        messages.add(LlmMessage.user(prompt));
        SQLGenerationModel gen = s.llm().structured(ModelRole.NON_REASONING, messages, SQLGenerationModel.class);
        st.recordThought("reconstruct_sql", gen.thought());
        if (gen.sql_code() == null || gen.sql_code().isBlank()) {
            st.put("unconstructable_explanation", gen.response());
            st.put("sql_code", null);
            return;
        }
        st.put("sql_generation_result", gen);
        st.put("sql_code", gen.sql_code().strip());
    }

    @SuppressWarnings("unchecked")
    private void addTables(AgentState st, List<String> queries) {
        String dbName = st.get("target_database");
        String dbId = st.get("database_id");
        if (dbId == null) {
            return;
        }
        List<Map<String, Object>> tables = new ArrayList<>((List<Map<String, Object>>) st.pathState.get("relevant_tables"));
        Set<String> ids = tables.stream().map(t -> (String) t.get("id")).collect(Collectors.toCollection(LinkedHashSet::new));
        for (String q : queries) {
            for (Candidate c : Retrieval.search(s, VectorStore.DATA_LAYER, q, q, 3, dbName, List.of("Table"), null)) {
                ids.add(c.id());
            }
        }
        JoinPathFinder.Result joins = s.joins().connect(dbId, new ArrayList<>(ids));
        List<Map<String, Object>> cols = new ArrayList<>();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : joins.tableIds()) {
            s.catalog().tableById(id).ifPresent(t -> {
                Map<String, Object> m = new LinkedHashMap<>(t);
                List<Map<String, Object>> c = s.catalog().columnsOfTable(id);
                m.put("columns", c);
                m.put("foreign_keys", s.catalog().foreignKeysOfTable(id));
                cols.addAll(c);
                out.add(m);
            });
        }
        st.put("relevant_tables", out);
        st.put("relevant_columns", cols);
        st.put("join_paths", joins.describe());
    }

    // ================================================================== format_and_respond

    @SuppressWarnings("unchecked")
    public void formatAndRespond(AgentState st) {
        SQLGenerationModel gen = st.get("sql_generation_result");
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) st.pathState.getOrDefault("candidates", List.of());
        String text = formatResponse(candidates, gen == null ? "" : gen.response());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("response", text);
        response.put("sql_code", st.get("validated_sql"));
        response.put("sql_columns", st.pathState.getOrDefault("sql_columns", List.of()));
        response.put("custom_analyses_used", ((List<Map<String, Object>>) st.pathState.getOrDefault("custom_analyses",
                List.of())).stream().map(a -> a.get("name")).toList());
        response.put("sql_response_from_db", st.get("sql_response_from_db"));
        st.put("formatted_response", text);
        st.put("final_response", response);
    }

    /** format_response: markdown clean-up plus entity links {@code [[[name/id]]]} (FR-FE-04). */
    static String formatResponse(List<Map<String, Object>> candidates, String response) {
        String out = response.replace("%%%", "```").replace("**", "*").replaceAll("(\\\\+n|\\n)", "\n ");
        Set<String> done = new java.util.HashSet<>();
        for (Map<String, Object> c : candidates) {
            String name = (String) c.get("name");
            if (name == null || name.length() < 3 || !done.add(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Matcher m = Pattern.compile("(?<![\\[/\\w])" + Pattern.quote(name) + "(?![\\]\\w])", Pattern.CASE_INSENSITIVE)
                    .matcher(out);
            if (m.find()) {
                out = out.substring(0, m.start()) + "[[[" + m.group() + "/" + c.get("id") + "]]]" + out.substring(m.end());
            }
        }
        return out;
    }

    // ================================================================== unconstructable_sql_response

    public void unconstructable(AgentState st) {
        String explanation = st.get("unconstructable_explanation");
        String text = explanation == null || explanation.isBlank() ? "SQL can't be constructed from the data." : explanation;
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("response", text);
        response.put("sql_code", "");
        response.put("sql_columns", List.of());
        response.put("custom_analyses_used", List.of());
        response.put("sql_response_from_db", null);
        st.put("final_response", response);
        st.put("unconstructable", true);
    }

    static String truncate(String s, int n) {
        return s == null ? "" : s.length() > n ? s.substring(0, n) + "..." : s;
    }

    /** Question type helper for routing. */
    static boolean isInformation(AgentState st) {
        return QuestionType.INFORMATION.name().equalsIgnoreCase(String.valueOf(st.<Object>get("question_type")));
    }
}
