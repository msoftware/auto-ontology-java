// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.semantic;

import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.common.CancellationToken;
import de.jentsch.autoontology.connectors.ConnectionSpec;
import de.jentsch.autoontology.connectors.SourceDatabase;
import de.jentsch.autoontology.ingestion.ConnectionService;
import de.jentsch.autoontology.ingestion.IngestScheduler;
import de.jentsch.autoontology.ingestion.PiiDetection;
import de.jentsch.autoontology.ingestion.RuleService;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.store.HistoryRepository;
import de.jentsch.autoontology.store.RuntimeSettings;
import de.jentsch.autoontology.store.SemanticRepository;
import de.jentsch.autoontology.vector.VectorStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Semantic scheduler (semantic_scheduler.py, FR-SEM-07/08): waits for the first ingest, then runs
 * every 24 h and on trigger; every run checks {@code semantic_compilation_enabled}; records history;
 * replays rules and propagates PII afterwards. Reset aborts a running pass first and deletes the
 * semantic layer (with embeddings) before recompiling, strictly in this order.
 */
public class SemanticCompilationService implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(SemanticCompilationService.class);

    private final SemanticCompiler compiler;
    private final ConnectionService connections;
    private final CatalogRepository catalog;
    private final SemanticRepository semantic;
    private final HistoryRepository history;
    private final RuntimeSettings settings;
    private final VectorStore vectors;
    private final RuleService rules;
    private final PiiDetection pii;
    private final IngestScheduler ingestScheduler;
    private final AoTime time;
    private final Duration interval;
    private final boolean schedulerEnabled;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("ao-semantic-", 0).factory());
    private final AtomicBoolean running = new AtomicBoolean();
    private final List<Runnable> afterRun = new CopyOnWriteArrayList<>();
    private volatile CancellationToken current;
    private volatile ScheduledFuture<?> next;
    private volatile boolean lifecycle;
    /** Guarded by {@code this}: a scheduled pass is executing / a trigger arrived meanwhile. */
    private boolean inProgress;
    private boolean rerunRequested;

    public SemanticCompilationService(SemanticCompiler compiler, ConnectionService connections, CatalogRepository catalog,
            SemanticRepository semantic, HistoryRepository history, RuntimeSettings settings, VectorStore vectors,
            RuleService rules, PiiDetection pii, IngestScheduler ingestScheduler, AoTime time, Duration interval,
            boolean schedulerEnabled) {
        this.compiler = compiler;
        this.connections = connections;
        this.catalog = catalog;
        this.semantic = semantic;
        this.history = history;
        this.settings = settings;
        this.vectors = vectors;
        this.rules = rules;
        this.pii = pii;
        this.ingestScheduler = ingestScheduler;
        this.time = time;
        this.interval = interval;
        this.schedulerEnabled = schedulerEnabled;
    }

    /** Hook after each finished pass (dataset sync of the NL-chart module). */
    public void onRunCompleted(Runnable r) {
        afterRun.add(r);
    }

    public boolean isCompiling() {
        return running.get();
    }

    /**
     * Non-blocking trigger (internal; the public API triggers by enabling the flag). A trigger
     * that arrives while a scheduled pass is executing is only noted and honoured right after it;
     * a second scheduled task would start a second timer chain that never ends.
     */
    public synchronized void trigger() {
        if (inProgress) {
            rerunRequested = true;
            return;
        }
        if (next != null) {
            next.cancel(false);
        }
        next = executor.schedule(this::scheduledRun, 0, TimeUnit.MILLISECONDS);
    }

    private void scheduledRun() {
        synchronized (this) {
            inProgress = true;
        }
        try {
            runNow();
        } catch (RuntimeException e) {
            log.error("Scheduled semantic compilation failed", e);
        } finally {
            synchronized (this) {
                inProgress = false;
                boolean rerun = rerunRequested;
                rerunRequested = false;
                if (lifecycle && (rerun || schedulerEnabled)) {
                    next = executor.schedule(this::scheduledRun, rerun ? 0 : interval.toMillis(), TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    /** One pass over all databases; does nothing while the flag is off. Returns false when skipped. */
    public boolean runNow() {
        if (!settings.semanticCompilationEnabled()) {
            log.info("Semantic compilation is disabled; skipping run");
            return false;
        }
        if (!running.compareAndSet(false, true)) {
            log.info("Semantic compilation already running");
            return false;
        }
        CancellationToken token = new CancellationToken();
        current = token;
        String hid = null;
        boolean ok = true;
        List<String> summaries = new ArrayList<>();
        try {
            // inside the try: a store error here must not leave "running" set forever
            hid = history.start(null, time.now());
            for (ConnectionSpec spec : connections.answeringConnections()) {
                token.check();
                Optional<Map<String, Object>> db = catalog.databaseByName(spec.name());
                if (db.isEmpty()) {
                    continue;
                }
                SourceDatabase connector = connections.connector(spec);
                summaries.add(compiler.compile((String) db.get().get("id"), spec.name(), connector, token).toString());
            }
        } catch (CancellationToken.CancelledException e) {
            ok = false;
            summaries.add("cancelled");
            log.info("Semantic compilation cancelled");
        } catch (RuntimeException e) {
            ok = false;
            summaries.add("failed: " + e.getMessage());
            log.error("Semantic compilation failed", e);
        } finally {
            current = null;
            running.set(false);
            if (hid != null) {
                try {
                    history.finish(hid, ok, String.join("; ", summaries), time.now());
                } catch (RuntimeException e) {
                    log.warn("Compilation history could not be written: {}", e.getMessage());
                }
            }
        }
        // failures of these stages never mark the compilation as failed (NFR-REL-01)
        try {
            rules.replayAll();
        } catch (RuntimeException e) {
            log.warn("Rule replay after compilation failed: {}", e.getMessage());
        }
        try {
            pii.propagate();
        } catch (RuntimeException e) {
            log.warn("PII propagation failed: {}", e.getMessage());
        }
        afterRun.forEach(r -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.warn("After-compilation hook failed: {}", e.getMessage());
            }
        });
        return ok;
    }

    /** Internal stop (POST /semantic/stop, scheduler.abort()): cooperative, running tables finish. */
    public void abort() {
        CancellationToken t = current;
        if (t != null) {
            t.cancel();
        }
    }

    /**
     * Reset (reset_semantic_layer_and_recompile): abort, wait, delete the semantic layer of all or
     * one database with its embeddings, then recompile asynchronously.
     *
     * @param databaseName {@code null} resets every database; an unknown name resets nothing
     * @return false when {@code databaseName} is given but not in the catalog
     */
    public boolean reset(String databaseName) {
        String dbId = null;
        if (databaseName != null) {
            // resolved first: an unknown name must never fall through to "reset everything"
            dbId = catalog.databaseByName(databaseName).map(r -> (String) r.get("id")).orElse(null);
            if (dbId == null) {
                log.warn("Semantic reset skipped: database {} is not in the catalog", databaseName);
                return false;
            }
        }
        abort();
        long deadline = System.currentTimeMillis() + 120_000;
        while (running.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        semantic.deleteSemanticLayer(dbId);
        vectors.deleteByFilter(VectorStore.SEMANTIC_LAYER, databaseName, null);
        trigger();
        return true;
    }

    /** GET /api/semantic-compilation/status. */
    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        // calculated means only: at least one Term exists
        s.put("calculated", semantic.countTerms() > 0);
        s.put("running", running.get());
        Optional<Map<String, Object>> success = history.last("succeeded");
        Optional<Map<String, Object>> failure = history.last("failed");
        String successAt = success.map(r -> String.valueOf(r.get("finished_at"))).orElse(null);
        String failureAt = failure.map(r -> String.valueOf(r.get("finished_at"))).orElse(null);
        s.put("last_success_at", successAt);
        s.put("last_failure_at", failureAt != null && (successAt == null
                || Instant.parse(failureAt).isAfter(Instant.parse(successAt))) ? failureAt : null);
        return s;
    }

    @Override
    public void start() {
        lifecycle = true;
        if (schedulerEnabled) {
            // the first pass waits for the first ingest run
            AtomicBoolean first = new AtomicBoolean(true);
            ingestScheduler.onRunCompleted(() -> {
                if (first.compareAndSet(true, false)) {
                    trigger();
                }
            });
            if (ingestScheduler.completedRuns() > 0 && first.compareAndSet(true, false)) {
                trigger();
            }
        }
    }

    @Override
    public void stop() {
        stop(() -> {});
    }

    @Override
    public void stop(Runnable callback) {
        lifecycle = false;
        abort();
        executor.shutdownNow();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return lifecycle;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 200;
    }
}
