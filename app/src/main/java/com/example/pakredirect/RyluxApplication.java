package com.example.pakredirect;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.configuration.ClientConfiguration;

public class RyluxApplication extends Application {
    private static final String BLACKBOX_TAG = "RYLUX-BlackBox";
    private volatile boolean blackBoxReady;
    private volatile String blackBoxError = "";
    private boolean updateChecked;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            BlackBoxCore core = BlackBoxCore.get();
            core.closeCodeInit();
            core.onBeforeMainApplicationAttach(this, base);
            core.doAttachBaseContext(base, new ClientConfiguration() {
                @Override
                public String getHostPackageName() {
                    return base.getPackageName();
                }

                @Override
                public boolean isEnableLauncherActivity() {
                    return false;
                }
            });
            core.onAfterMainApplicationAttach(this, base);
        } catch (Throwable error) {
            blackBoxError = error.getClass().getSimpleName() + ": " + error.getMessage();
            Log.e(BLACKBOX_TAG, "BlackBox attach failed", error);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            BlackBoxCore.get().doCreate();
            blackBoxReady = true;
            blackBoxError = "";
        } catch (Throwable error) {
            blackBoxError = error.getClass().getSimpleName() + ": " + error.getMessage();
            Log.e(BLACKBOX_TAG, "BlackBox startup failed", error);
        }
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                if (activity instanceof MainActivity) {
                    RyluxUiPolish.attach(activity);
                    RyluxResponsiveTuner.attach(activity);
                    GameInstallAndMirrorHelper.onActivityResumed(activity);
                    GameApkCloudInstaller.onActivityResumed(activity);
                    RyluxHomeNavigationPolish.attach(activity);
                    RyluxAdaptiveLayoutFix.attach(activity);
                    PatchUpdateInfo.attach(activity);
                    if (!updateChecked) {
                        updateChecked = true;
                        AppUpdateChecker.check(activity);
                    }
                }
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {
                if (activity instanceof MainActivity) {
                    PatchUpdateInfo.detach(activity);
                    RyluxAdaptiveLayoutFix.detach(activity);
                    RyluxHomeNavigationPolish.detach(activity);
                    GameApkCloudInstaller.detach(activity);
                    GameInstallAndMirrorHelper.detach(activity);
                    RyluxResponsiveTuner.detach(activity);
                    RyluxUiPolish.detach(activity);
                }
            }
        });
    }

    boolean isBlackBoxReady() {
        return blackBoxReady;
    }

    String getBlackBoxError() {
        return blackBoxError;
    }
}
