package com.example.pakredirect;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class CoraFlowInventoryDetailCloseTest {
    @Test public void usesTheRecoveredLuaInventoryDetailCloseTarget() {
        assertEquals(1184, CoraFlowWorkflowRuntime.INVENTORY_DETAIL_CLOSE_X);
        assertEquals(638, CoraFlowWorkflowRuntime.INVENTORY_DETAIL_CLOSE_Y);
    }

    @Test public void doesNotUseTheHeaderPointThatFailedToDismissThePanel() {
        assertNotEquals(1200, CoraFlowWorkflowRuntime.INVENTORY_DETAIL_CLOSE_X);
        assertNotEquals(80, CoraFlowWorkflowRuntime.INVENTORY_DETAIL_CLOSE_Y);
    }
}
