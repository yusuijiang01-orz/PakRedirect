package com.example.pakredirect.vision;

import android.graphics.Bitmap;
import android.graphics.Rect;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native OpenCV template candidate generator with bounded template-Mat cache.
 * Correlation scores are candidates only; trade decisions must pass the
 * caller's independent strict verifier and multi-frame state checks.
 */
public final class VisionMatcher implements AutoCloseable {
    public static final class Candidate {
        public final int left, top, width, height;
        public final double signal;
        Candidate(int left,int top,int width,int height,double signal){
            this.left=left;this.top=top;this.width=width;this.height=height;this.signal=signal;
        }
    }
    private static final int MAX_TEMPLATE_CACHE=64;
    private final VisionFrameCache frameCache=new VisionFrameCache();
    private final LinkedHashMap<String,TemplateData> templateCache=
            new LinkedHashMap<>(16,0.75f,true);

    private static final class TemplateData {
        final Bitmap source;
        final Mat rgb;
        final Mat mask;
        final int width,height,maskColor;
        final boolean coloredOnly;
        TemplateData(Bitmap source,Mat rgb,Mat mask,int width,int height,boolean coloredOnly,int maskColor){
            this.source=source;this.rgb=rgb;this.mask=mask;this.width=width;this.height=height;
            this.coloredOnly=coloredOnly;this.maskColor=maskColor;
        }
        void release(){rgb.release();if(mask!=null)mask.release();}
    }

    public synchronized List<Candidate> candidates(Bitmap frame,Bitmap original,Rect search,
                                      int baseWidth,int baseHeight,boolean coloredOnly){
        return candidates(frame,original,search,baseWidth,baseHeight,coloredOnly,-1);
    }

    /** maskColor is the legacy Lua color-key, or -1 when no color key is used. */
    public synchronized List<Candidate> candidates(Bitmap frame,Bitmap original,Rect search,
                                      int baseWidth,int baseHeight,boolean coloredOnly,int maskColor){
        ArrayList<Candidate> found=new ArrayList<>();
        if(!VisionBridge.ready()||frame==null||frame.isRecycled()||original==null
                ||original.isRecycled()||search==null)return found;
        Rect roi=new Rect(Math.max(0,search.left),Math.max(0,search.top),
                Math.min(frame.getWidth(),search.right),Math.min(frame.getHeight(),search.bottom));
        if(roi.width()<6||roi.height()<6)return found;
        Mat rgb=frameCache.rgb(frame);
        double[] factors={1d,0.94d,1.06d};
        for(double factor:factors){
            int width=Math.max(1,(int)Math.round(baseWidth*factor));
            int height=Math.max(1,(int)Math.round(baseHeight*factor));
            if(width<5||height<5||width>roi.width()||height>roi.height())continue;
            TemplateData template=templateData(original,width,height,coloredOnly,maskColor);
            Mat searchRgb=rgb.submat(new org.opencv.core.Rect(roi.left,roi.top,roi.width(),roi.height()));
            Mat response=new Mat();
            try{
                int method=template.mask==null?Imgproc.TM_SQDIFF_NORMED:Imgproc.TM_CCORR_NORMED;
                if(template.mask==null)Imgproc.matchTemplate(searchRgb,template.rgb,response,method);
                else Imgproc.matchTemplate(searchRgb,template.rgb,response,method,template.mask);
                for(int rank=0;rank<3;rank++){
                    Core.MinMaxLocResult extrema=Core.minMaxLoc(response);
                    boolean sqdiff=method==Imgproc.TM_SQDIFF_NORMED;
                    double signal=sqdiff?1d-extrema.minVal:extrema.maxVal;
                    Point spot=sqdiff?extrema.minLoc:extrema.maxLoc;
                    if(!Double.isFinite(signal)||signal<0.43d)break;
                    found.add(new Candidate(roi.left+(int)spot.x,roi.top+(int)spot.y,width,height,signal));
                    int x=(int)spot.x,y=(int)spot.y;
                    Imgproc.rectangle(response,new Point(Math.max(0,x-3),Math.max(0,y-3)),
                            new Point(Math.min(response.cols()-1,x+3),Math.min(response.rows()-1,y+3)),
                            new Scalar(sqdiff?1:0),Imgproc.FILLED);
                }
            }finally{searchRgb.release();response.release();}
        }
        found.sort(Comparator.comparingDouble((Candidate c)->c.signal).reversed());
        return found;
    }

    public Candidate bestCandidate(Bitmap frame,Bitmap template,Rect search,int width,int height,
                                    boolean coloredOnly,int maskColor){
        List<Candidate> values=candidates(frame,template,search,width,height,coloredOnly,maskColor);
        return values.isEmpty()?null:values.get(0);
    }

    /** Full-resolution RGB verification for inventory classifications. */
    public double verify(Bitmap frame,Bitmap template,Candidate candidate,boolean coloredOnly,int maskColor){
        if(frame==null||template==null||candidate==null||frame.isRecycled()||template.isRecycled())return 0d;
        Bitmap scaled=template;boolean owns=false;
        if(candidate.width!=template.getWidth()||candidate.height!=template.getHeight()){
            scaled=Bitmap.createScaledBitmap(template,candidate.width,candidate.height,true);owns=true;
        }
        try{
            int x0=candidate.left,y0=candidate.top,w=candidate.width,h=candidate.height;
            if(x0<0||y0<0||x0+w>frame.getWidth()||y0+h>frame.getHeight())return 0d;
            int[] expected=new int[w*h],actual=new int[w*h];
            scaled.getPixels(expected,0,w,0,0,w,h);
            frame.getPixels(actual,0,frame.getWidth(),x0,y0,w,h);
            long error=0;int count=0;
            for(int i=0;i<expected.length;i++){
                int e=expected[i];
                if(((e>>>24)&255)<32||(maskColor>=0&&(e&0x00ffffff)==(maskColor&0x00ffffff)))continue;
                if(coloredOnly){
                    int er=(e>>16)&255,eg=(e>>8)&255,eb=e&255;
                    int hi=Math.max(er,Math.max(eg,eb)),lo=Math.min(er,Math.min(eg,eb));
                    if(hi<60||hi-lo<24)continue;
                }
                int a=actual[i];
                error+=(Math.abs(((e>>16)&255)-((a>>16)&255))
                        +Math.abs(((e>>8)&255)-((a>>8)&255))
                        +Math.abs((e&255)-(a&255)))/3;
                count++;
            }
            return count==0?0d:1d-error/(count*255d);
        }finally{if(owns&&!scaled.isRecycled())scaled.recycle();}
    }

    private TemplateData templateData(Bitmap original,int width,int height,boolean coloredOnly,int maskColor){
        String key=System.identityHashCode(original)+":"+width+"x"+height+":"+coloredOnly+":"+maskColor;
        TemplateData cached=templateCache.get(key);
        if(cached!=null&&cached.source==original)return cached;
        if(cached!=null){cached.release();templateCache.remove(key);}
        Bitmap scaled=original;
        boolean owns=false;
        if(original.getWidth()!=width||original.getHeight()!=height){
            scaled=Bitmap.createScaledBitmap(original,width,height,true);owns=true;
        }
        Mat rgba=new Mat(),rgb=new Mat(),mask=null;
        try{
            Utils.bitmapToMat(scaled,rgba);
            Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB);
            mask=buildMask(scaled,coloredOnly,maskColor);
            TemplateData created=new TemplateData(original,rgb,mask,width,height,coloredOnly,maskColor);
            rgb=null;mask=null;
            templateCache.put(key,created);
            trimTemplateCache();
            return created;
        }finally{
            rgba.release();
            if(rgb!=null)rgb.release();
            if(mask!=null)mask.release();
            if(owns&&!scaled.isRecycled())scaled.recycle();
        }
    }

    private void trimTemplateCache(){
        while(templateCache.size()>MAX_TEMPLATE_CACHE){
            Map.Entry<String,TemplateData> eldest=templateCache.entrySet().iterator().next();
            eldest.getValue().release();
            templateCache.remove(eldest.getKey());
        }
    }

    private static Mat buildMask(Bitmap bitmap,boolean coloredOnly,int maskColor){
        int width=bitmap.getWidth(),height=bitmap.getHeight();
        int[] pixels=new int[width*height];bitmap.getPixels(pixels,0,width,0,0,width,height);
        byte[] bytes=new byte[pixels.length];int count=0,opaque=0;
        for(int i=0;i<pixels.length;i++){
            int color=pixels[i];boolean visible=((color>>>24)&255)>=32;
            if(visible)opaque++;
            if(visible&&maskColor>=0&&(color&0x00ffffff)==(maskColor&0x00ffffff))visible=false;
            if(visible&&coloredOnly){
                int r=(color>>16)&255,g=(color>>8)&255,b=color&255;
                int high=Math.max(r,Math.max(g,b)),low=Math.min(r,Math.min(g,b));
                visible=high>=60&&high-low>=24;
            }
            if(visible){bytes[i]=(byte)255;count++;}
        }
        if(count<12||(coloredOnly&&count<24))return null;
        if(!coloredOnly&&maskColor<0&&opaque==pixels.length)return null;
        Mat mask=new Mat(height,width,CvType.CV_8UC1);mask.put(0,0,bytes);return mask;
    }

    public synchronized void invalidateFrame(){frameCache.invalidate();}

    @Override public synchronized void close(){
        frameCache.close();
        for(TemplateData value:templateCache.values())value.release();
        templateCache.clear();
    }
}
