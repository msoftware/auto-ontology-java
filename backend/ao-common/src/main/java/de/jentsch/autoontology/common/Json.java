// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.common;

import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Small JSON helper for JSON-in-text columns and prompt payloads (Jackson 3). */
public final class Json {
    /** Lenient mapper for stored JSON. */
    public static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Json() {}

    public static String write(Object value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }

    public static <T> T read(String json, Class<T> type) {
        return MAPPER.readValue(json, type);
    }

    /** Parses any JSON value; returns the raw text when it is not JSON. */
    public static Object readAny(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (RuntimeException e) {
            return json;
        }
    }

    public static List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Object> raw = MAPPER.readValue(json, new TypeReference<List<Object>>() {});
            return raw.stream().map(String::valueOf).toList();
        } catch (RuntimeException e) {
            return List.of(json);
        }
    }

    public static List<Object> readList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Object>>() {});
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    public static Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
    }
}
