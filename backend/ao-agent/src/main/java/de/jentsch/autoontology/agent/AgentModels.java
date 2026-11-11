// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.agent;

import de.jentsch.autoontology.llm.LlmField;
import de.jentsch.autoontology.llm.LlmOutput;
import java.util.List;

/** LLM output schemas of the agent, taken over from the Pydantic models of retrieval/text_to_sql. */
public final class AgentModels {
    private AgentModels() {}

    /** question_intent.QuestionType. */
    public enum QuestionType {
        INFORMATION,
        PREDICTION,
        CALCULATION
    }

    /** question_intent.CalculationSubtype. */
    public enum CalculationSubtype {
        MATCH_BASED,
        RANKING,
        COMPARISON,
        COUNTING,
        AGGREGATION,
        DOMAIN_KNOWLEDGE,
        NUMERIC_COMPUTATION,
        SYNONYM,
        VALUE_ILLUSTRATION
    }

    /** Structured output of the intent preprocessing call. */
    public record QuestionIntentModel(
            @LlmField("Whether the request is informational, predictive, or a calculation.") QuestionType question_type,
            @LlmField(value = "The calculation subtype when question_type is calculation; otherwise null.", nullable = true)
                    CalculationSubtype calculation_subtype,
            @LlmField("The user's executable request with supporting inline instructions moved to extracted_evidence.")
                    String rewritten_question,
            @LlmField("Supporting instruction or context removed from the question, or an empty string when none was"
                    + " removed.") String extracted_evidence,
            @LlmField(value = "The exact configured database explicitly requested in the question, or null when the"
                    + " question does not select one.", nullable = true) String target_db,
            @LlmField("One-based numbers of SQL examples structurally relevant to the request.")
                    List<Integer> relevant_sql_example_numbers) implements LlmOutput {
        @Override
        public void validate() {
            if (question_type == QuestionType.CALCULATION && calculation_subtype == null) {
                throw new IllegalArgumentException("calculation questions require a calculation_subtype");
            }
            if (question_type != QuestionType.CALCULATION && calculation_subtype != null) {
                throw new IllegalArgumentException("calculation_subtype must be null unless question_type is calculation");
            }
        }
    }

    /** Full sanitize + entity extraction structured output (entity_coverage/models.py). */
    public record QuestionExtractionModel(
            @LlmField("Concise, SQL-ready rewrite of the user's request. Preserve factual constraints and remove narrative"
                    + " fluff.") String sanitized_question,
            @LlmField("Concepts present in the completed sanitized question that refer to database entities. Do not"
                    + " extract directly from supporting context. When words describe a single filterable item, keep them"
                    + " in one phrase instead of splitting. Keep brand names, product names, and descriptive named"
                    + " constants; omit numeric literals, dates, and number-based thresholds.")
                    List<String> required_entity_name,
            @LlmField("Main subject of the question - the single thing the user is asking about, resolved through the"
                    + " glossary when the question uses a shortcut. Excludes filters, aggregations, and date qualifiers.")
                    String subject,
            @LlmField("Names of glossary entries used to interpret the question. Return each name exactly as it appears in"
                    + " the glossary, or an empty list when no glossary definition applies.")
                    List<String> used_glossary_names) {}

    /** Sanitize + entity extraction only (QuestionExtractionLiteModel). */
    public record QuestionExtractionLiteModel(
            @LlmField("Concise, SQL-ready rewrite of the user's request. Preserve factual constraints and remove narrative"
                    + " fluff.") String sanitized_question,
            @LlmField("Concepts present in the completed sanitized question that refer to database entities.")
                    List<String> required_entity_name) {}

    /** LLM structured output for filtering custom analyses and SQL attributes in one call. */
    public record CombinedCandidateFilterModel(
            @LlmField("IDs of custom analyses to KEEP. Empty list if none are relevant.") List<String> custom_analysis_ids,
            @LlmField("IDs of SQL attributes to KEEP. Empty list if none are relevant.") List<String> sql_attribute_ids) {}

    /** One table the relevance filter proposes to remove, with its checks. */
    public record TableRemovalModel(
            @LlmField("Name of the table to remove.") String table,
            @LlmField("True only if this table supplies no column the answer needs — neither in the output, nor in a"
                    + " filter, nor in a grouping or sort.") boolean supplies_no_needed_column,
            @LlmField("True only if removing this table cannot change WHICH rows qualify. Joining a table also restricts"
                    + " the result to rows that have a match in it, so this is False whenever the question limits results"
                    + " to rows having a related record there (only schools that appear in frpm; only patients who have an"
                    + " examination record) — even though none of that table's columns are returned.")
                    boolean cannot_change_qualifying_rows,
            @LlmField("One sentence stating how you verified the two flags above, naming the row-scoping check"
                    + " explicitly.") String justification) {}

    /** LLM output for table relevance filtering. */
    public record TableRelevanceModel(
            @LlmField("Brief reasoning (1-2 sentences max) on which tables are relevant.") String reasoning,
            @LlmField("Tables that can be safely removed, each with its removal checks. Leave EMPTY [] if unsure or none"
                    + " should be removed.") List<TableRemovalModel> tables_to_remove) {}

    /** SQLGenerationModel: field order matters, the LLM fills fields sequentially. */
    public record SQLGenerationModel(
            @LlmField("Briefly explain the SQL approach and explicitly state every assumption made where the user's"
                    + " request or available schema does not uniquely determine the query. Do not omit assumptions. For"
                    + " each of the following that applies, state the choice AND the reason (\"X, because Y\"): (1) time"
                    + " window — the concrete boundary for any vague/relative time phrase (e.g. 'recently'); (2)"
                    + " zero/missing values — whether zero-count or NULL groups are included, excluded, or coerced to 0,"
                    + " and how any division guards against a zero denominator; (3) ties — what breaks a tie in a"
                    + " ranking/superlative query. If none were needed, state that explicitly.") String thought,
            @LlmField("The complete SQL query, no comments or delimiters.") String sql_code,
            @LlmField("2-4 sentences for the end user, in plain English, describing what is calculated.") String response) {}

    /** Structured response for the LLM phase of unified SQL validation. */
    public record IntentValidationModel(
            @LlmField("Brief reasoning (1-2 sentences max) on whether the SQL addresses the question's intent.")
                    String reasoning,
            @LlmField("Whether the SQL query has any CRITICAL issues. Should be True unless there are serious problems.")
                    boolean is_valid,
            @LlmField("List of CRITICAL join issues that would produce completely wrong results. Leave EMPTY [] if there"
                    + " are no join issues — do NOT add explanatory text like 'no join issues'.") List<String> join_issues,
            @LlmField("List of CRITICAL aggregation issues that are clearly wrong (not minor variations). Leave EMPTY [] if"
                    + " there are no aggregation issues — do NOT add explanatory text like 'no aggregation issues'.")
                    List<String> aggregation_issues,
            @LlmField("When strict short-answer validation is requested, list every extra, missing, duplicated, reordered,"
                    + " or reshaped output field compared with the exact projection requested by the question and"
                    + " evidence. Leave EMPTY [] only when the projection matches exactly.") List<String> projection_issues,
            @LlmField("List every way the SQL violates Authoritative Evidence, including a missing or substituted value,"
                    + " column/table mapping, filter, operator, or formula. Leave EMPTY [] when all evidence instructions"
                    + " are followed.") List<String> evidence_issues) {}

    /** Root-cause classification for reconstruction. */
    public enum ErrorType {
        MISSING_DATA,
        FIXABLE
    }

    /** LLM output: classify the root cause and optionally suggest VDB queries. */
    public record ErrorAnalysis(
            @LlmField("Classify the root cause:\n- missing_data: the available tables/columns are insufficient to answer"
                    + " the question — new data must be discovered.\n- fixable: the SQL has a syntax error, wrong column,"
                    + " runtime failure, or wrong logic — it can be fixed with the same tables.") ErrorType error_type,
            @LlmField("Only populate when error_type is 'missing_data'. 2-4 semantic search queries to find the missing"
                    + " data. Each query should describe a concept, entity, or relationship needed but not in the current"
                    + " tables.") List<String> search_queries,
            @LlmField("Brief explanation of the diagnosis.") String explanation) {}

    /** EmptyLikeCheckModel. */
    public record EmptyLikeCheckModel(
            @LlmField("LIKE/ILIKE predicates that identify the main subject and must be kept.")
                    List<String> essential_like_predicates,
            @LlmField("LIKE/ILIKE predicates that only constrain a feature or preference and can be removed.")
                    List<String> non_essential_like_predicates) {}

    /** FollowUpResolutionModel. */
    public record FollowUpResolutionModel(
            @LlmField("Whether the current question depends on prior conversation context.") boolean is_follow_up,
            @LlmField("A complete, natural-language question containing all context needed to answer the current"
                    + " request.") String standalone_question) implements LlmOutput {
        @Override
        public void validate() {
            if (standalone_question == null || standalone_question.isBlank()) {
                throw new IllegalArgumentException("standalone_question must not be empty");
            }
        }
    }

    /** One tool call of the information agent (information_agent.py). */
    public record InformationAgentAction(
            @LlmField("Brief reasoning about the next step.") String thought,
            @LlmField(value = "Tool to call next, or null when the final answer is ready.", nullable = true) String tool,
            @LlmField(value = "Argument of the tool: an id, a name or a search text.", nullable = true) String argument,
            @LlmField(value = "The final answer in Markdown once enough metadata was collected.", nullable = true)
                    String final_answer) {}

    /** Visualization decision and dashboard-compatible chart params (FR-AGT-12, NFR-QD-08). */
    public record VisualizationRecommendation(
            @LlmField("True when a chart answers the question better than the table.") boolean use_chart,
            @LlmField(value = "Dashboard viz_type: bar, line, scatter, heatmap, big_number or table.", nullable = true)
                    String viz_type,
            @LlmField(value = "Result column for the x axis or category.", nullable = true) String x,
            @LlmField("Result columns holding the measures.") List<String> metrics,
            @LlmField(value = "Optional result column splitting the series.", nullable = true) String series,
            @LlmField("A short chart title in the language of the question.") String title) {}
}
