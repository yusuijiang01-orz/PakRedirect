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
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/** Foreground, read-only bag probe that does not take focus away from the game. */
public final class BagMemoryProbeService extends Service {
    private static final String TAG = "RYLUX-BagProbe";
    private static final String CHANNEL = "bag_memory_probe";
    private static final int NOTIFICATION_ID = 136;
    private static final long TIMEOUT_MS = 10_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poller = this::poll;
    private SandboxBagSnapshotRelay relay;
    private SandboxBagSnapshot lastSnapshot;
    private long deadline;
    private boolean finished;
    private boolean started;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "背包内存诊断", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (started) return START_NOT_STICKY;
        started = true;
        startForeground(NOTIFICATION_ID, notification("正在启动只读读取器"));
        if (new File(getFilesDir(), "sandbox-bag-relay.endpoint").isFile()) {
            finishWith("内存探测未启动：已有工作流正在使用读取桥。请先停止工作流。", false);
            return START_NOT_STICKY;
        }
        try {
            relay = new SandboxBagSnapshotRelay(this);
            relay.start();
            deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
            publishOverlay("MEMORY_PROBE", "内存探测中：只读采样，不会点击或操作游戏");
            Log.i(TAG, "Read-only probe active; no game input will be sent");
            handler.postDelayed(poller, 250L);
        } catch (Exception failure) {
            finishWith("内存探测失败：中继启动失败：" + failure.getClass().getSimpleName()
                    + "：" + String.valueOf(failure.getMessage()), true);
        }
        return START_NOT_STICKY;
    }

    private void poll() {
        if (finished || relay == null) return;
        SandboxBagSnapshot snapshot = relay.snapshot();
        if (snapshot != null) {
            lastSnapshot = snapshot;
            if (snapshot.supported) {
                long age = Math.max(0L, SystemClock.elapsedRealtime() - snapshot.receivedAtElapsedMs);
                finishWith(String.format(Locale.ROOT,
                        "内存读取成功：剩余格子 %d，负重 %d/%d，延迟 %d ms，Build ID %s",
                        snapshot.freeSlots, snapshot.currentWeight, snapshot.maxWeight, age,
                        snapshot.buildId), true);
                return;
            }
            if (isPermanentFailure(snapshot.status)) {
                finishWith("内存读取失败：状态 " + snapshot.status
                        + "，Build ID " + snapshot.buildId, true);
                return;
            }
        }
        if (SystemClock.elapsedRealtime() >= deadline) {
            if (lastSnapshot == null) {
                finishWith("内存读取失败：10 秒内未收到快照。请确认游戏仍在 RYLUX 沙箱前台。", true);
            } else {
                finishWith("内存读取失败：状态 " + lastSnapshot.status
                        + "，Build ID " + lastSnapshot.buildId, true);
            }
            return;
        }
        handler.postDelayed(poller, 250L);
    }

    private static boolean isPermanentFailure(String status) {
        return status != null && (status.startsWith("UNSUPPORTED_")
                || "PROFILE_ADDRESS_UNREADABLE".equals(status)
                || "READER_UNAVAILABLE".equals(status));
    }

    private void finishWith(String message, boolean updateOverlay) {
        if (finished) return;
        finished = true;
        Log.i(TAG, message);
        getSharedPreferences("script_runtime_diagnostics", MODE_PRIVATE).edit()
                .putString("last_memory_probe", message)
                .putLong("last_memory_probe_time", System.currentTimeMillis())
                .apply();
        if (relay != null) {
            relay.close();
            relay = null;
        }
        if (updateOverlay) publishOverlay("IDLE", message);
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(message, false));
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH);
        else stopForeground(false);
        stopSelf();
    }

    private void publishOverlay(String state, String message) {
        Intent event = new Intent(ScriptRuntimeService.ACTION_STATUS).setPackage(getPackageName())
                .putExtra(ScriptRuntimeService.EXTRA_STATE, state)
                .putExtra(ScriptRuntimeService.EXTRA_MESSAGE, message);
        sendBroadcast(event);
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
