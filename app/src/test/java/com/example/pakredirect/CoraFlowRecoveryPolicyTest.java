package com.example.pakredirect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;

public final class CoraFlowRecoveryPolicyTest {
    @Test public void recoveryTargetIsUsedBeforeAbortAndStopsAfterConfiguredLimit() {
        HashSet<String> ids = new HashSet<>();
        ids.add("entry");
        HashMap<String, Integer> attempts = new HashMap<>();

        CoraFlowRecoveryPolicy.Decision first = CoraFlowRecoveryPolicy.plan(
                "abort", "not confirmed", "entry", 2, 1500, ids, attempts);
        assertEquals("entry", first.target);
        assertEquals(1, first.attempt);
        assertEquals(1500L, first.delayMs);
        assertTrue(first.shouldRecover());

        CoraFlowRecoveryPolicy.Decision second = CoraFlowRecoveryPolicy.plan(
                "abort", "not confirmed", "entry", 2, 1500, ids, attempts);
        assertTrue(second.shouldRecover());
        CoraFlowRecoveryPolicy.Decision exhausted = CoraFlowRecoveryPolicy.plan(
                "abort", "not confirmed", "entry", 2, 1500, ids, attempts);
        assertFalse(exhausted.shouldRecover());
        assertEquals(3, exhausted.attempt);
        assertEquals(2, exhausted.maximum);
    }

    @Test public void abortWithoutRecoveryTargetRemainsTerminal() {
        CoraFlowRecoveryPolicy.Decision decision = CoraFlowRecoveryPolicy.plan(
                "abort", "stop", "", 3, 3000, new HashSet<>(), new HashMap<>());
        assertNull(decision.target);
        assertFalse(decision.shouldRecover());
    }

    @Test public void recoveryTargetMustExistInTheWorkflow() {
        try {
            CoraFlowRecoveryPolicy.plan("abort", "stop",
                    "missing", 3, 3000,
                    new HashSet<>(), new HashMap<>());
            fail("missing recovery target must be rejected");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getMessage());
            assertTrue(expected.getMessage().contains("missing"));
        }
    }
}
