package com.example.pakredirect;

/** Bounded, fail-closed decision policy for restarting a crashed game process. */
final class ScriptAutoRecoveryPolicy {
    static final int MAX_RESTARTS_PER_WINDOW = 5;
    static final long RESTART_WINDOW_MS = 15 * 60_000L;

    private ScriptAutoRecoveryPolicy() { }

    static boolean shouldRestart(boolean enabled, boolean gameWasObserved,
                                 boolean stopRequested, ScriptGameProcessMonitor.State processState,
                                 int restartsInWindow) {
        return enabled && gameWasObserved && !stopRequested
                && processState == ScriptGameProcessMonitor.State.CRASHED
                && restartsInWindow < MAX_RESTARTS_PER_WINDOW;
    }

    static boolean shouldRestartStalledSandbox(boolean enabled, boolean gameWasObserved,
                                                boolean stopRequested, boolean sandboxTarget,
                                                int restartsInWindow) {
        return enabled && gameWasObserved && !stopRequested && sandboxTarget
                && restartsInWindow < MAX_RESTARTS_PER_WINDOW;
    }

    static long backoffMs(int restartNumber) {
        if (restartNumber <= 1) return 3_000L;
        if (restartNumber == 2) return 8_000L;
        if (restartNumber == 3) return 20_000L;
        return 45_000L;
    }
}
