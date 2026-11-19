package com.example.pakredirect.vision;

import android.graphics.Bitmap;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

/**
 * Keeps one native RGB screenshot per worker. A fresh screen frame replaces the
 * old Mat, preventing 35 inventory cells from decoding the same image 35 times.
 * Not shared across scripts or threads.
 */
public final class VisionFrameCache implements AutoCloseable {
    private Bitmap bitmap;
    private Mat rgba;
    private Mat rgb;

    public synchronized Mat rgb(Bitmap frame) {
        if (frame == null || frame.isRecycled()) throw new IllegalArgumentException("Invalid screenshot");
        if (bitmap != frame || rgb == null || rgb.empty()) {
            close();
            rgba = new Mat();
            rgb = new Mat();
            try {
                Utils.bitmapToMat(frame, rgba);
                Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
                bitmap = frame;
            } catch (RuntimeException | LinkageError failure) {
                close();
                throw failure;
            }
        }
        return rgb;
    }

    @Override public synchronized void close() {
        bitmap = null;
        if (rgb != null) { rgb.release(); rgb = null; }
        if (rgba != null) { rgba.release(); rgba = null; }
    }
}
