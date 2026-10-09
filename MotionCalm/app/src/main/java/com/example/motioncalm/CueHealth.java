package com.example.motioncalm;

/**
 * Решения сторожа службы: когда датчик или цикл кадров нужно (пере)запустить.
 * Чистая логика без Android, поэтому её покрывают обычные JVM-тесты.
 */
final class CueHealth {

    /** Датчик в режиме GAME отдаёт данные постоянно. Тишина дольше этого означает, что подписка сломалась. */
    static final long SENSOR_SILENCE_NS = 2_000_000_000L;

    /** Кадры идут с частотой экрана. Пауза дольше этого означает, что цикл остановился. */
    static final long FRAME_SILENCE_NS = 1_000_000_000L;

    private CueHealth() {
    }

    /**
     * Нужно ли подписаться на датчик или переподписаться.
     * Подписка имеет смысл только при включённом экране: при выключенном датчик не отдаёт данные.
     */
    static boolean sensorNeedsSubscribe(boolean screenOn, boolean testing, boolean sensorAvailable,
                                        boolean subscribed, long nowNs, long lastEventNs) {
        if (!screenOn || testing || !sensorAvailable) {
            return false;
        }
        if (!subscribed) {
            return true;
        }
        return nowNs - lastEventNs > SENSOR_SILENCE_NS;
    }

    /** Нужно ли снова запустить цикл кадров. */
    static boolean frameLoopNeedsStart(boolean screenOn, boolean overlayAttached, boolean loopScheduled,
                                       long nowNs, long lastFrameNs) {
        if (!screenOn || !overlayAttached) {
            return false;
        }
        if (!loopScheduled) {
            return true;
        }
        return nowNs - lastFrameNs > FRAME_SILENCE_NS;
    }
}
