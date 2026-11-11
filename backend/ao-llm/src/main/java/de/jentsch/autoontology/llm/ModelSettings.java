// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

import de.jentsch.autoontology.common.AoProperties;

/**
 * Resolved endpoint triplet of one role; missing fields fall back to {@code ao.models.defaults}
 * (FR-LLM-01). The API key never appears in {@link #toString()} (NFR-SEC-02).
 */
public record ModelSettings(String endpoint, String apiKey, String model, String structuredOutput) {

    public static ModelSettings resolve(AoProperties.Models models, ModelRole role) {
        AoProperties.Model d = models.defaults();
        AoProperties.Model m = switch (role) {
            case REASONING -> models.reasoning();
            case NON_REASONING -> models.nonReasoning();
            case EMBED -> models.embed();
            case RERANK -> models.rerank();
        };
        return new ModelSettings(
                pick(m.endpoint(), d.endpoint()),
                pick(m.apiKey(), d.apiKey()),
                pick(m.model(), d.model()),
                pick(pick(m.structuredOutput(), d.structuredOutput()), "json_schema"));
    }

    private static String pick(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    public boolean usable() {
        return endpoint != null && !endpoint.isBlank() && model != null && !model.isBlank();
    }

    public boolean hasKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "ModelSettings[endpoint=" + endpoint + ", model=" + model + ", structuredOutput=" + structuredOutput + "]";
    }
}
