package com.example.pakredirect;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class LegacyTuringCompatTest {
    @Test
    public void scalesSmallLegacyOcrRegionsToMlKitMinimumWithoutChangingAspectRatio() {
        int[] size = LegacyTuringCompat.ocrInputDimensions(55, 22);

        assertEquals(80, size[0]);
        assertEquals(32, size[1]);
    }

    @Test
    public void leavesAlreadyLargeOcrImagesAtTheirOriginalSize() {
        assertArrayEquals(new int[]{1600, 900}, LegacyTuringCompat.ocrInputDimensions(1600, 900));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyOcrImages() {
        LegacyTuringCompat.ocrInputDimensions(55, 0);
    }
}
