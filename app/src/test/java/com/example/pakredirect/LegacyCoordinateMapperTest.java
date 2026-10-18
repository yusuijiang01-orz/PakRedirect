package com.example.pakredirect;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class LegacyCoordinateMapperTest {
    @Test public void rotatesPortraitReferenceAndScalesToLandscapeScreenshot() {
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(720, 1280, 1600, 900);

        assertEquals(1280, mapper.referenceWidth());
        assertEquals(720, mapper.referenceHeight());
        assertEquals(1449, mapper.toScreenX(1159));
        assertEquals(138, mapper.toScreenY(110));
        assertEquals(1159, mapper.toReferenceX(1449));
        assertEquals(110, mapper.toReferenceY(138));
    }

    @Test public void portraitReferenceScalesWithoutSwappingAxes() {
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(720, 1280, 900, 1600);

        assertEquals(720, mapper.referenceWidth());
        assertEquals(1280, mapper.referenceHeight());
        assertEquals(625, mapper.toScreenX(500));
        assertEquals(1250, mapper.toScreenY(1000));
    }

    @Test public void colorSignaturesUseTheSameScreenTransform() {
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(720, 1280, 1600, 900);

        assertEquals("757|566|c69a84-111111,0|0|ffffff-000000",
                mapper.mapColorPoints("605.6|452.8|c69a84-111111,0|0|ffffff-000000"));
    }

    @Test public void onlyPackagedTemplatesAreScaledToCapturedResolution() {
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(720, 1280, 1600, 900);

        assertEquals(true, mapper.shouldScaleTemplate(false));
        assertEquals(false, mapper.shouldScaleTemplate(true));
        assertEquals(false, new LegacyCoordinateMapper(1280, 720, 1280, 720)
                .shouldScaleTemplate(false));
    }
}
