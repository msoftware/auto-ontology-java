// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic local embedding (FR-DEMO-06): hashed bag of words and character trigrams,
 * L2-normalised. Good enough for tests and the offline demo; similar wording gives similar vectors.
 */
public class HashEmbedder implements Embedder {
    private final int dimensions;

    public HashEmbedder(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts, InputType type) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            out.add(vector(t));
        }
        return out;
    }

    float[] vector(String text) {
        float[] v = new float[dimensions];
        String norm = text == null ? "" : text.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace('_', ' ')
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
        for (String word : norm.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            String stem = stem(word);
            add(v, "w:" + stem, 2.0f);
            String padded = "#" + stem + "#";
            for (int i = 0; i + 3 <= padded.length(); i++) {
                add(v, "t:" + padded.substring(i, i + 3), 0.5f);
            }
        }
        double n = 0;
        for (float f : v) {
            n += f * f;
        }
        if (n > 0) {
            float inv = (float) (1.0 / Math.sqrt(n));
            for (int i = 0; i < v.length; i++) {
                v[i] *= inv;
            }
        }
        return v;
    }

    private static String stem(String w) {
        if (w.length() > 4 && w.endsWith("ies")) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }

    private void add(float[] v, String token, float weight) {
        int h = fnv(token);
        int idx = Math.floorMod(h, dimensions);
        v[idx] += ((h >>> 31) == 0 ? 1 : -1) * weight;
    }

    private static int fnv(String s) {
        int h = 0x811c9dc5;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= b;
            h *= 0x01000193;
        }
        return h;
    }
}
