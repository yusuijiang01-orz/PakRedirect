package com.example.pakredirect;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ScriptScreenshotRetryTest {
    @Test public void retriesInternalScreenshotFailuresWithBoundedBackoff() {
        assertEquals(250L, ScriptScreenshotRetry.internalErrorDelayMs(1));
        assertEquals(500L, ScriptScreenshotRetry.internalErrorDelayMs(2));
        assertEquals(750L, ScriptScreenshotRetry.internalErrorDelayMs(3));
        assertEquals(1_000L, ScriptScreenshotRetry.internalErrorDelayMs(4));
        assertEquals(1_250L, ScriptScreenshotRetry.internalErrorDelayMs(5));
        assertEquals(-1L, ScriptScreenshotRetry.internalErrorDelayMs(6));
    }

    @Test public void waitsOutAndroidScreenshotRateLimitBeforeRetrying() {
        assertEquals(1_500L, ScriptScreenshotRetry.intervalErrorDelayMs());
    }

    @Test public void screenshotRequestGateKeepsAServiceWideMinimumSpacing() {
        ScriptScreenshotRequestGate gate = new ScriptScreenshotRequestGate();
        assertEquals(0L, gate.delayUntilNextRequestMs(10_000L));
        gate.recordRequest(10_000L);
        assertEquals(750L, gate.delayUntilNextRequestMs(10_500L));
        assertEquals(0L, gate.delayUntilNextRequestMs(11_250L));
        gate.recordRequest(11_250L);
        gate.reset();
        assertEquals(0L, gate.delayUntilNextRequestMs(11_250L));
    }
}
