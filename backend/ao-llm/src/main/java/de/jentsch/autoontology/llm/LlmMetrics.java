// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.llm;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters of LLM calls (NFR-OBS-01). A Micrometer registry, when present, is fed by the API module;
 * this class has no Micrometer dependency so the LLM module stays small.
 */
public class LlmMetrics {
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong nanos = new AtomicLong();
    private volatile Listener listener;

    /** Receives every call. */
    public interface Listener {
        void onCall(ModelRole role, String schema, long nanos, boolean success);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void record(ModelRole role, String schema, long durationNanos, boolean success) {
        calls.incrementAndGet();
        nanos.addAndGet(durationNanos);
        if (!success) {
            failures.incrementAndGet();
        }
        Listener l = listener;
        if (l != null) {
            l.onCall(role, schema, durationNanos, success);
        }
    }

    public long calls() {
        return calls.get();
    }

    public long failures() {
        return failures.get();
    }
}
