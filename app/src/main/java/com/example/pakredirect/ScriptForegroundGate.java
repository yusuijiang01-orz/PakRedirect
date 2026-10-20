package com.example.pakredirect;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Waits for a target app to own the foreground before screen automation starts. */
final class ScriptForegroundGate {
    private static final int MAX_SCREENSHOT_ATTEMPTS = 3;

    private ScriptForegroundGate() { }

    interface ScreenshotCapture<T> {
        T capture(long timeoutMs) throws Exception;
    }

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

    /** Wait only after a real foreground loss, with hooks for cache invalidation and status. */
    static boolean awaitRecovery(BooleanSupplier ready, Runnable checkpoint,
                                 Runnable onWaiting, Runnable onResumed,
                                 long timeoutMs, String timeoutMessage)
            throws InterruptedException, TimeoutException {
        if (ready == null || checkpoint == null) throw new IllegalArgumentException("Gate callbacks are required");
        checkpoint.run();
        if (ready.getAsBoolean()) return false;
        if (onWaiting != null) onWaiting.run();
        await(ready, checkpoint, timeoutMs, timeoutMessage);
        checkpoint.run();
        if (onResumed != null) onResumed.run();
        return true;
    }

    /** Retry only a screenshot rejected by the foreground guard; every attempt rechecks the target. */
    static <T> T captureWithRecovery(ScreenshotCapture<T> capture, BooleanSupplier ready,
                                     Runnable checkpoint, Runnable onRejected,
                                     Runnable onWaiting, Runnable onResumed,
                                     long timeoutMs, String timeoutMessage) throws Exception {
        if (capture == null || ready == null || checkpoint == null) {
            throw new IllegalArgumentException("Capture and gate callbacks are required");
        }
        if (timeoutMs < 0) throw new IllegalArgumentException("Timeout must not be negative");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        for (int attempt = 1; attempt <= MAX_SCREENSHOT_ATTEMPTS; attempt++) {
            checkpoint.run();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException(timeoutMessage);
            try {
                return capture.capture(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)));
            } catch (TargetGameNotForegroundException rejected) {
                checkpoint.run();
                if (onRejected != null) onRejected.run();
                if (attempt == MAX_SCREENSHOT_ATTEMPTS) throw rejected;
                remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException(timeoutMessage);
                awaitRecovery(ready, checkpoint, onWaiting, onResumed,
                        Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining)), timeoutMessage);
            }
        }
        throw new AssertionError("Screenshot attempt limit was not enforced");
    }

    static void requireReady(BooleanSupplier ready, String message) {
        if (ready == null || !ready.getAsBoolean()) throw new IllegalStateException(message);
    }
}
