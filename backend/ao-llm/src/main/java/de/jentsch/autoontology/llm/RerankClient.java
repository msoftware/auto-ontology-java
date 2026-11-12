// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Rerank client supporting both schemas of the original (FR-LLM-05), chosen by URL suffix:
 * {@code .../rerank}: {@code {query, documents, top_n}} scored by {@code results[].relevance_score};
 * {@code .../reranking} or {@code .../ranking}: {@code {query:{text}, passages:[{text}], truncate}}
 * scored by {@code rankings[].logit}.
 */
public class RerankClient {
    private final ModelSettings settings;
    private final RestClient http;

    public RerankClient(ModelSettings settings, Duration timeout) {
        this.settings = settings;
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory();
        rf.setReadTimeout(timeout);
        RestClient.Builder b = RestClient.builder().requestFactory(rf);
        if (settings.hasKey()) {
            b.defaultHeader("Authorization", "Bearer " + settings.apiKey());
        }
        this.http = b.build();
    }

    public boolean configured() {
        return settings.usable();
    }

    /** Scores in document order. */
    @SuppressWarnings("unchecked")
    public List<Double> scores(String query, List<String> documents) {
        if (!configured()) {
            throw new LlmException("No rerank endpoint configured (ao.models.rerank.* / RERANK_*)");
        }
        String url = settings.endpoint();
        boolean v1 = url.endsWith("/rerank");
        if (!v1 && !url.endsWith("/reranking") && !url.endsWith("/ranking")) {
            url = url.endsWith("/") ? url + "ranking" : url + "/ranking";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", settings.model());
        if (v1) {
            body.put("query", query);
            body.put("documents", documents);
            body.put("top_n", documents.size());
        } else {
            body.put("query", Map.of("text", query));
            body.put("passages", documents.stream().map(d -> Map.of("text", d)).toList());
            body.put("truncate", "END");
        }
        Map<String, Object> resp = http.post().uri(url).contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
                .body(Map.class);
        Object rows = resp == null ? null : resp.get(v1 ? "results" : "rankings");
        if (!(rows instanceof List<?> list)) {
            throw new LlmException("Rerank endpoint " + url + " returned no " + (v1 ? "results" : "rankings"));
        }
        List<Double> scores = new ArrayList<>(java.util.Collections.nCopies(documents.size(), Double.NEGATIVE_INFINITY));
        for (Object o : list) {
            Map<String, Object> r = (Map<String, Object>) o;
            // documents the endpoint did not score keep -infinity; rows outside the request are ignored
            if (r.get("index") instanceof Number idx && idx.intValue() >= 0 && idx.intValue() < scores.size()
                    && r.get(v1 ? "relevance_score" : "logit") instanceof Number score) {
                scores.set(idx.intValue(), score.doubleValue());
            }
        }
        return scores;
    }
}
