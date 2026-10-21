package com.example.pakredirect;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ScriptScreenshotRetryTest {
    @Test public void retriesInternalScreenshotFailuresWithBoundedBackoff() {
        assertEquals(250L, ScriptScreenshotRetry.internalErrorDelayMs(1));
        assertEquals(500L, ScriptScreenshotRetry.internalErrorDelayMs(2));
        assertEquals(750L, ScriptScreenshotRetry.internalErrorDelayMs(3));
        assertEquals(-1L, ScriptScreenshotRetry.internalErrorDelayMs(4));
    }
}
