// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

import java.util.List;

/**
 * Embedding client with the {@code input_type} distinction of the original (FR-LLM-04). The same
 * model is used for ingest (passage) and search (query).
 */
public interface Embedder {
    /** {@code query} for search texts, {@code passage} for indexed texts. */
    enum InputType {
        QUERY,
        PASSAGE
    }

    List<float[]> embed(List<String> texts, InputType type);

    default float[] embedQuery(String text) {
        return embed(List.of(text), InputType.QUERY).get(0);
    }

    default List<float[]> embedPassages(List<String> texts) {
        return embed(texts, InputType.PASSAGE);
    }
}
