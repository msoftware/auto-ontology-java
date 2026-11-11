// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.common;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation (FR-SEM-06, FR-CHAT-02): a flag plus cancel hooks of resources in use
 * (JDBC {@code Statement.cancel()}, HTTP calls). A run binds its token to the executing thread so
 * that connectors can register their statements without explicit plumbing.
 */
public final class CancellationToken {
    private static final ThreadLocal<CancellationToken> CURRENT = new ThreadLocal<>();

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final Set<Runnable> hooks = ConcurrentHashMap.newKeySet();

    public static CancellationToken current() {
        return CURRENT.get();
    }

    /** Binds this token to the current thread until the returned scope is closed. */
    public Scope bind() {
        CancellationToken previous = CURRENT.get();
        CURRENT.set(this);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            for (Runnable hook : hooks) {
                try {
                    hook.run();
                } catch (RuntimeException e) {
                    // best effort: the resource may already be closed
                }
            }
        }
    }

    /** Registers a cancel hook; returns a handle that removes it again. */
    public Scope onCancel(Runnable hook) {
        hooks.add(hook);
        if (cancelled.get()) {
            hook.run();
        }
        return () -> hooks.remove(hook);
    }

    /** Throws {@link CancelledException} when cancelled. */
    public void check() {
        if (cancelled.get()) {
            throw new CancelledException();
        }
    }

    /** A closable registration without checked exceptions. */
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /** The run was cancelled. */
    public static class CancelledException extends RuntimeException {
        public CancelledException() {
            super("cancelled");
        }
    }
}
