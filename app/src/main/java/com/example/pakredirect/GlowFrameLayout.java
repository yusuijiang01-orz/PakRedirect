package com.example.pakredirect;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.widget.FrameLayout;

/**
 * Restrained premium frame for the home game hero card.
 * The previous pulsing neon glow was intentionally removed so poster art and
 * hierarchy carry the screen instead of a permanent animation.
 */
public final class GlowFrameLayout extends FrameLayout {
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public GlowFrameLayout(Context context) {
        super(context);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);

        fillPaint.setColor(Color.rgb(23, 29, 39));

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1f));
        borderPaint.setColor(Color.rgb(48, 58, 73));

        highlightPaint.setStyle(Paint.Style.STROKE);
        highlightPaint.setStrokeWidth(dp(1f));
        highlightPaint.setColor(Color.argb(34, 255, 255, 255));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = dp(0.75f);
        float radius = dp(26f);
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        canvas.drawRoundRect(rect, radius, radius, fillPaint);
        canvas.drawRoundRect(rect, radius, radius, borderPaint);

        RectF topHighlight = new RectF(
                dp(1.75f),
                dp(1.75f),
                getWidth() - dp(1.75f),
                getHeight() - dp(1.75f)
        );
        canvas.drawRoundRect(topHighlight, radius - dp(1f), radius - dp(1f), highlightPaint);
        super.onDraw(canvas);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
