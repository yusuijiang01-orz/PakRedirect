package com.example.pakredirect.vision;
import static org.junit.Assert.*;
import org.junit.Test;

public class VisionFusionTest {
    @Test public void agreeingFramesConfirmAndConflictsRemainUnknown(){
        assertTrue(VisionFusion.agreeFrames("RED",0.95,"RED",0.91,0.85,0.85).confirmed);
        assertFalse(VisionFusion.agreeFrames("RED",0.99,"EMPTY",0.99,0.85,0.85).confirmed);
        assertFalse(VisionFusion.agreeFrames("RED",0.7,"RED",0.99,0.85,0.85).confirmed);
    }
    @Test public void twoIndependentSignalsMustAgree(){
        assertEquals("RED",VisionFusion.agreeSignals("RED",0.95,"RED",0.91,"UNKNOWN",0,0.85).label);
        assertEquals("UNKNOWN",VisionFusion.agreeSignals("RED",0.95,"BLUE",0.95,"UNKNOWN",0,0.85).label);
        assertEquals("UNKNOWN",VisionFusion.agreeSignals("RED",0.8,"RED",0.7,"RED",0.99,0.85).label);
    }
}
