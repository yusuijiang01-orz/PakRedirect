package com.example.pakredirect;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Offline check that screenshot geometry comes from the PNG raster itself. */
public final class AdbLoginPngDecodeTest {
    @Test public void decodedPngUsesItsLandscapeRasterDimensions() throws Exception {
        BufferedImage source = new BufferedImage(1600, 900, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(source, "png", bytes));

        BufferedImage decoded = AdbSemanticLoginTest.decodePng(bytes.toByteArray());

        assertEquals(1600, decoded.getWidth());
        assertEquals(900, decoded.getHeight());
    }

}
