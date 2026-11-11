// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.agent;

import java.util.Map;

/** User-facing node labels, 1:1 from retrieval/text_to_sql/node_labels.py (FR-AGT-01c). */
public final class NodeLabels {
    public static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("question_intent", "Classifying the question"),
            Map.entry("question_extraction", "Understanding the question"),
            Map.entry("prepare_prediction_graph", "Preparing prediction"),
            Map.entry("kumo_predict", "Preparing prediction"),
            Map.entry("retrieve_candidates", "Retrieving candidates"),
            Map.entry("information_agent", "Answering from metadata"),
            Map.entry("prepare_candidates", "Preparing candidates"),
            Map.entry("refine_evidence", "Refining evidence"),
            Map.entry("precheck_combined", "Checking joins and filter values"),
            Map.entry("construct_sql_from_candidates", "Constructing SQL"),
            Map.entry("reconstruct_sql", "Reconstructing SQL"),
            Map.entry("validate_sql_values", "Validating filter values"),
            Map.entry("validate_sql_query", "Validating SQL and intent"),
            Map.entry("execute_sql_query", "Executing SQL"),
            Map.entry("check_empty_like_result", "Checking results"),
            Map.entry("check_value_repair", "Checking values"),
            Map.entry("format_and_respond", "Formatting response"),
            Map.entry("unconstructable_sql_response", "SQL could not be constructed"));

    private NodeLabels() {}

    /** Unknown nodes fall through to the raw name, never a blank step. */
    public static String label(String node) {
        return LABELS.getOrDefault(node, node);
    }
}
