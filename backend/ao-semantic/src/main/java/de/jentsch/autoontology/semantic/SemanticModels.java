// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.semantic;

import de.jentsch.autoontology.llm.LlmField;
import java.util.List;

/** LLM output schemas of the compilation, taken over from semantic/models.py and the stage modules. */
public final class SemanticModels {
    private SemanticModels() {}

    /** LLM output: column assignment with a user-friendly display label. */
    public record TermColumnRef(
            @LlmField("Physical column name — must match a candidate column.") String source_column,
            @LlmField("User-friendly ColumnAttribute label with spaces between words (e.g. Order Date, Total Amount).")
                    String display_name) {}

    /** LLM output: one business Term with column assignments only. */
    public record RawTermProposal(
            @LlmField("User-friendly business Term name with spaces between words (e.g. Purchase Order).") String name,
            @LlmField("Short business definition.") String description,
            @LlmField("Candidate columns assigned to this Term with user-friendly display names.")
                    List<TermColumnRef> attributes) {}

    /** LLM output: Terms and column assignments for a single physical table. */
    public record RawTableTermsResult(
            @LlmField("Usually one Term. Propose multiple only when columns clearly belong to distinct business concepts.")
                    List<RawTermProposal> terms) {}

    /** term_extractor._SynonymExtractionModel. */
    public record SynonymExtraction(
            @LlmField("Abbreviations, acronyms, or alternate names explicitly written in the description. Empty list if"
                    + " none are stated.") List<String> synonyms) {}

    /** One column the LLM suspects is a foreign key. */
    public record PotentialFkSuggestion(
            @LlmField("Physical column name that likely references another table.") String column_name,
            @LlmField("Brief reason this column looks like a foreign key.") String rationale) {}

    /** LLM output: FK/PK suggestions plus table-grain classification. */
    public record FkAndPkResult(
            @LlmField("Suspected FK columns; empty when none apply.") List<PotentialFkSuggestion> fk_suggestions,
            @LlmField("Column names that appear to be the table's own primary key even if not declared as such. Usually"
                    + " empty or one entry.") List<String> pk_column_names,
            @LlmField("True only when each row represents an association between at least two entity roles.")
                    boolean is_junction_table,
            @LlmField("Brief reason for the junction-table decision.") String junction_table_rationale) {}

    /** LLM-generated business description for a single column. */
    public record ColumnDescription(
            @LlmField("Physical column name — must match a provided column.") String column_name,
            @LlmField("One concise sentence describing what the column represents.") String description) {}

    /** LLM output: business descriptions for a table's columns. */
    public record ColumnDescriptionResult(
            @LlmField("One entry per column provided in the prompt.") List<ColumnDescription> descriptions) {}

    /** LLM output: selects the best matching hit from a VDB result list. */
    public record FkHitSelection(
            @LlmField(value = "The column_id of the candidate that is the primary-key column this FK references,"
                    + " exactly as shown in the candidate list. Return null if none of the candidates are a confident"
                    + " match.", nullable = true) String selected_id,
            String rationale) {}

    /** LLM output selecting one FK column as the default join relationship. */
    public record SemanticFkJoinDefaultSelection(
            @LlmField(value = "The exact source-column ID of the relationship that best represents the default join"
                    + " between the supplied tables, or null when uncertain.", nullable = true) String selected_column_id,
            String rationale) {}

    /** sql_attribute_suggester._SuggestedAttr. */
    public record SuggestedAttr(String name, String description, String expression) {}

    /** sql_attribute_suggester._Suggestions. */
    public record Suggestions(List<SuggestedAttr> suggestions) {}

    /** bridge_tables._BridgeSqlAttributeProposal. */
    public record BridgeSqlAttributeProposal(String name, String description, String expression) {}
}
