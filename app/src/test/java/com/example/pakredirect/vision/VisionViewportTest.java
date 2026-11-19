package com.example.pakredirect.vision;

import static org.junit.Assert.*;
import org.junit.Test;

public final class VisionViewportTest {
    @Test public void fullFrameMaintainsLegacy1920CoordinateMapping() {
        VisionViewport v = VisionViewport.full(1280, 720);
        assertFalse(v.calibrated);
        assertTrue(v.isFull());
        assertEquals(640, v.toPixelX(960, 1920));
        assertEquals(360, v.toPixelY(540, 1080));
    }

    @Test public void letterboxMappingKeepsOffsetsInViewport() {
        VisionViewport v = new VisionViewport(160, 0, 1440, 720, 1600, 720, true);
        assertTrue(v.calibrated);
        assertEquals(160, v.toPixelX(0, 1920));
        assertEquals(800, v.toPixelX(960, 1920));
        assertEquals(1439, v.toPixelX(1920, 1920));
        assertEquals(0.5, v.normalizedX(800), 1e-9);
    }

    @Test public void invalidViewportFailsClosed() {
        try {
            new VisionViewport(400, 0, 100, 720, 1280, 720, true);
            fail("negative viewport cannot be used to click");
        } catch (IllegalArgumentException expected) { }
    }
}
