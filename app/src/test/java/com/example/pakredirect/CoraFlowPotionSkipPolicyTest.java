package com.example.pakredirect;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CoraFlowPotionSkipPolicyTest {
    @Test public void unknownPotionSkipsToNextOuterCycleWithoutChoosingTradeBranch() {
        CoraFlowPotionSkipPolicy.Decision next =
                CoraFlowPotionSkipPolicy.nextCycle("combat_entry", 2, -1);
        assertTrue(next.canContinue);
        assertEquals("combat_entry", next.nextBody);
        assertEquals(3, next.nextIteration);
    }

    @Test public void finiteLoopHonorsIterationLimit() {
        CoraFlowPotionSkipPolicy.Decision next =
                CoraFlowPotionSkipPolicy.nextCycle("main", 1, 2);
        assertTrue(next.canContinue);
        assertEquals(2, next.nextIteration);
        assertFalse(CoraFlowPotionSkipPolicy.nextCycle("main", 2, 2).canContinue);
    }

    @Test public void noValidatedLoopFailsClosedRatherThanInventingSkipTarget() {
        assertFalse(CoraFlowPotionSkipPolicy.nextCycle(null, 1, -1).canContinue);
        assertFalse(CoraFlowPotionSkipPolicy.nextCycle("", 1, -1).canContinue);
        assertFalse(CoraFlowPotionSkipPolicy.nextCycle("main", 0, -1).canContinue);
        assertFalse(CoraFlowPotionSkipPolicy.nextCycle("main", 1, 0).canContinue);
        assertFalse(CoraFlowPotionSkipPolicy.nextCycle("main", Integer.MAX_VALUE, -1).canContinue);
    }
}
