package com.raafi.cyberhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

public class DreamBackdropView extends View {

    private final Paint skyPaint = new Paint();
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint starPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint moonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint moonEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path moonPath = new Path();
    private final Random random = new Random(2077);

    private float[] starX = new float[0];
    private float[] starY = new float[0];
    private float[] starSize = new float[0];
    private float[] starPhase = new float[0];
    private float[] starSpeed = new float[0];

    private float width;
    private float height;
    private float density;
    private float moonX;
    private float moonY;
    private float moonRadius;
    private float ringX;
    private float ringY;
    private float glowRadius;
    private RadialGradient glowA;
    private RadialGradient glowB;
    private RadialGradient glowC;
    private float glowAX;
    private float glowAY;
    private float glowBX;
    private float glowBY;
    private float glowCX;
    private float glowCY;
    private long startTime;
    private boolean running;

    public DreamBackdropView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;

        skyPaint.setStyle(Paint.Style.FILL);
        glowPaint.setStyle(Paint.Style.FILL);
        starPaint.setStyle(Paint.Style.FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(dp(1));
        ringPaint.setColor(Color.WHITE);
        ringPaint.setPathEffect(new DashPathEffect(
                new float[]{dp(2), dp(9)}, 0));
        moonPaint.setStyle(Paint.Style.FILL);
        moonPaint.setColor(Color.WHITE);
        moonPaint.setAlpha(16);
        moonEdgePaint.setStyle(Paint.Style.STROKE);
        moonEdgePaint.setStrokeWidth(dp(1.1f));
        moonEdgePaint.setColor(Color.WHITE);
        moonEdgePaint.setAlpha(40);
        vignettePaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        width = w;
        height = h;
        glowRadius = dp(320);
        moonX = w * 0.78f;
        moonY = h * 0.26f;
        moonRadius = dp(112);
        ringX = w * 0.5f;
        ringY = h * 0.41f;

        skyPaint.setShader(new LinearGradient(0, 0, 0, h,
                new int[]{0xFF040D1C, 0xFF071B36, 0xFF0A2A55},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));

        moonPath.reset();
        Path outer = new Path();
        outer.addCircle(moonX, moonY, moonRadius, Path.Direction.CW);
        Path inner = new Path();
        inner.addCircle(moonX + dp(34), moonY - dp(18), moonRadius * 0.9f, Path.Direction.CW);
        moonPath.op(outer, inner, Path.Op.DIFFERENCE);

        starX = new float[52];
        starY = new float[52];
        starSize = new float[52];
        starPhase = new float[52];
        starSpeed = new float[52];
        for (int i = 0; i < 52; i++) {
            starX[i] = random.nextFloat() * w;
            starY[i] = random.nextFloat() * h * 0.86f;
            starSize[i] = dp(0.7f + random.nextFloat() * 1.7f);
            starPhase[i] = random.nextFloat() * 6.283f;
            starSpeed[i] = 0.5f + random.nextFloat() * 1.1f;
            if (random.nextInt(6) == 0) {
                starSize[i] = dp(2.9f);
            }
        }

        float max = Math.max(w, h);
        vignettePaint.setShader(new RadialGradient(w * 0.5f, h * 0.46f, max * 0.72f,
                new int[]{Color.TRANSPARENT, 0x3302060C, 0xA6030810},
                new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP));

        glowAX = width * 0.16f;
        glowAY = height * 0.2f;
        glowBX = width * 0.86f;
        glowBY = height * 0.86f;
        glowCX = width * 0.56f;
        glowCY = height * 0.06f;
        glowA = new RadialGradient(glowAX, glowAY, glowRadius,
                new int[]{0x4D2E7BFF, 0x142E7BFF, Color.TRANSPARENT},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        glowB = new RadialGradient(glowBX, glowBY, glowRadius,
                new int[]{0x380F63C9, 0x0F0F63C9, Color.TRANSPARENT},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
        glowC = new RadialGradient(glowCX, glowCY, glowRadius,
                new int[]{0x242F7DFF, 0x082F7DFF, Color.TRANSPARENT},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startTime = SystemClock.uptimeMillis();
        running = true;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (width <= 0 || height <= 0) {
            return;
        }
        float time = (SystemClock.uptimeMillis() - startTime) / 1000f;

        canvas.drawRect(0, 0, width, height, skyPaint);

        drawGlow(canvas, glowA, glowAX, glowAY,
                (float) Math.sin(time * 0.075f) * dp(30),
                (float) Math.cos(time * 0.055f) * dp(24));
        drawGlow(canvas, glowB, glowBX, glowBY,
                (float) Math.cos(time * 0.062f + 1.4f) * dp(34),
                (float) Math.sin(time * 0.048f + 0.7f) * dp(26));
        drawGlow(canvas, glowC, glowCX, glowCY,
                (float) Math.sin(time * 0.05f + 2.2f) * dp(40), 0);

        ringPaint.setAlpha(16);
        canvas.save();
        canvas.rotate(time * 0.7f, ringX, ringY);
        canvas.drawCircle(ringX, ringY, dp(232), ringPaint);
        canvas.restore();

        ringPaint.setAlpha(10);
        canvas.save();
        canvas.rotate(-time * 0.45f, ringX, ringY);
        canvas.drawCircle(ringX, ringY, dp(300), ringPaint);
        canvas.restore();

        for (int i = 0; i < starX.length; i++) {
            float twinkle = 0.55f + 0.45f * (float) Math.sin(time * starSpeed[i] + starPhase[i]);
            int alpha = (int) (40 + 150 * twinkle);
            starPaint.setColor(i % 7 == 0 ? 0xFF9CC8FF : Color.WHITE);
            starPaint.setAlpha(Math.min(210, alpha));
            canvas.drawCircle(starX[i], starY[i], starSize[i], starPaint);
        }

        canvas.save();
        canvas.translate(0, (float) Math.sin(time * 0.12f) * dp(6));
        moonPaint.setAlpha((int) (16 + 6 * (1f + Math.sin(time * 0.09f))));
        canvas.drawPath(moonPath, moonPaint);
        canvas.drawPath(moonPath, moonEdgePaint);
        canvas.restore();

        canvas.drawRect(0, 0, width, height, vignettePaint);

        if (running) {
            postInvalidateDelayed(33);
        }
    }

    private void drawGlow(Canvas canvas, RadialGradient shader,
                          float cx, float cy, float dx, float dy) {
        canvas.save();
        canvas.translate(dx, dy);
        glowPaint.setShader(shader);
        canvas.drawCircle(cx, cy, glowRadius, glowPaint);
        canvas.restore();
    }

    private float dp(float value) {
        return value * density;
    }
}
