package com.example.pakredirect.vision;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Detects genuinely blank/symmetric letterbox strips, not game UI backgrounds.
 * At uncertain scales it intentionally keeps the full frame instead of warping
 * coordinates and causing accidental purchases.
 */
public final class VisionViewportDetector {
    private VisionViewportDetector() { }

    public static VisionViewport detect(Bitmap frame) {
        if (frame == null || frame.isRecycled()) throw new IllegalArgumentException("screenshot required");
        int w=frame.getWidth(), h=frame.getHeight();
        if(w<320 || h<180) return VisionViewport.full(w,h);
        int left=0,right=w,top=0,bottom=h;
        int maxX=Math.min(w/5,160),maxY=Math.min(h/5,120);
        for(int x=0;x<maxX;x+=2){
            if(!darkColumn(frame,x)) break;
            left=x+2;
        }
        for(int x=w-1;x>=w-maxX;x-=2){
            if(!darkColumn(frame,x)) break;
            right=x-1;
        }
        for(int y=0;y<maxY;y+=2){
            if(!darkRow(frame,y)) break;
            top=y+2;
        }
        for(int y=h-1;y>=h-maxY;y-=2){
            if(!darkRow(frame,y)) break;
            bottom=y-1;
        }
        int leftPad=left,rightPad=w-right,topPad=top,bottomPad=h-bottom;
        boolean horizontalBars=leftPad>=w/40 && rightPad>=w/40
                && Math.abs(leftPad-rightPad)<=Math.max(8,w/80);
        boolean verticalBars=topPad>=h/40 && bottomPad>=h/40
                && Math.abs(topPad-bottomPad)<=Math.max(8,h/80);
        if(!horizontalBars && !verticalBars) return VisionViewport.full(w,h);
        if(!horizontalBars){left=0;right=w;}
        if(!verticalBars){top=0;bottom=h;}
        if(right-left < w/2 || bottom-top < h/2) return VisionViewport.full(w,h);
        return new VisionViewport(left,top,right,bottom,w,h,true);
    }

    /** A measured UI anchor pair used to fit scale and offset without guessing from aspect ratio. */
    public static final class Anchor {
        public final double referenceX,referenceY,screenX,screenY;
        public Anchor(double referenceX,double referenceY,double screenX,double screenY){
            this.referenceX=referenceX;this.referenceY=referenceY;this.screenX=screenX;this.screenY=screenY;
        }
    }

    /**
     * Fits independent X/Y scale and offset from at least three measured UI
     * anchors. Rejects inconsistent layouts instead of manufacturing a viewport.
     */
    public static VisionViewport fitAnchors(int frameWidth,int frameHeight,int referenceWidth,
                                             int referenceHeight,java.util.List<Anchor> anchors,
                                             double maximumResidualPixels){
        if(frameWidth<=0||frameHeight<=0||referenceWidth<=0||referenceHeight<=0
                ||anchors==null||anchors.size()<3||!Double.isFinite(maximumResidualPixels)
                ||maximumResidualPixels<0) return VisionViewport.full(Math.max(1,frameWidth),Math.max(1,frameHeight));
        double[] x=fitAxis(anchors,true),y=fitAxis(anchors,false);
        if(x==null||y==null||x[0]<=0||y[0]<=0||x[0]>3||y[0]>3||x[0]<0.25||y[0]<0.25)
            return VisionViewport.full(frameWidth,frameHeight);
        double error=0;
        for(Anchor a:anchors){
            double dx=(x[0]*a.referenceX+x[1])-a.screenX;
            double dy=(y[0]*a.referenceY+y[1])-a.screenY;
            error+=dx*dx+dy*dy;
        }
        double rms=Math.sqrt(error/(anchors.size()*2d));
        if(!Double.isFinite(rms)||rms>maximumResidualPixels)return VisionViewport.full(frameWidth,frameHeight);
        int left=(int)Math.round(x[1]),top=(int)Math.round(y[1]);
        int right=(int)Math.round(x[0]*referenceWidth+x[1]);
        int bottom=(int)Math.round(y[0]*referenceHeight+y[1]);
        if(left<0||top<0||right>frameWidth||bottom>frameHeight||right-left<frameWidth/2
                ||bottom-top<frameHeight/2)return VisionViewport.full(frameWidth,frameHeight);
        return new VisionViewport(left,top,right,bottom,frameWidth,frameHeight,true);
    }

    private static double[] fitAxis(java.util.List<Anchor> anchors,boolean horizontal){
        double meanRef=0,meanScreen=0;
        for(Anchor a:anchors){meanRef+=horizontal?a.referenceX:a.referenceY;meanScreen+=horizontal?a.screenX:a.screenY;}
        meanRef/=anchors.size();meanScreen/=anchors.size();
        double covariance=0,variance=0;
        for(Anchor a:anchors){
            double r=(horizontal?a.referenceX:a.referenceY)-meanRef;
            double s=(horizontal?a.screenX:a.screenY)-meanScreen;
            covariance+=r*s;variance+=r*r;
        }
        if(variance<1e-6)return null;
        double scale=covariance/variance;
        return new double[]{scale,meanScreen-scale*meanRef};
    }

    private static boolean darkColumn(Bitmap image,int x){
        int height=image.getHeight(),dark=0,count=0;
        for(int y=height/12;y<height-height/12;y+=Math.max(1,height/48)){
            if(isDark(image.getPixel(x,y))) dark++;
            count++;
        }
        return count>0 && dark>=count-1;
    }

    private static boolean darkRow(Bitmap image,int y){
        int width=image.getWidth(),dark=0,count=0;
        for(int x=width/12;x<width-width/12;x+=Math.max(1,width/48)){
            if(isDark(image.getPixel(x,y))) dark++;
            count++;
        }
        return count>0 && dark>=count-1;
    }
    private static boolean isDark(int c){
        return Color.red(c)<=12 && Color.green(c)<=12 && Color.blue(c)<=12;
    }
}
