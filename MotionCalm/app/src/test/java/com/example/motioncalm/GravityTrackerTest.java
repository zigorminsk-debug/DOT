package com.example.motioncalm;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GravityTrackerTest {

    private static final float EPS = 0.01f;
    private static final float G = 9.81f;

    private static float[] v(float x, float y, float z) {
        return new float[]{x, y, z};
    }

    private static float length(float[] v) {
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    @Test
    public void restingPhoneHasNoAcceleration() {
        GravityTracker t = new GravityTracker();
        float[] a = null;
        for (int i = 0; i < 200; i++) {
            a = t.update(v(0f, G, 0f), 0.02f, null);
        }
        assertEquals(0f, length(a), EPS);
    }

    @Test
    public void sustainedAccelerationIsNotAbsorbedIntoGravity() {
        GravityTracker t = new GravityTracker();
        t.update(v(0f, G, 0f), 0.02f, null);
        for (int i = 0; i < 300; i++) {   // 3 секунды разгона, 2.5 м/с² вперёд (против оси z)
            float[] a = t.update(v(0f, G, -2.5f), 0.01f, null);
            assertEquals("шаг " + i, -2.5f, a[2], EPS);
        }
        assertEquals("опора не должна сдвинуться", 0f, t.reference()[2], EPS);
    }

    @Test
    public void endOfAccelerationGivesNoFalseOppositeCue() {
        GravityTracker t = new GravityTracker();
        t.update(v(0f, G, 0f), 0.02f, null);
        for (int i = 0; i < 200; i++) {
            t.update(v(0f, G, -2.5f), 0.01f, null);
        }
        float[] a = t.update(v(0f, G, 0f), 0.01f, null);   // ускорение кончилось
        assertEquals(0f, a[2], EPS);
    }

    @Test
    public void slowTiltChangeIsFollowed() {
        GravityTracker t = new GravityTracker();
        t.update(v(0f, G, 0f), 0.02f, null);
        float[] a = null;
        for (int i = 0; i < 3000; i++) {   // 60 секунд при постоянном небольшом наклоне
            a = t.update(v(0f, G, -0.5f), 0.02f, null);
        }
        assertEquals(0f, a[2], 0.05f);
    }

    @Test
    public void remountIsCorrectedFromFusedGravity() {
        GravityTracker t = new GravityTracker();
        t.update(v(0f, G, 0f), 0.02f, null);   // опора: телефон стоял вертикально
        double tilt = Math.toRadians(20);
        float[] tilted = v(0f, (float) (G * Math.cos(tilt)), (float) (G * Math.sin(tilt)));
        float[] a = null;
        for (int i = 0; i < 300; i++) {        // телефон переставили, датчик гравитации видит новое положение
            a = t.update(tilted, 0.02f, tilted);
        }
        assertEquals(0f, length(a), 0.05f);
    }

    @Test
    public void blindTrackerRecoversAfterLongClosure() {
        GravityTracker t = new GravityTracker();
        t.update(v(0f, G, 0f), 0.02f, null);
        float[] tilted = v(0f, G * 0.94f, G * 0.34f);   // датчика гравитации нет
        float[] a = null;
        for (int i = 0; i < 1500; i++) {                // 30 секунд
            a = t.update(tilted, 0.02f, null);
        }
        assertEquals(0f, length(a), 0.05f);
    }
}
