package com.example.pakredirect.vision;

import android.util.Log;
import org.opencv.android.OpenCVLoader;

/**
 * OpenCV 2.6.1 engine bootstrap.  OpenCV's official Maven AAR owns the native
 * libraries (arm64-v8a/armeabi-v7a); our existing BlackBox NDK module remains unchanged.
 */
public final class VisionBridge {
    private static final String TAG = "RYLUX-Vision";
    private static boolean attempted;
    private static boolean ready;
    private static String failure = "";

    private VisionBridge() { }

    public static synchronized boolean ready() {
        if (!attempted) {
            attempted = true;
            try {
                ready = OpenCVLoader.initLocal();
                if (!ready) failure = "OpenCVLoader.initLocal returned false";
            } catch (RuntimeException | LinkageError error) {
                ready = false;
                failure = error.toString();
            }
            Log.i(TAG, ready ? "OpenCV native image engine initialized" :
                    "OpenCV unavailable; legacy matcher remains available: " + failure);
        }
        return ready;
    }

    public static synchronized String status() {
        return ready() ? "OpenCV native ready" : "OpenCV fallback: " + failure;
    }
}
