package com.example.pakredirect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.Locale;

/** Foreground, read-only bag probe that does not take focus away from the game. */
public final class BagMemoryProbeService extends Service {
    private static final String TAG = "RYLUX-BagProbe";
    private static final String CHANNEL = "bag_memory_probe";
    private static final int NOTIFICATION_ID = 136;
    private static final long TIMEOUT_MS = 30_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poller = this::poll;
    private SandboxBagSnapshotRelay relay;
    private long deadline;
    private boolean finished;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "背包内存诊断", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("正在启动只读读取器"));
        try {
            relay = new SandboxBagSnapshotRelay(this);
            relay.start();
            deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
            Log.i(TAG, "Read-only probe active; game remains in foreground");
            handler.postDelayed(poller, 250L);
        } catch (Exception failure) {
            finishWith("中继启动失败：" + failure.getClass().getSimpleName()
                    + "：" + String.valueOf(failure.getMessage()));
        }
        return START_NOT_STICKY;
    }

    private void poll() {
        if (finished || relay == null) return;
        SandboxBagSnapshot snapshot = relay.snapshot();
        if (snapshot != null) {
            if (snapshot.supported) {
                long age = Math.max(0L, SystemClock.elapsedRealtime() - snapshot.receivedAtElapsedMs);
                finishWith(String.format(Locale.ROOT,
                        "读取成功（只读）：剩余格子 %d，负重 %d/%d，延迟 %d ms，Build ID %s",
                        snapshot.freeSlots, snapshot.currentWeight, snapshot.maxWeight, age,
                        snapshot.buildId));
            } else {
                finishWith("读取器未接受数据：状态 " + snapshot.status
                        + "，Build ID " + snapshot.buildId);
            }
            return;
        }
        if (SystemClock.elapsedRealtime() >= deadline) {
            finishWith("30 秒内未收到快照；请检查游戏前台状态和 RYLUX-BagPublisher 日志。");
            return;
        }
        handler.postDelayed(poller, 250L);
    }

    private void finishWith(String message) {
        if (finished) return;
        finished = true;
        Log.i(TAG, message);
        if (relay != null) {
            relay.close();
            relay = null;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(message, false));
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH);
        else stopForeground(false);
        stopSelf();
    }

    private Notification notification(String message) { return notification(message, true); }

    private Notification notification(String message, boolean ongoing) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("RYLUX 背包读取（只读）")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setOngoing(ongoing).build();
    }

    @Override public void onDestroy() {
        finished = true;
        handler.removeCallbacks(poller);
        if (relay != null) {
            relay.close();
            relay = null;
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
