// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.semantic;

import de.jentsch.autoontology.common.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * FR-SEM-09: optional domain summary per database ({@code .semantic_summaries/<db>.json} with
 * {@code domains}, {@code core_entities}, {@code business_rules}) for the Term extraction
 * (semantic/domain.py).
 */
final class DomainContext {
    private DomainContext() {}

    @SuppressWarnings("unchecked")
    static String block(String databaseName) {
        Path p = Path.of(".semantic_summaries", databaseName + ".json");
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            Map<String, Object> m = Json.readMap(Files.readString(p));
            List<String> domains = (List<String>) m.getOrDefault("domains", List.of());
            List<String> entities = (List<String>) m.getOrDefault("core_entities", List.of());
            return "\nDomains: " + String.join(", ", domains.subList(0, Math.min(8, domains.size()))) + "\n"
                    + "Core entities: " + String.join(", ", entities.subList(0, Math.min(12, entities.size()))) + "\n";
        } catch (Exception e) {
            return null;
        }
    }
}
