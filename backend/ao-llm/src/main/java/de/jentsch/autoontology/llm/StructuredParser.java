// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.util.Iterator;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Strict parsing of structured output ({@code StrictLLMOutputModel}): unknown fields fail, keys
 * starting with {@code $} (leaked tool metadata) are stripped first, an empty string for a list
 * field means an empty list.
 */
public final class StructuredParser {
    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private StructuredParser() {}

    public static <T> T parse(String raw, Class<T> type) {
        String json = extractJson(raw);
        JsonNode node = STRICT.readTree(json);
        if (node instanceof ObjectNode obj) {
            clean(obj, type);
        }
        T value = STRICT.treeToValue(node, type);
        if (value instanceof LlmOutput out) {
            out.validate();
        }
        return value;
    }

    private static void clean(ObjectNode obj, Class<?> type) {
        Iterator<Map.Entry<String, JsonNode>> it = obj.properties().iterator();
        while (it.hasNext()) {
            if (it.next().getKey().startsWith("$")) {
                it.remove();
            }
        }
        if (!type.isRecord()) {
            return;
        }
        for (RecordComponent rc : type.getRecordComponents()) {
            JsonNode child = obj.get(rc.getName());
            if (child == null) {
                continue;
            }
            if (rc.getGenericType() instanceof ParameterizedType pt && pt.getRawType() == java.util.List.class) {
                if (child.isString() && child.asString().isEmpty()) {
                    obj.putArray(rc.getName());
                } else if (child.isArray() && pt.getActualTypeArguments()[0] instanceof Class<?> c && c.isRecord()) {
                    for (JsonNode item : child) {
                        if (item instanceof ObjectNode o) {
                            clean(o, c);
                        }
                    }
                }
            } else if (rc.getType().isRecord() && child instanceof ObjectNode o) {
                clean(o, rc.getType());
            }
        }
    }

    /** Accepts fenced code blocks and leading prose around the JSON object. */
    static String extractJson(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("empty LLM response");
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int nl = s.indexOf('\n');
            int end = s.lastIndexOf("```");
            if (nl > 0 && end > nl) {
                s = s.substring(nl + 1, end).trim();
            }
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        throw new IllegalArgumentException("LLM response contains no JSON object");
    }
}
