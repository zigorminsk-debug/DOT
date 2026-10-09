package com.example.motioncalm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Точки у левого и правого края экрана, как «Признаки движения» на iPhone.
 * Точки ведут себя как объекты с инерцией: при ускорении смещаются в противоположную сторону,
 * затем плавно возвращаются на место.
 *
 * Чтобы точки не перекрывали текст, смещение вбок ограничено полосой у края (см. DotLayout).
 * Вверх и вниз точки смещаются вдоль края, где текста обычно нет, и ограничены только экраном.
 */
public class DotsView extends View {

    /** Количество точек на каждый край: Мало / Средне / Много. */
    public static final int[] COUNTS = {5, 8, 12};

    private static final float VERTICAL_MARGIN_DP = 14f;   // отступ сверху и снизу
    private static final float MIN_DIAMETER_DP = 4f;
    /** Больше 12 dp точка с ореолом не помещается в полосу у края. */
    private static final float MAX_DIAMETER_DP = 12f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    /** Точки в покое: {x, y, сторона}. Сторона −1 — левый край, +1 — правый. Координаты в пикселях. */
    private final List<float[]> base = new ArrayList<>();
    private int dotCount = COUNTS[1];
    private float intensity = 0.5f;
    private float radiusDp = 4f;   // радиус точки в dp (диаметр 8 dp)

    private final DotPhysics physics = new DotPhysics();
    private float offsetLeftPx = 0f;    // смещение левых точек по горизонтали, пиксели
    private float offsetRightPx = 0f;   // смещение правых точек по горизонтали, пиксели
    private float offsetYPx = 0f;       // смещение по вертикали, пиксели

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

    /** Толщина точки (диаметр), dp. Ограничена полосой у края. */
    public void setDotSize(float diameterDp) {
        float clamped = Math.max(MIN_DIAMETER_DP, Math.min(MAX_DIAMETER_DP, diameterDp));
        radiusDp = clamped / 2f;
        buildDots(getWidth(), getHeight());
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
        float ox = physics.offsetX();   // dp, без ограничения
        float oy = physics.offsetY();
        float inward = DotLayout.travelInDp(radiusDp);
        float outward = DotLayout.travelOutDp();
        // Левый край: смещение вправо — внутрь. Правый край: внутрь — это смещение влево.
        offsetLeftPx = DotLayout.softLimit(ox, inward, outward) * density;
        offsetRightPx = -DotLayout.softLimit(-ox, inward, outward) * density;
        offsetYPx = oy * density;
        invalidate();
    }

    /** Смещение левых точек по горизонтали, dp (для диагностики). */
    public float getOffsetXdp() {
        return offsetLeftPx / density;
    }

    /** Смещение точек по вертикали, dp (для диагностики). */
    public float getOffsetYdp() {
        return offsetYPx / density;
    }

    /** Амплитуда: 0.3 (слабо) … 2.0 (сильно), 1.0 — стандарт. */
    public void setAmplitude(float value) {
        physics.setAmplitude(value);
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
        float edge = DotLayout.restCenterDp(radiusDp) * density;
        float top = VERTICAL_MARGIN_DP * density;
        float bottom = h - top;
        float step = (bottom - top) / (dotCount - 1);

        for (int i = 0; i < dotCount; i++) {
            float y = top + i * step;
            base.add(new float[]{edge, y, -1f});        // левый край
            base.add(new float[]{w - edge, y, 1f});     // правый край
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float r = radiusDp * density;
        float haloR = DotLayout.HALO_DP * density;
        fill.setColor(Color.argb((int) (255 * intensity), 20, 20, 20));
        halo.setColor(Color.argb((int) (255 * intensity * 0.5f), 255, 255, 255));

        for (float[] p : base) {
            float cx = p[0] + (p[2] < 0f ? offsetLeftPx : offsetRightPx);
            float cy = p[1] + offsetYPx;
            canvas.drawCircle(cx, cy, r + haloR, halo);
            canvas.drawCircle(cx, cy, r, fill);
        }
    }
}
