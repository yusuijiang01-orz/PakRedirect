package com.example.pakredirect;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class LegacyTemplateScalePlanTest {
    @Test public void landscapeLetterboxUsesTheLimitingAxisAndMeasuredOffsets() {
        double[] scales = LegacyTemplateScalePlan.candidates(1d, 589d / 720d);
        assertEquals(3, scales.length);
        assertEquals(589d / 720d, scales[0], 0.0001d);
        assertEquals(0.84d * 589d / 720d, scales[1], 0.0001d);
        assertEquals(1.05d * 589d / 720d, scales[2], 0.0001d);
    }

    @Test public void legacy1600By900CaptureIncludesBothObservedTemplateScales() {
        double[] scales = LegacyTemplateScalePlan.candidates(1.25d, 1.25d);
        assertTrue(containsNear(scales, 1.25d));
        assertTrue(containsNear(scales, 1.05d));
    }

    @Test public void invalidScaleFallsBackToReferenceSize() {
        double[] scales = LegacyTemplateScalePlan.candidates(Double.NaN, 0d);
        assertEquals(1, scales.length);
        assertEquals(1d, scales[0], 0d);
    }

    private static boolean containsNear(double[] values, double target) {
        for (double value : values) if (Math.abs(value - target) < 0.005d) return true;
        return false;
    }
}
