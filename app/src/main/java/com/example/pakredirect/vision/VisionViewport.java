package com.example.pakredirect.vision;

/** Conservative viewport transform; returns UNKNOWN rather than guessing bars. */
public final class VisionViewport {
    public final int left, top, right, bottom;
    public final int imageWidth, imageHeight;
    public final boolean calibrated;

    public VisionViewport(int left, int top, int right, int bottom,
                          int imageWidth, int imageHeight, boolean calibrated) {
        if (imageWidth < 1 || imageHeight < 1 || left < 0 || top < 0
                || right > imageWidth || bottom > imageHeight
                || right <= left || bottom <= top) throw new IllegalArgumentException("invalid viewport");
        this.left=left; this.top=top; this.right=right; this.bottom=bottom;
        this.imageWidth=imageWidth; this.imageHeight=imageHeight; this.calibrated=calibrated;
    }

    public static VisionViewport full(int width, int height) {
        return new VisionViewport(0,0,width,height,width,height,false);
    }

    public double normalizedX(double x) { return (x-left)/(right-left); }
    public double normalizedY(double y) { return (y-top)/(bottom-top); }
    public int toPixelX(double referenceX,int referenceWidth) {
        return Math.max(left, Math.min(right-1,(int) Math.round(left+referenceX*(right-left)/referenceWidth)));
    }
    public int toPixelY(double referenceY,int referenceHeight) {
        return Math.max(top, Math.min(bottom-1,(int) Math.round(top+referenceY*(bottom-top)/referenceHeight)));
    }
    public boolean isFull() { return left==0 && top==0 && right==imageWidth && bottom==imageHeight; }
}
