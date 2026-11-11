// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

import java.util.List;
import java.util.Optional;

/**
 * Facade for every LLM call (NFR-ARC-04), modelled on auto_ontology/utils/llm_invoke.py. Keeps
 * providers exchangeable and makes deterministic test stubs possible.
 */
public interface LlmGateway {
    /**
     * Structured output into a Java record. Retries parse/validation errors with backoff; for
     * {@link ModelRole#NON_REASONING} falls back to {@link ModelRole#REASONING} afterwards
     * ({@code safe_invoke_structured_nr}).
     *
     * @throws LlmException when every attempt failed
     */
    <T> T structured(ModelRole role, List<LlmMessage> messages, Class<T> type);

    /** Plain text completion. */
    String text(ModelRole role, List<LlmMessage> messages);

    /** Like {@link #structured} but returns empty instead of throwing ({@code invoke_with_structured_output} → None). */
    default <T> Optional<T> tryStructured(ModelRole role, List<LlmMessage> messages, Class<T> type) {
        try {
            return Optional.ofNullable(structured(role, messages, type));
        } catch (LlmException e) {
            return Optional.empty();
        }
    }

    default String text(ModelRole role, String prompt) {
        return text(role, List.of(LlmMessage.user(prompt)));
    }

    /** Whether a model is configured at all (readiness). */
    default boolean configured() {
        return true;
    }
}
