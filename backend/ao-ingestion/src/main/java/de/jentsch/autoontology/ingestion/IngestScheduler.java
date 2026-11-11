// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.ingestion;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * FR-ING-02: runs at start and then every {@code INGEST_INTERVAL_HOURS} measured from the end of
 * the last run. A manual trigger resets the timer. Connections are reloaded on every run.
 */
public class IngestScheduler implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(IngestScheduler.class);

    private final IngestService ingest;
    private final Duration interval;
    private final boolean enabled;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("ao-ingest-", 0).factory());
    private final List<Runnable> afterRun = new CopyOnWriteArrayList<>();
    private volatile ScheduledFuture<?> next;
    private volatile boolean running;
    private volatile int completedRuns;

    public IngestScheduler(IngestService ingest, Duration interval, boolean enabled) {
        this.ingest = ingest;
        this.interval = interval;
        this.enabled = enabled;
    }

    /** Called after every completed run (the semantic scheduler waits for the first one, FR-SEM-07). */
    public void onRunCompleted(Runnable r) {
        afterRun.add(r);
    }

    public int completedRuns() {
        return completedRuns;
    }

    /** Non-blocking manual trigger; resets the timer. */
    public synchronized void trigger() {
        if (next != null) {
            next.cancel(false);
        }
        next = executor.schedule(this::runOnce, 0, TimeUnit.MILLISECONDS);
    }

    private void runOnce() {
        try {
            ingest.runAll();
        } catch (RuntimeException e) {
            log.error("Scheduled ingest failed", e);
        } finally {
            completedRuns++;
            afterRun.forEach(r -> {
                try {
                    r.run();
                } catch (RuntimeException e) {
                    log.warn("After-ingest hook failed: {}", e.getMessage());
                }
            });
            synchronized (this) {
                if (running) {
                    next = executor.schedule(this::runOnce, interval.toMillis(), TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    @Override
    public void start() {
        running = true;
        if (enabled) {
            trigger();
        }
    }

    @Override
    public void stop() {
        running = false;
        executor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // after the web server is up
        return Integer.MAX_VALUE - 100;
    }
}
