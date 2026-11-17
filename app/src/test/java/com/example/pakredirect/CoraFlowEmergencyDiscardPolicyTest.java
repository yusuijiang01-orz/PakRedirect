package com.example.pakredirect;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class CoraFlowEmergencyDiscardPolicyTest {
    @Test public void lowMedicineVisitAtLoadTenDoesNotNeedEmergencyDiscard() {
        assertFalse(CoraFlowWorkflowRuntime.shouldContinueEmergencyDiscard(10, 200));
    }

    @Test public void stopDiscardingAsSoonAsLoadFallsBelowMaximumEvenWhenBagHasNoFreeSlots() {
        assertFalse(CoraFlowWorkflowRuntime.shouldContinueEmergencyDiscard(199, 200));
    }

    @Test public void keepDiscardingAtOrAboveMaximumLoad() {
        assertTrue(CoraFlowWorkflowRuntime.shouldContinueEmergencyDiscard(200, 200));
        assertTrue(CoraFlowWorkflowRuntime.shouldContinueEmergencyDiscard(249, 200));
    }
}
