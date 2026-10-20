package com.example.pakredirect;

import java.util.function.Supplier;

/** Bounded, pause/stop-aware wait for Android to reconnect the accessibility adapter. */
final class ScriptAdapterConnectionGate {
    private ScriptAdapterConnectionGate() { }

    static <T> T await(Supplier<T> connection, ScriptExecutionControl control,
                       long timeoutMs, String timeoutMessage) {
        long deadline = System.nanoTime() + Math.max(0L, timeoutMs) * 1_000_000L;
        while (true) {
            control.checkpoint();
            T current = connection.get();
            if (current != null) return current;
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) throw new IllegalStateException(timeoutMessage);
            control.sleep(Math.min(100L, Math.max(1L, remainingNanos / 1_000_000L)));
        }
    }
}
