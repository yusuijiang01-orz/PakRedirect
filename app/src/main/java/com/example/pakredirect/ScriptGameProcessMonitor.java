package com.example.pakredirect;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

import java.util.List;

import top.niunaijun.blackbox.fake.frameworks.BActivityManager;

/** Process-level signal used to distinguish a game crash from a user switching apps. */
final class ScriptGameProcessMonitor {
    enum State { RUNNING, CRASHED, STOPPED, UNKNOWN }

    private static final String GAME = ScriptGameAdapter.TARGET_GAME_PACKAGE;
    private boolean observedRunningProcess;
    private boolean currentlyRunning;
    private long observedAtMillis;
    private long lastClassifiedExitMillis;
    private String observedProcessName;
    private State lastExitState = State.UNKNOWN;
    private boolean pendingCrashDetected;

    boolean hasObservedRunningProcess() { return observedRunningProcess; }

    void acknowledgeRecoveryAttempt() {
        lastExitState = State.STOPPED;
        currentlyRunning = false;
        pendingCrashDetected = false;
    }

    State inspect(Context context, String targetMode) {
        if (pendingCrashDetected) return State.CRASHED;
        State raw = ScriptToolsActivity.TARGET_MODE_DESKTOP.equals(targetMode)
                ? inspectDesktop(context) : inspectSandbox(context);
        if (raw == State.CRASHED) pendingCrashDetected = true;
        if (raw == State.RUNNING) {
            if (!currentlyRunning) {
                observedAtMillis = System.currentTimeMillis();
                lastExitState = State.UNKNOWN;
            }
            currentlyRunning = true;
            observedRunningProcess = true;
        }
        if (raw == State.STOPPED) {
            currentlyRunning = false;
            if (!observedRunningProcess) return State.UNKNOWN;
            return classifyExit(context, targetMode);
        }
        return raw;
    }

    private State inspectDesktop(Context context) {
        // A regular RYLUX install cannot read another UID's ApplicationExitInfo
        // (that API requires DUMP for cross-UID queries). Use the accessibility
        // error dialog, which names the target app, as the desktop crash signal.
        if (ScriptGameAdapter.isTargetCrashDialogVisible()) return State.CRASHED;
        return ScriptGameAdapter.isTargetGameForeground() ? State.RUNNING : State.UNKNOWN;
    }

    private State inspectSandbox(Context context) {
        return inspectSandboxProcess(context);
    }

    static State inspectSandboxProcess(Context context) {
        try {
            if (!((RyluxApplication) context.getApplicationContext()).isBlackBoxReady()) {
                return State.UNKNOWN;
            }
            top.niunaijun.blackbox.entity.am.RunningAppProcessInfo processes =
                    BActivityManager.get().getRunningAppProcesses(GAME, 0);
            if (processes == null || processes.mAppProcessInfoList == null) return State.UNKNOWN;
            if (processes.mAppProcessInfoList.isEmpty()) return State.STOPPED;
            observedProcessName = processes.mAppProcessInfoList.get(0).processName;
            return State.RUNNING;
        } catch (Exception unavailable) {
            return State.UNKNOWN;
        }
    }

    private State classifyExit(Context context, String targetMode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return State.UNKNOWN;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return State.UNKNOWN;
        String packageName = ScriptToolsActivity.TARGET_MODE_DESKTOP.equals(targetMode)
                ? GAME : context.getPackageName();
        try {
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(packageName, 0, 12);
            if (exits == null) return State.UNKNOWN;
            ApplicationExitInfo latest = null;
            for (ApplicationExitInfo exit : exits) {
                if (exit == null || exit.getTimestamp() < observedAtMillis - 2_000L) continue;
                String exitedProcess = exit.getProcessName();
                if (observedProcessName != null && exitedProcess != null
                        && !observedProcessName.equals(exitedProcess)) continue;
                if (latest == null || exit.getTimestamp() > latest.getTimestamp()) latest = exit;
            }
            if (latest == null) return State.STOPPED;
            if (latest.getTimestamp() <= lastClassifiedExitMillis) {
                return lastExitState == State.UNKNOWN ? State.STOPPED : lastExitState;
            }
            lastClassifiedExitMillis = latest.getTimestamp();
            int reason = latest.getReason();
            if (reason == ApplicationExitInfo.REASON_CRASH
                    || reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                    || reason == ApplicationExitInfo.REASON_ANR) lastExitState = State.CRASHED;
            else lastExitState = State.STOPPED;
            return lastExitState;
        } catch (RuntimeException unavailable) {
            return State.UNKNOWN;
        }
    }
}
