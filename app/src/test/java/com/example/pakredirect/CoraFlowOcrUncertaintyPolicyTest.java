package com.example.pakredirect;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CoraFlowOcrUncertaintyPolicyTest {
    @Test public void recognizesExactPotionOcrFailureFromDeviceLog() {
        IllegalStateException failure = new IllegalStateException(
                "第 1 行第 2 列命中药瓶图标但数量 OCR 不可靠（7，69.0%）；停止统计。");
        assertTrue(CoraFlowOcrUncertaintyPolicy.isUncertain(failure));
        assertTrue(CoraFlowOcrUncertaintyPolicy.isUncertain(
                new RuntimeException("wrapped", failure)));
    }

    @Test public void recognizesMultiframeMismatchAndLegacyFailures() {
        assertTrue(CoraFlowOcrUncertaintyPolicy.isUncertain(
                new IllegalStateException("背包药瓶数量多帧一致性未通过")));
        assertTrue(CoraFlowOcrUncertaintyPolicy.isUncertain(
                new IllegalStateException("药品数量未确认")));
        assertTrue(CoraFlowOcrUncertaintyPolicy.isUncertain(
                new IllegalStateException("第 3 格药瓶识别不确定")));
    }

    @Test public void doesNotHideOtherRuntimeFailures() {
        assertFalse(CoraFlowOcrUncertaintyPolicy.isUncertain(null));
        assertFalse(CoraFlowOcrUncertaintyPolicy.isUncertain(
                new IllegalStateException("工作流缺少物品图标素材")));
    }
}
