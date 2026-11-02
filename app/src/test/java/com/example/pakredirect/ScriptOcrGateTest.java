package com.example.pakredirect;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ScriptOcrGateTest {
    @Test public void timeoutReleasesOneRecoverySlotAndLateCompletionCannotOverRelease() throws Exception {
        ScriptOcrGate gate = new ScriptOcrGate();
        ScriptOcrGate.Lease first = gate.acquire(100);
        assertNotNull(first);

        gate.markTimedOut(first);
        assertEquals(1, gate.orphanedRequestCount());
        ScriptOcrGate.Lease recovery = gate.acquire(100);
        assertNotNull(recovery);

        gate.complete(first);
        assertEquals(0, gate.orphanedRequestCount());
        assertNull("The stale callback must not release the recovery request's permit", gate.acquire(1));

        gate.complete(recovery);
        ScriptOcrGate.Lease next = gate.acquire(100);
        assertNotNull(next);
        gate.complete(next);
    }

    @Test public void twoHungRequestsOpenCircuitAndLateCallbacksRestoreService() throws Exception {
        ScriptOcrGate gate = new ScriptOcrGate();
        ScriptOcrGate.Lease first = gate.acquire(100);
        gate.markTimedOut(first);
        ScriptOcrGate.Lease second = gate.acquire(100);
        assertNotNull(second);
        gate.markTimedOut(second);

        assertTrue(gate.isCircuitOpen());
        assertNull("Do not queue more work behind a recognizer that has timed out twice", gate.acquire(10_000));

        gate.complete(first);
        assertFalse(gate.isCircuitOpen());
        gate.complete(second);
        ScriptOcrGate.Lease next = gate.acquire(100);
        assertNotNull(next);
        gate.complete(next);
    }
}
