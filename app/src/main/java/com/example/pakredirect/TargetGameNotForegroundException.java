package com.example.pakredirect;

/** The screen/input guard rejected an operation because the target is not confirmed foreground. */
final class TargetGameNotForegroundException extends IllegalStateException {
    TargetGameNotForegroundException(String message) {
        super(message);
    }
}
