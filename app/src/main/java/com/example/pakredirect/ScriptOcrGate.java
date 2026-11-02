package com.example.pakredirect;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Serializes ML Kit requests and blocks new work until a timed-out task finishes. */
final class ScriptOcrGate {
    // ML Kit's Task cannot be cancelled reliably. A timed-out inference still
    // consumes CPU, so never start another inference until its callback returns.
    static final int MAX_ORPHANED_REQUESTS = 1;
    private static final long RECOVERY_ACQUIRE_MS = 250L;

    private final Semaphore permit = new Semaphore(1, true);
    private int orphanedRequests;

    Lease acquire(long timeoutMs) throws InterruptedException {
        final long waitMs;
        synchronized (this) {
            if (orphanedRequests >= MAX_ORPHANED_REQUESTS) return null;
            waitMs = orphanedRequests == 0
                    ? Math.max(1L, timeoutMs)
                    : Math.min(Math.max(1L, timeoutMs), RECOVERY_ACQUIRE_MS);
        }
        if (!permit.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) return null;
        synchronized (this) {
            if (orphanedRequests >= MAX_ORPHANED_REQUESTS) {
                permit.release();
                return null;
            }
            return new Lease(this);
        }
    }

    synchronized void markTimedOut(Lease lease) {
        if (lease == null || lease.owner != this || lease.finished || lease.timedOut) return;
        lease.timedOut = true;
        orphanedRequests++;
        // Keep the semaphore until the original ML Kit callback completes. The
        // circuit opens immediately, rather than running a second expensive OCR
        // task beside an inference that did not respect its timeout.
    }

    synchronized void complete(Lease lease) {
        if (lease == null || lease.owner != this || lease.finished) return;
        lease.finished = true;
        if (lease.timedOut) orphanedRequests = Math.max(0, orphanedRequests - 1);
        release(lease);
    }

    synchronized boolean isCircuitOpen() {
        return orphanedRequests >= MAX_ORPHANED_REQUESTS;
    }

    synchronized int orphanedRequestCount() {
        return orphanedRequests;
    }

    private void release(Lease lease) {
        if (lease.released) return;
        lease.released = true;
        permit.release();
    }

    static final class Lease {
        private final ScriptOcrGate owner;
        private boolean timedOut;
        private boolean finished;
        private boolean released;

        private Lease(ScriptOcrGate owner) { this.owner = owner; }
    }
}
