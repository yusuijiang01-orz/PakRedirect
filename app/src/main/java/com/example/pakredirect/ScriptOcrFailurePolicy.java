package com.example.pakredirect;

/** Fail closed when the device OCR backend stops responding. */
final class ScriptOcrFailurePolicy {
    private ScriptOcrFailurePolicy() { }

    static String failRuntimeIfUnavailable(ScriptExecutionControl control, Throwable error) {
        if (control == null || !ScriptOcr.isUnavailable(error)) return null;
        String message = ScriptOcr.isCircuitOpen(error)
                ? "OCR 连续无响应，脚本已安全停止；未继续挂机或出售"
                : "OCR 识别超时，脚本已安全停止；未继续挂机或出售";
        control.fail(new IllegalStateException(message, error));
        return message;
    }
}
