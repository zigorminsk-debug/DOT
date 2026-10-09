package com.example.motioncalm;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DotLayoutTest {

    private static final float EPS = 1e-3f;

    @Test
    public void defaultThicknessStaysInsideGutter() {
        float radius = 4f;   // диаметр 8 dp
        float rest = DotLayout.restCenterDp(radius);
        float travel = DotLayout.travelInDp(radius);
        assertEquals(6f, rest, EPS);
        assertEquals(4.5f, travel, EPS);
        // Ореол в покое и при полном смещении внутрь не выходит за полосу у края
        assertTrue(rest - DotLayout.haloRadiusDp(radius) >= 0f);
        assertTrue(rest + travel + DotLayout.haloRadiusDp(radius) <= DotLayout.GUTTER_DP + EPS);
    }

    @Test
    public void maxThicknessStillFitsInGutter() {
        float radius = 6f;   // диаметр 12 dp, максимум в приложении
        float travel = DotLayout.travelInDp(radius);
        assertEquals(0.5f, travel, EPS);
        assertTrue(DotLayout.restCenterDp(radius) + travel + DotLayout.haloRadiusDp(radius)
                <= DotLayout.GUTTER_DP + EPS);
    }

    @Test
    public void thinnerDotsHaveMoreTravel() {
        assertTrue(DotLayout.travelInDp(2f) > DotLayout.travelInDp(4f));
    }

    @Test
    public void travelNeverBecomesNegative() {
        assertEquals(0f, DotLayout.travelInDp(10f), EPS);
    }

    @Test
    public void softLimitIsZeroAtRest() {
        assertEquals(0f, DotLayout.softLimit(0f, 4.5f, 0.5f), EPS);
    }

    @Test
    public void softLimitStaysWithinInwardBound() {
        float v = DotLayout.softLimit(1000f, 4.5f, 0.5f);
        assertTrue(v <= 4.5f + 1e-4f && v >= 4.49f);
    }

    @Test
    public void softLimitStaysWithinOutwardBound() {
        float v = DotLayout.softLimit(-1000f, 4.5f, 0.5f);
        assertTrue(v >= -0.5f - 1e-4f && v <= -0.49f);
    }

    @Test
    public void softLimitKeepsSmallMovementAlmostUnchanged() {
        assertEquals(0.1f, DotLayout.softLimit(0.1f, 4.5f, 0.5f), 1e-3f);
    }

    @Test
    public void softLimitIsMonotonic() {
        float previous = Float.NEGATIVE_INFINITY;
        for (float x = -50f; x <= 50f; x += 0.5f) {
            float v = DotLayout.softLimit(x, 4.5f, 0.5f);
            assertTrue("монотонность при x=" + x, v >= previous);
            previous = v;
        }
    }

    @Test
    public void zeroTravelKeepsDotStill() {
        assertEquals(0f, DotLayout.softLimit(5f, 0f, 0f), EPS);
    }
}
