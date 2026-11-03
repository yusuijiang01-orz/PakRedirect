package com.example.pakredirect;

/** Retry one isolated OCR timeout; fail closed if the bounded OCR circuit opens. */
final class ScriptOcrFailurePolicy {
    private ScriptOcrFailurePolicy() { }

    static boolean shouldRetryWithNoResult(Throwable error) {
        return hasMessagePrefix(error, "OCR 识别超时")
                && !isCircuitOpen(error);
    }

    static String retryMessage(Throwable error) {
        return shouldRetryWithNoResult(error)
                ? "OCR 暂时超时，本次按未识别处理，稍后重试" : null;
    }

    static String failRuntimeIfCircuitOpen(ScriptExecutionControl control, Throwable error) {
        if (control == null || !isCircuitOpen(error)) return null;
        String message = "OCR 连续无响应，脚本已安全停止；未继续挂机或出售";
        control.fail(new IllegalStateException(message, error));
        return message;
    }

    private static boolean isCircuitOpen(Throwable error) {
        return hasMessagePrefix(error, "OCR 连续超时")
                || hasMessagePrefix(error, "OCR 连续无响应");
    }

    private static boolean hasMessagePrefix(Throwable error, String prefix) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.startsWith(prefix)) return true;
        }
        return false;
    }
}
