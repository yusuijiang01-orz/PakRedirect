package com.example.pakredirect;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ScriptOcrAvailabilityTest {
    @Test public void recognizesInferenceTimeoutAsUnavailable() {
        assertTrue(ScriptOcr.isUnavailable(
                new IllegalStateException("OCR 识别超时（等待 5000 毫秒）")));
    }

    @Test public void recognizesCircuitOpenAsUnavailable() {
        assertTrue(ScriptOcr.isUnavailable(new RuntimeException("wrapper",
                new IllegalStateException("OCR 连续超时，识别已暂停；为避免脚本卡住，已停止后续识别"))));
    }

    @Test public void leavesUnrelatedOcrErrorsFatal() {
        assertFalse(ScriptOcr.isUnavailable(new IllegalStateException("OCR 图片尺寸无效")));
    }
}
