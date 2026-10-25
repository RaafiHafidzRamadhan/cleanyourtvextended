package com.raafi.cyberhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

public class PrayerProgressView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint corePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bar = new RectF();

    private float fraction;
    private float barHeight;
    private float radius;
    private float dotRadius;
    private float glowRadius;

    public PrayerProgressView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        barHeight = 3f * density;
        radius = barHeight / 2f;
        dotRadius = 3.2f * density;
        glowRadius = 11f * density;

        trackPaint.setColor(0x1FFFFFFF);
        trackPaint.setStyle(Paint.Style.FILL);

        glowPaint.setShader(new RadialGradient(0, 0, glowRadius,
                new int[]{0xCC4C8DFF, 0x334C8DFF, Color.TRANSPARENT},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));

        corePaint.setColor(0xFFEAF3FF);
        corePaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fillPaint.setShader(new LinearGradient(0, 0, w, 0,
                new int[]{0xFF2F6BFF, 0xFF4C8DFF, 0xFF9CC8FF},
                new float[]{0f, 0.65f, 1f}, Shader.TileMode.CLAMP));
    }

    public void setFraction(float value) {
        float clamped = Math.max(0f, Math.min(1f, value));
        if (Math.abs(clamped - fraction) < 0.0001f) {
            return;
        }
        fraction = clamped;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float centerY = getHeight() / 2f;
        bar.set(0, centerY - barHeight / 2f, getWidth(), centerY + barHeight / 2f);

        canvas.drawRoundRect(bar, radius, radius, trackPaint);

        float x = fraction * getWidth();
        if (x > 0.5f) {
            bar.right = x;
            canvas.drawRoundRect(bar, radius, radius, fillPaint);
        }

        canvas.save();
        canvas.translate(x, centerY);
        canvas.drawCircle(0, 0, glowRadius, glowPaint);
        canvas.drawCircle(0, 0, dotRadius, corePaint);
        canvas.restore();
    }
}
