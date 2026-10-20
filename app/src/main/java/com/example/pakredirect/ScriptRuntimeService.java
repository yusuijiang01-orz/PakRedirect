package com.example.pakredirect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.IBinder;
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

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "rylux-lua-host");
        thread.setDaemon(true);
        return thread;
    });
    private volatile ScriptExecutionControl control;
    private volatile String lastMessage = "就绪";
    private volatile String state = ScriptExecutionControl.State.IDLE.name();

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        startForeground(NOTIFICATION_ID, notification("准备处理脚本命令"));
        if (!ACTION_START.equals(action) && control == null) {
            publish("脚本尚未启动");
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) startScript(intent.getStringExtra(EXTRA_CONFIG));
        else if (ACTION_PAUSE.equals(action)) { if (control != null) control.pause(); publish("已暂停"); }
        else if (ACTION_RESUME.equals(action)) { if (control != null) control.resume(); publish("继续运行"); }
        else if (ACTION_STOP.equals(action)) { if (control != null) control.requestStop(); publish("正在停止"); }
        return START_NOT_STICKY;
    }

    private synchronized void startScript(String json) {
        if (control != null && (control.getState() == ScriptExecutionControl.State.RUNNING
                || control.getState() == ScriptExecutionControl.State.PAUSED
                || control.getState() == ScriptExecutionControl.State.STOP_REQUESTED)) {
            publish("脚本已在运行");
            return;
        }
        if (!ScriptGameAdapter.isConnected()) {
            publish("启动失败：请先启用 RYLUX 游戏输入服务");
            stopSelf();
            return;
        }
        String[] blockers = LegacyLuaRuntime.compatibilityBlockers(this);
        if (blockers.length > 0) {
            publish("启动被兼容检查拦截：" + String.join("；", blockers));
            stopSelf();
            return;
        }
        JSONObject config;
        try { config = new JSONObject(json == null ? "{}" : json); }
        catch (Exception invalid) { publish("启动失败：方案配置无效"); stopSelf(); return; }
        control = new ScriptExecutionControl();
        control.start();
        state = ScriptExecutionControl.State.RUNNING.name();
        lastMessage = "Lua 宿主已启动";
        startForeground(NOTIFICATION_ID, notification(lastMessage));
        publish(lastMessage);
        executor.execute(() -> {
            LegacyLuaRuntime runtime = new LegacyLuaRuntime(this, control, config,
                    this::publish, this::saveFailure);
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
                stopForeground(true);
                stopSelf();
            }
        });
    }

    private void verifyStartupDisplayCapture() throws StartupDisplayCaptureException {
        android.graphics.Bitmap frame = null;
        try {
            frame = ScriptGameAdapter.requireConnected(control).captureDisplay(10_000);
        } catch (ScriptExecutionControl.StopRequestedException stopped) {
            throw stopped;
        } catch (Exception failure) {
            throw new StartupDisplayCaptureException(failure);
        } finally {
            if (frame != null && !frame.isRecycled()) frame.recycle();
        }
    }

    private void acceptFirstUseTermsIfPresent() throws Exception {
        long deadline = android.os.SystemClock.uptimeMillis() + 45_000L;
        boolean termsAccepted = false;
        try (ScriptOcr ocr = new ScriptOcr()) {
            while (true) {
                control.checkpoint();
                Bitmap frame = null;
                try {
                    frame = ScriptGameAdapter.requireConnected(control).captureDisplay(5_000);
                    ScriptOcr.Result result = ocr.recognize(frame, true, 5_000);
                    JSONArray recognized = new JSONArray(result.json);
                    List<ScriptStartupTerms.Line> lines = new ArrayList<>();
                    for (int i = 0; i < recognized.length(); i++) {
                        JSONObject line = recognized.getJSONObject(i);
                        lines.add(new ScriptStartupTerms.Line(line.optString("text", ""),
                                line.optInt("x", -1), line.optInt("y", -1)));
                    }
                    ScriptStartupTerms.Tap agree = ScriptStartupTerms.findAgreeTap(
                            lines, frame.getWidth(), frame.getHeight());
                    if (!termsAccepted && agree != null) {
                        control.checkpoint();
                        if (!ScriptGameAdapter.requireConnected(control).tap(agree.x, agree.y, 60, 3_000)) {
                            throw new IllegalStateException("游戏首次使用条款的同意按钮未能完成触控");
                        }
                        termsAccepted = true;
                        publish("已确认游戏首次使用条款");
                    }
                    // The game can show its first-use terms several seconds after
                    // the BlackBox activity becomes foreground. Keep polling until
                    // the localized account page appears, so Lua does not start
                    // against a loading screen or remain trapped behind the terms.
                    if (ScriptStartupTerms.isLocalizedLoginPage(lines)) return;
                } finally {
                    if (frame != null && !frame.isRecycled()) frame.recycle();
                }
                long remaining = deadline - android.os.SystemClock.uptimeMillis();
                if (remaining <= 0) {
                    publish("启动页等待结束，交由 Lua 登录流程继续");
                    return;
                }
                control.sleep(Math.min(1_000L, remaining));
            }
        }
    }

    private void awaitTargetGameForeground() throws Exception {
        try {
            ScriptForegroundGate.await(
                    ScriptGameAdapter::isTargetGameForeground,
                    control::checkpoint,
                    15_000L,
                    "15 秒内未检测到沙箱中的汉化版封神榜处于前台；已阻止脚本截图和触控");
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
        lastMessage = message == null ? "" : message;
        ScriptExecutionControl current = control;
        state = current == null ? ScriptExecutionControl.State.IDLE.name() : current.getState().name();
        Intent event = new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_STATE, state).putExtra(EXTRA_MESSAGE, lastMessage);
        sendBroadcast(event);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && !ScriptExecutionControl.State.IDLE.name().equals(state)) {
            try { manager.notify(NOTIFICATION_ID, notification(lastMessage)); }
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
        ScriptExecutionControl current = control;
        if (current != null) current.requestStop();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
