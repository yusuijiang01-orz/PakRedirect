package com.example.pakredirect;

/** Coalesces the recovered script's periodic HUD updates into concise status changes. */
final class ScriptHudStatus {
    private static final long MIN_INTERVAL_MS = 1_000L;
    private String lastMessage = "";
    private long lastPublishedAt = Long.MIN_VALUE;

    synchronized String next(String rawMessage, long nowMs) {
        String message = rawMessage == null ? "" : rawMessage.trim();
        if (message.isEmpty() || message.equals(lastMessage)) return null;
        if (lastPublishedAt != Long.MIN_VALUE && nowMs - lastPublishedAt < MIN_INTERVAL_MS) return null;
        lastMessage = message;
        lastPublishedAt = nowMs;
        return message;
    }
}
