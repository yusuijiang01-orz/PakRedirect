package com.example.pakredirect;

import android.app.Activity;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.LinearLayout;

import java.util.WeakHashMap;

/** Adds the game-install entry; GameApkCloudInstaller owns its click behavior. */
public final class GameInstallAndMirrorHelper {
    private static final String INSTALL_BUTTON_TAG = "rylux_install_game_button";
    private static final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> LISTENERS =
            new WeakHashMap<>();

    private GameInstallAndMirrorHelper() {}

    public static void onActivityResumed(Activity activity) {
        if (activity instanceof MainActivity) attach(activity);
    }

    public static void attach(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        synchronized (LISTENERS) {
            if (LISTENERS.containsKey(activity)) {
                activity.getWindow().getDecorView().post(() -> decorate(activity));
                return;
            }
            View decor = activity.getWindow().getDecorView();
            ViewTreeObserver.OnGlobalLayoutListener listener =
                    () -> decor.post(() -> decorate(activity));
            LISTENERS.put(activity, listener);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            decor.post(() -> decorate(activity));
        }
    }

    public static void detach(Activity activity) {
        synchronized (LISTENERS) {
            ViewTreeObserver.OnGlobalLayoutListener listener = LISTENERS.remove(activity);
            if (listener == null) return;
            View decor = activity.getWindow().getDecorView();
            ViewTreeObserver observer = decor.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listener);
        }
    }

    private static void decorate(Activity activity) {
        View startButton = findTagged(activity.getWindow().getDecorView(), "rylux_start_game_button");
        if (startButton == null || !(startButton.getParent() instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) startButton.getParent();
        if (!"rylux_game_panel_polished".equals(parent.getTag()) || hasInstallButton(parent)) return;

        Button installButton = new Button(activity);
        installButton.setTag(INSTALL_BUTTON_TAG);
        installButton.setText("安装游戏");
        installButton.setGravity(Gravity.CENTER);
        installButton.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
        RyluxUiPolish.stylePrimaryButton(activity, installButton);

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 56));
        lp.topMargin = dp(activity, 10);
        parent.addView(installButton, Math.max(0, parent.indexOfChild(startButton)), lp);
    }

    private static boolean hasInstallButton(LinearLayout parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (INSTALL_BUTTON_TAG.equals(parent.getChildAt(i).getTag())) return true;
        }
        return false;
    }

    private static View findTagged(View root, String tag) {
        if (root == null) return null;
        if (tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findTagged(group.getChildAt(i), tag);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
