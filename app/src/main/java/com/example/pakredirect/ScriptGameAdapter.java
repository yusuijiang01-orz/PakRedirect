package com.example.pakredirect;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.os.Bundle;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Display-level screenshot and touch adapter. The user must enable its accessibility service. */
public final class ScriptGameAdapter extends AccessibilityService {
    static final String TARGET_GAME_PACKAGE = "com.tepaylink.tamgioiphantranhmobile";
    private static volatile ScriptGameAdapter instance;
    private volatile String foregroundPackage = "";

    public static boolean isConnected() { return instance != null; }

    public static ScriptGameAdapter requireConnected() {
        ScriptGameAdapter current = instance;
        if (current == null) throw new IllegalStateException("请先启用 RYLUX 游戏输入服务");
        return current;
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        foregroundPackage = "";
        instance = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (event.getPackageName() == null) {
            foregroundPackage = "";
            return;
        }
        // Include RYLUX itself: otherwise the last game package could remain cached
        // after the user returns to the console and incorrectly pass startup gating.
        foregroundPackage = event.getPackageName().toString();
    }
    @Override public void onInterrupt() { }

    public static String foregroundPackage() {
        ScriptGameAdapter current = instance;
        return current == null ? "" : current.foregroundPackage;
    }

    private void requireTargetGameForeground() {
        ScriptForegroundGate.requireReady(
                () -> TARGET_GAME_PACKAGE.equals(foregroundPackage),
                "沙箱中的汉化版封神榜不在前台，已阻止截图、输入和触控");
    }

    public boolean pressHome() {
        return performGlobalAction(GLOBAL_ACTION_HOME);
    }

    /** Set text in the currently focused editable node exposed by the game. */
    public boolean setFocusedText(String text) {
        requireTargetGameForeground();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo focused = null;
        try {
            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (focused == null || !focused.isEditable()) return false;
            CharSequence nodePackage = focused.getPackageName();
            if (nodePackage == null || !TARGET_GAME_PACKAGE.contentEquals(nodePackage)) return false;
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text == null ? "" : text);
            return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        } finally {
            if (focused != null) focused.recycle();
            root.recycle();
        }
    }

    public boolean clearFocusedText() { return setFocusedText(""); }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public Bitmap captureDisplay(long timeoutMs) throws Exception {
        requireTargetGameForeground();
        if (Build.VERSION.SDK_INT < 30) {
            throw new UnsupportedOperationException("系统版本过低：截图需要 Android 11 或更新版本");
        }
        CountDownLatch done = new CountDownLatch(1);
        Bitmap[] result = new Bitmap[1];
        Throwable[] failure = new Throwable[1];
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult screenshot) {
                HardwareBuffer buffer = screenshot.getHardwareBuffer();
                try {
                    Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
                    if (hardware == null) {
                        failure[0] = new IllegalStateException("截图缓冲区不可用");
                    } else {
                        result[0] = hardware.copy(Bitmap.Config.ARGB_8888, false);
                        hardware.recycle();
                        if (result[0] == null) failure[0] = new IllegalStateException("无法读取截图像素");
                    }
                } catch (Throwable error) {
                    failure[0] = error;
                } finally {
                    buffer.close();
                    done.countDown();
                }
            }

            @Override public void onFailure(int errorCode) {
                failure[0] = new IllegalStateException("系统截图失败，错误码 " + errorCode);
                done.countDown();
            }
        });
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new IllegalStateException("等待游戏截图超时");
        if (failure[0] != null) throw new IllegalStateException("无法获取游戏画面", failure[0]);
        return result[0];
    }

    public boolean tap(float x, float y, long durationMs, long timeoutMs) throws Exception {
        Path path = new Path();
        path.moveTo(x, y);
        return dispatch(path, Math.max(1L, durationMs), timeoutMs);
    }

    public boolean swipe(float x1, float y1, float x2, float y2,
                         long durationMs, long timeoutMs) throws Exception {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        return dispatch(path, Math.max(1L, durationMs), timeoutMs);
    }

    private boolean dispatch(Path path, long durationMs, long timeoutMs) throws Exception {
        requireTargetGameForeground();
        if (Build.VERSION.SDK_INT < 24) throw new UnsupportedOperationException("触控需要 Android 7 或更新版本");
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs)).build();
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override public void onCompleted(GestureDescription description) {
                completed.set(true);
                done.countDown();
            }

            @Override public void onCancelled(GestureDescription description) {
                done.countDown();
            }
        }, null);
        return accepted && done.await(timeoutMs, TimeUnit.MILLISECONDS) && completed.get();
    }
}
