package com.example.motioncalm;

/**
 * Геометрия точек у края экрана. Чистая логика без Android, поэтому её покрывают JVM-тесты.
 *
 * Точки стоят в полосе у левого и правого края. Полоса шириной GUTTER_DP — это отступ,
 * внутри которого обычно нет текста (в Android по умолчанию 16 dp). Точка вместе с ореолом
 * не выходит за эту полосу при смещении вбок, поэтому не перекрывает текст. Вверх и вниз
 * точки смещаются вдоль края, где текста нет, поэтому там ограничивает только экран.
 */
final class DotLayout {

    /** Полоса у края, за которую точки не выходят при смещении вбок, dp. */
    static final float GUTTER_DP = 16f;

    /** Ореол вокруг точки, dp. */
    static final float HALO_DP = 1.5f;

    /** Минимальный зазор между ореолом и краем экрана в покое, dp. */
    static final float MIN_EDGE_DP = 0.5f;

    private DotLayout() {
    }

    /** Радиус вместе с ореолом, dp. */
    static float haloRadiusDp(float radiusDp) {
        return radiusDp + HALO_DP;
    }

    /** Положение центра точки в покое, dp от края экрана. */
    static float restCenterDp(float radiusDp) {
        return haloRadiusDp(radiusDp) + MIN_EDGE_DP;
    }

    /** На сколько точка может сместиться внутрь экрана, к тексту, dp. Не меньше нуля. */
    static float travelInDp(float radiusDp) {
        float innermostCenter = GUTTER_DP - haloRadiusDp(radiusDp);
        return Math.max(0f, innermostCenter - restCenterDp(radiusDp));
    }

    /** На сколько точка может сместиться наружу, к краю экрана, dp. Точка остаётся на экране. */
    static float travelOutDp() {
        return MIN_EDGE_DP;
    }

    /**
     * Смещение с мягким ограничением. Положительные значения ограничены inMax, отрицательные — outMax.
     * При малых значениях почти не меняет входное, поэтому чувствительность сохраняется.
     */
    static float softLimit(float x, float inMax, float outMax) {
        if (x >= 0f) {
            return inMax <= 0f ? 0f : (float) (inMax * Math.tanh(x / inMax));
        }
        return outMax <= 0f ? 0f : (float) (-outMax * Math.tanh(-x / outMax));
    }
}
