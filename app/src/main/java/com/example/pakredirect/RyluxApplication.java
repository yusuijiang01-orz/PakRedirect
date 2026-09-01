package com.example.pakredirect;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

public class RyluxApplication extends Application {
    private boolean updateChecked;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                if (activity instanceof MainActivity) {
                    RyluxUiPolish.attach(activity);
                    RyluxResponsiveTuner.attach(activity);
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
                    RyluxResponsiveTuner.detach(activity);
                    RyluxUiPolish.detach(activity);
                }
            }
        });
    }
}
