// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

import de.jentsch.autoontology.common.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Deterministic {@link LlmGateway} for tests (NFR-TST-01) and the offline demo (FR-DEMO-06):
 * answers are scripted per output type as JSON, optionally depending on the prompt. Every call is
 * recorded.
 */
public class ScriptedLlmGateway implements LlmGateway {
    private final Map<Class<?>, Function<String, Object>> scripts = new ConcurrentHashMap<>();
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile Function<String, String> textScript = p -> "";

    /** Script for one output type: prompt (all messages joined) → record or JSON string; null = failure. */
    public <T> ScriptedLlmGateway on(Class<T> type, Function<String, Object> answer) {
        scripts.put(type, answer);
        return this;
    }

    public ScriptedLlmGateway onText(Function<String, String> answer) {
        this.textScript = answer;
        return this;
    }

    public List<String> calls() {
        return calls;
    }

    @Override
    public <T> T structured(ModelRole role, List<LlmMessage> messages, Class<T> type) {
        String prompt = join(messages);
        calls.add(type.getSimpleName());
        Function<String, Object> f = scripts.get(type);
        Object answer = f == null ? null : f.apply(prompt);
        if (answer == null) {
            throw new LlmException("No scripted answer for " + type.getSimpleName());
        }
        if (type.isInstance(answer)) {
            return type.cast(answer);
        }
        String json = answer instanceof String s ? s : Json.write(answer);
        return StructuredParser.parse(json, type);
    }

    @Override
    public String text(ModelRole role, List<LlmMessage> messages) {
        calls.add("text");
        return textScript.apply(join(messages));
    }

    private static String join(List<LlmMessage> messages) {
        List<String> parts = new ArrayList<>();
        for (LlmMessage m : messages) {
            parts.add(m.content());
        }
        return String.join("\n\n", parts);
    }
}
