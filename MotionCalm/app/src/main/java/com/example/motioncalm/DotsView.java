package com.example.motioncalm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Точки по левому и правому краю экрана, как «Признаки движения» на iPhone.
 * Точки ведут себя как объекты с инерцией: при ускорении они смещаются
 * в противоположную сторону, затем плавно возвращаются на место.
 * При равномерном движении стоят на месте.
 */
public class DotsView extends View {

    /** Количество точек на каждый край: Мало / Средне / Много. */
    public static final int[] COUNTS = {5, 8, 12};

    private static final float MARGIN_DP = 14f;    // отступ от края
    private static final float RADIUS_DP = 4f;     // радиус точки

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    private final List<float[]> base = new ArrayList<>();
    private int dotCount = COUNTS[1];
    private float intensity = 0.5f;

    private final DotPhysics physics = new DotPhysics();
    private float px, py;   // текущее смещение, пиксели

    public DotsView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        fill.setStyle(Paint.Style.FILL);
        halo.setStyle(Paint.Style.FILL);
    }

    /** intensity от 0.1 до 1.0 */
    public void setIntensity(float value) {
        intensity = Math.max(0.1f, Math.min(1f, value));
        invalidate();
    }

    /** count: 0 = Мало, 1 = Средне, 2 = Много */
    public void setDotLevel(int level) {
        int count = COUNTS[Math.max(0, Math.min(COUNTS.length - 1, level))];
        if (count != dotCount) {
            dotCount = count;
            buildDots(getWidth(), getHeight());
            invalidate();
        }
    }

    /**
     * Один шаг физики.
     * ux — ускорение вправо (м/с²), uy — ускорение вниз (м/с²) в координатах экрана.
     * dt — прошедшее время (с).
     */
    public void step(float ux, float uy, float dt) {
        physics.step(ux, uy, dt);
        px = physics.offsetX() * density;
        py = physics.offsetY() * density;
        invalidate();
    }

    /** Амплитуда: 0.3 (слабо) … 2.0 (сильно), 1.0 — стандарт. */
    public void setAmplitude(float value) {
        physics.setAmplitude(value);
    }

    /** Текущее смещение точек в dp (для диагностики). */
    public float getOffsetXdp() {
        return physics.offsetX();
    }

    public float getOffsetYdp() {
        return physics.offsetY();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildDots(w, h);
    }

    private void buildDots(int w, int h) {
        base.clear();
        if (w <= 0 || h <= 0) {
            return;
        }
        float m = MARGIN_DP * density;
        float top = m;
        float bottom = h - m;
        float step = (bottom - top) / (dotCount - 1);

        for (int i = 0; i < dotCount; i++) {
            float y = top + i * step;
            base.add(new float[]{m, y});       // левый край
            base.add(new float[]{w - m, y});   // правый край
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float r = RADIUS_DP * density;
        fill.setColor(Color.argb((int) (255 * intensity), 20, 20, 20));
        halo.setColor(Color.argb((int) (255 * intensity * 0.5f), 255, 255, 255));

        for (float[] p : base) {
            float cx = p[0] + px;
            float cy = p[1] + py;
            canvas.drawCircle(cx, cy, r + 1.5f * density, halo);
            canvas.drawCircle(cx, cy, r, fill);
        }
    }
}
