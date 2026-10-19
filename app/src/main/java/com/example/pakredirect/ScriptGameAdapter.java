package com.example.pakredirect;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.os.Bundle;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;

import top.niunaijun.blackbox.fake.frameworks.BActivityManager;

/** Display-level screenshot and touch adapter. The user must enable its accessibility service. */
public final class ScriptGameAdapter extends AccessibilityService {
    static final String TARGET_GAME_PACKAGE = "com.tepaylink.tamgioiphantranhmobile";
    private static volatile ScriptGameAdapter instance;
    private volatile ForegroundEvent foregroundEvent = ForegroundEvent.EMPTY;

    private static final class ForegroundEvent {
        static final ForegroundEvent EMPTY = new ForegroundEvent("", "", -1);
        final String packageName;
        final String className;
        final int windowId;

        ForegroundEvent(String packageName, String className, int windowId) {
            this.packageName = packageName;
            this.className = className;
            this.windowId = windowId;
        }
    }

    public static boolean isConnected() { return instance != null; }

    public static ScriptGameAdapter requireConnected() {
        ScriptGameAdapter current = instance;
        if (current == null) throw new IllegalStateException("请先启用 RYLUX 游戏输入服务");
        return current;
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        foregroundEvent = ForegroundEvent.EMPTY;
        instance = this;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        // Keep the Android activity class and window identity with the package.
        // A host-package event alone also describes the RYLUX workbench.
        foregroundEvent = new ForegroundEvent(
                event.getPackageName() == null ? "" : event.getPackageName().toString(),
                event.getClassName() == null ? "" : event.getClassName().toString(),
                event.getWindowId());
    }
    @Override public void onInterrupt() { }

    public static String foregroundPackage() {
        ScriptGameAdapter current = instance;
        if (current == null) return "";
        // Legacy game scripts expect the guest package name, not BlackBox's Android host package.
        return current.hasTargetGameForeground()
                ? TARGET_GAME_PACKAGE : current.foregroundEvent.packageName;
    }

    public static boolean isTargetGameForeground() {
        ScriptGameAdapter current = instance;
        return current != null && current.hasTargetGameForeground();
    }

    private boolean hasTargetGameForeground() {
        ForegroundEvent event = foregroundEvent;
        boolean currentWindow = false;
        boolean eventWindowActive = false;
        String activeWindowPackage = null;
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (RuntimeException unavailable) {
            return false;
        }
        if (windows != null) {
            try {
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null || !window.isActive()
                            || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    currentWindow = true;
                    eventWindowActive = event.windowId >= 0 && window.getId() == event.windowId;
                    // In BlackBox the Android window belongs to the host app even though
                    // its rendered content and virtual activity belong to the guest game.
                    AccessibilityNodeInfo root = null;
                    try {
                        root = window.getRoot();
                        CharSequence rootPackage = root == null ? null : root.getPackageName();
                        if (rootPackage != null) activeWindowPackage = rootPackage.toString();
                    } catch (RuntimeException ignored) {
                        // Keep the active-window fact; the event path below is a safe fallback.
                    } finally {
                        if (root != null) root.recycle();
                    }
                    break;
                }
            } finally {
                for (AccessibilityWindowInfo window : windows) {
                    if (window != null) window.recycle();
                }
            }
        }
        String virtualTopPackage = null;
        if (currentWindow && (getPackageName().equals(activeWindowPackage)
                || getPackageName().equals(event.packageName))) {
            try {
                ComponentName top = BActivityManager.get().getTopActivity(0);
                if (top != null) virtualTopPackage = top.getPackageName();
            } catch (RuntimeException ignored) {
                // A failed BlackBox query must keep input blocked.
            }
        }
        boolean activeWindowIsSandboxGame = ScriptForegroundTarget.isActiveSandboxGameWindow(
                activeWindowPackage, event.packageName, event.className,
                virtualTopPackage, TARGET_GAME_PACKAGE);
        if (activeWindowIsSandboxGame) return true;

        // Compatibility fallback for devices where a window root cannot be retrieved.
        return ScriptForegroundTarget.isTarget(event.packageName, event.className,
                eventWindowActive, virtualTopPackage, TARGET_GAME_PACKAGE);
    }

    private void requireTargetGameForeground() {
        ScriptForegroundGate.requireReady(
                this::hasTargetGameForeground,
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
            ForegroundEvent event = foregroundEvent;
            if (nodePackage == null || focused.getWindowId() != event.windowId) return false;
            boolean directGameNode = TARGET_GAME_PACKAGE.contentEquals(nodePackage);
            boolean sandboxProxyNode = getPackageName().contentEquals(event.packageName)
                    && ScriptForegroundTarget.isProxyActivity(event.className)
                    && getPackageName().contentEquals(nodePackage);
            if (!directGameNode && !sandboxProxyNode) return false;
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
        if (Build.VERSION.SDK_INT < 30) {
            throw new UnsupportedOperationException("系统版本过低：截图需要 Android 11 或更新版本");
        }
        long deadline = SystemClock.elapsedRealtime() + Math.max(1L, timeoutMs);
        int lastErrorCode = 0;
        do {
            requireTargetGameForeground();
            CountDownLatch done = new CountDownLatch(1);
            Bitmap[] result = new Bitmap[1];
            Throwable[] failure = new Throwable[1];
            int[] errorCode = new int[1];
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult screenshot) {
                    HardwareBuffer buffer = screenshot.getHardwareBuffer();
                    try {
                        Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer,
                                ColorSpace.get(ColorSpace.Named.SRGB));
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

                @Override public void onFailure(int code) {
                    errorCode[0] = code;
                    done.countDown();
                }
            });
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0 || !done.await(remaining, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("等待游戏截图超时");
            }
            if (result[0] != null) return result[0];
            if (failure[0] != null) throw new IllegalStateException("无法获取游戏画面", failure[0]);
            lastErrorCode = errorCode[0];
            if (lastErrorCode != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                throw new IllegalStateException("系统截图失败：" + screenshotErrorDescription(lastErrorCode)
                        + "（错误码 " + lastErrorCode + "）");
            }
            long delay = Math.min(250L, deadline - SystemClock.elapsedRealtime());
            if (delay > 0) SystemClock.sleep(delay);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new IllegalStateException("系统截图请求过于频繁，等待重试超时（错误码 " + lastErrorCode + "）");
    }

    private static String screenshotErrorDescription(int errorCode) {
        if (errorCode == ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) return "无障碍服务没有截图权限";
        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return "两次截图间隔过短";
        if (errorCode == ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY) return "目标显示屏无效";
        if (Build.VERSION.SDK_INT >= 34 && errorCode == ERROR_TAKE_SCREENSHOT_INVALID_WINDOW) {
            return "目标窗口已失效";
        }
        if (errorCode == ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR) return "系统截图内部错误";
        return "未知截图错误";
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
