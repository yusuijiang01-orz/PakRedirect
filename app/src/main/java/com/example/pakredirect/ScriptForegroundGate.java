package com.example.pakredirect;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Waits for a target app to own the foreground before screen automation starts. */
final class ScriptForegroundGate {
    private ScriptForegroundGate() { }

    static void await(BooleanSupplier ready, Runnable checkpoint, long timeoutMs,
                      String timeoutMessage) throws InterruptedException, TimeoutException {
        if (ready == null || checkpoint == null) throw new IllegalArgumentException("Gate callbacks are required");
        if (timeoutMs < 0) throw new IllegalArgumentException("Timeout must not be negative");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            checkpoint.run();
            if (ready.getAsBoolean()) return;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException(timeoutMessage);
            long sleepMs = Math.min(100L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            Thread.sleep(sleepMs);
        }
    }

    static void requireReady(BooleanSupplier ready, String message) {
        if (ready == null || !ready.getAsBoolean()) throw new IllegalStateException(message);
    }
}
