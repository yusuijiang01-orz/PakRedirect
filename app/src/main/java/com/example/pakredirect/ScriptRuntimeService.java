package com.example.pakredirect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

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
    private static final int STARTUP_OCR_MAX_EDGE = 640;
    private static final long STARTUP_TITLE_OCR_TIMEOUT_MS = 5_000L;
    private static final long STARTUP_DIALOG_OCR_TIMEOUT_MS = 15_000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "rylux-lua-host");
        thread.setDaemon(true);
        return thread;
    });
    private volatile ScriptExecutionControl control;
    private volatile String lastMessage = "就绪";
    private volatile String state = ScriptExecutionControl.State.IDLE.name();
    private final ScriptWebRouteBridge routeBridge = new ScriptWebRouteBridge();
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
        control = new ScriptExecutionControl();
        control.start();
        final ScriptExecutionControl runControl = control;
        workerRunning = true;
        state = ScriptExecutionControl.State.RUNNING.name();
        lastMessage = "Lua 宿主已启动";
        startForeground(NOTIFICATION_ID, notification(lastMessage));
        publish(lastMessage);
        executor.execute(() -> {
            LegacyLuaRuntime runtime = new LegacyLuaRuntime(this, runControl, config,
                    this::publish, this::saveFailure, routeBridge, runtimeLog);
            try {
                awaitTargetGameForeground();
                verifyStartupDisplayCapture();
                acceptFirstUseTermsIfPresent();
                runtime.run();
                control.complete();
                Throwable workerFailure = control.getFailure();
                if (workerFailure != null) reportFailure(workerFailure);
                else publish("脚本已完成");
            } catch (ScriptExecutionControl.StopRequestedException stopped) {
                Throwable workerFailure = control.getFailure();
                if (workerFailure != null) reportFailure(workerFailure);
                else { control.complete(); publish("已停止"); }
            } catch (Throwable failure) {
                Throwable workerFailure = control.getFailure();
                if (workerFailure != null) reportFailure(workerFailure);
                else if (control.isStopRequested()) {
                    control.complete();
                    publish("已停止");
                } else if (failure instanceof StartupDisplayCaptureException) {
                    if (control.fail(failure)) {
                        saveFailure(failure);
                        publish("启动失败：显示截图检查失败：" + rootMessage(failure.getCause()));
                    }
                } else if (failure instanceof StartupTargetForegroundException) {
                    if (control.fail(failure)) {
                        saveFailure(failure);
                        publish("启动失败：" + failure.getMessage());
                    }
                } else {
                    if (control.fail(failure)) reportFailure(failure);
                    else if (control.getFailure() != null) reportFailure(control.getFailure());
                    else { control.complete(); publish("已停止"); }
                }
            } finally {
                finishWorker(runControl);
            }
        });
    }

    private synchronized void finishWorker(ScriptExecutionControl finished) {
        if (control != finished) return;
        workerRunning = false;
        routeBridge.close();
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

    private void acceptFirstUseTermsIfPresent() throws Exception {
        // Most launches start from an already-running game screen. OCR only the
        // fixed title strip first; scanning every HUD label at 960x540 can saturate
        // LDPlayer's CPU and prevent the Lua host from ever starting.
        try (ScriptOcr ocr = new ScriptOcr()) {
            control.checkpoint();
            Bitmap frame = null;
            Bitmap titleCrop = null;
            Bitmap dialogCrop = null;
            try {
                frame = captureStartupFrame(30_000, 5_000);
                int[] titleRegion = ScriptStartupTerms.titleRegion(frame.getWidth(), frame.getHeight());
                titleCrop = Bitmap.createBitmap(frame, titleRegion[0], titleRegion[1],
                        titleRegion[2] - titleRegion[0], titleRegion[3] - titleRegion[1]);
                ScriptOcr.Result titleResult;
                try {
                    titleResult = ocr.recognize(titleCrop, true, STARTUP_TITLE_OCR_TIMEOUT_MS,
                            STARTUP_OCR_MAX_EDGE);
                } catch (Exception slowOcr) {
                    if (!ScriptOcr.isUnavailable(slowOcr)) throw slowOcr;
                    publish("启动页小区域 OCR 超时；跳过非必要条款预检，交由 Lua 流程继续");
                    return;
                }
                if (!ScriptStartupTerms.isTermsTitle(titleResult.text)) {
                    publish("未检测到首次使用条款，交由 Lua 流程继续");
                    return;
                }

                int[] dialogRegion = ScriptStartupTerms.dialogRegion(frame.getWidth(), frame.getHeight());
                dialogCrop = Bitmap.createBitmap(frame, dialogRegion[0], dialogRegion[1],
                        dialogRegion[2] - dialogRegion[0], dialogRegion[3] - dialogRegion[1]);
                ScriptOcr.Result result;
                try {
                    result = ocr.recognize(dialogCrop, true, STARTUP_DIALOG_OCR_TIMEOUT_MS,
                            STARTUP_OCR_MAX_EDGE);
                } catch (Exception slowOcr) {
                    if (ScriptOcr.isUnavailable(slowOcr)) {
                        publish("检测到使用条款，但 OCR 未能完整核实；未执行点击，请先手动处理条款");
                        throw new IllegalStateException(
                                "使用条款页面 OCR 超时，未自动确认；请手动处理后重启脚本", slowOcr);
                    }
                    throw slowOcr;
                }

                JSONArray recognized = new JSONArray(result.json);
                List<ScriptStartupTerms.Line> lines = new ArrayList<>();
                for (int i = 0; i < recognized.length(); i++) {
                    JSONObject line = recognized.getJSONObject(i);
                    lines.add(new ScriptStartupTerms.Line(line.optString("text", ""),
                            line.optInt("x", -1) + dialogRegion[0],
                            line.optInt("y", -1) + dialogRegion[1]));
                }
                ScriptStartupTerms.Tap agree = ScriptStartupTerms.findAgreeTap(
                        lines, frame.getWidth(), frame.getHeight());
                if (agree == null) {
                    throw new IllegalStateException(
                            "检测到使用条款，但无法完整核实页面内容；未执行点击");
                }
                control.checkpoint();
                try {
                    if (!ScriptGameAdapter.requireConnected(control).tap(agree.x, agree.y, 60, 3_000)) {
                        throw new IllegalStateException("游戏首次使用条款的同意按钮未能完成触控");
                    }
                } catch (TargetGameNotForegroundException focusChanged) {
                    publish("游戏在确认首次使用条款时离开前台；未复用旧截图点击");
                    return;
                }
                publish("已确认游戏首次使用条款");
            } finally {
                if (dialogCrop != null && !dialogCrop.isRecycled()) dialogCrop.recycle();
                if (titleCrop != null && !titleCrop.isRecycled()) titleCrop.recycle();
                if (frame != null && !frame.isRecycled()) frame.recycle();
            }
        }
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
                    15_000L,
                    "15 秒内未检测到所选的封神榜客户端处于前台；已阻止脚本截图和触控");
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
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
