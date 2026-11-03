package com.example.pakredirect;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ScriptOcrFailurePolicyTest {
    @Test public void oneOcrTimeoutReturnsNoResultWithoutFailingRuntime() {
        ScriptExecutionControl control = new ScriptExecutionControl();
        control.start();

        IllegalStateException timeout = new IllegalStateException("OCR 识别超时（等待 30000 毫秒）");
        assertTrue(ScriptOcrFailurePolicy.shouldRetryWithNoResult(timeout));
        assertEquals("OCR 暂时超时，本次按未识别处理，稍后重试",
                ScriptOcrFailurePolicy.retryMessage(timeout));
        assertNull(ScriptOcrFailurePolicy.failRuntimeIfCircuitOpen(control, timeout));

        assertEquals(ScriptExecutionControl.State.RUNNING, control.getState());
        assertNull(control.getFailure());
    }

    @Test public void ocrCircuitOpenStopsRuntimeAfterBoundedRecovery() {
        ScriptExecutionControl control = new ScriptExecutionControl();
        control.start();

        String message = ScriptOcrFailurePolicy.failRuntimeIfCircuitOpen(control,
                new IllegalStateException("OCR 连续超时，识别已暂停"));

        assertNotNull(message);
        assertTrue(message.contains("安全停止"));
        assertEquals(ScriptExecutionControl.State.FAILED, control.getState());
        assertNotNull(control.getFailure());
    }

    @Test public void ordinaryErrorsDoNotStopRuntime() {
        ScriptExecutionControl control = new ScriptExecutionControl();
        control.start();

        assertFalse(ScriptOcrFailurePolicy.shouldRetryWithNoResult(
                new IllegalStateException("unrelated error")));
        assertNull(ScriptOcrFailurePolicy.failRuntimeIfCircuitOpen(control,
                new IllegalStateException("unrelated error")));

        assertEquals(ScriptExecutionControl.State.RUNNING, control.getState());
        assertFalse(control.isStopRequested());
    }
}
