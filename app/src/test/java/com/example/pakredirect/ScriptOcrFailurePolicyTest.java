package com.example.pakredirect;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ScriptOcrFailurePolicyTest {
    @Test public void unavailableOcrFailsRuntimeBeforeLuaCanRetry() {
        ScriptExecutionControl control = new ScriptExecutionControl();
        control.start();

        String message = ScriptOcrFailurePolicy.failRuntimeIfUnavailable(control,
                new IllegalStateException("OCR 连续超时，识别已暂停"));

        assertNotNull(message);
        assertTrue(message.contains("安全停止"));
        assertEquals(ScriptExecutionControl.State.FAILED, control.getState());
        assertNotNull(control.getFailure());
    }

    @Test public void ordinaryErrorsDoNotStopRuntime() {
        ScriptExecutionControl control = new ScriptExecutionControl();
        control.start();

        assertNull(ScriptOcrFailurePolicy.failRuntimeIfUnavailable(control,
                new IllegalStateException("unrelated error")));

        assertEquals(ScriptExecutionControl.State.RUNNING, control.getState());
        assertFalse(control.isStopRequested());
    }
}
