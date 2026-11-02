package com.example.pakredirect;

import android.util.Log;

/** JNI entry point for the strict-build-ID, read-only game memory reader. */
final class SandboxGameMemoryNative {
    private static final String TAG = "RYLUX-BagMemory";
    private static volatile boolean attempted;
    private static volatile boolean loaded;

    private SandboxGameMemoryNative() { }

    static String read() {
        if (!attempted) {
            synchronized (SandboxGameMemoryNative.class) {
                if (!attempted) {
                    attempted = true;
                    try {
                        System.loadLibrary("rylux_game_memory");
                        loaded = true;
                    } catch (LinkageError | SecurityException failure) {
                        Log.w(TAG, "Native memory reader unavailable", failure);
                    }
                }
            }
        }
        if (!loaded) return "READER_UNAVAILABLE";
        try {
            String result = readSnapshot();
            return result == null ? "READER_UNAVAILABLE" : result;
        } catch (LinkageError | SecurityException failure) {
            Log.w(TAG, "Native snapshot call unavailable", failure);
            loaded = false;
            return "READER_UNAVAILABLE";
        }
    }

    private static native String readSnapshot();
}
