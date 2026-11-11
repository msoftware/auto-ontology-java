// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt resources under {@code prompts/} (section 0, item 6). Each file starts with comment lines
 * ({@code #! ...}) naming its source, which are stripped. Placeholders are {@code {{name}}}.
 */
public final class Prompts {
    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    private Prompts() {}

    public static String get(String name) {
        return CACHE.computeIfAbsent(name, Prompts::load);
    }

    public static String render(String name, Map<String, ?> values) {
        return fill(get(name), values);
    }

    public static String fill(String template, Map<String, ?> values) {
        String out = template;
        for (Map.Entry<String, ?> e : values.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        return out;
    }

    private static String load(String name) {
        String path = "prompts/" + name + ".txt";
        try (InputStream in = Prompts.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing prompt resource " + path);
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            StringBuilder sb = new StringBuilder();
            boolean header = true;
            for (String line : text.split("\n", -1)) {
                if (header && line.startsWith("#!")) {
                    continue;
                }
                header = false;
                sb.append(line).append('\n');
            }
            String s = sb.toString();
            // the trailing newline of the file is not part of the prompt
            return s.endsWith("\n\n") ? s.substring(0, s.length() - 2) : s.substring(0, Math.max(0, s.length() - 1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
