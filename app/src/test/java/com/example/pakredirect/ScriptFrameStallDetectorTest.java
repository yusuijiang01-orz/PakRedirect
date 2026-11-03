package com.example.pakredirect;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ScriptFrameStallDetectorTest {
    @Test public void oneTransientChangedFrameDoesNotResetTheStableTimer() {
        ScriptFrameStallDetector detector = new ScriptFrameStallDetector();
        int[] original = solidScreen(0xff101010);
        detector.observePixels(original, 10, 10, 1_000L);

        ScriptFrameStallDetector.Observation transientFrame = detector.observePixels(
                changedPixels(original), 10, 10, 61_000L);
        assertFalse(transientFrame.stalled);
        assertEquals(60_000L, transientFrame.stableForMs);

        ScriptFrameStallDetector.Observation returnedToOriginal = detector.observePixels(
                original.clone(), 10, 10, 66_000L);
        assertEquals(65_000L, returnedToOriginal.stableForMs);
        assertTrue(detector.observePixels(original.clone(), 10, 10, 121_000L).stalled);
    }

    @Test public void twoPersistentChangedFramesRestartTheStableTimer() {
        ScriptFrameStallDetector detector = new ScriptFrameStallDetector();
        int[] original = solidScreen(0xff101010);
        int[] changed = changedPixels(original);
        detector.observePixels(original, 10, 10, 1_000L);
        detector.observePixels(original.clone(), 10, 10, 61_000L);

        ScriptFrameStallDetector.Observation firstChanged = detector.observePixels(
                changed.clone(), 10, 10, 66_000L);
        assertEquals(65_000L, firstChanged.stableForMs);
        ScriptFrameStallDetector.Observation persistentChanged = detector.observePixels(
                changed.clone(), 10, 10, 71_000L);
        assertEquals(0L, persistentChanged.stableForMs);
        assertFalse(persistentChanged.stalled);
        assertTrue(detector.observePixels(changed.clone(), 10, 10, 191_000L).stalled);
    }

    private static int[] solidScreen(int color) {
        int[] pixels = new int[100];
        Arrays.fill(pixels, color);
        return pixels;
    }

    private static int[] changedPixels(int[] source) {
        int[] pixels = source.clone();
        pixels[0] = 0xfff0f0f0;
        pixels[1] = 0xfff0f0f0;
        return pixels;
    }
}
