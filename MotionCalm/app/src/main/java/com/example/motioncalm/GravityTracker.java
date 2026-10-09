package com.example.motioncalm;

/**
 * Опора для выделения ускорения автомобиля из показаний акселерометра.
 *
 * Акселерометр измеряет ускорение телефона вместе с силой тяжести. Чтобы их разделить,
 * нужно знать направление силы тяжести в координатах телефона. Если брать его из системного
 * датчика, который сам подстраивается под длительное ускорение, то разгон и торможение
 * автомобиля уходят в «наклон», и точки при старте и торможении почти не двигаются.
 *
 * Поэтому опора меняется медленно и только в покое: пока ускорение меньше GATE_MS2.
 * Если телефон переставили и опора осталась неверной, её сверяем с датчиком гравитации,
 * а если его нет, с текущими показаниями.
 *
 * Чистая логика без Android: покрыта JVM-тестами.
 */
final class GravityTracker {

    /** Ускорение выше этого порога — это движение, а не наклон. Опору в этот момент не меняем, м/с². */
    static final float GATE_MS2 = 1.0f;

    /** Постоянная времени медленного уточнения опоры, с. */
    static final float TAU_S = 10f;

    /** Сколько «движение» может держаться, прежде чем опору сверят с датчиком гравитации, с. */
    static final float RESYNC_FUSED_S = 4f;

    /** Сколько «движение» может держаться без датчика гравитации, прежде чем опору возьмут по показаниям, с. */
    static final float RESYNC_BLIND_S = 20f;

    private final float[] reference = new float[3];
    private boolean ready = false;
    private float closedS = 0f;

    /** Сбрасывает опору, например после новой подписки на датчики. */
    void invalidate() {
        ready = false;
        closedS = 0f;
    }

    boolean isReady() {
        return ready;
    }

    /** Текущая опора: направление силы тяжести в координатах телефона, м/с². Только для чтения. */
    float[] reference() {
        return reference;
    }

    /** Ставит опору явно. */
    void reset(float[] gravityUp) {
        System.arraycopy(gravityUp, 0, reference, 0, 3);
        ready = true;
        closedS = 0f;
    }

    /**
     * Возвращает ускорение автомобиля без силы тяжести в координатах телефона, м/с².
     *
     * @param f            показания акселерометра, м/с²
     * @param dtS          время с прошлого показания, с
     * @param fusedGravity сила тяжести от датчика гравитации или null, если его нет
     */
    float[] update(float[] f, float dtS, float[] fusedGravity) {
        if (!ready) {
            reset(fusedGravity != null ? fusedGravity : f);
        }
        float[] a = difference(f, reference);
        if (length(a) < GATE_MS2) {
            closedS = 0f;
            float k = 1f - (float) Math.exp(-dtS / TAU_S);
            for (int i = 0; i < 3; i++) {
                reference[i] += k * (f[i] - reference[i]);
            }
        } else {
            closedS += dtS;
            float limit = fusedGravity != null ? RESYNC_FUSED_S : RESYNC_BLIND_S;
            if (closedS > limit) {
                System.arraycopy(fusedGravity != null ? fusedGravity : f, 0, reference, 0, 3);
                closedS = 0f;
                a = difference(f, reference);
            }
        }
        return a;
    }

    private static float[] difference(float[] f, float[] g) {
        return new float[]{f[0] - g[0], f[1] - g[1], f[2] - g[2]};
    }

    private static float length(float[] v) {
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
}
