package com.example.pakredirect;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/** Compatibility facade for the authenticated, encrypted Lua bundle updater. */
final class RemoteLuaScriptStore {
    private static final String TAG = "RYLUX-RemoteLua";
    private static final String ASSET_ROOT = "script_modules/";
    private static final Object REFRESH_LOCK = new Object();
    private static boolean refreshInProgress;
    private static long lastRefreshAttemptAt;
    private static CountDownLatch refreshFinished = new CountDownLatch(0);

    private RemoteLuaScriptStore() {}

    static CountDownLatch refreshInBackground(Context sourceContext) {
        Context context = sourceContext.getApplicationContext();
        final CountDownLatch finished;
        synchronized (REFRESH_LOCK) {
            long now = System.currentTimeMillis();
            if (refreshInProgress || now - lastRefreshAttemptAt < 30_000L) return refreshFinished;
            refreshInProgress = true;
            lastRefreshAttemptAt = now;
            finished = new CountDownLatch(1);
            refreshFinished = finished;
        }
        try {
            Thread worker = new Thread(() -> {
                try {
                    refresh(context, message -> Log.i(TAG, message));
                } finally {
                    finished.countDown();
                    synchronized (REFRESH_LOCK) { refreshInProgress = false; }
                }
            }, "RYLUX-RemoteLua");
            worker.setDaemon(true);
            worker.start();
        } catch (RuntimeException error) {
            finished.countDown();
            synchronized (REFRESH_LOCK) { refreshInProgress = false; }
            Log.w(TAG, "Unable to start background Lua update", error);
        }
        return finished;
    }

    static String refresh(Context context, Consumer<String> status) {
        return LuaScriptUpdateManager.prepare(context, status);
    }

    static String currentVersion(Context context) {
        return LuaScriptUpdateManager.currentVersion(context);
    }

    static InputStream open(Context context, String modulePath) throws IOException {
        return open(context, modulePath, currentVersion(context));
    }

    static InputStream open(Context context, String modulePath, String pinnedVersion)
            throws IOException {
        try {
            return LuaScriptUpdateManager.open(context, ASSET_ROOT + modulePath, pinnedVersion);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("无法打开 Lua 模块", error);
        }
    }
}
