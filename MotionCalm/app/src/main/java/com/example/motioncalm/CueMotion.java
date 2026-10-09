package com.example.motioncalm;

/**
 * Перевод показаний датчиков в движение точек на экране. Чистая логика без Android,
 * поэтому её покрывают обычные JVM-тесты.
 *
 * Координаты экрана: x вправо, y вниз (как в DotPhysics). На выходе:
 *   ux: ускорение автомобиля вправо, м/с². Поворот направо даёт положительное значение.
 *   uy: ускорение автомобиля вниз, м/с². Разгон даёт отрицательное значение: точки уходят вниз.
 *       Торможение даёт положительное значение: точки уходят вверх.
 *
 * Направление «вперёд» определяется по положению телефона, а не по его верху:
 *   - телефон стоит вертикально экраном к водителю: вперёд = от экрана, то есть -z устройства;
 *   - телефон лежит экраном вверх: вперёд = верх экрана;
 *   - при наклоне направления смешиваются, и реакция остаётся полной.
 * Оси устройства: x вправо, y вверх по экрану в портретной ориентации, z из экрана к зрителю.
 * Поворот экрана (rotation) учитывается только для направления «вправо» и «вверх» по экрану.
 */
final class CueMotion {

    /** Если направление вверх не известно (датчика гравитации нет и данных ещё нет). */
    private static final float[] DEFAULT_UP = {0f, 1f, 0f};

    private CueMotion() {
    }

    /**
     * Считает ускорение точек.
     *
     * @param accel    ускорение без силы тяжести в координатах устройства, м/с²
     * @param up       направление «вверх» (сила тяжести, указывает вверх), координаты устройства
     * @param rotation поворот экрана: 0, 1, 2, 3 (как Surface.ROTATION_*)
     * @return массив {ux, uy, вперёд, вбок}; вперёд и вбок — ускорения автомобиля, м/с²
     */
    static float[] screenAccel(float[] accel, float[] up, int rotation) {
        float[] u = normalize(length(up) > 1e-3f ? up : DEFAULT_UP);

        // Вертикальность экрана: 0 — экран вертикален, 1 — экран лежит горизонтально.
        float flatness = Math.abs(u[2]);

        // Кандидат 1: от экрана к водителю, в горизонтальной плоскости (для вертикального телефона).
        float[] fromScreen = horizontalUnit(new float[]{0f, 0f, -1f}, u);
        // Кандидат 2: верх экрана, в горизонтальной плоскости (для телефона, лежащего экраном вверх).
        float[] screenUp = horizontalUnit(screenUpDevice(rotation), u);

        float wVertical = 1f - flatness;
        float wFlat = flatness;
        float[] forwardDir = new float[3];
        for (int k = 0; k < 3; k++) {
            forwardDir[k] = wVertical * fromScreen[k] + wFlat * screenUp[k];
        }
        if (length(forwardDir) < 1e-3f) {
            // Крайний случай: оба кандидата вырождены. Считаем, что вперёд — от экрана.
            forwardDir = fromScreen;
        }
        forwardDir = normalize(forwardDir);

        float forward = dot(accel, forwardDir);
        float lateral = dot(accel, horizontalUnit(screenRightDevice(rotation), u));

        return new float[]{lateral, -forward, forward, lateral};
    }

    /** Проекция вектора на горизонтальную плоскость (перпендикулярно «вверх») и нормировка. */
    static float[] horizontalUnit(float[] v, float[] up) {
        float d = dot(v, up);
        float[] h = {v[0] - d * up[0], v[1] - d * up[1], v[2] - d * up[2]};
        if (length(h) < 1e-3f) {
            return new float[]{0f, 0f, 0f};
        }
        return normalize(h);
    }

    /** Направление «вверх по экрану» в координатах устройства для данного поворота. */
    static float[] screenUpDevice(int rotation) {
        switch (rotation & 3) {
            case 1:
                return new float[]{1f, 0f, 0f};
            case 2:
                return new float[]{0f, -1f, 0f};
            case 3:
                return new float[]{-1f, 0f, 0f};
            case 0:
            default:
                return new float[]{0f, 1f, 0f};
        }
    }

    /** Направление «вправо по экрану» в координатах устройства для данного поворота. */
    static float[] screenRightDevice(int rotation) {
        switch (rotation & 3) {
            case 1:
                return new float[]{0f, -1f, 0f};
            case 2:
                return new float[]{-1f, 0f, 0f};
            case 3:
                return new float[]{0f, 1f, 0f};
            case 0:
            default:
                return new float[]{1f, 0f, 0f};
        }
    }

    static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static float length(float[] v) {
        return (float) Math.sqrt(dot(v, v));
    }

    static float[] normalize(float[] v) {
        float len = length(v);
        if (len < 1e-6f) {
            return new float[]{0f, 0f, 0f};
        }
        return new float[]{v[0] / len, v[1] / len, v[2] / len};
    }
}
