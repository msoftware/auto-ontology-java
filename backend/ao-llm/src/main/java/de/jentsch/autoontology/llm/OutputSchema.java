// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNullSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Strict JSON schema of a record (all fields required, no additional properties, field order of
 * the record = order the model fills them, see {@code _SQL_GENERATION_OUTPUT_SPEC}).
 */
public final class OutputSchema {
    private OutputSchema() {}

    /** Plain JSON schema as map (json_object mode puts it into the prompt). */
    public static Map<String, Object> jsonSchema(Class<?> type) {
        return object(type);
    }

    private static Map<String, Object> object(Class<?> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException("Structured output types must be records: " + type);
        }
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (RecordComponent rc : type.getRecordComponents()) {
            LlmField f = rc.getAnnotation(LlmField.class);
            Map<String, Object> p = new LinkedHashMap<>(element(rc.getGenericType()));
            if (f != null && !f.value().isEmpty()) {
                p.put("description", f.value());
            }
            if (f != null && f.nullable()) {
                Object t = p.get("type");
                if (t instanceof String s) {
                    p.put("type", List.of(s, "null"));
                }
                if (p.containsKey("enum")) {
                    List<Object> values = new ArrayList<>((List<?>) p.get("enum"));
                    values.add(null);
                    p.put("enum", values);
                }
            }
            props.put(rc.getName(), p);
            required.add(rc.getName());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "object");
        LlmField doc = type.getAnnotation(LlmField.class);
        if (doc != null && !doc.value().isEmpty()) {
            m.put("description", doc.value());
        }
        m.put("properties", props);
        m.put("required", required);
        m.put("additionalProperties", false);
        return m;
    }

    private static Map<String, Object> element(Type t) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (t instanceof ParameterizedType pt && pt.getRawType() == List.class) {
            m.put("type", "array");
            m.put("items", element(pt.getActualTypeArguments()[0]));
            return m;
        }
        Class<?> c = (Class<?>) t;
        if (c == String.class) {
            m.put("type", "string");
        } else if (c == boolean.class || c == Boolean.class) {
            m.put("type", "boolean");
        } else if (c == int.class || c == Integer.class || c == long.class || c == Long.class) {
            m.put("type", "integer");
        } else if (c == double.class || c == Double.class || c == float.class || c == Float.class) {
            m.put("type", "number");
        } else if (c.isEnum()) {
            m.put("type", "string");
            m.put("enum", enumValues(c));
        } else if (c.isRecord()) {
            return object(c);
        } else {
            throw new IllegalArgumentException("Unsupported structured output field type: " + c);
        }
        return m;
    }

    static List<String> enumValues(Class<?> c) {
        List<String> values = new ArrayList<>();
        for (Object e : c.getEnumConstants()) {
            values.add(((Enum<?>) e).name().toLowerCase(Locale.ROOT));
        }
        return values;
    }

    /** The same schema as LangChain4j elements (json_schema and tool_calling modes). */
    public static JsonObjectSchema langchain(Class<?> type) {
        return (JsonObjectSchema) toLangchain(jsonSchema(type));
    }

    @SuppressWarnings("unchecked")
    private static JsonSchemaElement toLangchain(Map<String, Object> s) {
        Object type = s.get("type");
        String desc = (String) s.get("description");
        if (type instanceof List<?> types) {
            Map<String, Object> copy = new LinkedHashMap<>(s);
            copy.put("type", types.get(0));
            copy.remove("description");
            if (copy.containsKey("enum")) {
                List<Object> values = new ArrayList<>((List<Object>) copy.get("enum"));
                values.removeIf(v -> v == null);
                copy.put("enum", values);
            }
            return JsonAnyOfSchema.builder().description(desc)
                    .anyOf(toLangchain(copy), new JsonNullSchema()).build();
        }
        return switch (String.valueOf(type)) {
            case "object" -> {
                JsonObjectSchema.Builder b = JsonObjectSchema.builder().description(desc);
                Map<String, Object> props = (Map<String, Object>) s.get("properties");
                props.forEach((k, v) -> b.addProperty(k, toLangchain((Map<String, Object>) v)));
                b.required((List<String>) s.get("required"));
                b.additionalProperties(false);
                yield b.build();
            }
            case "array" -> JsonArraySchema.builder().description(desc)
                    .items(toLangchain((Map<String, Object>) s.get("items"))).build();
            case "boolean" -> JsonBooleanSchema.builder().description(desc).build();
            case "integer" -> JsonIntegerSchema.builder().description(desc).build();
            case "number" -> JsonNumberSchema.builder().description(desc).build();
            default -> s.containsKey("enum")
                    ? JsonEnumSchema.builder().description(desc).enumValues((List<String>) s.get("enum")).build()
                    : JsonStringSchema.builder().description(desc).build();
        };
    }
}
