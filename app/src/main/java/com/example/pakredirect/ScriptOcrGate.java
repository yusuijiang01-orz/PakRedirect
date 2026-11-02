package com.example.pakredirect;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Serializes live ML Kit requests while bounding timed-out work. */
final class ScriptOcrGate {
    // ML Kit's Task cannot be cancelled reliably. Allow one recovery request
    // alongside a stuck inference, but stop if that request also times out.
    static final int MAX_ORPHANED_REQUESTS = 2;
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
        // The task retains its bitmap and recognizer until its callback. Only
        // its gate slot is released, allowing a bounded recovery attempt.
        release(lease);
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
