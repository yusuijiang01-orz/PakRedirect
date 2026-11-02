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
        String[] blockers = LegacyLuaRuntime.compatibilityBlockers(this);
        if (blockers.length > 0) {
            publish("启动被兼容检查拦截：" + String.join("；", blockers));
            stopSelf(latestStartId);
            return;
        }
        try {
            routeBridge.start();
        } catch (java.io.IOException failure) {
            publish("启动失败：Lua 路线桥接无法监听：" + failure.getMessage());
            stopSelf(latestStartId);
            return;
        }
        if (ScriptToolsActivity.TARGET_MODE_SANDBOX.equals(config.optString("目标运行环境",
                ScriptToolsActivity.TARGET_MODE_SANDBOX))) {
            try {
                bagSnapshotRelay.start();
                publish("沙箱背包内存读取桥已就绪，等待游戏快照");
            } catch (java.io.IOException failure) {
                Log.w("RYLUX-Lua", "沙箱背包内存桥不可用，将使用屏幕识别", failure);
                publish("沙箱内存桥不可用，将使用屏幕识别");
            }
        } else {
            bagSnapshotRelay.close();
        }
        control = new ScriptExecutionControl();
        control.start();
        final ScriptExecutionControl runControl = control;
        workerRunning = true;
        state = ScriptExecutionControl.State.RUNNING.name();
        lastMessage = "Lua 宿主已启动";
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
                long now = android.os.SystemClock.elapsedRealtime();
                if (now - restartWindowStarted >= ScriptAutoRecoveryPolicy.RESTART_WINDOW_MS) {
                    restartWindowStarted = now;
                    restartsInWindow = 0;
                }
                if (ScriptAutoRecoveryPolicy.shouldRestart(autoLogin, gameWasObserved, false,
                        processState, restartsInWindow)) {
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
                    publish("已检测到游戏崩溃/无响应，" + (backoff / 1000) + " 秒后自动重启并重新登录（"
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
        Thread watcher = new Thread(() -> {
            while (watching.get() && !runControl.isStopRequested()) {
                ScriptGameProcessMonitor.State processState = processMonitor.inspect(this, targetMode);
                if (processState == ScriptGameProcessMonitor.State.RUNNING) {
                    gameObserved.set(true);
                } else if (processState == ScriptGameProcessMonitor.State.CRASHED
                        && gameObserved.get()) {
                    GameProcessExitedException crashed = new GameProcessExitedException();
                    if (runControl.fail(crashed)) {
                        publish("检测到游戏崩溃/无响应，正在按自动登录设置恢复");
                    }
                    return;
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
            awaitTargetGameForeground();
            gameObserved.set(true);
            verifyStartupDisplayCapture();
            waitForFirstUseTermsIfNeeded();
            LegacyLuaRuntime runtime = new LegacyLuaRuntime(this, runControl, config,
                    this::publish, this::saveFailure, routeBridge, runtimeLog, bagSnapshotRelay);
            runtime.run();
            return gameObserved.get();
        } finally {
            watching.set(false);
            watcher.interrupt();
            try { watcher.join(1_500L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    private static final class GameProcessExitedException extends Exception {
        GameProcessExitedException() { super("目标游戏进程已退出"); }
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
        // The consent control is a fixed image asset. Detect it by template so
        // startup does not consume or poison the dynamic-text OCR pipeline.
        Bitmap template;
        try (InputStream input = getAssets().open(STARTUP_AGREE_TEMPLATE)) {
            template = BitmapFactory.decodeStream(input);
        }
        if (template == null) throw new IllegalStateException("使用条款按钮模板无法读取，脚本已停止");
        try {
            control.checkpoint();
            Bitmap frame = captureStartupFrame(30_000L, 5_000L);
            try {
                if (!isStartupAgreeButtonVisible(frame, template)) {
                    publish("未检测到使用条款确认按钮，交由 Lua 流程继续");
                    return;
                }
            } finally {
                if (!frame.isRecycled()) frame.recycle();
            }
            publish("检测到使用条款确认按钮，请在游戏界面手动阅读并确认；脚本不会代点");
            while (true) {
                control.sleep(1_500L);
                Bitmap nextFrame = captureStartupFrame(30_000L, 5_000L);
                try {
                    if (!isStartupAgreeButtonVisible(nextFrame, template)) {
                        publish("使用条款按钮已消失，继续自动登录流程");
                        return;
                    }
                } finally {
                    if (!nextFrame.isRecycled()) nextFrame.recycle();
                }
            }
        } finally {
            if (!template.isRecycled()) template.recycle();
        }
    }

    private boolean isStartupAgreeButtonVisible(Bitmap frame, Bitmap sourceTemplate) {
        if (frame == null || frame.isRecycled()) return false;
        LegacyCoordinateMapper mapper = new LegacyCoordinateMapper(
                1280, 720, frame.getWidth(), frame.getHeight());
        int templateWidth = Math.max(1, mapper.toScreenDeltaX(sourceTemplate.getWidth()));
        int templateHeight = Math.max(1, mapper.toScreenDeltaY(sourceTemplate.getHeight()));
        Bitmap template = sourceTemplate;
        if (templateWidth != sourceTemplate.getWidth() || templateHeight != sourceTemplate.getHeight()) {
            template = Bitmap.createScaledBitmap(sourceTemplate, templateWidth, templateHeight, true);
        }
        try {
            LegacyImageMatcher.Pixels screenPixels = bitmapPixels(frame);
            LegacyImageMatcher.Pixels templatePixels = bitmapPixels(template);
            LegacyImageMatcher.Match match = LegacyImageMatcher.find(screenPixels, templatePixels,
                    mapper.left(320), mapper.top(504), mapper.right(640), mapper.bottom(684),
                    0x00101010, 0.86d, control::checkpoint);
            return match != null;
        } finally {
            if (template != sourceTemplate && !template.isRecycled()) template.recycle();
        }
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

