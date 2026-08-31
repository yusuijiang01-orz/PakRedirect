package com.example.pakredirect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public class InterceptService extends Service implements BundledPakServer.Listener {
    public static final String ACTION_START = "com.example.pakredirect.START";
    public static final String ACTION_STOP = "com.example.pakredirect.STOP";
    public static final String ACTION_STATUS = "com.example.pakredirect.STATUS";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_HITS = "hits";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_PROGRESS = "progress";
    public static final int LOCAL_HTTP_PORT = BundledPakServer.PORT;

    private static volatile boolean currentRunning;
    private static volatile int currentHits;

    private final Object lifecycleLock = new Object();
    private BundledPakServer server;
    private volatile boolean starting;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            new Thread(() -> {
                synchronized (lifecycleLock) { stopServerLocked("已停止本地模块服务"); }
                stopSelf();
            }, "RYLUX-Stop").start();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_STICKY;

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(7, notification("正在准备本地游戏模块…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(7, notification("正在准备本地游戏模块…"));
            }
        } catch (Throwable t) {
            Log.e("RYLUX", "startForeground failed", t);
            String message = "启动失败: " + safeMessage(t);
            LaunchProgress.fail(message);
            broadcast(message, 0, false, -1);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (starting) {
            String message = LaunchProgress.message();
            if (message == null || message.trim().isEmpty()) message = "本地模块正在启动…";
            broadcast(message, -1, currentRunning, LaunchProgress.progress());
            return START_STICKY;
        }

        starting = true;
        LaunchProgress.begin("正在检查封神榜资源更新…");
        new Thread(() -> {
            synchronized (lifecycleLock) {
                try { startServerLocked(); }
                finally { starting = false; }
            }
        }, "RYLUX-Local-Module").start();
        return START_STICKY;
    }

    private void startServerLocked() {
        BundledPakServer next = null;
        try {
            stopServerLocked(null);
            updateLaunchStatus("正在检查封神榜资源更新…", -1);
            ContentUpdateManager.UpdateResult update = ContentUpdateManager.checkAndApply(
                    this,
                    (message, percent, indeterminate) ->
                            updateLaunchStatus(message, indeterminate ? -1 : percent)
            );
            if (update.updated) {
                updateLaunchStatus("封神榜资源已更新 " + update.changedFiles + " 个文件", 100);
            }

            updateLaunchStatus("正在启动本地 PAK 服务…", 100);
            next = new BundledPakServer(this, this);
            next.prepare();
            next.start();
            server = next;
            currentHits = 0;
            currentRunning = true;
            String ready = "本地游戏模块已启动";
            LaunchProgress.ready(ready);
            updateNotification("本地游戏模块运行中 · 127.0.0.1:" + LOCAL_HTTP_PORT);
            broadcast(ready, 0, true, 100);
        } catch (Throwable t) {
            Log.e("RYLUX", "local server start failed", t);
            if (next != null) try { next.stop(); } catch (Throwable ignored) {}
            server = null;
            currentRunning = false;
            String message = "启动失败: " + safeMessage(t);
            LaunchProgress.fail(message);
            broadcast(message, 0, false, -1);
            stopSelf();
        }
    }

    private void updateLaunchStatus(String message, int progress) {
        LaunchProgress.update(message, progress);
        updateNotification(message);
        broadcast(message, -1, currentRunning, progress);
    }

    private void stopServerLocked(String message) {
        currentRunning = false;
        BundledPakServer old = server;
        server = null;
        if (old != null) try { old.stop(); } catch (Throwable ignored) {}
        if (message != null) {
            LaunchProgress.stopped(message);
            broadcast(message, 0, false, -1);
        }
    }

    @Override public void onDestroy() {
        synchronized (lifecycleLock) { stopServerLocked(null); }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onLog(String line) {
        broadcast(line, -1, currentRunning, LaunchProgress.progress());
        updateNotification(line.length() > 70 ? line.substring(0, 70) : line);
    }

    @Override public void onHit(int count) {
        currentHits = count;
        broadcast("命中本地 PAK", count, true, 100);
    }

    public static boolean isRunning() { return currentRunning; }
    public static int hitCount() { return currentHits; }

    private void broadcast(String message, int hits, boolean running, int progress) {
        Intent intent = new Intent(ACTION_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_HITS, hits);
        intent.putExtra(EXTRA_RUNNING, running);
        intent.putExtra(EXTRA_PROGRESS, progress);
        sendBroadcast(intent);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    "rylux_local_module",
                    "RYLUX 本地游戏模块",
                    NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this,
                1,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "rylux_local_module")
                : new Notification.Builder(this);
        return b.setContentTitle("RYLUX")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(7, notification(text));
    }

    private static String safeMessage(Throwable t) {
        String message = t.getMessage();
        return message == null || message.trim().isEmpty() ? t.getClass().getSimpleName() : message;
    }
}
