package com.example.pakredirect;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.widget.FrameLayout;

/**
 * Static luminous game-card frame inspired by the approved RYLUX UI reference.
 * It deliberately avoids an always-running animator: the poster remains the
 * visual focus while the thin blue edge gives the card a game-launcher feel.
 */
public final class GlowFrameLayout extends FrameLayout {
    private final Paint outerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public GlowFrameLayout(Context context) {
        super(context);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);

        outerPaint.setStyle(Paint.Style.STROKE);
        outerPaint.setStrokeWidth(dp(2.2f));
        outerPaint.setColor(Color.argb(105, 40, 119, 255));

        innerPaint.setStyle(Paint.Style.STROKE);
        innerPaint.setStrokeWidth(dp(0.8f));
        innerPaint.setColor(Color.argb(220, 105, 181, 255));

        accentPaint.setStyle(Paint.Style.STROKE);
        accentPaint.setStrokeWidth(dp(1.5f));
        accentPaint.setStrokeCap(Paint.Cap.ROUND);
        accentPaint.setColor(Color.argb(235, 68, 151, 255));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float outerInset = dp(1.5f);
        float radius = dp(24f);
        rect.set(
                outerInset,
                outerInset,
                getWidth() - outerInset,
                getHeight() - outerInset
        );

        canvas.drawRoundRect(rect, radius, radius, outerPaint);

        RectF inner = new RectF(
                dp(4f),
                dp(4f),
                getWidth() - dp(4f),
                getHeight() - dp(4f)
        );
        canvas.drawRoundRect(inner, radius - dp(2f), radius - dp(2f), innerPaint);

        float edge = dp(23f);
        float pad = dp(8f);
        canvas.drawLine(pad, pad, pad + edge, pad, accentPaint);
        canvas.drawLine(pad, pad, pad, pad + edge, accentPaint);

        canvas.drawLine(getWidth() - pad - edge, pad, getWidth() - pad, pad, accentPaint);
        canvas.drawLine(getWidth() - pad, pad, getWidth() - pad, pad + edge, accentPaint);

        canvas.drawLine(pad, getHeight() - pad, pad + edge, getHeight() - pad, accentPaint);
        canvas.drawLine(pad, getHeight() - pad - edge, pad, getHeight() - pad, accentPaint);

        canvas.drawLine(
                getWidth() - pad - edge,
                getHeight() - pad,
                getWidth() - pad,
                getHeight() - pad,
                accentPaint
        );
        canvas.drawLine(
                getWidth() - pad,
                getHeight() - pad - edge,
                getWidth() - pad,
                getHeight() - pad,
                accentPaint
        );

        super.onDraw(canvas);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
