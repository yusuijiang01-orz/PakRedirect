package com.example.pakredirect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
            LegacyLuaRuntime runtime = new LegacyLuaRuntime(this, control, config, this::publish);
            try {
                runtime.run();
                control.complete();
                publish("脚本已完成");
            } catch (ScriptExecutionControl.StopRequestedException stopped) {
                control.complete();
                publish("已停止");
            } catch (Throwable failure) {
                if (control.isStopRequested()) {
                    control.complete();
                    publish("已停止");
                } else {
                    control.fail();
                    publish("脚本失败：" + rootMessage(failure));
                }
            } finally {
                stopForeground(true);
                stopSelf();
            }
        });
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
        return message == null ? cause.getClass().getSimpleName() : message;
    }

    @Override public void onDestroy() {
        ScriptExecutionControl current = control;
        if (current != null) current.requestStop();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
