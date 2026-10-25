package com.raafi.cyberhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

public class WeatherIconView extends View {

    public static final int SUN = 0;
    public static final int MOON = 1;
    public static final int PARTLY_DAY = 2;
    public static final int PARTLY_NIGHT = 3;
    public static final int CLOUD = 4;
    public static final int FOG = 5;
    public static final int RAIN = 6;
    public static final int SNOW = 7;
    public static final int STORM = 8;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int mode = CLOUD;
    private int accent = 0xFF4C8DFF;
    private int secondary = 0xFF8B9AB0;
    private float phase = 0f;

    public WeatherIconView(Context context) {
        super(context);
        init();
    }

    public WeatherIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WeatherIconView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        fill.setStyle(Paint.Style.FILL);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setMode(int mode) {
        if (this.mode != mode) {
            this.mode = mode;
            invalidate();
        }
    }

    public void setColors(int accent, int secondary) {
        this.accent = accent;
        this.secondary = secondary;
        invalidate();
    }

    public static int modeFor(int code, boolean day) {
        if (code == 0) {
            return day ? SUN : MOON;
        }
        if (code == 1 || code == 2) {
            return day ? PARTLY_DAY : PARTLY_NIGHT;
        }
        if (code == 3) {
            return CLOUD;
        }
        if (code == 45 || code == 48) {
            return FOG;
        }
        if (code >= 51 && code <= 57) {
            return RAIN;
        }
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) {
            return RAIN;
        }
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
            return SNOW;
        }
        if (code >= 95) {
            return STORM;
        }
        return CLOUD;
    }

    public static String describe(int code) {
        if (code == 0) {
            return "Cerah";
        }
        if (code == 1 || code == 2) {
            return "Cerah Berawan";
        }
        if (code == 3) {
            return "Berawan";
        }
        if (code == 45 || code == 48) {
            return "Berkabut";
        }
        if (code >= 51 && code <= 57) {
            return "Gerimis";
        }
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) {
            return "Hujan";
        }
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
            return "Hujan Salju";
        }
        if (code >= 95) {
            return "Badai";
        }
        return "Berawan";
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        if (size <= 0f) {
            return;
        }

        float cx = getWidth() * 0.5f;
        float cy = getHeight() * 0.5f;
        float drift = (float) Math.sin(phase * Math.PI * 2f) * size * 0.012f;
        float sway = (float) Math.sin(phase * Math.PI * 2f) * 7f;

        switch (mode) {
            case SUN:
                drawSun(canvas, cx, cy, size * 0.17f, sway);
                break;
            case MOON:
                drawMoon(canvas, cx, cy, size * 0.24f);
                break;
            case PARTLY_DAY:
                drawSun(canvas, cx - size * 0.13f, cy - size * 0.12f, size * 0.10f, sway);
                drawCloud(canvas, cx + size * 0.08f, cy + size * 0.10f, size * 0.185f, drift);
                break;
            case PARTLY_NIGHT:
                drawMoon(canvas, cx - size * 0.12f, cy - size * 0.10f, size * 0.14f);
                drawCloud(canvas, cx + size * 0.09f, cy + size * 0.11f, size * 0.18f, drift);
                break;
            case FOG:
                drawCloud(canvas, cx, cy - size * 0.06f, size * 0.22f, drift);
                drawFog(canvas, cx, cy, size);
                break;
            case RAIN:
                drawCloud(canvas, cx, cy - size * 0.07f, size * 0.22f, drift);
                drawRain(canvas, cx, cy, size * 0.22f, size);
                break;
            case SNOW:
                drawCloud(canvas, cx, cy - size * 0.07f, size * 0.22f, drift);
                drawSnow(canvas, cx, cy, size * 0.22f, size);
                break;
            case STORM:
                drawCloud(canvas, cx, cy - size * 0.08f, size * 0.22f, drift);
                drawStorm(canvas, cx, cy, size * 0.22f, size);
                break;
            case CLOUD:
            default:
                drawCloud(canvas, cx, cy, size * 0.24f, drift);
                break;
        }

        if (isShown()) {
            phase += 0.0022f;
            if (phase > 1f) {
                phase -= 1f;
            }
            postInvalidateOnAnimation();
        }
    }

    private void drawSun(Canvas canvas, float cx, float cy, float radius, float rotation) {
        fill.setColor(withAlpha(accent, 36));
        canvas.drawCircle(cx, cy, radius * 1.65f, fill);

        fill.setColor(accent);
        canvas.drawCircle(cx, cy, radius * 0.84f, fill);

        line.setColor(accent);
        line.setStrokeWidth(Math.max(2f, radius * 0.16f));
        canvas.save();
        canvas.rotate(rotation, cx, cy);
        float start = radius * 1.2f;
        float end = radius * 1.55f;
        for (int i = 0; i < 8; i++) {
            double angle = Math.toRadians(i * 45);
            float dx = (float) Math.cos(angle);
            float dy = (float) Math.sin(angle);
            canvas.drawLine(cx + dx * start, cy + dy * start,
                    cx + dx * end, cy + dy * end, line);
        }
        canvas.restore();
    }

    private void drawMoon(Canvas canvas, float cx, float cy, float radius) {
        fill.setColor(withAlpha(accent, 40));
        canvas.drawCircle(cx, cy, radius * 1.35f, fill);

        Path disc = new Path();
        disc.addCircle(cx, cy, radius, Path.Direction.CW);
        Path cut = new Path();
        cut.addCircle(cx + radius * 0.5f, cy - radius * 0.45f, radius * 0.9f,
                Path.Direction.CW);
        if (disc.op(cut, Path.Op.DIFFERENCE)) {
            fill.setColor(accent);
            canvas.drawPath(disc, fill);
        }

        fill.setColor(withAlpha(secondary, 200));
        canvas.drawCircle(cx + radius * 0.55f, cy - radius * 0.55f, radius * 0.08f, fill);
        canvas.drawCircle(cx + radius * 0.72f, cy - radius * 0.16f, radius * 0.05f, fill);
    }

    private void drawCloud(Canvas canvas, float cx, float cy, float radius, float drift) {
        float left = cx - radius * 1.05f + drift;
        float right = cx + radius * 1.05f + drift;

        Path cloud = new Path();
        cloud.addRoundRect(new RectF(left, cy - radius * 0.18f, right,
                        cy + radius * 0.62f), radius * 0.5f, radius * 0.5f,
                Path.Direction.CW);
        cloud.addCircle(cx - radius * 0.48f + drift, cy + radius * 0.02f,
                radius * 0.5f, Path.Direction.CW);
        cloud.addCircle(cx + drift, cy - radius * 0.35f, radius * 0.62f,
                Path.Direction.CW);
        cloud.addCircle(cx + radius * 0.65f + drift, cy + radius * 0.05f,
                radius * 0.42f, Path.Direction.CW);

        fill.setColor(secondary);
        canvas.drawPath(cloud, fill);
    }

    private void drawFog(Canvas canvas, float cx, float cy, float size) {
        line.setStrokeWidth(Math.max(2f, size * 0.045f));
        line.setColor(accent);
        float left = cx - size * 0.21f;
        float right = cx + size * 0.21f;
        canvas.drawLine(left, cy + size * 0.22f, right, cy + size * 0.22f, line);
        line.setColor(secondary);
        canvas.drawLine(left + size * 0.05f, cy + size * 0.34f,
                right - size * 0.05f, cy + size * 0.34f, line);
    }

    private void drawRain(Canvas canvas, float cx, float cy, float radius, float size) {
        line.setStrokeWidth(Math.max(2f, size * 0.045f));
        line.setColor(accent);
        for (int i = 0; i < 3; i++) {
            float offset = ((phase * 0.9f + i * 0.33f) % 1f) * size * 0.2f;
            float x = cx + (i - 1) * radius * 0.7f;
            float y = cy + radius * 0.86f + offset;
            canvas.drawLine(x, y, x - size * 0.045f, y + size * 0.11f, line);
        }
    }

    private void drawSnow(Canvas canvas, float cx, float cy, float radius, float size) {
        fill.setColor(accent);
        for (int i = 0; i < 3; i++) {
            float wobble = (float) Math.sin((phase + i * 0.33f) * Math.PI * 2f)
                    * size * 0.018f;
            float x = cx + (i - 1) * radius * 0.7f + wobble;
            float y = cy + radius * 0.92f + i * size * 0.055f;
            canvas.drawCircle(x, y, size * 0.022f, fill);
        }
    }

    private void drawStorm(Canvas canvas, float cx, float cy, float radius, float size) {
        float baseY = cy + radius * 0.82f;
        Path bolt = new Path();
        bolt.moveTo(cx - size * 0.03f, baseY);
        bolt.lineTo(cx + size * 0.08f, baseY + size * 0.14f);
        bolt.lineTo(cx + size * 0.015f, baseY + size * 0.14f);
        bolt.lineTo(cx + size * 0.06f, baseY + size * 0.3f);
        bolt.lineTo(cx - size * 0.09f, baseY + size * 0.11f);
        bolt.lineTo(cx - size * 0.015f, baseY + size * 0.11f);
        bolt.close();
        fill.setColor(accent);
        canvas.drawPath(bolt, fill);
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }
}
