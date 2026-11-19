package com.example.pakredirect.vision;

import android.graphics.Bitmap;
import android.graphics.Rect;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Native-backed OpenCV candidate generator. It does NOT authorize touches or
 * sales: callers must verify its candidates against raw pixels and original
 * per-node thresholds.  OpenCV correlation scores are not OCR confidence.
 */
public final class VisionMatcher implements AutoCloseable {
    public static final class Candidate {
        public final int left, top, width, height;
        public final double signal;
        Candidate(int left, int top, int width, int height, double signal) {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.signal = signal;
        }
    }

    private final VisionFrameCache frameCache = new VisionFrameCache();

    public List<Candidate> candidates(Bitmap frame, Bitmap original, Rect search,
                                      int baseWidth, int baseHeight, boolean coloredOnly) {
        return candidates(frame, original, search, baseWidth, baseHeight, coloredOnly, -1);
    }

    /** maskColor is an RGB color-key from the legacy Lua findPic API, or -1 for no key. */
    public List<Candidate> candidates(Bitmap frame, Bitmap original, Rect search,
                                      int baseWidth, int baseHeight, boolean coloredOnly, int maskColor) {
        ArrayList<Candidate> found = new ArrayList<>();
        if (!VisionBridge.ready() || frame == null || frame.isRecycled()
                || original == null || original.isRecycled() || search == null) return found;
        Rect roi = new Rect(
                Math.max(0, search.left), Math.max(0, search.top),
                Math.min(frame.getWidth(), search.right), Math.min(frame.getHeight(), search.bottom));
        if (roi.width() < 6 || roi.height() < 6) return found;

        Mat rgb = frameCache.rgb(frame);
        // A small multi-scale search compensates for emulator/UI raster changes.
        double[] factors = {1.0d, 0.94d, 1.06d};
        for (double factor : factors) {
            int width = Math.max(1, (int) Math.round(baseWidth * factor));
            int height = Math.max(1, (int) Math.round(baseHeight * factor));
            if (width < 5 || height < 5 || width > roi.width() || height > roi.height()) continue;
            Bitmap scaled = Bitmap.createScaledBitmap(original, width, height, true);
            Mat rgbaTemplate = new Mat(), rgbTemplate = new Mat(), response = new Mat();
            Mat searchRgb = null, mask = null;
            try {
                Utils.bitmapToMat(scaled, rgbaTemplate);
                Imgproc.cvtColor(rgbaTemplate, rgbTemplate, Imgproc.COLOR_RGBA2RGB);
                searchRgb = rgb.submat(new org.opencv.core.Rect(roi.left, roi.top, roi.width(), roi.height()));
                mask = buildMask(scaled, coloredOnly, maskColor);
                // Masked CCORR is supported by OpenCV. Plain SQDIFF works with
                // low-variance UI templates where CCOEFF can be degenerate.
                int method = mask == null ? Imgproc.TM_SQDIFF_NORMED : Imgproc.TM_CCORR_NORMED;
                if (mask == null) Imgproc.matchTemplate(searchRgb, rgbTemplate, response, method);
                else Imgproc.matchTemplate(searchRgb, rgbTemplate, response, method, mask);

                for (int rank = 0; rank < 4; rank++) {
                    Core.MinMaxLocResult extrema = Core.minMaxLoc(response);
                    boolean sqdiff = method == Imgproc.TM_SQDIFF_NORMED;
                    double signal = sqdiff ? 1d - extrema.minVal : extrema.maxVal;
                    Point spot = sqdiff ? extrema.minLoc : extrema.maxLoc;
                    if (!Double.isFinite(signal) || signal < 0.43d) break;
                    found.add(new Candidate(roi.left + (int) spot.x, roi.top + (int) spot.y,
                            width, height, signal));
                    int x = (int) spot.x, y = (int) spot.y;
                    Imgproc.rectangle(response,
                            new Point(Math.max(0, x - 3), Math.max(0, y - 3)),
                            new Point(Math.min(response.cols() - 1, x + 3),
                                    Math.min(response.rows() - 1, y + 3)),
                            new Scalar(sqdiff ? 1 : 0), Imgproc.FILLED);
                }
            } finally {
                if (searchRgb != null) searchRgb.release();
                if (mask != null) mask.release();
                rgbaTemplate.release();
                rgbTemplate.release();
                response.release();
                if (!scaled.isRecycled() && scaled != original) scaled.recycle();
            }
        }
        found.sort(Comparator.comparingDouble((Candidate c) -> c.signal).reversed());
        return found;
    }

    private static Mat buildMask(Bitmap bitmap, boolean coloredOnly, int maskColor) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        byte[] bytes = new byte[pixels.length];
        int count = 0, opaque = 0;
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            boolean visible = ((c >>> 24) & 255) >= 32;
            if (visible) opaque++;
            if (visible && maskColor >= 0 && (c & 0x00ffffff) == (maskColor & 0x00ffffff)) visible = false;
            if (visible && coloredOnly) {
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                int high = Math.max(r, Math.max(g,b)), low = Math.min(r, Math.min(g,b));
                visible = high >= 60 && high - low >= 24;
            }
            if (visible) { bytes[i] = (byte) 255; count++; }
        }
        if (count < 12 || (coloredOnly && count < 24)) return null;
        if (!coloredOnly && opaque == pixels.length) return null;
        Mat mask = new Mat(height, width, CvType.CV_8UC1);
        mask.put(0, 0, bytes);
        return mask;
    }

    @Override public void close() { frameCache.close(); }
}
