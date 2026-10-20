package com.example.pakredirect;

import org.junit.Test;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public final class ScriptForegroundGateTest {
    @Test public void readyTargetPassesOnlyAfterCheckpoint() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        ScriptForegroundGate.await(() -> true, checks::incrementAndGet, 100,
                "target did not become foreground");
        assertEquals(1, checks.get());
    }

    @Test public void waitsUntilTheTargetBecomesForeground() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        ScriptForegroundGate.await(() -> checks.get() >= 3,
                checks::incrementAndGet, 5_000, "target did not become foreground");
        assertEquals(3, checks.get());
    }

    @Test public void timeoutPreventsWorkFromPassingTheGate() throws Exception {
        AtomicBoolean actionRan = new AtomicBoolean(false);
        try {
            ScriptForegroundGate.await(() -> false, () -> { }, 20,
                    "sandbox game is not foreground");
            actionRan.set(true);
            fail("Expected the foreground gate to time out");
        } catch (TimeoutException expected) {
            assertEquals("sandbox game is not foreground", expected.getMessage());
        }
        assertTrue("Automation must remain blocked if the game never becomes foreground", !actionRan.get());
    }

    @Test public void cancellationCheckpointInterruptsTheWait() {
        try {
            ScriptForegroundGate.await(() -> false,
                    () -> { throw new IllegalStateException("stopped"); }, 1_000,
                    "target did not become foreground");
            fail("Expected cancellation to propagate");
        } catch (IllegalStateException expected) {
            assertEquals("stopped", expected.getMessage());
        } catch (Exception unexpected) {
            fail("Unexpected checked exception: " + unexpected);
        }
    }

    @Test public void rejectsInputWhenTheTargetIsNoLongerForeground() {
        try {
            ScriptForegroundGate.requireReady(() -> false, "game is not foreground");
            fail("Expected the foreground input gate to reject the operation");
        } catch (IllegalStateException expected) {
            assertEquals("game is not foreground", expected.getMessage());
        }
    }
}
