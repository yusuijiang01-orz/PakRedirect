package com.example.pakredirect;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;

/** Lightweight pulsing outer-glow container for the main game canvas. */
public final class GlowFrameLayout extends FrameLayout {
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private ValueAnimator animator;
    private float phase;

    public GlowFrameLayout(Context context) {
        super(context);
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setClipChildren(false);
        setClipToPadding(false);

        fillPaint.setColor(Color.rgb(25, 29, 37));
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(dp(1.5f));
        glowPaint.setColor(Color.rgb(72, 113, 255));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1f));
        borderPaint.setColor(Color.rgb(58, 67, 84));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startGlow();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        animator = null;
        super.onDetachedFromWindow();
    }

    private void startGlow() {
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1800L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.setInterpolator(new AccelerateDecelerateInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = dp(9f);
        float radius = dp(22f);
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        float shadowRadius = dp(10f + phase * 8f);
        int alpha = 70 + Math.round(phase * 55f);
        glowPaint.setShadowLayer(shadowRadius, 0f, 0f, Color.argb(alpha, 72, 113, 255));
        glowPaint.setColor(Color.argb(115 + Math.round(phase * 55f), 72, 113, 255));

        canvas.drawRoundRect(rect, radius, radius, fillPaint);
        canvas.drawRoundRect(rect, radius, radius, glowPaint);
        canvas.drawRoundRect(rect, radius, radius, borderPaint);
        super.onDraw(canvas);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
