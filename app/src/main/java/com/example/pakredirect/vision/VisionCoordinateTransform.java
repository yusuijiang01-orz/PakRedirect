package com.example.pakredirect.vision;

import android.graphics.Rect;

/**
 * Converts a script reference canvas into the detected viewport. Rotation is
 * clockwise and explicit; callers must not infer a 90-degree rotation merely
 * because the device is portrait.
 */
public final class VisionCoordinateTransform {
    public final int referenceWidth, referenceHeight;
    public final int rotationDegrees;
    public final VisionViewport viewport;

    public VisionCoordinateTransform(int referenceWidth, int referenceHeight,
                                     VisionViewport viewport, int rotationDegrees) {
        if (referenceWidth <= 0 || referenceHeight <= 0 || viewport == null)
            throw new IllegalArgumentException("reference dimensions and viewport are required");
        int normalized = ((rotationDegrees % 360) + 360) % 360;
        if (normalized % 90 != 0) throw new IllegalArgumentException("rotation must be 0/90/180/270");
        this.referenceWidth = referenceWidth;
        this.referenceHeight = referenceHeight;
        this.viewport = viewport;
        this.rotationDegrees = normalized;
    }

    public int mapX(double x, double y) { return (int) Math.round(map(x,y)[0]); }
    public int mapY(double x, double y) { return (int) Math.round(map(x,y)[1]); }

    public double[] map(double x, double y) {
        double u = clamp(x / referenceWidth, 0d, 1d);
        double v = clamp(y / referenceHeight, 0d, 1d);
        double ru, rv;
        switch (rotationDegrees) {
            case 90:  ru = 1d-v; rv = u; break;
            case 180: ru = 1d-u; rv = 1d-v; break;
            case 270: ru = v; rv = 1d-u; break;
            default:  ru = u; rv = v;
        }
        return new double[] {
                viewport.left + ru * Math.max(0, viewport.right - viewport.left - 1),
                viewport.top + rv * Math.max(0, viewport.bottom - viewport.top - 1)
        };
    }

    public double[] inverse(double px, double py) {
        double u = (px - viewport.left) / Math.max(1d, viewport.right - viewport.left - 1d);
        double v = (py - viewport.top) / Math.max(1d, viewport.bottom - viewport.top - 1d);
        double x, y;
        switch (rotationDegrees) {
            case 90:  x = v; y = 1d-u; break;
            case 180: x = 1d-u; y = 1d-v; break;
            case 270: x = 1d-v; y = u; break;
            default:  x = u; y = v;
        }
        return new double[] { clamp(x,0d,1d)*referenceWidth, clamp(y,0d,1d)*referenceHeight };
    }

    public Rect mapRect(Rect reference) {
        if (reference == null || reference.isEmpty()) return new Rect();
        double[][] p = {
                map(reference.left, reference.top), map(reference.right, reference.top),
                map(reference.left, reference.bottom), map(reference.right, reference.bottom)
        };
        double minX=Double.MAX_VALUE,minY=Double.MAX_VALUE,maxX=-1,maxY=-1;
        for(double[] point:p){minX=Math.min(minX,point[0]);minY=Math.min(minY,point[1]);maxX=Math.max(maxX,point[0]);maxY=Math.max(maxY,point[1]);}
        return new Rect((int)Math.floor(minX),(int)Math.floor(minY),
                Math.min(viewport.right,(int)Math.ceil(maxX)+1),
                Math.min(viewport.bottom,(int)Math.ceil(maxY)+1));
    }

    private static double clamp(double value,double min,double max) {
        return Math.max(min,Math.min(max,value));
    }
}
