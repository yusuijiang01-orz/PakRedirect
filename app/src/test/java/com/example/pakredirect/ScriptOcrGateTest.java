package com.example.pakredirect;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ScriptOcrGateTest {
    @Test public void timeoutAllowsOneRecoveryAndBoundsOrphanedRequests() throws Exception {
        ScriptOcrGate gate = new ScriptOcrGate();
        ScriptOcrGate.Lease first = gate.acquire(100);
        assertNotNull(first);

        gate.markTimedOut(first);
        assertEquals(1, gate.orphanedRequestCount());
        assertFalse(gate.isCircuitOpen());
        ScriptOcrGate.Lease recovery = gate.acquire(100);
        assertNotNull("Allow one bounded recovery attempt", recovery);

        gate.markTimedOut(recovery);
        assertEquals(2, gate.orphanedRequestCount());
        assertTrue(gate.isCircuitOpen());
        assertNull("Stop after two outstanding timed-out inferences", gate.acquire(100));

        gate.complete(first);
        assertEquals(1, gate.orphanedRequestCount());
        assertFalse(gate.isCircuitOpen());
        ScriptOcrGate.Lease next = gate.acquire(100);
        assertNotNull(next);
        gate.complete(next);
        gate.complete(recovery);
        assertEquals(0, gate.orphanedRequestCount());
    }

    @Test public void lateCallbackReleasesItsOwnTimedOutLease() throws Exception {
        ScriptOcrGate gate = new ScriptOcrGate();
        ScriptOcrGate.Lease first = gate.acquire(100);
        gate.markTimedOut(first);

        assertFalse(gate.isCircuitOpen());
        ScriptOcrGate.Lease next = gate.acquire(10_000);
        assertNotNull(next);
        gate.complete(next);

        gate.complete(first);
        assertFalse(gate.isCircuitOpen());
        assertEquals(0, gate.orphanedRequestCount());
        ScriptOcrGate.Lease restored = gate.acquire(100);
        assertNotNull(restored);
        gate.complete(restored);
    }
}
