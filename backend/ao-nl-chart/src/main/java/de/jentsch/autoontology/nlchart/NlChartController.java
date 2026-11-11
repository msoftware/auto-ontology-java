// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.nlchart;

import de.jentsch.autoontology.common.ApiException;
import de.jentsch.autoontology.common.Json;
import de.jentsch.quasardashboard.model.Models.Dataset;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * NL-chart API (section 23.6) under {@code ao.nl-chart.api-prefix} (default {@code /api/nl-chart}),
 * outside the dashboard prefix. Errors use {@code {"detail": ...}}. There is no own save path:
 * charts are saved through {@code POST /api/v1/chart/} of the dashboard.
 */
@RestController
@RequestMapping("${ao.nl-chart.api-prefix:${ao.api-prefix:/api}/nl-chart}")
public class NlChartController {
    private final NlChartService service;
    private final ChartValidator validator;
    private final DatasetSync sync;

    public NlChartController(NlChartService service, ChartValidator validator, DatasetSync sync) {
        this.service = service;
        this.validator = validator;
        this.sync = sync;
    }

    @SuppressWarnings("unchecked")
    @PostMapping(path = "/suggest", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter suggest(@RequestBody Map<String, Object> body, HttpServletResponse resp) {
        String q = body.get("question") == null ? null : String.valueOf(body.get("question"));
        if (q == null || q.isBlank()) {
            throw ApiException.unprocessable("question is required");
        }
        resp.setHeader("Cache-Control", "no-cache, no-transform");
        resp.setHeader("X-Accel-Buffering", "no");
        NlChartService.Request req = new NlChartService.Request(q,
                body.get("dataset_id") instanceof Number n ? n.longValue() : null,
                body.get("viz_type") == null ? null : String.valueOf(body.get("viz_type")),
                body.get("current_params") instanceof Map<?, ?> m ? (Map<String, Object>) m : null,
                body.get("conversation_id") == null ? null : String.valueOf(body.get("conversation_id")),
                body.get("dashboard_id") instanceof Number n ? n.longValue() : null);
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);
        Thread.ofVirtual().name("ao-nl-chart").start(() -> {
            try {
                service.suggest(req, e -> send(emitter, Json.write(e)));
            } catch (RuntimeException e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("type", "error");
                err.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                send(emitter, Json.write(err));
            } finally {
                send(emitter, "[DONE]");
                emitter.complete();
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, String payload) {
        try {
            emitter.send(SseEmitter.event().data(" " + payload, MediaType.TEXT_PLAIN));
        } catch (IOException | IllegalStateException e) {
            // client gone; the run finishes anyway
        }
    }

    /** Validates given {dataset_id, viz_type, params} without an LLM (FR-NLC-11). */
    @SuppressWarnings("unchecked")
    @PostMapping("/validate")
    public Map<String, Object> validate(@RequestBody Map<String, Object> body) {
        if (!(body.get("dataset_id") instanceof Number id) || body.get("viz_type") == null) {
            throw ApiException.unprocessable("dataset_id and viz_type are required");
        }
        Dataset d = sync.datasets().stream().filter(x -> x.id() == id.longValue()).findFirst()
                .orElseThrow(() -> ApiException.notFound("Dataset not found"));
        ChartValidator.Result r = validator.validate(d, String.valueOf(body.get("viz_type")),
                body.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(), false);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", r.valid());
        out.put("errors", r.errors());
        out.put("warnings", r.warnings());
        out.put("query_context", r.valid() ? r.queryContext() : null);
        return out;
    }

    @GetMapping("/datasets")
    public Map<String, Object> datasets() {
        List<Map<String, Object>> data = sync.datasets().stream().map(sync::coverage).toList();
        return Map.of("data", data, "count", data.size(), "sync", sync.lastStatus());
    }

    @PostMapping("/sync")
    public Map<String, Object> runSync() {
        return Map.of("data", sync.sync());
    }

    @GetMapping("/viz-catalog")
    public Map<String, Object> vizCatalog() {
        return validator.catalog();
    }
}
