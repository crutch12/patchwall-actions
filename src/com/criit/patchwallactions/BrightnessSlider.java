package com.criit.patchwallactions;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.View;

// широкий слайдер как в пункте управления iOS: светлая заливка, солнце слева, проценты справа.
// Шкала от Backlight.MIN до 100, отрицательная часть это затемнение поверх экрана
class BrightnessSlider extends View {

    interface Listener {
        void onChange(int value);
    }

    private static final int MIN = Backlight.MIN;
    private static final int MAX = 100;
    private static final int STEP = 5;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final float radius;
    private final float tick;
    private final Paint zeroMark = new Paint();
    private final Paint zeroMarkOnFill = new Paint();

    private int value = 50;
    private Listener listener;

    BrightnessSlider(Context ctx) {
        super(ctx);
        float d = ctx.getResources().getDisplayMetrics().density;
        radius = 22 * d;
        tick = 2 * d;
        zeroMark.setColor(0x99D1D1D6);
        zeroMarkOnFill.setColor(0x668E8E93);
        track.setColor(0xF03A3A3C);
        fill.setColor(0xFFD1D1D6);
        icon.setColor(0xFF8E8E93);
        icon.setStrokeWidth(2.5f * d);
        icon.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(0xFF8E8E93);
        text.setTextSize(17 * d);
        text.setTextAlign(Paint.Align.RIGHT);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3 * d);
        ring.setColor(0xFFFFFFFF);
        setFocusable(true);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setValue(int newValue) {
        value = clamp(newValue);
        invalidate();
    }

    private int clamp(int v) {
        int rounded = Math.round(v / (float) STEP) * STEP;
        return Math.max(MIN, Math.min(MAX, rounded));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int delta = 0;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            delta = -STEP;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            delta = STEP;
        }
        if (delta == 0) {
            return super.onKeyDown(keyCode, event);
        }
        value = clamp(value + delta);
        invalidate();
        if (listener != null) {
            listener.onChange(value);
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth();
        float h = getHeight();
        // при фокусе рамка снаружи, а сам слайдер чуть уже, чтобы рамка не наезжала на содержимое
        float gap = isFocused() ? ring.getStrokeWidth() * 2 : 0;
        rect.set(gap, gap, w - gap, h - gap);
        float r = radius - gap;

        c.save();
        clip.reset();
        clip.addRoundRect(rect, r, r, Path.Direction.CW);
        c.clipPath(clip);
        c.drawRect(rect, track);
        float zeroX = rect.left + rect.width() * (0 - MIN) / (MAX - MIN);
        float valueX = rect.left + rect.width() * (value - MIN) / (MAX - MIN);
        c.drawRect(rect.left, rect.top, valueX, rect.bottom, fill);
        // отметка нуля: левее неё уже не подсветка, а затемнение поверх экрана
        c.drawRect(zeroX - tick / 2, rect.top + rect.height() * 0.3f, zeroX + tick / 2, rect.bottom - rect.height() * 0.3f,
                valueX > zeroX ? zeroMarkOnFill : zeroMark);
        c.restore();

        drawSun(c, h / 2 + 4, h / 2, h * 0.14f);

        String label = value + "%";
        float baseline = h / 2 - (text.descent() + text.ascent()) / 2;
        c.drawText(label, w - h * 0.4f, baseline, text);

        if (isFocused()) {
            float inset = ring.getStrokeWidth() / 2;
            rect.set(inset, inset, w - inset, h - inset);
            c.drawRoundRect(rect, radius, radius, ring);
        }
    }

    private void drawSun(Canvas c, float cx, float cy, float r) {
        icon.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, cy, r, icon);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float cos = (float) Math.cos(a);
            float sin = (float) Math.sin(a);
            c.drawLine(cx + cos * r * 1.6f, cy + sin * r * 1.6f, cx + cos * r * 2.2f, cy + sin * r * 2.2f, icon);
        }
    }
}
