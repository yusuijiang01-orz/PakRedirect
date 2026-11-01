package com.example.pakredirect;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.view.View;

/** Shared colors and surfaces for the RYLUX navigation screens. */
final class RyluxDesignSystem {
    static final int BACKGROUND = Color.rgb(3, 14, 33);
    static final int CARD = Color.rgb(10, 29, 52);
    static final int SOFT = Color.rgb(13, 38, 65);
    static final int TEXT = Color.rgb(244, 248, 255);
    static final int MUTED = Color.rgb(158, 184, 208);
    static final int ACCENT = Color.rgb(70, 184, 245);
    static final int BORDER = Color.rgb(54, 125, 174);

    private RyluxDesignSystem() {}

    static GradientDrawable card(Context context, float radiusDp) {
        return surface(context, radiusDp, new int[]{CARD, Color.rgb(7, 23, 43)}, BORDER);
    }

    static GradientDrawable primaryButton(Context context, float radiusDp) {
        return surface(context, radiusDp,
                new int[]{Color.rgb(29, 100, 176), Color.rgb(9, 43, 89)},
                Color.rgb(105, 210, 255));
    }

    static GradientDrawable secondaryButton(Context context, float radiusDp) {
        return surface(context, radiusDp, new int[]{SOFT, Color.rgb(8, 29, 52)}, BORDER);
    }

    static GradientDrawable emphasizedButton(Context context, float radiusDp) {
        return surface(context, radiusDp,
                new int[]{Color.rgb(23, 68, 116), Color.rgb(9, 34, 69)}, ACCENT, 1.4f);
    }

    static void installHomeBackdrop(Context context, FrameLayout host) {
        host.setBackgroundColor(BACKGROUND);
        ImageView background = new ImageView(context);
        background.setImageResource(R.drawable.rylux_home_background);
        background.setScaleType(ImageView.ScaleType.FIT_XY);
        background.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        host.addView(background, 0, new FrameLayout.LayoutParams(-1, -1));
    }

    private static GradientDrawable surface(Context context, float radiusDp, int[] colors, int stroke) {
        return surface(context, radiusDp, colors, stroke, 1f);
    }

    private static GradientDrawable surface(Context context, float radiusDp, int[] colors,
                                             int stroke, float strokeDp) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        float density = context.getResources().getDisplayMetrics().density;
        drawable.setCornerRadius(radiusDp * density);
        drawable.setStroke(Math.max(1, Math.round(strokeDp * density)), stroke);
        return drawable;
    }
}
