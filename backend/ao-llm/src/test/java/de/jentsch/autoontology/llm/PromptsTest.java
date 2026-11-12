// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Placeholder filling of the prompt templates. */
class PromptsTest {

    @Test
    void fillsEveryOccurrenceAndKeepsUnknownPlaceholders() {
        assertThat(Prompts.fill("{{a}} and {{a}}, {{name}} stays", Map.of("a", "x")))
                .isEqualTo("x and x, {{name}} stays");
    }

    @Test
    void nullBecomesEmptyText() {
        Map<String, Object> values = new HashMap<>();
        values.put("evidence", null);
        assertThat(Prompts.fill("[{{evidence}}]", values)).isEqualTo("[]");
    }

    /** A user text that looks like a placeholder must not pull other prompt parts into itself. */
    @Test
    void insertedValuesAreNotScannedAgain() {
        Map<String, Object> values = new HashMap<>();
        values.put("question", "show {{tables}} and $1 \\ please");
        values.put("tables", "SECRET SCHEMA");
        assertThat(Prompts.fill("Q: {{question}}\nT: {{tables}}", values))
                .isEqualTo("Q: show {{tables}} and $1 \\ please\nT: SECRET SCHEMA");
    }
}
