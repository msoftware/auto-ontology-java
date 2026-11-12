// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

import de.jentsch.autoontology.common.AoProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * OpenAI-compatible {@code /embeddings} client (FR-LLM-04): sends {@code input_type} and
 * {@code truncate: END}, which the standard OpenAI client of LangChain4j does not support, and
 * prefixes the text for models that drop {@code input_type}. Chunked with retry. Exposed behind
 * LangChain4j's {@link EmbeddingModel} interface as well (passage mode).
 */
public class OpenAiCompatibleEmbedder implements Embedder, EmbeddingModel {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbedder.class);

    private final RestClient http;
    private final ModelSettings settings;
    private final AoProperties.Embedding cfg;
    private final int dimensions;

    public OpenAiCompatibleEmbedder(ModelSettings settings, AoProperties.Embedding cfg, int dimensions,
            Duration timeout) {
        this.settings = settings;
        this.cfg = cfg;
        this.dimensions = dimensions;
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        rf.setReadTimeout(timeout);
        RestClient.Builder b = RestClient.builder().requestFactory(rf).baseUrl(trimSlash(settings.endpoint()));
        if (settings.hasKey()) {
            b.defaultHeader("Authorization", "Bearer " + settings.apiKey());
        }
        this.http = b.build();
    }

    private static String trimSlash(String s) {
        return s == null ? "" : s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    @Override
    public List<float[]> embed(List<String> texts, InputType type) {
        if (!settings.usable()) {
            throw new LlmException("No embedding endpoint configured (ao.models.embed.* / EMBED_*)");
        }
        String inputType = type.name().toLowerCase(Locale.ROOT);
        boolean prefixed = cfg.textPrefixedModels().stream().anyMatch(m -> settings.model().contains(m));
        List<float[]> out = new ArrayList<>(texts.size());
        int batch = Math.max(1, cfg.batchSize());
        for (int i = 0; i < texts.size(); i += batch) {
            List<String> chunk = new ArrayList<>(texts.subList(i, Math.min(texts.size(), i + batch)));
            if (prefixed) {
                chunk.replaceAll(t -> inputType + ": " + t);
            }
            out.addAll(chunkWithRetry(chunk, inputType));
        }
        return out;
    }

    private List<float[]> chunkWithRetry(List<String> chunk, String inputType) {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return call(chunk, inputType);
            } catch (LlmException e) {
                throw e;
            } catch (RuntimeException e) {
                last = e;
                log.warn("Embedding chunk of {} texts failed (attempt {}): {}", chunk.size(), attempt + 1, e.getMessage());
                try {
                    Thread.sleep(1000L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlmException("Interrupted", ie);
                }
            }
        }
        throw new LlmException("Embedding failed after retries", last);
    }

    @SuppressWarnings("unchecked")
    private List<float[]> call(List<String> chunk, String inputType) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", chunk);
        body.put("model", settings.model());
        body.put("encoding_format", "float");
        if (cfg.sendInputType()) {
            body.put("input_type", inputType);
            body.put("truncate", "END");
        }
        Map<String, Object> resp = http.post().uri("/embeddings").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(Map.class);
        if (resp == null || !(resp.get("data") instanceof List<?> rows) || rows.size() != chunk.size()) {
            // a short answer would silently shift every following vector onto the wrong text
            throw new IllegalStateException("Embedding endpoint returned "
                    + (resp != null && resp.get("data") instanceof List<?> l ? l.size() + " vectors" : "no data")
                    + " for " + chunk.size() + " texts");
        }
        List<Map<String, Object>> data = new ArrayList<>((List<Map<String, Object>>) resp.get("data"));
        data.sort(Comparator.comparingInt(d -> ((Number) d.getOrDefault("index", 0)).intValue()));
        List<float[]> out = new ArrayList<>();
        for (Map<String, Object> d : data) {
            List<Number> e = (List<Number>) d.get("embedding");
            if (e.size() != dimensions) {
                // FR-LLM-04: stop immediately with an understandable message
                throw new LlmException("Embedding model " + settings.model() + " returns " + e.size()
                        + " dimensions, but ao.vector.dimensions=" + dimensions + ". Set ao.vector.dimensions="
                        + e.size() + " and rebuild the vector store.");
            }
            float[] v = new float[e.size()];
            for (int i = 0; i < v.length; i++) {
                v[i] = e.get(i).floatValue();
            }
            out.add(v);
        }
        return out;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        List<float[]> vectors = embedPassages(segments.stream().map(TextSegment::text).toList());
        return Response.from(vectors.stream().map(Embedding::from).toList());
    }

    @Override
    public int dimension() {
        return dimensions;
    }
}
