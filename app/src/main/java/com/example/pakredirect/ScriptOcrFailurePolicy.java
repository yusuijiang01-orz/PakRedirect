package com.example.pakredirect;

/** Treat bounded OCR timeouts as an unavailable result, without stopping the script. */
final class ScriptOcrFailurePolicy {
    private ScriptOcrFailurePolicy() { }

    static boolean shouldRetryWithNoResult(Throwable error) {
        return hasMessagePrefix(error, "OCR 识别超时")
                || hasMessagePrefix(error, "OCR 连续超时")
                || hasMessagePrefix(error, "OCR 连续无响应");
    }

    static String retryMessage(Throwable error) {
        return shouldRetryWithNoResult(error)
                ? "OCR 暂时超时，本次按未识别处理，稍后重试" : null;
    }

    private static boolean hasMessagePrefix(Throwable error, String prefix) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.startsWith(prefix)) return true;
        }
        return false;
    }
}
