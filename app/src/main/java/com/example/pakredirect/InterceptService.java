package com.example.pakredirect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.net.URI;

public class InterceptService extends Service implements ProxyServer.Listener {
    public static final String ACTION_START = "com.example.pakredirect.START";
    public static final String ACTION_STOP = "com.example.pakredirect.STOP";
    public static final String ACTION_STATUS = "com.example.pakredirect.STATUS";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_DIRECTORY_URI = "directory_uri";
    public static final String EXTRA_ADB_MANAGED = "adb_managed";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_HITS = "hits";
    public static final String EXTRA_RUNNING = "running";

    public static final String MODE_LOCAL_HTTP = "local_http";
    public static final String MODE_LEGACY_TLS = "legacy_tls";
    public static final int LOCAL_HTTP_PORT = 18480;
    private static final int TLS_PORT = 18443;
    private static final String MARKER = "# PakRedirect";
    private static volatile boolean currentRunning;
    private static volatile int currentHits;

    private final Object lifecycleLock = new Object();
    private ProxyServer server;
    private volatile boolean active;
    private volatile boolean starting;
    private volatile boolean rootRulesApplied;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            new Thread(() -> {
                synchronized (lifecycleLock) {
                    stopInterceptLocked("已停止");
                }
                stopSelf();
            }, "PakRedirect-Stop").start();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            if (starting) {
                broadcast("正在启动，请稍候…", -1, active);
                return START_STICKY;
            }
            String url = intent.getStringExtra(EXTRA_URL);
            String directoryUri = intent.getStringExtra(EXTRA_DIRECTORY_URI);
            boolean adbManaged = intent.getBooleanExtra(EXTRA_ADB_MANAGED, false);
            String mode = intent.getStringExtra(EXTRA_MODE);
            if (mode == null || mode.isEmpty()) mode = MODE_LOCAL_HTTP;
            final String requestedMode = mode;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    startForeground(7, notification("正在启动拦截…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                } else {
                    startForeground(7, notification("正在启动拦截…"));
                }
            } catch (Throwable t) {
                Log.e("PakRedirect", "startForeground failed", t);
                broadcast("启动失败: 前台服务权限不足或通知权限被限制: " + safeMessage(t), 0, false);
                stopSelf();
                return START_NOT_STICKY;
            }
            starting = true;
            new Thread(() -> {
                synchronized (lifecycleLock) {
                    try {
                        startInterceptLocked(url, directoryUri, adbManaged, requestedMode);
                    } finally {
                        starting = false;
                    }
                }
            }, "PakRedirect-Start").start();
        }
        return START_STICKY;
    }

    private void startInterceptLocked(String url, String directoryUriString, boolean adbManaged, String mode) {
        ProxyServer newServer = null;
        try {
            stopInterceptLocked(null);

            URI parsed = URI.create(url);
            String host = parsed.getHost();
            if (!"https".equalsIgnoreCase(parsed.getScheme()) || host == null || host.isEmpty()) {
                throw new IllegalArgumentException("仅支持完整 https:// URL");
            }
            if (directoryUriString == null || directoryUriString.isEmpty()) {
                throw new IllegalArgumentException("尚未选择 PAK 目录");
            }

            boolean localHttp = MODE_LOCAL_HTTP.equals(mode);
            if (!localHttp && !MODE_LEGACY_TLS.equals(mode)) throw new IllegalArgumentException("未知运行模式: " + mode);
            newServer = new ProxyServer(this, url, ProxyServer.DEFAULT_MANIFEST_URL, Uri.parse(directoryUriString), this,
                    localHttp, LOCAL_HTTP_PORT);
            newServer.prepare();

            if (localHttp) {
                newServer.startHttp(LOCAL_HTTP_PORT);
                rootRulesApplied = false;
            } else {
                RootShell.Result r = applyRootRules(newServer.interceptedHosts());
                if (!r.ok()) {
                    // On recent emulators ADB may already be root while app processes
                    // are forbidden from changing the system mount namespace.
                    if (!adbManaged) throw new IllegalStateException("Root 规则失败:\n" + r);
                    onLog("ADB-root 托管模式：网络规则由电脑端设置");
                } else {
                    rootRulesApplied = true;
                }
                newServer.startTls(TLS_PORT);
            }
            server = newServer;
            active = true;
            currentRunning = true;
            currentHits = 0;
            String status = localHttp ? "本地直供中 · 无需 Root/CA" : "HTTPS 拦截中 · 兼容模式";
            updateNotification(status);
            broadcast(status, 0, true);
        } catch (Throwable t) {
            Log.e("PakRedirect", "start failed", t);
            if (newServer != null) {
                try { newServer.stop(); } catch (Throwable ignored) {}
            }
            server = null;
            active = false;
            currentRunning = false;
            if (rootRulesApplied) cleanupRootRules();
            broadcast("启动失败: " + safeMessage(t), 0, false);
            stopSelf();
        }
    }

    private RootShell.Result applyRootRules(String[] hosts) {
        StringBuilder echo = new StringBuilder();
        for (String host : hosts) {
            if (host == null) continue;
            String safeHost = host.replaceAll("[^A-Za-z0-9.-]", "");
            if (!safeHost.isEmpty()) {
                echo.append("echo '127.0.0.1 ").append(safeHost).append(" ").append(MARKER).append("' >> $tmp; ");
            }
        }

        String cmd =
                "set -e; " +
                "for m in / /system /system_root; do mount -o rw,remount $m >/dev/null 2>&1 || true; done; " +
                "tmp=/data/local/tmp/pakredirect_hosts.$$; " +
                "grep -v '" + MARKER + "$' /system/etc/hosts > $tmp || true; " +
                echo +
                "cat $tmp > /system/etc/hosts; rm -f $tmp; restorecon /system/etc/hosts >/dev/null 2>&1 || true; " +
                "while iptables -t nat -D OUTPUT -p tcp -d 127.0.0.1 --dport 443 -j REDIRECT --to-ports " + TLS_PORT + " >/dev/null 2>&1; do :; done; " +
                "iptables -t nat -A OUTPUT -p tcp -d 127.0.0.1 --dport 443 -j REDIRECT --to-ports " + TLS_PORT + "; " +
                "while iptables -D OUTPUT -p udp -d 127.0.0.1 --dport 443 -j REJECT >/dev/null 2>&1; do :; done; " +
                "iptables -A OUTPUT -p udp -d 127.0.0.1 --dport 443 -j REJECT; ";
        return RootShell.run(cmd);
    }

    private void stopInterceptLocked(String message) {
        active = false;
        currentRunning = false;
        ProxyServer old = server;
        server = null;
        if (old != null) {
            try { old.stop(); } catch (Throwable ignored) {}
        }
        if (rootRulesApplied) cleanupRootRules();
        rootRulesApplied = false;
        if (message != null) broadcast(message, 0, false);
    }

    private void cleanupRootRules() {
        RootShell.run(
                "for m in / /system /system_root; do mount -o rw,remount $m >/dev/null 2>&1 || true; done; " +
                "tmp=/data/local/tmp/pakredirect_hosts.$$; " +
                "grep -v '" + MARKER + "$' /system/etc/hosts > $tmp 2>/dev/null || true; " +
                "if [ -f $tmp ]; then cat $tmp > /system/etc/hosts; fi; rm -f $tmp; " +
                "while iptables -t nat -D OUTPUT -p tcp -d 127.0.0.1 --dport 443 -j REDIRECT --to-ports " + TLS_PORT + " >/dev/null 2>&1; do :; done; " +
                "while iptables -D OUTPUT -p udp -d 127.0.0.1 --dport 443 -j REJECT >/dev/null 2>&1; do :; done; true");
    }

    @Override public void onDestroy() {
        synchronized (lifecycleLock) {
            stopInterceptLocked(null);
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onLog(String line) {
        broadcast(line, -1, active);
        updateNotification(line.length() > 70 ? line.substring(0, 70) : line);
    }

    @Override public void onHit(int count) {
        currentHits = count;
        broadcast("命中本地 PAK", count, true);
    }

    public static boolean isRunning() { return currentRunning; }
    public static int hitCount() { return currentHits; }

    private void broadcast(String msg, int hits, boolean running) {
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_MESSAGE, msg);
        i.putExtra(EXTRA_HITS, hits);
        i.putExtra(EXTRA_RUNNING, running);
        sendBroadcast(i);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("pakredirect", "PakRedirect", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "pakredirect")
                : new Notification.Builder(this);
        return b.setContentTitle("PakRedirect")
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
        String m = t.getMessage();
        return (m == null || m.trim().isEmpty()) ? t.getClass().getSimpleName() : m;
    }
}
