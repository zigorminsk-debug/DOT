package com.example.motioncalm;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CueHealthTest {

    private static final long S = 1_000_000_000L;

    @Test
    public void sensorIsNotSubscribedWhenScreenIsOff() {
        assertFalse(CueHealth.sensorNeedsSubscribe(false, false, true, false, 10 * S, 0));
    }

    @Test
    public void sensorIsSubscribedWhenScreenIsOnAndNotSubscribed() {
        assertTrue(CueHealth.sensorNeedsSubscribe(true, false, true, false, 10 * S, 0));
    }

    @Test
    public void healthySensorIsLeftAlone() {
        assertFalse(CueHealth.sensorNeedsSubscribe(true, false, true, true, 10 * S, 10 * S - S / 10));
    }

    @Test
    public void silentSensorIsResubscribed() {
        assertTrue(CueHealth.sensorNeedsSubscribe(true, false, true, true, 10 * S, 7 * S));
    }

    @Test
    public void shortSilenceIsTolerated() {
        assertFalse(CueHealth.sensorNeedsSubscribe(true, false, true, true, 10 * S, 9 * S));
    }

    @Test
    public void noSensorMeansNothingToSubscribe() {
        assertFalse(CueHealth.sensorNeedsSubscribe(true, false, false, false, 10 * S, 0));
    }

    @Test
    public void noSubscriptionWhileTesting() {
        assertFalse(CueHealth.sensorNeedsSubscribe(true, true, true, false, 10 * S, 0));
    }

    @Test
    public void stoppedFrameLoopIsStarted() {
        assertTrue(CueHealth.frameLoopNeedsStart(true, true, false, 5 * S, 5 * S));
    }

    @Test
    public void stalledFrameLoopIsRestarted() {
        assertTrue(CueHealth.frameLoopNeedsStart(true, true, true, 5 * S, 3 * S));
    }

    @Test
    public void healthyFrameLoopIsLeftAlone() {
        assertFalse(CueHealth.frameLoopNeedsStart(true, true, true, 5 * S, 5 * S - S / 60));
    }

    @Test
    public void frameLoopIsIdleWhenScreenIsOff() {
        assertFalse(CueHealth.frameLoopNeedsStart(false, true, false, 5 * S, 0));
    }

    @Test
    public void frameLoopIsIdleWithoutOverlay() {
        assertFalse(CueHealth.frameLoopNeedsStart(true, false, false, 5 * S, 0));
    }
}
