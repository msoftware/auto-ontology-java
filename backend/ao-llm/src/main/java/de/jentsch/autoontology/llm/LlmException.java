// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

/** An LLM call failed after all retries. */
public class LlmException extends RuntimeException {
    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }

    public LlmException(String message) {
        super(message);
    }
}
