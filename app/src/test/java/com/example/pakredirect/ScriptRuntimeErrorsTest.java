package com.example.pakredirect;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ScriptRuntimeErrorsTest {
    @Test public void screenshotFailureIncludesAndroidCallbackCause() {
        IllegalStateException errorCode = new IllegalStateException("系统截图失败，错误码 5");
        IllegalStateException captureError = new IllegalStateException("无法获取游戏画面", errorCode);

        assertEquals("无法获取游戏画面；底层原因：系统截图失败，错误码 5",
                ScriptRuntimeErrors.summarize(captureError));
    }

    @Test public void repeatedCauseMessageIsNotDuplicated() {
        IllegalStateException cause = new IllegalStateException("同一原因");
        IllegalStateException wrapper = new IllegalStateException("同一原因", cause);

        assertEquals("同一原因", ScriptRuntimeErrors.summarize(wrapper));
    }
}
