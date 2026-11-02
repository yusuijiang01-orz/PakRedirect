package com.example.pakredirect;

import android.app.Activity;
import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import top.niunaijun.blackbox.BlackBoxCore;

/** Runs only while a BlackBox game activity is resumed and publishes read-only snapshots. */
public final class SandboxGameMemoryBridge {
    private static final String TAG = "RYLUX-BagPublisher";
    private static final String GAME_PACKAGE = "com.tepaylink.tamgioiphantranhmobile";
    private static final String ENDPOINT_FILE = "sandbox-bag-relay.endpoint";
    private static final Object LOCK = new Object();
    private static final Set<Activity> RESUMED_ACTIVITIES = Collections.newSetFromMap(new WeakHashMap<>());
    private static Thread worker;

    private SandboxGameMemoryBridge() { }

    public static void onGameActivityResumed(Activity activity) {
        if (!isGameActivity(activity)) return;
        synchronized (LOCK) {
            RESUMED_ACTIVITIES.add(activity);
            ensureWorkerLocked();
        }
    }

    public static void onGameActivityPaused(Activity activity) {
        deactivate(activity);
    }

    public static void onGameActivityDestroyed(Activity activity) {
        deactivate(activity);
    }

    private static boolean isGameActivity(Activity activity) {
        try { return activity != null && GAME_PACKAGE.equals(activity.getPackageName()); }
        catch (RuntimeException unavailable) { return false; }
    }

    private static void deactivate(Activity activity) {
        synchronized (LOCK) {
            if (activity != null) RESUMED_ACTIVITIES.remove(activity);
            if (RESUMED_ACTIVITIES.isEmpty() && worker != null) worker.interrupt();
        }
    }

    private static void publishLoop() {
        Thread current = Thread.currentThread();
        WeakReference<Thread> workerRef = new WeakReference<>(current);
        while (!Thread.currentThread().isInterrupted()) {
            synchronized (LOCK) {
                if (RESUMED_ACTIVITIES.isEmpty()) break;
            }
            try {
                Context hostContext = BlackBoxCore.getContext();
                Endpoint endpoint = readEndpoint(hostContext);
                if (endpoint != null) {
                    String payload = SandboxGameMemoryNative.read();
                    sendSnapshot(endpoint, payload);
                }
                Thread.sleep(1_000L);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            } catch (Throwable failure) {
                Log.w(TAG, "Snapshot publish attempt failed", failure);
                try { Thread.sleep(1_000L); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            }
        }
        synchronized (LOCK) {
            if (workerRef.get() == worker) worker = null;
            if (!RESUMED_ACTIVITIES.isEmpty()) ensureWorkerLocked();
        }
    }

    private static void ensureWorkerLocked() {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(SandboxGameMemoryBridge::publishLoop, "rylux-sandbox-bag-publisher");
        worker.setDaemon(true);
        worker.start();
    }

    private static Endpoint readEndpoint(Context context) {
        if (context == null || !"com.example.pakredirect".equals(context.getPackageName())) return null;
        File file = new File(context.getFilesDir(), ENDPOINT_FILE);
        if (!file.isFile()) return null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String socketName = reader.readLine();
            String token = reader.readLine();
            if (socketName == null || token == null || socketName.length() > 100 || token.length() != 32)
                return null;
            return new Endpoint(socketName, token);
        } catch (IOException | SecurityException unavailable) {
            return null;
        }
    }

    private static void sendSnapshot(Endpoint endpoint, String payload) {
        if (payload == null || payload.indexOf('\n') >= 0 || payload.indexOf('\r') >= 0) return;
        LocalSocket socket = new LocalSocket();
        try {
            socket.connect(new LocalSocketAddress(endpoint.socketName,
                    LocalSocketAddress.Namespace.ABSTRACT), 1_000);
            OutputStreamWriter writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(endpoint.token);
            writer.write('|');
            writer.write(GAME_PACKAGE);
            writer.write('|');
            writer.write(payload);
            writer.write('\n');
            writer.flush();
        } catch (IOException ignored) {
            // The host relay is intentionally absent outside an active script run.
        } finally {
            try { socket.close(); }
            catch (IOException ignored) { }
        }
    }

    private static final class Endpoint {
        final String socketName;
        final String token;
        Endpoint(String socketName, String token) {
            this.socketName = socketName;
            this.token = token;
        }
    }
}
