// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

/** FR-LLM-01: the four model roles. */
public enum ModelRole {
    /** Build the ontology, write SQL, answer text, answerability. */
    REASONING,
    /** Fast steps: intent, entities, candidate filters, SQL checks and repair. */
    NON_REASONING,
    /** Indexing and search. */
    EMBED,
    /** Ranking (bridge tables). */
    RERANK
}
