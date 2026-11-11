// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

/** A chat message independent of the LLM library. */
public record LlmMessage(Role role, String content) {
    /** Message author. */
    public enum Role {
        SYSTEM,
        USER,
        ASSISTANT
    }

    public static LlmMessage system(String content) {
        return new LlmMessage(Role.SYSTEM, content);
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(Role.ASSISTANT, content);
    }
}
