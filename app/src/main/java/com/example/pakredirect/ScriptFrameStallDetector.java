package com.example.pakredirect;

import android.graphics.Bitmap;

/** Detects a continuously unchanged screen using tolerant, downsampled frame comparison. */
final class ScriptFrameStallDetector {
    static final long STALL_AFTER_MS = 120_000L;
    static final double REQUIRED_SIMILARITY = 0.99d;
    private static final int SAMPLE_WIDTH = 96;
    private static final int SAMPLE_HEIGHT = 54;
    private static final int MAX_RGB_DISTANCE = 24;

    static final class Observation {
        final double similarity;
        final long stableForMs;
        final boolean initialized;
        final boolean stalled;

        Observation(double similarity, long stableForMs, boolean initialized, boolean stalled) {
            this.similarity = similarity;
            this.stableForMs = stableForMs;
            this.initialized = initialized;
            this.stalled = stalled;
        }
    }

    private int[] previousPixels;
    private int previousWidth;
    private int previousHeight;
    private long unchangedSinceMs;
    private int consecutiveChangedFrames;

    Observation observe(Bitmap frame, long nowMs) {
        if (frame == null || frame.isRecycled() || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
            reset();
            return new Observation(0d, 0L, false, false);
        }

        Bitmap sample = Bitmap.createScaledBitmap(frame, SAMPLE_WIDTH, SAMPLE_HEIGHT, true);
        int[] pixels = new int[SAMPLE_WIDTH * SAMPLE_HEIGHT];
        sample.getPixels(pixels, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT);
        if (sample != frame && !sample.isRecycled()) sample.recycle();
        return observePixels(pixels, SAMPLE_WIDTH, SAMPLE_HEIGHT, nowMs);
    }

    Observation observePixels(int[] pixels, int width, int height, long nowMs) {
        if (pixels == null || pixels.length == 0 || width <= 0 || height <= 0
                || (long) width * height != pixels.length) {
            reset();
            return new Observation(0d, 0L, false, false);
        }
        if (previousPixels == null || previousWidth != width
                || previousHeight != height || previousPixels.length != pixels.length) {
            previousPixels = pixels;
            previousWidth = width;
            previousHeight = height;
            unchangedSinceMs = nowMs;
            return new Observation(1d, 0L, false, false);
        }

        int similar = 0;
        for (int i = 0; i < pixels.length; i++) {
            if (rgbDistance(previousPixels[i], pixels[i]) <= MAX_RGB_DISTANCE) similar++;
        }
        double similarity = (double) similar / pixels.length;
        if (similarity < REQUIRED_SIMILARITY) {
            consecutiveChangedFrames++;
            // Ignore one transient frame (for example a tap ripple or a brief render glitch).
            // A real screen transition persists across samples and resets the timer on its second frame.
            if (consecutiveChangedFrames >= 2) {
                unchangedSinceMs = nowMs;
                consecutiveChangedFrames = 0;
                previousPixels = pixels;
            }
        } else {
            consecutiveChangedFrames = 0;
            previousPixels = pixels;
        }
        long stableFor = Math.max(0L, nowMs - unchangedSinceMs);
        return new Observation(similarity, stableFor, true,
                similarity >= REQUIRED_SIMILARITY && stableFor >= STALL_AFTER_MS);
    }

    void reset() {
        previousPixels = null;
        previousWidth = 0;
        previousHeight = 0;
        unchangedSinceMs = 0L;
        consecutiveChangedFrames = 0;
    }

    private static int rgbDistance(int first, int second) {
        return Math.abs(((first >> 16) & 0xff) - ((second >> 16) & 0xff))
                + Math.abs(((first >> 8) & 0xff) - ((second >> 8) & 0xff))
                + Math.abs((first & 0xff) - (second & 0xff));
    }
}
