package com.example.motioncalm;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DotPhysicsTest {

    @Test
    public void staysStillWithoutInput() {
        DotPhysics p = new DotPhysics();
        for (int i = 0; i < 200; i++) {
            p.step(0f, 0f, 0.02f);
        }
        assertTrue(Math.abs(p.offsetX()) < 1e-3f);
        assertTrue(Math.abs(p.offsetY()) < 1e-3f);
    }

    @Test
    public void movesOppositeToAcceleration() {
        DotPhysics p = new DotPhysics();
        for (int i = 0; i < 50; i++) {
            p.step(3f, 0f, 0.02f);   // ускорение вправо
        }
        assertTrue("точки должны сместиться влево", p.offsetX() < -1f);
    }

    @Test
    public void returnsToCenterAfterInputStops() {
        DotPhysics p = new DotPhysics();
        for (int i = 0; i < 50; i++) {
            p.step(3f, 0f, 0.02f);
        }
        for (int i = 0; i < 1000; i++) {
            p.step(0f, 0f, 0.02f);   // 20 секунд покоя
        }
        assertTrue(Math.abs(p.offsetX()) < 0.5f);
    }

    @Test
    public void offsetStaysWithinAmplitudeLimit() {
        DotPhysics p = new DotPhysics();
        p.setAmplitude(2f);
        for (int i = 0; i < 500; i++) {
            p.step(-50f, 50f, 0.02f);   // сильный толчок
        }
        float limit = DotPhysics.BASE_MAX * 2f + 1e-3f;
        assertTrue(Math.abs(p.offsetX()) <= limit);
        assertTrue(Math.abs(p.offsetY()) <= limit);
        assertFalse(Float.isNaN(p.offsetX()));
    }
}
