package com.example.motioncalm;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CueMotionTest {

    private static final float EPS = 1e-3f;
    private static final float[] UPRIGHT = {0f, 1f, 0f};   // телефон стоит вертикально
    private static final float[] FLAT = {0f, 0f, 1f};      // телефон лежит экраном вверх

    private static void assertResult(float[] result, float ux, float uy) {
        assertEquals("ux", ux, result[0], EPS);
        assertEquals("uy", uy, result[1], EPS);
    }

    @Test
    public void verticalPhoneAccelerationMovesDotsDown() {
        // Вперёд у телефона, стоящего экраном к водителю, — это минус z устройства
        float[] r = CueMotion.screenAccel(new float[]{0f, 0f, -2f}, UPRIGHT, 0);
        assertResult(r, 0f, -2f);
        assertEquals("вперёд", 2f, r[2], EPS);
    }

    @Test
    public void verticalPhoneBrakingMovesDotsUp() {
        float[] r = CueMotion.screenAccel(new float[]{0f, 0f, 2f}, UPRIGHT, 0);
        assertResult(r, 0f, 2f);
    }

    @Test
    public void verticalPhoneTurningRightMovesDotsLeft() {
        // Поворот направо: ускорение вправо, точки уходят влево (ux > 0 в терминах DotPhysics)
        float[] r = CueMotion.screenAccel(new float[]{2f, 0f, 0f}, UPRIGHT, 0);
        assertResult(r, 2f, 0f);
    }

    @Test
    public void bumpsDoNotMoveDotsSidewaysOrForward() {
        // Вертикальный толчок (вдоль оси y) не должен двигать точки ни вбок, ни вперёд
        float[] r = CueMotion.screenAccel(new float[]{0f, 3f, 0f}, UPRIGHT, 0);
        assertResult(r, 0f, 0f);
    }

    @Test
    public void tiltedPhoneGivesFullForwardResponse() {
        // Телефон наклонён на 30 градусов. Ускорение вперёд 2 м/с² даёт по осям устройства
        // (0, 2*sin30, -2*cos30). Реакция должна быть полной, а не в половину.
        double tilt = Math.toRadians(30);
        float[] up = {0f, (float) Math.cos(tilt), (float) Math.sin(tilt)};
        float[] accel = {0f, (float) (2 * Math.sin(tilt)), (float) (-2 * Math.cos(tilt))};
        float[] r = CueMotion.screenAccel(accel, up, 0);
        assertEquals("вперёд", 2f, r[2], EPS);
        assertResult(r, 0f, -2f);
    }

    @Test
    public void flatPhoneKeepsOldMappingForEveryRotation() {
        // Для телефона, лежащего экраном вверх, результат должен совпадать со старой формулой.
        // Вход: fx = 1, fy = 2. Старые выходы (ux, uy): rot0 (1,-2), rot1 (-2,-1), rot2 (-1,2), rot3 (2,1).
        float[] accel = {1f, 2f, 0f};
        float[][] expected = {{1f, -2f}, {-2f, -1f}, {-1f, 2f}, {2f, 1f}};
        for (int rotation = 0; rotation < 4; rotation++) {
            float[] r = CueMotion.screenAccel(accel, FLAT, rotation);
            assertEquals("ux rotation " + rotation, expected[rotation][0], r[0], EPS);
            assertEquals("uy rotation " + rotation, expected[rotation][1], r[1], EPS);
        }
    }

    @Test
    public void displayRotationDoesNotChangeForwardResponse() {
        // Вперёд не зависит от поворота экрана: при повороте на 180 градусов меняются только оси экрана
        float[] r = CueMotion.screenAccel(new float[]{0f, 0f, -2f}, UPRIGHT, 2);
        assertEquals("вперёд", 2f, r[2], EPS);
        assertEquals("uy", -2f, r[1], EPS);
    }

    @Test
    public void landscapeVerticalPhoneWorksToo() {
        // Телефон стоит вертикально, но в альбомной ориентации: вертикаль — ось x устройства
        float[] up = {1f, 0f, 0f};
        float[] forward = CueMotion.screenAccel(new float[]{0f, 0f, -2f}, up, 1);
        assertEquals("вперёд", 2f, forward[2], EPS);
        assertEquals("uy", -2f, forward[1], EPS);
        float[] right = CueMotion.screenAccel(new float[]{0f, -2f, 0f}, up, 1);
        assertEquals("ux", 2f, right[0], EPS);
    }

    @Test
    public void missingGravityFallsBackToPortrait() {
        float[] r = CueMotion.screenAccel(new float[]{0f, 0f, -2f}, new float[]{0f, 0f, 0f}, 0);
        assertResult(r, 0f, -2f);
    }

    @Test
    public void resultsAreFiniteForAnyInput() {
        float[] r = CueMotion.screenAccel(new float[]{5f, -3f, 1f}, new float[]{0.3f, 0.2f, 0.9f}, 3);
        for (float v : r) {
            assertTrue("значение должно быть конечным", !Float.isNaN(v) && !Float.isInfinite(v));
        }
    }
}
