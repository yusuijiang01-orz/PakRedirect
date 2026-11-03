package com.example.pakredirect;

import org.junit.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import static org.junit.Assert.*;

public final class ScriptOcrFailurePolicyTest {
    @Test public void repeatedOcrTimeoutsLeaveRuntimeRunningAndReturnNoMatches() {
        ScriptExecutionControl control = new ScriptExecutionControl();
        control.start();
        Throwable[] errors = {
                new IllegalStateException("OCR 识别超时（等待 30000 毫秒）"),
                new IllegalStateException("OCR 连续超时，识别已暂停"),
                new RuntimeException("wrapped", new IllegalStateException("OCR 连续无响应"))
        };
        LuaTable candidates = new LuaTable();
        candidates.set(1, LuaValue.valueOf("目标"));
        for (Throwable error : errors) {
            assertTrue(ScriptOcrFailurePolicy.shouldRetryWithNoResult(error));
            assertEquals("OCR 暂时超时，本次按未识别处理，稍后重试",
                    ScriptOcrFailurePolicy.retryMessage(error));
            LuaTable emptyResult = new LuaTable();
            assertNull(LegacyLuaCompat.findOcrMatch(emptyResult, candidates, 0.8d));
            assertNull(LegacyLuaCompat.findOcrTextMatch(emptyResult, "目标", 0.8d));
            control.checkpoint();
            assertEquals(ScriptExecutionControl.State.RUNNING, control.getState());
            assertNull(control.getFailure());
        }
    }

    @Test public void unrelatedErrorsAreNotSwallowed() {
        assertFalse(ScriptOcrFailurePolicy.shouldRetryWithNoResult(
                new IllegalStateException("unrelated error")));
        assertNull(ScriptOcrFailurePolicy.retryMessage(new IllegalStateException("unrelated error")));
    }
}
