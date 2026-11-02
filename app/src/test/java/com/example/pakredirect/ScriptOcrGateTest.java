package com.example.pakredirect;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ScriptOcrGateTest {
    @Test public void timeoutKeepsSingleInferenceSlotUntilLateCompletion() throws Exception {
        ScriptOcrGate gate = new ScriptOcrGate();
        ScriptOcrGate.Lease first = gate.acquire(100);
        assertNotNull(first);

        gate.markTimedOut(first);
        assertEquals(1, gate.orphanedRequestCount());
        assertTrue(gate.isCircuitOpen());
        assertNull("Do not launch a second inference while the first timed-out task is still running",
                gate.acquire(100));

        gate.complete(first);
        assertEquals(0, gate.orphanedRequestCount());
        ScriptOcrGate.Lease next = gate.acquire(100);
        assertNotNull(next);
        gate.complete(next);
    }

    @Test public void circuitOpensOnFirstHungRequestAndLateCallbackRestoresService() throws Exception {
        ScriptOcrGate gate = new ScriptOcrGate();
        ScriptOcrGate.Lease first = gate.acquire(100);
        gate.markTimedOut(first);

        assertTrue(gate.isCircuitOpen());
        assertNull("Do not queue more work behind a recognizer that has timed out", gate.acquire(10_000));

        gate.complete(first);
        assertFalse(gate.isCircuitOpen());
        ScriptOcrGate.Lease next = gate.acquire(100);
        assertNotNull(next);
        gate.complete(next);
    }
}
