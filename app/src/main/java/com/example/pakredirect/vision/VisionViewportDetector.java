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
