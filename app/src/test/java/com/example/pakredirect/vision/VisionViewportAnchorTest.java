package com.example.pakredirect.vision;
import static org.junit.Assert.*;
import java.util.Arrays;
import org.junit.Test;

public class VisionViewportAnchorTest {
    @Test public void fitsScaleAndOffsetFromMultipleAnchors(){
        VisionViewport v=VisionViewportDetector.fitAnchors(2200,1200,1920,1080,Arrays.asList(
                new VisionViewportDetector.Anchor(0,0,100,30),
                new VisionViewportDetector.Anchor(1920,0,2020,30),
                new VisionViewportDetector.Anchor(0,1080,100,1110),
                new VisionViewportDetector.Anchor(1920,1080,2020,1110)),2);
        assertTrue(v.calibrated);
        assertEquals(100,v.left);assertEquals(2020,v.right);
        assertEquals(30,v.top);assertEquals(1110,v.bottom);
    }
    @Test public void rejectsInconsistentAnchors(){
        VisionViewport v=VisionViewportDetector.fitAnchors(2200,1200,1920,1080,Arrays.asList(
                new VisionViewportDetector.Anchor(0,0,100,30),
                new VisionViewportDetector.Anchor(1920,0,2020,30),
                new VisionViewportDetector.Anchor(0,1080,100,1110),
                new VisionViewportDetector.Anchor(1920,1080,1700,900)),2);
        assertFalse(v.calibrated);
    }
}
