package com.example.pakredirect;

import android.app.Activity;
import android.content.res.Configuration;
import android.util.DisplayMetrics;
import android.view.View;

/** Measures the app's usable window in dp, including split-screen and emulator overrides. */
final class RyluxViewport {
    private RyluxViewport() {}

    static int widthDp(Activity activity) {
        return dimensionDp(activity, true);
    }

    static int heightDp(Activity activity) {
        return dimensionDp(activity, false);
    }

    private static int dimensionDp(Activity activity, boolean horizontal) {
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        float density = metrics.density > 0f ? metrics.density : 1f;

        View content = activity.findViewById(android.R.id.content);
        int pixels = content == null ? 0 : (horizontal ? content.getWidth() : content.getHeight());
        if (pixels > 0) return Math.max(1, Math.round(pixels / density));

        Configuration configuration = activity.getResources().getConfiguration();
        int configured = horizontal ? configuration.screenWidthDp : configuration.screenHeightDp;
        if (configured > 0) return configured;

        int displayPixels = horizontal ? metrics.widthPixels : metrics.heightPixels;
        return Math.max(1, Math.round(displayPixels / density));
    }
}
