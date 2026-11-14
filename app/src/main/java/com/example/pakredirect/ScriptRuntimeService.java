package com.example.pakredirect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Explicitly started foreground host for the recovered Lua script. */
public final class ScriptRuntimeService extends Service {
    public static final String ACTION_START = "com.example.pakredirect.script.START";
    public static final String ACTION_PAUSE = "com.example.pakredirect.script.PAUSE";
    public static final String ACTION_RESUME = "com.example.pakredirect.script.RESUME";
    public static final String ACTION_STOP = "com.example.pakredirect.script.STOP";
    public static final String ACTION_STATUS = "com.example.pakredirect.script.STATUS";
    public static final String EXTRA_CONFIG = "config";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_MESSAGE = "message";
    private static final String CHANNEL = "script_runtime";
    private static final int NOTIFICATION_ID = 49;
    private static final String DIAGNOSTICS = "script_runtime_diagnostics";
    private static final String LAST_ERROR = "last_error";
    private static final String STARTUP_AGREE_TEMPLATE =
            "script_modules/脚本/素材库/汉化条款同意按钮.png";
    // The same world HUD anchors used by 在主页面 in 通用数据.lua (1280x720).
    private static final String WORLD_SETTINGS_POINTS =
            "1213|662|c69e63-111111,1212|651|e7cb84-111111,1207|646|f7df8c-111111,1199|646|f7e794-111111,1196|650|f7df8c-111111,1195|663|dec37b-111111,1203|666|cea66b-111111,1227|670|b5ae9c-111111,1227|660|d6cfbd-111111,1225|659|e7e3d6-111111,1222|648|c6bead-111111,1223|641|d6cbbd-111111,1218|636|e7dfce-111111";
    private static final String WORLD_SKILL_POINTS =
            "1250|662|eff7ff-222222,1248|660|f7fbff-222222,1240|659|f7fbff-222222,1233|659|f7fbff-222222,1230|660|f7fbff-222222,1237|662|eff7ff-222222,1246|662|eff7ff-222222,1243|678|9cd7ef-222222,1239|675|addfef-222222,1231|674|b5e3ef-222222,1224|674|b5e3ef-222222,1222|675|addfef-222222,1224|678|9cd7ef-222222,1234|676|a5dfef-222222,1240|677|a5dbef-222222,1237|250|bdb2ef-222222,1234|246|cecff7-222222,1215|249|bdbaef-222222,1218|246|cecff7-222222,1219|232|ffffff-222222,1216|228|ffffff-222222,1234|231|ffffff-222222,1238|228|ffffff-222222";
    private static final long STALL_SCREEN_SAMPLE_INTERVAL_MS = 5_000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "rylux-lua-host");
        thread.setDaemon(true);
        return thread;
    });
    private volatile ScriptExecutionControl control;
    private volatile String lastMessage = "就绪";
    private volatile String state = ScriptExecutionControl.State.IDLE.name();
    private final ScriptWebRouteBridge routeBridge = new ScriptWebRouteBridge();
    private SandboxBagSnapshotRelay bagSnapshotRelay;
    private ScriptRuntimeLog runtimeLog;
    private volatile boolean workerRunning;
    private volatile boolean entitlementCheckPending;
    private volatile int entitlementGeneration;
    private volatile boolean serviceDestroyed;
    private String pendingRestartConfig;
    private int latestStartId;
    private long lastMembershipRefreshAt;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable entitlementWatchdog = new Runnable() {
        @Override public void run() {
            if (workerRunning && !MembershipAccess.hasCachedAccess(ScriptRuntimeService.this)) {
                stopForExpiredMembership();
                return;
            }
            if (workerRunning && System.currentTimeMillis() - lastMembershipRefreshAt >= 60_000L) {
                lastMembershipRefreshAt = System.currentTimeMillis();
                MembershipAccess.refresh(ScriptRuntimeService.this, result -> {
                    if (serviceDestroyed) return;
                    if (result != null && result.requestOk
                            && (!result.success || !result.allowed)) {
                        stopForMembershipDenied(result.message + "，脚本与汉化已停用");
                    }
                });
            }
            mainHandler.postDelayed(this, 10_000L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        runtimeLog = new ScriptRuntimeLog(new java.io.File(getFilesDir(), "logs"));
        bagSnapshotRelay = new SandboxBagSnapshotRelay(this);
        createNotificationChannel();
        mainHandler.postDelayed(entitlementWatchdog, 10_000L);
    }

    @Override public synchronized int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        String action = intent == null ? null : intent.getAction();
        startForeground(NOTIFICATION_ID, notification("准备处理脚本命令"));
        if (ACTION_STOP.equals(action)) {
            entitlementGeneration++;
            entitlementCheckPending = false;
            pendingRestartConfig = null;
            if (control != null) control.requestStop();
            publish("正在停止");
            if (control == null) {
                stopForeground(true);
                stopSelf(startId);
            }
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action) && control == null) {
            publish("脚本尚未启动");
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) startScript(intent.getStringExtra(EXTRA_CONFIG));
        else if (ACTION_PAUSE.equals(action)) { if (control != null) control.pause(); publish("已暂停"); }
        else if (ACTION_RESUME.equals(action)) resumeScriptWithEntitlement();
        return START_NOT_STICKY;
    }

    private synchronized void resumeScriptWithEntitlement() {
        ScriptExecutionControl current = control;
        if (current == null) {
            publish("脚本尚未启动");
            return;
        }
        int checkGeneration = ++entitlementGeneration;
        MembershipAccess.refresh(this, result -> {
            synchronized (ScriptRuntimeService.this) {
                if (checkGeneration != entitlementGeneration) return;
                if (result == null || !result.allowed) {
                    stopForMembershipDenied(result == null
                            ? "无法验证会员状态，脚本与汉化已停用"
                            : result.message + "，脚本与汉化已停用");
                    return;
                }
                ScriptExecutionControl running = control;
                if (running != null) {
                    running.resume();
                    publish("继续运行");
                }
            }
        });
    }

    private synchronized void startScript(String json) {
        if (control != null && (control.getState() == ScriptExecutionControl.State.RUNNING
                || control.getState() == ScriptExecutionControl.State.PAUSED
                || control.getState() == ScriptExecutionControl.State.STOP_REQUESTED)) {
            publish("脚本已在运行");
            return;
        }
        if (entitlementCheckPending) {
            pendingRestartConfig = json;
            publish("正在验证会员状态，请稍候…");
            return;
        }
        entitlementCheckPending = true;
        int checkGeneration = ++entitlementGeneration;
        lastMembershipRefreshAt = System.currentTimeMillis();
        publish("正在验证 VIP / 体验会员权限…");
        MembershipAccess.refresh(this, result -> {
            synchronized (ScriptRuntimeService.this) {
                if (checkGeneration != entitlementGeneration) return;
                entitlementCheckPending = false;
                if (result == null || !result.allowed) {
                    String message = result == null
                            ? "无法验证会员状态，脚本未启动"
                            : result.message + "，脚本未启动";
                    LocalizationSettings.setEnabled(ScriptRuntimeService.this, false);
                    stopService(new Intent(ScriptRuntimeService.this, InterceptService.class));
                    publish(message);
                    stopService(new Intent(ScriptRuntimeService.this, ScriptControlOverlayService.class));
                    stopForeground(true);
                    stopSelf(latestStartId);
                    return;
                }
                String requested = pendingRestartConfig == null ? json : pendingRestartConfig;
                pendingRestartConfig = null;
                startScriptAuthorized(requested);
            }
        });
    }

    private synchronized void startScriptAuthorized(String json) {
        if (control != null && (control.getState() == ScriptExecutionControl.State.RUNNING
                || control.getState() == ScriptExecutionControl.State.PAUSED
                || control.getState() == ScriptExecutionControl.State.STOP_REQUESTED)) {
            publish("脚本已在运行");
            return;
        }
        // A STOPPED broadcast can reach the overlay before the previous Lua
        // worker has run its finally block. Queue a fast restart until that
        // worker has fully left; it must not stop the new foreground service.
        if (workerRunning) {
            pendingRestartConfig = json;
            publish("等待上一轮脚本退出后重新启动");
            return;
        }
        JSONObject config;
        try { config = new JSONObject(json == null ? "{}" : json); }
        catch (Exception invalid) { publish("启动失败：方案配置无效"); stopSelf(latestStartId); return; }
        ScriptGameAdapter.setTargetMode(config.optString("目标运行环境",
                ScriptToolsActivity.TARGET_MODE_SANDBOX));
        runtimeLog.configure(config.optBoolean(ScriptRuntimeLog.AUTO_SAVE, true),
                config.optBoolean(ScriptRuntimeLog.SOURCE_CONTEXT, true));
        if (!ScriptGameAdapter.isConnected()) {
            publish("启动失败：请先启用 RYLUX 游戏输入服务");
            stopSelf(latestStartId);
            return;
        }
        routeBridge.close();
        bagSnapshotRelay.close();
        control = new ScriptExecutionControl();
        control.start();
        final ScriptExecutionControl runControl = control;
        workerRunning = true;
        state = ScriptExecutionControl.State.RUNNING.name();
        lastMessage = "RYLUX CoraFlow JSON 执行器已启动";
        startForeground(NOTIFICATION_ID, notification(lastMessage));
        publish(lastMessage);
        executor.execute(() -> {
            try {
                runWithCrashRecovery(config, runControl);
            } finally {
                finishWorker(runControl);
            }
        });
    }

    private void runWithCrashRecovery(JSONObject config, ScriptExecutionControl runControl) {
        boolean autoLogin = config.optBoolean("自动登录", true);
        String targetMode = config.optString("目标运行环境", ScriptToolsActivity.TARGET_MODE_SANDBOX);
        ScriptGameProcessMonitor processMonitor = new ScriptGameProcessMonitor();
        boolean gameWasObserved = false;
        int restartsInWindow = 0;
        long restartWindowStarted = android.os.SystemClock.elapsedRealtime();

        while (!runControl.isStopRequested()) {
            try {
                gameWasObserved |= runMonitoredAttempt(config, runControl, targetMode,
                        processMonitor);
                Throwable workerFailure = runControl.getFailure();
                if (workerFailure != null) throw workerFailure;
                if (runControl.isStopRequested()) {
                    runControl.complete();
                    publish("已停止");
                } else {
                    runControl.complete();
                    publish("脚本已完成");
                }
                return;
            } catch (Throwable failure) {
                if (runControl.isStopRequested()) {
                    runControl.complete();
                    publish("已停止");
                    return;
                }

                ScriptGameProcessMonitor.State processState = processMonitor.inspect(this, targetMode);
                gameWasObserved |= processMonitor.hasObservedRunningProcess();
                Throwable currentWorkerFailure = runControl.getFailure();
                boolean stalledSandbox = isSandboxScreenStall(targetMode, failure,
                        currentWorkerFailure);
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - restartWindowStarted >= ScriptAutoRecoveryPolicy.RESTART_WINDOW_MS) {
                    restartWindowStarted = now;
                    restartsInWindow = 0;
                }
                boolean shouldRestart = ScriptAutoRecoveryPolicy.shouldRestart(autoLogin,
                        gameWasObserved, false, processState, restartsInWindow)
                        || ScriptAutoRecoveryPolicy.shouldRestartStalledSandbox(autoLogin,
                        gameWasObserved, false,
                        ScriptToolsActivity.TARGET_MODE_SANDBOX.equals(targetMode) && stalledSandbox,
                        restartsInWindow);
                if (shouldRestart) {
                    restartsInWindow++;
                    processMonitor.acknowledgeRecoveryAttempt();
                    if (runControl.getState() == ScriptExecutionControl.State.FAILED) {
                        if (runControl.hasActiveWorkers()) {
                            reportFailure(new IllegalStateException(
                                    "游戏已退出，但仍有 Lua 工作线程未结束；为避免重复触控，自动恢复已停止"));
                            return;
                        }
                        try {
                            runControl.start();
                        } catch (RuntimeException cannotRestart) {
                            reportFailure(new IllegalStateException(
                                    "游戏已退出，但上一轮 Lua 工作线程尚未完全结束；为避免重复触控，自动恢复已停止",
                                    cannotRestart));
                            return;
                        }
                    }
                    long backoff = ScriptAutoRecoveryPolicy.backoffMs(restartsInWindow);
                    publish((stalledSandbox ? "沙箱画面连续 2 分钟基本无变化，"
                            : "已检测到游戏崩溃/无响应，")
                            + (backoff / 1000) + " 秒后自动重启并重新登录（"
                            + restartsInWindow + "/" + ScriptAutoRecoveryPolicy.MAX_RESTARTS_PER_WINDOW + "）");
                    try {
                        runControl.sleep(backoff);
                    } catch (ScriptExecutionControl.StopRequestedException stopped) {
                        runControl.complete();
                        publish("已停止");
                        return;
                    }
                    if (ScriptToolsActivity.TARGET_MODE_DESKTOP.equals(targetMode)
                            && processState == ScriptGameProcessMonitor.State.CRASHED) {
                        boolean dismissed = ScriptGameAdapter.dismissTargetCrashDialog();
                        try { runControl.sleep(500L); }
                        catch (ScriptExecutionControl.StopRequestedException stopped) {
                            runControl.complete();
                            publish("已停止");
                            return;
                        }
                        if (!dismissed && ScriptGameAdapter.isTargetCrashDialogVisible()) {
                            IllegalStateException blocked = new IllegalStateException(
                                    "已识别游戏崩溃提示，但无法自动关闭系统弹窗；请手动点“关闭应用”后再启动脚本");
                            if (runControl.fail(blocked)) reportFailure(blocked);
                            return;
                        }
                    }
                    boolean sandboxNeedsStop = ScriptToolsActivity.TARGET_MODE_SANDBOX
                            .equals(targetMode) && (stalledSandbox
                            || processState == ScriptGameProcessMonitor.State.CRASHED);
                    if (sandboxNeedsStop) {
                        try {
                            if (!stopSandboxGameForRecovery(runControl)) {
                                IllegalStateException stopFailure = new IllegalStateException(
                                        "已检测到沙箱游戏卡死/无响应，但 BlackBox 未能确认游戏进程已停止；已中止重启以避免叠加启动");
                                if (runControl.fail(stopFailure)) reportFailure(stopFailure);
                                return;
                            }
                        } catch (ScriptExecutionControl.StopRequestedException stopped) {
                            runControl.complete();
                            publish("已停止");
                            return;
                        }
                    }
                    if (!launchConfiguredGame(targetMode)) {
                        IllegalStateException launchFailure = new IllegalStateException(
                                "自动恢复无法启动所选的封神榜客户端；请检查安装状态与目标来源");
                        if (runControl.fail(launchFailure)) reportFailure(launchFailure);
                        return;
                    }
                    publish("游戏已重新启动，等待登录流程进入主界面后继续挂机方案");
                    continue;
                }

                if (autoLogin && gameWasObserved
                        && processState == ScriptGameProcessMonitor.State.RUNNING
                        && isForegroundWaitFailure(failure)) {
                    if (runControl.getState() == ScriptExecutionControl.State.FAILED) {
                        if (runControl.hasActiveWorkers()) {
                            reportFailure(new IllegalStateException(
                                    "游戏仍在运行，但仍有 Lua 工作线程未结束；已停止以避免并发触控"));
                            return;
                        }
                        try { runControl.start(); }
                        catch (RuntimeException cannotResume) {
                            reportFailure(new IllegalStateException(
                                    "游戏仍在运行，但旧 Lua 工作线程未退出；已停止以避免并发触控",
                                    cannotResume));
                            return;
                        }
                    }
                    publish("游戏进程仍在运行但暂时不在前台；等待游戏返回，不会强行重开或抢占其他应用");
                    continue;
                }

                Throwable workerFailure = runControl.getFailure();
                Throwable reported = workerFailure == null ? failure : workerFailure;
                if (runControl.fail(reported)) reportFailure(reported);
                else if (runControl.getFailure() != null) reportFailure(runControl.getFailure());
                else {
                    runControl.complete();
                    if (processState == ScriptGameProcessMonitor.State.UNKNOWN && autoLogin) {
                        publish("自动恢复未执行：系统无法确认游戏进程状态；请检查游戏是否仍在运行后手动重启脚本");
                    } else if (failure instanceof StartupDisplayCaptureException) {
                        saveFailure(failure);
                        publish("启动失败：显示截图检查失败：" + rootMessage(failure.getCause()));
                    } else if (failure instanceof StartupTargetForegroundException) {
                        saveFailure(failure);
                        publish("启动失败：" + failure.getMessage());
                    } else {
                        reportFailure(failure);
                    }
                }
                return;
            }
        }
        runControl.complete();
        publish("已停止");
    }

    private boolean runMonitoredAttempt(JSONObject config, ScriptExecutionControl runControl,
                                        String targetMode,
                                        ScriptGameProcessMonitor processMonitor) throws Exception {
        AtomicBoolean watching = new AtomicBoolean(true);
        AtomicBoolean gameObserved = new AtomicBoolean(false);
        AtomicReference<Throwable> watchdogFailure = new AtomicReference<>();
        boolean sandboxTarget = ScriptToolsActivity.TARGET_MODE_SANDBOX.equals(targetMode);
        boolean autoLogin = config.optBoolean("自动登录", true);
        ScriptFrameStallDetector stallDetector = new ScriptFrameStallDetector();
        Thread watcher = new Thread(() -> {
            // Let the workflow take its initial screen sample first. Android throttles
            // accessibility screenshots, so racing the watchdog against the first
            // recognition node can make both captures fail as "interval too short".
            long nextScreenSampleAt = android.os.SystemClock.elapsedRealtime()
                    + STALL_SCREEN_SAMPLE_INTERVAL_MS;
            long lastStallStatusAt = 0L;
            while (watching.get() && !runControl.isStopRequested()) {
                ScriptGameProcessMonitor.State processState = processMonitor.inspect(this, targetMode);
                if (processState == ScriptGameProcessMonitor.State.RUNNING) {
                    gameObserved.set(true);
                } else if (processState == ScriptGameProcessMonitor.State.CRASHED
                        && sandboxTarget && processMonitor.hasUnresponsiveBlackBoxProcess()) {
                    // A newly observed BlackBox ANR is itself proof that the
                    // configured guest was running, even if the watcher missed
                    // its last healthy sample.
                    gameObserved.set(true);
                }
                if (processState == ScriptGameProcessMonitor.State.CRASHED
                        && gameObserved.get()) {
                    GameProcessExitedException crashed = new GameProcessExitedException();
                    watchdogFailure.compareAndSet(null, crashed);
                    if (sandboxTarget && processMonitor.hasUnresponsiveBlackBoxProcess()) {
                        Log.w("RYLUX-Lua", "BlackBox guest process is not responding: "
                                + processMonitor.getUnresponsiveBlackBoxProcessName());
                    }
                    if (runControl.fail(crashed)) {
                        publish(sandboxTarget && processMonitor.hasUnresponsiveBlackBoxProcess()
                                ? "检测到沙箱游戏无响应（Android ANR），正在自动恢复登录"
                                : "检测到游戏崩溃/无响应，正在按自动登录设置恢复");
                    }
                    return;
                }
                ScriptExecutionControl.State controlState = runControl.getState();
                boolean keepWatchingAfterLuaFailure = sandboxTarget && autoLogin
                        && controlState == ScriptExecutionControl.State.FAILED;
                if (sandboxTarget && (controlState == ScriptExecutionControl.State.RUNNING
                        || keepWatchingAfterLuaFailure)
                        && processState == ScriptGameProcessMonitor.State.RUNNING
                        && ScriptGameAdapter.isTargetGameForeground()) {
                    long sampleAt = android.os.SystemClock.elapsedRealtime();
                    if (sampleAt >= nextScreenSampleAt) {
                        nextScreenSampleAt = sampleAt + STALL_SCREEN_SAMPLE_INTERVAL_MS;
                        Bitmap frame = null;
                        try {
                            frame = ScriptGameAdapter.requireConnected(runControl).captureDisplay(5_000L);
                            ScriptFrameStallDetector.Observation observation =
                                    stallDetector.observe(frame, android.os.SystemClock.elapsedRealtime());
                            if (observation.initialized) {
                                Log.i("RYLUX-Lua", "sandbox screen watchdog similarity="
                                        + String.format(java.util.Locale.US, "%.4f", observation.similarity)
                                        + " stableSeconds=" + (observation.stableForMs / 1_000L));
                                if (observation.stableForMs >= 30_000L
                                        && android.os.SystemClock.elapsedRealtime() - lastStallStatusAt
                                        >= 30_000L) {
                                    lastStallStatusAt = android.os.SystemClock.elapsedRealtime();
                                    publish("沙箱画面持续无变化："
                                            + (observation.stableForMs / 1_000L) + " 秒 / 120 秒");
                                }
                            }
                            if (observation.stalled) {
                                GameScreenStalledException stalled = new GameScreenStalledException(
                                        observation.similarity, observation.stableForMs);
                                watchdogFailure.compareAndSet(null, stalled);
                                if (runControl.fail(stalled)) {
                                    Log.w("RYLUX-Lua", stalled.getMessage());
                                    publish("检测到沙箱游戏画面连续 2 分钟无变化，正在自动恢复登录");
                                } else if (watchdogFailure.get() == stalled) {
                                    Log.w("RYLUX-Lua", stalled.getMessage());
                                    publish("挂机脚本本轮已退出，但沙箱仍卡在同一画面；正在启动自动恢复");
                                }
                                return;
                            }
                        } catch (Exception captureFailure) {
                            stallDetector.reset();
                            if (watching.get() && !runControl.isStopRequested()) {
                                Log.w("RYLUX-Lua", "sandbox screen watchdog screenshot failed; timer reset",
                                        captureFailure);
                            }
                        } finally {
                            if (frame != null && !frame.isRecycled()) frame.recycle();
                        }
                    }
                } else {
                    stallDetector.reset();
                    lastStallStatusAt = 0L;
                }
                try { Thread.sleep(1_000L); }
                catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "rylux-game-process-watch");
        watcher.setDaemon(true);
        watcher.start();
        try {
            CoraFlowWorkflowRuntime.WorkflowPackage workflow =
                    CoraFlowWorkflowUpdateManager.load(this, config, this::publish);
            try {
                if (CoraFlowWorkflowRuntime.startsWithStartupGate(workflow)) {
                    publish("启动流程先检查游戏；未启动时由工作流启动，确认前台后再截图和操作");
                } else {
                    awaitTargetGameForeground();
                    gameObserved.set(true);
                    verifyStartupDisplayCapture();
                    waitForFirstUseTermsIfNeeded();
                }
                new CoraFlowWorkflowRuntime(workflow, runControl, this::publish,
                        () -> launchConfiguredGame(targetMode)).run();
                Throwable workerFailure = runControl.getFailure();
                if (workerFailure != null) throw workerFailure;
            } catch (Throwable scriptFailure) {
                if (sandboxTarget && autoLogin && gameObserved.get()
                        && !runControl.isStopRequested()) {
                    String detail = rootMessage(scriptFailure);
                    Log.e("RYLUX-Lua", "CoraFlow JSON workflow attempt failed; recovery monitor remains active: "
                            + detail, scriptFailure);
                    awaitSandboxWatchdogAfterLuaFailure(runControl, watchdogFailure, watcher, detail);
                }
                if (scriptFailure instanceof Exception) throw (Exception) scriptFailure;
                if (scriptFailure instanceof Error) throw (Error) scriptFailure;
                throw new IllegalStateException("CoraFlow JSON 工作流异常退出", scriptFailure);
            } finally {
                workflow.close();
            }
            return gameObserved.get();
        } finally {
            watching.set(false);
            watcher.interrupt();
            try { watcher.join(1_500L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    /** Keep the sandbox freeze monitor alive after a workflow error while auto-login remains enabled. */
    private void awaitSandboxWatchdogAfterLuaFailure(ScriptExecutionControl runControl,
                                                     AtomicReference<Throwable> watchdogFailure,
                                                     Thread watcher, String failureDetail) throws Exception {
        long workerDrainDeadline = android.os.SystemClock.elapsedRealtime() + 10_000L;
        while (runControl.hasActiveWorkers() && !runControl.isStopRequested()
                && android.os.SystemClock.elapsedRealtime() < workerDrainDeadline) {
            Thread.sleep(100L);
        }
        if (runControl.isStopRequested()) throw new ScriptExecutionControl.StopRequestedException();
        if (runControl.hasActiveWorkers()) {
            throw new IllegalStateException("Lua 工作线程未退出，无法安全等待沙箱卡屏恢复");
        }

        Throwable detected = watchdogFailure.get();
        if (detected != null) throwWatchdogFailure(detected);
        if (runControl.getState() == ScriptExecutionControl.State.FAILED) runControl.start();
        publish("工作流本轮失败：" + failureDetail
                + "；自动登录仍开启，继续监控沙箱画面，卡住 2 分钟后自动重启");

        while (!runControl.isStopRequested()) {
            detected = watchdogFailure.get();
            if (detected != null) throwWatchdogFailure(detected);
            if (!watcher.isAlive()) {
                throw new IllegalStateException("沙箱画面监控线程意外退出，无法继续自动恢复");
            }
            runControl.sleep(250L);
        }
        throw new ScriptExecutionControl.StopRequestedException();
    }

    private static void throwWatchdogFailure(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new IllegalStateException("沙箱自动恢复监控失败", failure);
    }

    private static final class GameProcessExitedException extends Exception {
        GameProcessExitedException() { super("目标游戏进程已退出"); }
    }

    private static final class GameScreenStalledException extends Exception {
        GameScreenStalledException(double similarity, long stableForMs) {
            super("沙箱游戏画面超过 2 分钟有 99% 以上内容未变化（相似度 "
                    + String.format(java.util.Locale.US, "%.2f%%", similarity * 100d)
                    + "，持续 " + (stableForMs / 1_000L) + " 秒）");
        }
    }

    private static boolean isSandboxScreenStall(String targetMode, Throwable failure,
                                                Throwable workerFailure) {
        if (!ScriptToolsActivity.TARGET_MODE_SANDBOX.equals(targetMode)) return false;
        for (Throwable current = workerFailure; current != null; current = current.getCause()) {
            if (current instanceof GameScreenStalledException) return true;
        }
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof GameScreenStalledException) return true;
        }
        return false;
    }

    private boolean stopSandboxGameForRecovery(ScriptExecutionControl runControl) {
        try {
            top.niunaijun.blackbox.BlackBoxCore.get()
                    .stopPackage(ScriptGameAdapter.TARGET_GAME_PACKAGE, 0);
            long deadline = android.os.SystemClock.elapsedRealtime() + 10_000L;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                runControl.checkpoint();
                ScriptGameProcessMonitor.State processState =
                        ScriptGameProcessMonitor.inspectSandboxProcess(this);
                if (processState == ScriptGameProcessMonitor.State.STOPPED) {
                    publish("沙箱游戏进程已停止，准备重新启动");
                    return true;
                }
                runControl.sleep(250L);
            }
            Log.e("RYLUX-Lua", "BlackBox did not stop the stalled game process within 10 seconds");
            return false;
        } catch (ScriptExecutionControl.StopRequestedException stopped) {
            throw stopped;
        } catch (Exception failure) {
            Log.e("RYLUX-Lua", "Unable to stop stalled sandbox game", failure);
            return false;
        }
    }

    private boolean launchConfiguredGame(String targetMode) {
        if (!ScriptToolsActivity.TARGET_MODE_DESKTOP.equals(targetMode)) {
            try {
                return top.niunaijun.blackbox.BlackBoxCore.get()
                        .launchApk(ScriptGameAdapter.TARGET_GAME_PACKAGE, 0);
            } catch (RuntimeException unavailable) {
                Log.w("RYLUX-Lua", "自动恢复无法启动沙箱游戏", unavailable);
                return false;
            }
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(
                ScriptGameAdapter.TARGET_GAME_PACKAGE);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(launch);
            return true;
        } catch (RuntimeException unavailable) {
            Log.w("RYLUX-Lua", "自动恢复无法启动桌面游戏", unavailable);
            return false;
        }
    }

    private static boolean isForegroundWaitFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof StartupTargetForegroundException
                    || (current.getMessage() != null
                    && (current.getMessage().contains("不在前台")
                    || current.getMessage().contains("等待所选游戏回到前台")))) return true;
        }
        return false;
    }

    private synchronized void finishWorker(ScriptExecutionControl finished) {
        if (control != finished) return;
        workerRunning = false;
        routeBridge.close();
        if (bagSnapshotRelay != null) bagSnapshotRelay.close();
        String restart = pendingRestartConfig;
        pendingRestartConfig = null;
        if (restart != null) {
            startScript(restart);
            return;
        }
        stopForeground(true);
        stopSelf(latestStartId);
    }

    private synchronized void stopForExpiredMembership() {
        stopForMembershipDenied("VIP / 体验会员已到期，脚本与汉化已停用");
    }

    private synchronized void stopForMembershipDenied(String message) {
        ScriptExecutionControl current = control;
        if (current != null) current.requestStop();
        pendingRestartConfig = null;
        LocalizationSettings.setEnabled(this, false);
        stopService(new Intent(this, InterceptService.class));
        publish(message);
        stopService(new Intent(this, ScriptControlOverlayService.class));
        stopForeground(true);
        stopSelf(latestStartId);
    }

    private void verifyStartupDisplayCapture()
            throws StartupDisplayCaptureException, StartupTargetForegroundException {
        android.graphics.Bitmap frame = null;
        try {
            frame = captureStartupFrame(10_000, 10_000);
        } catch (ScriptExecutionControl.StopRequestedException stopped) {
            throw stopped;
        } catch (StartupTargetForegroundException notForeground) {
            throw notForeground;
        } catch (Exception failure) {
            throw new StartupDisplayCaptureException(failure);
        } finally {
            if (frame != null && !frame.isRecycled()) frame.recycle();
        }
    }

    private void waitForFirstUseTermsIfNeeded() throws Exception {
        // The consent control is a fixed image asset. Detect and tap only this
        // known button so startup does not depend on the dynamic-text OCR path.
        Bitmap template;
        try (InputStream input = getAssets().open(STARTUP_AGREE_TEMPLATE)) {
            template = BitmapFactory.decodeStream(input);
        }
        if (template == null) throw new IllegalStateException("使用条款按钮模板无法读取，脚本已停止");
        try {
            int clickAttempts = 0;
            int noMatchFrames = 0;
            while (clickAttempts < 10) {
                control.checkpoint();
                if (clickAttempts == 0) {
                    publish("正在检查使用条款页（" + (noMatchFrames + 1) + "/12）");
                }
                Bitmap frame = captureStartupFrame(30_000L, 5_000L);
                ScriptStartupTerms.Tap tap;
                try {
                    if (isWorldHudVisible(frame)) {
                        Log.i("RYLUX-Lua", "world HUD detected; skipping startup terms scan");
                        publish("已检测到游戏主界面，继续挂机流程");
                        return;
                    }
                    tap = findStartupAgreeButton(frame, template);
                } finally {
                    if (!frame.isRecycled()) frame.recycle();
                }
                if (tap == null) {
                    if (clickAttempts > 0) {
                        Log.i("RYLUX-Lua", "startup terms page closed after consent tap");
                        publish("使用条款页已关闭，继续自动登录流程");
                        return;
                    }
                    noMatchFrames++;
                    if (noMatchFrames >= 12) {
                        Log.w("RYLUX-Lua", "startup terms template not found after " + noMatchFrames
                                + " scans; continuing startup; see screen-size/region diagnostics above");
                        publish("多次图片识别未发现使用条款按钮，继续自动登录流程");
                        return;
                    }
                    control.sleep(500L);
                    continue;
                }
                noMatchFrames = 0;
                clickAttempts++;
                Log.i("RYLUX-Lua", "startup terms consent detected; tap=" + tap.x + "," + tap.y
                        + " attempt=" + clickAttempts + "/10");
                publish("检测到使用条款页，正在点击“同意”（" + clickAttempts + "/10）");
                boolean clicked = ScriptGameAdapter.requireConnected(control)
                        .tap(tap.x, tap.y, 100L, 1_500L);
                Log.i("RYLUX-Lua", "startup terms consent tap accepted=" + clicked);
                if (!clicked) {
                    publish("“同意”点击未送达，继续重试（" + clickAttempts + "/10）");
                    control.sleep(250L);
                    continue;
                }
                control.sleep(500L);
            }
            Bitmap verifyFrame = captureStartupFrame(30_000L, 5_000L);
            try {
                if (!isWorldHudVisible(verifyFrame)
                        && findStartupAgreeButton(verifyFrame, template) != null) {
                    throw new IllegalStateException("已重试 10 次点击使用条款“同意”，页面仍未关闭");
                }
                publish("使用条款页已关闭，继续自动登录流程");
            } finally {
                if (!verifyFrame.isRecycled()) verifyFrame.recycle();
            }
        } finally {
            if (!template.isRecycled()) template.recycle();
        }
    }

    private boolean isWorldHudVisible(Bitmap frame) {
        if (frame == null || frame.isRecycled() || frame.getWidth() < frame.getHeight()) return false;
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(1280, 720,
                frame.getWidth(), frame.getHeight());
        return LegacyLuaCompat.matchesColorPoints(mapper.mapColorPoints(WORLD_SETTINGS_POINTS),
                0.9d, frame.getWidth(), frame.getHeight(), frame::getPixel)
                || LegacyLuaCompat.matchesColorPoints(mapper.mapColorPoints(WORLD_SKILL_POINTS),
                0.9d, frame.getWidth(), frame.getHeight(), frame::getPixel);
    }

    private ScriptStartupTerms.Tap findStartupAgreeButton(Bitmap frame, Bitmap sourceTemplate) {
        if (frame == null || frame.isRecycled()) return null;
        int[] region = ScriptStartupTerms.agreeSearchRegion(frame.getWidth(), frame.getHeight());
        Log.i("RYLUX-Lua", "startup terms image scan display=" + frame.getWidth() + "x"
                + frame.getHeight() + " region=" + region[0] + "," + region[1] + "-"
                + region[2] + "," + region[3]);
        LegacyImageMatcher.Pixels screenPixels = bitmapPixels(frame);
        ScriptStartupTerms.Tap bestTap = null;
        double bestScore = 0.74d;
        for (double scale : ScriptStartupTerms.agreeTemplateScales(frame.getWidth(), frame.getHeight())) {
            control.checkpoint();
            int templateWidth = Math.max(1, (int) Math.round(sourceTemplate.getWidth() * scale));
            int templateHeight = Math.max(1, (int) Math.round(sourceTemplate.getHeight() * scale));
            Bitmap template = sourceTemplate;
            if (templateWidth != sourceTemplate.getWidth() || templateHeight != sourceTemplate.getHeight()) {
                template = Bitmap.createScaledBitmap(sourceTemplate, templateWidth, templateHeight, true);
            }
            try {
                LegacyImageMatcher.Match match = LegacyImageMatcher.find(screenPixels, bitmapPixels(template),
                        region[0], region[1], region[2], region[3], 0x00101010, 0.74d,
                        control::checkpoint);
                if (match != null && match.score > bestScore) {
                    bestScore = match.score;
                    bestTap = ScriptStartupTerms.templateCenter(match.x, match.y,
                            template.getWidth(), template.getHeight());
                }
            } finally {
                if (template != sourceTemplate && !template.isRecycled()) template.recycle();
            }
        }
        if (bestTap != null) {
            Log.i("RYLUX-Lua", "startup terms image hit center=" + bestTap.x + "," + bestTap.y
                    + " score=" + bestScore);
        }
        return bestTap;
    }

    private static LegacyImageMatcher.Pixels bitmapPixels(Bitmap bitmap) {
        return new LegacyImageMatcher.Pixels() {
            @Override public int width() { return bitmap.getWidth(); }
            @Override public int height() { return bitmap.getHeight(); }
            @Override public int getPixel(int x, int y) { return bitmap.getPixel(x, y); }
        };
    }

    /** A game activity can lose focus briefly while opening or changing screens. */
    private Bitmap captureStartupFrame(long waitMs, long captureTimeoutMs) throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + waitMs;
        boolean waiting = false;
        while (true) {
            control.checkpoint();
            long remaining = deadline - android.os.SystemClock.uptimeMillis();
            if (remaining <= 0) {
                String message = "等待所选游戏回到前台超时；已停止截图和触控（诊断："
                        + ScriptGameAdapter.foregroundDiagnostic() + "）";
                throw new StartupTargetForegroundException(message, null);
            }
            if (ScriptGameAdapter.isTargetGameForeground()) {
                try {
                    Bitmap frame = ScriptGameAdapter.requireConnected(control)
                            .captureDisplay(Math.min(captureTimeoutMs, remaining));
                    if (waiting) publish("游戏已回到前台，继续检查启动页");
                    return frame;
                } catch (TargetGameNotForegroundException focusChanged) {
                    // Recheck after a focus handoff instead of failing the script.
                }
            }
            if (!waiting) {
                waiting = true;
                publish("游戏暂时不在前台，等待游戏返回");
            }
            control.sleep(Math.min(100L, remaining));
        }
    }

    private void awaitTargetGameForeground() throws Exception {
        try {
            ScriptForegroundGate.await(
                    ScriptGameAdapter::isTargetGameForeground,
                    control::checkpoint,
                    60_000L,
                    "60 秒内未检测到所选的封神榜客户端处于前台；已阻止脚本截图和触控");
        } catch (TimeoutException timeout) {
            String detail = timeout.getMessage() + "（诊断："
                    + ScriptGameAdapter.foregroundDiagnostic() + "）";
            throw new StartupTargetForegroundException(detail, timeout);
        }
    }

    private static final class StartupDisplayCaptureException extends Exception {
        StartupDisplayCaptureException(Throwable cause) {
            super("启动前显示截图检查失败", cause);
        }
    }

    private static final class StartupTargetForegroundException extends Exception {
        StartupTargetForegroundException(String message, Throwable cause) { super(message, cause); }
    }

    private void publish(String message) {
        String eventMessage = message == null ? "" : message;
        lastMessage = eventMessage;
        ScriptExecutionControl current = control;
        String eventState = current == null ? ScriptExecutionControl.State.IDLE.name() : current.getState().name();
        state = eventState;
        try { runtimeLog.status(eventState, eventMessage); }
        catch (java.io.IOException | RuntimeException error) {
            Log.w("RYLUX-Lua", "运行日志写入失败", error);
        }
        Intent event = new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_STATE, eventState).putExtra(EXTRA_MESSAGE, eventMessage);
        sendBroadcast(event);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && !ScriptExecutionControl.State.IDLE.name().equals(eventState)) {
            try { manager.notify(NOTIFICATION_ID, notification(eventMessage)); }
            catch (RuntimeException ignored) { }
        }
    }

    private Notification notification(String message) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("RYLUX 脚本运行中").setContentText(message)
                .setOngoing(true).build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "脚本运行宿主", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持用户主动启动的 Lua 脚本运行");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String message = cause.getMessage();
        String type = cause.getClass().getName();
        return message == null || message.trim().isEmpty() ? type : type + ": " + message;
    }

    private void reportFailure(Throwable failure) {
        saveFailure(failure);
        publish("脚本失败：" + rootMessage(failure));
    }

    private void saveFailure(Throwable failure) {
        ScriptExecutionControl current = control;
        try { runtimeLog.failure(current == null ? state : current.getState().name(), failure); }
        catch (java.io.IOException | RuntimeException error) {
            Log.w("RYLUX-Lua", "失败日志写入失败", error);
        }
        java.io.StringWriter buffer = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(buffer));
        String detail = buffer.toString();
        // Preserve the complete useful stack while keeping SharedPreferences bounded.
        if (detail.length() > 64_000) detail = detail.substring(0, 64_000) + "\n…(诊断内容已截断)";
        getSharedPreferences(DIAGNOSTICS, MODE_PRIVATE).edit()
                .putString(LAST_ERROR, detail)
                .putLong("last_error_time", System.currentTimeMillis())
                .commit();
        Log.e("RYLUX-Lua", "脚本执行失败", failure);
    }

    @Override public void onDestroy() {
        serviceDestroyed = true;
        mainHandler.removeCallbacks(entitlementWatchdog);
        synchronized (this) {
            entitlementGeneration++;
            entitlementCheckPending = false;
            pendingRestartConfig = null;
        }
        ScriptExecutionControl current = control;
        if (current != null) current.requestStop();
        routeBridge.close();
        if (bagSnapshotRelay != null) bagSnapshotRelay.close();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

