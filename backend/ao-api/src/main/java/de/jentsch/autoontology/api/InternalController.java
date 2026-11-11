// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.api;

import de.jentsch.autoontology.common.ApiException;
import de.jentsch.autoontology.ingestion.ConnectionService;
import de.jentsch.autoontology.ingestion.DataLayerEmbedder;
import de.jentsch.autoontology.ingestion.IngestScheduler;
import de.jentsch.autoontology.ingestion.IngestService;
import de.jentsch.autoontology.semantic.SemanticCompilationService;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.vector.VectorStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal endpoints of the ingestion service (docs/openapi/ingestion.json, FR-ING-04) under the
 * fixed path {@code /internal}; only for the private network, optionally guarded by a shared
 * secret ({@code ao.internal.secret}, NFR-SEC-03). Also {@code /health} (readiness) and
 * {@code /health/live}.
 */
@RestController
public class InternalController {
    private final IngestScheduler scheduler;
    private final IngestService ingest;
    private final SemanticCompilationService compilation;
    private final ConnectionService connections;
    private final CatalogRepository catalog;
    private final DataLayerEmbedder embedder;
    private final VectorStore vectors;
    private final CatalogController health;
    private final String secret;

    public InternalController(IngestScheduler scheduler, IngestService ingest, SemanticCompilationService compilation,
            ConnectionService connections, CatalogRepository catalog, DataLayerEmbedder embedder, VectorStore vectors,
            CatalogController health, @Value("${ao.internal.secret:}") String secret) {
        this.scheduler = scheduler;
        this.ingest = ingest;
        this.compilation = compilation;
        this.connections = connections;
        this.catalog = catalog;
        this.embedder = embedder;
        this.vectors = vectors;
        this.health = health;
        this.secret = secret;
    }

    private void guard(String header) {
        if (secret != null && !secret.isBlank() && !secret.equals(header)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Not found");
        }
    }

    /** Readiness: answers with a status, never with a 500. */
    @GetMapping("/health")
    public Map<String, Object> readiness() {
        return health.health();
    }

    @GetMapping("/health/live")
    public Map<String, Object> liveness() {
        return Map.of("status", "ok");
    }

    @PostMapping("/internal/ingest")
    public Map<String, Object> ingest(@RequestHeader(name = "x-ao-internal-secret", required = false) String s) {
        guard(s);
        scheduler.trigger();
        return Map.of("status", "triggered");
    }

    @PostMapping("/internal/ingest/delete")
    public Map<String, Object> delete(@RequestHeader(name = "x-ao-internal-secret", required = false) String s,
            @RequestBody Map<String, Object> body) {
        guard(s);
        String name = String.valueOf(body.get("database_name"));
        return Map.of("deleted", ingest.deleteDatabase(name).orElse(null) != null);
    }

    @PostMapping("/internal/semantic/compile")
    public Map<String, Object> compile(@RequestHeader(name = "x-ao-internal-secret", required = false) String s) {
        guard(s);
        compilation.trigger();
        return Map.of("status", "triggered");
    }

    @PostMapping("/internal/semantic/reset")
    public Map<String, Object> reset(@RequestHeader(name = "x-ao-internal-secret", required = false) String s,
            @RequestBody(required = false) Map<String, Object> body) {
        guard(s);
        Object db = body == null ? null : body.get("database_name");
        Thread.ofVirtual().start(() -> compilation.reset(db == null ? null : String.valueOf(db)));
        return Map.of("status", "accepted");
    }

    @GetMapping("/internal/semantic/status")
    public Map<String, Object> status(@RequestHeader(name = "x-ao-internal-secret", required = false) String s) {
        guard(s);
        return compilation.status();
    }

    @PostMapping("/internal/semantic/stop")
    public Map<String, Object> stop(@RequestHeader(name = "x-ao-internal-secret", required = false) String s) {
        guard(s);
        compilation.abort();
        return Map.of("status", "stopping");
    }

    /**
     * FR-VEC-06: compares the IDs of the data layer per database with the store, deletes orphaned
     * points and re-embeds missing entries.
     */
    @PostMapping("/internal/vector/reconcile")
    public Map<String, Object> reconcile(@RequestHeader(name = "x-ao-internal-secret", required = false) String s) {
        guard(s);
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map<String, Object> d : catalog.databases()) {
            String name = (String) d.get("name");
            List<String> stored = vectors.ids(VectorStore.DATA_LAYER, name);
            List<String> expected = embedder.rows((String) d.get("id"), name).stream()
                    .map(DataLayerEmbedder.Row::id).toList();
            List<String> orphaned = stored.stream().filter(id -> !expected.contains(id)).toList();
            vectors.deleteByIds(VectorStore.DATA_LAYER, orphaned);
            boolean missing = expected.stream().anyMatch(id -> !stored.contains(id));
            if (missing) {
                embedder.embedDatabase((String) d.get("id"), name);
            }
            out.put(name, Map.of("orphaned_deleted", orphaned.size(), "reembedded", missing));
        }
        connections.refresh();
        return out;
    }
}
