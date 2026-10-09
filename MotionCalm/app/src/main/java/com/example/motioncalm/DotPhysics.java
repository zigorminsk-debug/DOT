package com.example.motioncalm;

/**
 * Физика точек без зависимостей от Android: пружина с затуханием и сила,
 * направленная против ускорения автомобиля. Все величины в dp.
 */
public class DotPhysics {

    public static final float BASE_GAIN = 450f;   // dp на 1 м/с² при амплитуде 1.0 (установившийся режим = BASE_GAIN / STIFFNESS)
    public static final float STIFFNESS = 8f;     // жёсткость пружины
    public static final float DAMPING = 3.4f;     // затухание (≈ 0.6 критического)
    public static final float BASE_MAX = 110f;    // предел визуального смещения при амплитуде 1.0, dp
    public static final float INPUT_ALPHA = 0.3f;

    /** Внутреннее состояние (без ограничений). */
    private float x, y;
    private float vx, vy;
    private float ax, ay;  // сглаженное ускорение, м/с²
    private float amp = 1f; // множитель амплитуды (настройка пользователя)

    public void setAmplitude(float value) {
        amp = Math.max(0.1f, Math.min(3f, value));
    }

    /**
     * ux, uy — ускорение автомобиля в координатах экрана (м/с²): x вправо, y вниз.
     * dt — шаг по времени (с).
     */
    public void step(float ux, float uy, float dt) {
        dt = Math.max(0.001f, Math.min(0.05f, dt));

        ax += INPUT_ALPHA * (ux - ax);
        ay += INPUT_ALPHA * (uy - ay);

        // Сила направлена против ускорения автомобиля (как у iPhone)
        float fx = -ax * BASE_GAIN * amp - STIFFNESS * x - DAMPING * vx;
        float fy = -ay * BASE_GAIN * amp - STIFFNESS * y - DAMPING * vy;

        vx += fx * dt;
        vy += fy * dt;
        x += vx * dt;
        y += vy * dt;
    }

    /** Смещение для отрисовки по X, dp. Плавно ограничено, без рывков на краю. */
    public float offsetX() {
        return BASE_MAX * amp * (float) Math.tanh(x / (BASE_MAX * amp));
    }

    /** Смещение для отрисовки по Y, dp. */
    public float offsetY() {
        return BASE_MAX * amp * (float) Math.tanh(y / (BASE_MAX * amp));
    }
}
