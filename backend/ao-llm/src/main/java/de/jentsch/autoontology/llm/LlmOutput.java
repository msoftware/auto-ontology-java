// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

/**
 * Optional validation hook of a structured output record (the Pydantic {@code model_validator} of
 * the original). A thrown {@link IllegalArgumentException} counts as a validation error and
 * triggers a retry.
 */
public interface LlmOutput {
    default void validate() {}
}
