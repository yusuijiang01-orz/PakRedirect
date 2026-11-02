package com.example.pakredirect;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ScriptAutoRecoveryPolicyTest {
    @Test public void restartsOnlyAfterObservedGameProcessStops() {
        assertTrue(ScriptAutoRecoveryPolicy.shouldRestart(true, true, false,
                ScriptGameProcessMonitor.State.CRASHED, 0));
        assertFalse(ScriptAutoRecoveryPolicy.shouldRestart(true, false, false,
                ScriptGameProcessMonitor.State.CRASHED, 0));
        assertFalse(ScriptAutoRecoveryPolicy.shouldRestart(true, true, false,
                ScriptGameProcessMonitor.State.RUNNING, 0));
        assertFalse(ScriptAutoRecoveryPolicy.shouldRestart(true, true, false,
                ScriptGameProcessMonitor.State.UNKNOWN, 0));
        assertFalse(ScriptAutoRecoveryPolicy.shouldRestart(true, true, false,
                ScriptGameProcessMonitor.State.STOPPED, 0));
    }

    @Test public void respectsSwitchStopRequestAndRestartLimit() {
        assertFalse(ScriptAutoRecoveryPolicy.shouldRestart(false, true, false,
                ScriptGameProcessMonitor.State.CRASHED, 0));
        assertFalse(ScriptAutoRecoveryPolicy.shouldRestart(true, true, true,
                ScriptGameProcessMonitor.State.CRASHED, 0));
        assertFalse(ScriptAutoRecoveryPolicy.shouldRestart(true, true, false,
                ScriptGameProcessMonitor.State.CRASHED,
                ScriptAutoRecoveryPolicy.MAX_RESTARTS_PER_WINDOW));
    }

    @Test public void restartBackoffIsBoundedAndIncreases() {
        assertEquals(3_000L, ScriptAutoRecoveryPolicy.backoffMs(1));
        assertEquals(8_000L, ScriptAutoRecoveryPolicy.backoffMs(2));
        assertEquals(20_000L, ScriptAutoRecoveryPolicy.backoffMs(3));
        assertEquals(45_000L, ScriptAutoRecoveryPolicy.backoffMs(9));
    }
}
