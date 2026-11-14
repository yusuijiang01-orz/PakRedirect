package com.example.pakredirect;

/** Bounded backoff for transient Android accessibility screenshot failures. */
final class ScriptScreenshotRetry {
    private static final int MAX_INTERNAL_ERROR_RETRIES = 3;
    private static final long INTERVAL_ERROR_DELAY_MS = 1_000L;

    private ScriptScreenshotRetry() { }

    /** Returns the delay after this internal-error retry, or -1 after the retry budget. */
    static long internalErrorDelayMs(int retryNumber) {
        if (retryNumber < 1 || retryNumber > MAX_INTERNAL_ERROR_RETRIES) return -1L;
        return 250L * retryNumber;
    }

    /** Android rate-limits accessibility screenshots; wait long enough before retrying. */
    static long intervalErrorDelayMs() {
        return INTERVAL_ERROR_DELAY_MS;
    }
}
