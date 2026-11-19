package com.example.pakredirect.vision;
import static org.junit.Assert.*;
import android.graphics.Rect;
import org.junit.Test;

public class VisionCoordinateTransformTest {
    @Test public void mapsScaleAndOffset() {
        VisionViewport v=new VisionViewport(100,50,1700,950,1800,1000,true);
        VisionCoordinateTransform t=new VisionCoordinateTransform(1920,1080,v,0);
        assertEquals(100,t.mapX(0,0));
        assertEquals(1699,t.mapX(1920,1080));
        assertEquals(50,t.mapY(0,0));
        assertEquals(950,t.mapY(1920,1080));
        double[] back=t.inverse(t.mapX(480,270),t.mapY(480,270));
        assertEquals(480,back[0],1.5);
        assertEquals(270,back[1],1.5);
    }
    @Test public void supportsAllQuarterTurns() {
        VisionViewport v=VisionViewport.full(1080,1920);
        VisionCoordinateTransform t90=new VisionCoordinateTransform(1920,1080,v,90);
        assertEquals(1079,t90.mapX(0,0));
        assertEquals(0,t90.mapY(0,0));
        VisionCoordinateTransform t180=new VisionCoordinateTransform(1920,1080,v,180);
        assertEquals(1079,t180.mapX(0,0));
        assertEquals(1919,t180.mapY(0,0));
        VisionCoordinateTransform t270=new VisionCoordinateTransform(1920,1080,v,270);
        assertEquals(0,t270.mapX(0,0));
        assertEquals(1919,t270.mapY(0,0));
    }
    @Test public void mapsRectAndRejectsUnsupportedAngles() {
        VisionCoordinateTransform t=new VisionCoordinateTransform(100,100,VisionViewport.full(200,200),0);
        Rect mapped=t.mapRect(new Rect(10,20,30,40));
        assertEquals(new Rect(20,40,61,81),mapped);
        try { new VisionCoordinateTransform(100,100,VisionViewport.full(200,200),45); fail(); }
        catch(IllegalArgumentException expected){}
    }
}
