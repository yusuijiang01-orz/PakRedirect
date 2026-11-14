package com.example.pakredirect;

/** Enforces Android's minimum spacing between AccessibilityService screenshot requests. */
final class ScriptScreenshotRequestGate {
    static final long MIN_INTERVAL_MS = 1_250L;

    private long lastRequestAtMs = -1L;

    long delayUntilNextRequestMs(long nowMs) {
        if (lastRequestAtMs < 0L) return 0L;
        long elapsed = Math.max(0L, nowMs - lastRequestAtMs);
        return Math.max(0L, MIN_INTERVAL_MS - elapsed);
    }

    void recordRequest(long nowMs) {
        lastRequestAtMs = nowMs;
    }

    void reset() {
        lastRequestAtMs = -1L;
    }
}
