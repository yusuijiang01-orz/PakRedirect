package com.example.pakredirect;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Shared readiness gate for launching the localized game in BlackBox. */
public final class LocalizationLaunchPreparation {
    private static final long READY_TIMEOUT_MS = 5L * 60L * 1000L;

    public interface ProgressListener {
        void onProgress(String message);
    }

    private LocalizationLaunchPreparation() {}

    public static void prepare(Context context, ProgressListener listener) throws InterruptedException {
        Context appContext = context.getApplicationContext();
        if (!LocalizationSettings.isEnabled(appContext)) {
            throw new IllegalStateException("RYLUX 汉化开关已关闭，请在首页开启后重试");
        }
        if (InterceptService.isRunning()) return;

        LaunchProgress.begin("正在准备 RYLUX 汉化资源…");
        Intent service = new Intent(appContext, InterceptService.class)
                .setAction(InterceptService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) appContext.startForegroundService(service);
        else appContext.startService(service);

        long deadline = System.currentTimeMillis() + READY_TIMEOUT_MS;
        String lastMessage = null;
        while (System.currentTimeMillis() < deadline) {
            if (InterceptService.isRunning()) return;
            String error = LaunchProgress.error();
            if (!LaunchProgress.isStarting() && error != null && !error.trim().isEmpty()) {
                throw new IllegalStateException(error);
            }
            String message = LaunchProgress.message();
            if (listener != null && message != null && !message.trim().isEmpty()
                    && !message.equals(lastMessage)) {
                lastMessage = message;
                listener.onProgress(message);
            }
            Thread.sleep(200L);
        }
        LaunchProgress.fail("等待 RYLUX 汉化服务超时");
        String error = LaunchProgress.error();
        throw new IllegalStateException(error == null || error.trim().isEmpty()
                ? "RYLUX 汉化服务未能就绪" : error);
    }
}
