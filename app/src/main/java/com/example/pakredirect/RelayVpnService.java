package com.example.pakredirect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.amnezia.awg.hevtunnel.TProxyService;

/** Per-game VPN that routes only the target package and target TCP address. */
public final class RelayVpnService extends VpnService {
    public static final String ACTION_START = "com.example.pakredirect.RELAY_START";
    public static final String ACTION_STOP = "com.example.pakredirect.RELAY_STOP";
    public static final String ACTION_STATUS = "com.example.pakredirect.RELAY_STATUS";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_RELAY_TOKEN = "relay_token";
    public static final int NOTIFICATION_ID = 8;

    private static final String TAG = "RYLUX-Relay";
    private static final String TARGET_PACKAGE = "com.tepaylink.tamgioiphantranhmobile";
    private static final String GAME_HOST = "103.206.217.41";
    private static final int IDLE_STOP_SECONDS = 90;

    private static volatile boolean running;
    private static volatile boolean starting;
    private static volatile String lastError = "";

    private final Object lifecycleLock = new Object();
    private final AtomicInteger sessions = new AtomicInteger();
    private volatile boolean acceptedSession;
    private RelaySocks5Server socksServer;
    private ParcelFileDescriptor tunInterface;
    private File configFile;
    private volatile String relayToken;
    private boolean nativeStarted;
    private ScheduledExecutorService idleScheduler;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRelay();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) return START_NOT_STICKY;
        String requestedToken = intent.getStringExtra(EXTRA_RELAY_TOKEN);
        if (requestedToken != null && !requestedToken.trim().isEmpty()) {
            relayToken = requestedToken.trim();
        }

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                        NOTIFICATION_ID,
                        notification("正在启动游戏 relay…"),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                );
            } else {
                startForeground(NOTIFICATION_ID, notification("正在启动游戏 relay…"));
            }
        } catch (Throwable t) {
            fail("前台 relay 服务启动失败：" + safeMessage(t));
            return START_NOT_STICKY;
        }

        synchronized (lifecycleLock) {
            if (starting || running) return START_STICKY;
            starting = true;
            lastError = "";
        }
        new Thread(this::startRelay, "RYLUX-Relay-Start").start();
        return START_STICKY;
    }

    private void startRelay() {
        try {
            String token = relayToken;
            if (token == null || token.trim().isEmpty()) {
                throw new IllegalStateException("relay 凭据无效，请重新授权");
            }

            updateNotification("正在检查 relay 到游戏服务器的连接…");
            String probeFailure = RelayWebSocketBridge.probeFailure(this, token);
            if (probeFailure != null) {
                throw new IllegalStateException(probeFailure);
            }

            RelaySocks5Server nextSocks = new RelaySocks5Server(this, token, new RelaySocks5Server.Listener() {
                @Override public void onSessionOpened() {
                    acceptedSession = true;
                    sessions.incrementAndGet();
                }

                @Override public void onSessionClosed() {
                    if (sessions.decrementAndGet() <= 0) {
                        sessions.set(0);
                        scheduleIdleStop();
                    }
                }
            });
            nextSocks.start();

            File nextConfig = new File(getFilesDir(), "rylux-relay/hev.yml");
            writeConfig(nextConfig);
            ParcelFileDescriptor nextTun = new Builder()
                    .setSession("RYLUX 游戏 relay")
                    .setMtu(1500)
                    .addAddress("198.18.0.1", 15)
                    .addRoute(GAME_HOST, 32)
                    .addAllowedApplication(TARGET_PACKAGE)
                    .establish();
            if (nextTun == null) throw new IllegalStateException("VPN 接口创建失败");

            synchronized (lifecycleLock) {
                socksServer = nextSocks;
                configFile = nextConfig;
                tunInterface = nextTun;
                nativeStarted = true;
            }

            Thread nativeThread = new Thread(() -> {
                try {
                    TProxyService.TProxyStartService(nextConfig.getAbsolutePath(), nextTun.getFd());
                } catch (Throwable t) {
                    Log.e(TAG, "tun2socks native service failed", t);
                    fail("tun2socks 启动失败：" + safeMessage(t));
                }
            }, "RYLUX-HevTun");
            nativeThread.start();

            Thread.sleep(500L);
            synchronized (lifecycleLock) {
                if (!starting) return;
                starting = false;
                running = true;
            }
            updateNotification("游戏 relay 运行中 · 仅接管封神榜");
            broadcast("游戏 relay 已启动");
        } catch (Throwable t) {
            fail("relay 启动失败：" + safeMessage(t));
        }
    }

    private void scheduleIdleStop() {
        synchronized (lifecycleLock) {
            if (!running || !acceptedSession) return;
            if (idleScheduler == null) {
                idleScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread thread = new Thread(r, "RYLUX-Relay-Idle");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            idleScheduler.schedule(() -> {
                if (running && acceptedSession && sessions.get() == 0) {
                    stopRelay();
                    stopSelf();
                }
            }, IDLE_STOP_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void writeConfig(File file) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建 relay 配置目录");
        }
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(
                    "tunnel:\n" +
                    "  name: RYLUX\n" +
                    "  mtu: 1500\n" +
                    "  ipv4: 198.18.0.1\n" +
                    "  icmp: 'off'\n" +
                    "socks5:\n" +
                    "  address: 127.0.0.1\n" +
                    "  port: " + RelaySocks5Server.PORT + "\n" +
                    "  udp: 'udp'\n" +
                    "misc:\n" +
                    "  connect-timeout: 15000\n" +
                    "  tcp-read-write-timeout: 600000\n" +
                    "  log-level: warn\n"
            );
        }
    }

    private void stopRelay() {
        synchronized (lifecycleLock) {
            starting = false;
            running = false;
            ScheduledExecutorService scheduler = idleScheduler;
            idleScheduler = null;
            if (scheduler != null) scheduler.shutdownNow();

            if (nativeStarted) {
                try { TProxyService.TProxyStopService(); } catch (Throwable t) {
                    Log.w(TAG, "tun2socks stop failed", t);
                }
                nativeStarted = false;
            }

            RelaySocks5Server server = socksServer;
            socksServer = null;
            if (server != null) server.close();

            ParcelFileDescriptor tun = tunInterface;
            tunInterface = null;
            if (tun != null) try { tun.close(); } catch (Exception ignored) {}
            configFile = null;
            sessions.set(0);
            acceptedSession = false;
            relayToken = null;
        }
        broadcast("游戏 relay 已停止");
    }

    private void fail(String message) {
        lastError = message == null ? "relay 启动失败" : message;
        Log.e(TAG, lastError);
        stopRelay();
        broadcast(lastError);
        stopSelf();
    }

    @Override public void onRevoke() {
        stopRelay();
        stopSelf();
        super.onRevoke();
    }

    @Override public void onDestroy() {
        stopRelay();
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }

    public static boolean isRunning() { return running; }
    public static boolean isStarting() { return starting; }
    public static String error() { return lastError; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    "rylux_game_relay",
                    "RYLUX 游戏网络优化",
                    NotificationManager.IMPORTANCE_LOW
            );
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this,
                2,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "rylux_game_relay")
                : new Notification.Builder(this);
        return builder.setContentTitle("RYLUX")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }

    private void broadcast(String message) {
        Intent intent = new Intent(ACTION_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(intent);
    }

    private static String safeMessage(Throwable t) {
        String message = t == null ? null : t.getMessage();
        return message == null || message.trim().isEmpty()
                ? (t == null ? "unknown error" : t.getClass().getSimpleName())
                : message;
    }
}
