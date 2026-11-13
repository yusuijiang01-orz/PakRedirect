package com.example.pakredirect;

import android.app.ActivityManager;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Point;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.os.Bundle;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.ArrayDeque;

import top.niunaijun.blackbox.fake.frameworks.BActivityManager;

/** Display-level screenshot and touch adapter. The user must enable its accessibility service. */
public final class ScriptGameAdapter extends AccessibilityService {
    private static final String TAG = "ScriptGameAdapter";
    private static final String SEMANTIC_NODE_PREFIX = "rylux.game.";
    private static final long RECONNECT_WAIT_MS = 10_000L;
    static final String TARGET_GAME_PACKAGE = "com.tepaylink.tamgioiphantranhmobile";
    private static volatile ScriptGameAdapter instance;
    private static volatile String targetMode = ScriptToolsActivity.TARGET_MODE_SANDBOX;
    private volatile ForegroundEvent foregroundEvent = ForegroundEvent.EMPTY;
    private volatile String targetGameLabel = "";
    private volatile String crashDialogPackage = "";
    private volatile String crashDialogClass = "";
    private volatile String crashDialogText = "";
    private volatile long crashDialogObservedAt;

    private static final long CRASH_DIALOG_TTL_MS = 60_000L;

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

    private static final class ScreenshotWindow {
        final int id;
        final Rect bounds;

        ScreenshotWindow(int id, Rect bounds) {
            this.id = id;
            this.bounds = bounds;
        }
    }

    public static boolean isConnected() { return instance != null; }

    static void setTargetMode(String mode) {
        targetMode = ScriptToolsActivity.TARGET_MODE_DESKTOP.equals(mode)
                ? ScriptToolsActivity.TARGET_MODE_DESKTOP : ScriptToolsActivity.TARGET_MODE_SANDBOX;
        ScriptGameAdapter current = instance;
        if (current != null) current.targetGameLabel = current.loadTargetGameLabel();
    }

    public static ScriptGameAdapter requireConnected() {
        ScriptGameAdapter current = instance;
        if (current == null) throw new IllegalStateException("请先启用 RYLUX 游戏输入服务");
        return current;
    }

    /** Wait briefly for Android to rebind the accessibility service after a transient disconnect. */
    public static ScriptGameAdapter requireConnected(ScriptExecutionControl control) {
        return ScriptAdapterConnectionGate.await(() -> instance, control, RECONNECT_WAIT_MS,
                "RYLUX 游戏输入服务连接中断，等待 Android 重连超时");
    }

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        foregroundEvent = ForegroundEvent.EMPTY;
        instance = this;
        targetGameLabel = loadTargetGameLabel();
        Log.i(TAG, "accessibility service connected; instance=" + System.identityHashCode(this));
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getEventType() != AccessibilityEvent.TYPE_WINDOWS_CHANGED)) return;
        String packageName = event.getPackageName() == null ? "" : event.getPackageName().toString();
        String className = event.getClassName() == null ? "" : event.getClassName().toString();
        // Window-visibility changes from the RYLUX overlay can have no package
        // or describe the workbench itself. Do not let those events erase the
        // last real game/proxy event used to identify a window with no root node.
        boolean ryluxWorkbenchWindow = getPackageName().equals(packageName)
                && !ScriptForegroundTarget.isProxyActivity(className);
        if (!packageName.isEmpty() && !ryluxWorkbenchWindow) {
            foregroundEvent = new ForegroundEvent(packageName, className, event.getWindowId());
        }
        updateCrashDialog(event);
    }
    @Override public void onInterrupt() { }

    @Override public boolean onUnbind(android.content.Intent intent) {
        if (instance == this) instance = null;
        Log.w(TAG, "accessibility service unbound; instance=" + System.identityHashCode(this));
        return super.onUnbind(intent);
    }

    @Override public void onRebind(android.content.Intent intent) {
        super.onRebind(intent);
        foregroundEvent = ForegroundEvent.EMPTY;
        instance = this;
        Log.i(TAG, "accessibility service rebound; instance=" + System.identityHashCode(this));
    }

    public static String foregroundPackage() {
        ScriptGameAdapter current = instance;
        if (current == null) return "";
        // Legacy game scripts expect the guest package name, not BlackBox's Android host package.
        if (current.hasTargetGameForeground()) return TARGET_GAME_PACKAGE;
        // A desktop copy and a sandbox copy share one Android package name. Do not
        // report the desktop event as a match when the selected target is the sandbox.
        if (TARGET_GAME_PACKAGE.equals(current.foregroundEvent.packageName)) return "";
        return current.foregroundEvent.packageName;
    }

    public static boolean isTargetGameForeground() {
        ScriptGameAdapter current = instance;
        return current != null && current.hasTargetGameForeground();
    }

    static boolean isTargetCrashDialogVisible() {
        ScriptGameAdapter current = instance;
        return current != null && current.hasRecentTargetCrashDialog();
    }

    static boolean dismissTargetCrashDialog() {
        ScriptGameAdapter current = instance;
        return current != null && current.dismissCrashDialog();
    }

    public static String foregroundDiagnostic() {
        ScriptGameAdapter current = instance;
        return current == null ? "无障碍服务未连接" : current.describeForegroundState();
    }

    private boolean hasTargetGameForeground() {
        ForegroundEvent event = foregroundEvent;
        boolean currentWindow = false;
        int activeWindowId = -1;
        String activeWindowPackage = null;
        String activeWindowTitle = null;
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
                    activeWindowId = window.getId();
                    CharSequence title = window.getTitle();
                    activeWindowTitle = title == null ? null : title.toString();
                    // In BlackBox the Android window belongs to the host app even though
                    // its rendered content and virtual activity belong to the guest game.
                    AccessibilityNodeInfo root = null;
                    try {
                        root = window.getRoot();
                        CharSequence rootPackage = root == null ? null : root.getPackageName();
                        if (rootPackage != null && rootPackage.length() > 0) {
                            activeWindowPackage = rootPackage.toString();
                        }
                    } catch (RuntimeException ignored) {
                        // Keep the active-window fact and title for the null-root check.
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
        String hostTopActivityClass = null;
        // Diagnostics print an empty package and title as "activeWindow=/" even
        // when no application window is marked active. Check the two live top
        // activities in that case as well; the foreground rule requires both.
        if (!currentWindow || ScriptForegroundTarget.isUnavailableWindowPackage(activeWindowPackage)
                || getPackageName().equals(activeWindowPackage)) {
            try {
                ComponentName top = BActivityManager.get().getTopActivity(0);
                if (top != null) virtualTopPackage = top.getPackageName();
            } catch (RuntimeException ignored) {
                // A failed BlackBox query must keep input blocked.
            }
            try {
                ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                List<ActivityManager.RunningTaskInfo> tasks = activityManager == null
                        ? null : activityManager.getRunningTasks(1);
                if (tasks != null && !tasks.isEmpty() && tasks.get(0).topActivity != null) {
                    hostTopActivityClass = tasks.get(0).topActivity.getClassName();
                }
            } catch (RuntimeException ignored) {
                // This is only a fallback for host windows whose title is generic.
            }
        }
        if (ScriptToolsActivity.TARGET_MODE_DESKTOP.equals(targetMode)) {
            boolean directPackageWindow = currentWindow
                    && TARGET_GAME_PACKAGE.equals(activeWindowPackage);
            boolean directEventForActiveWindow = currentWindow
                    && ScriptForegroundTarget.isUnavailableWindowPackage(activeWindowPackage)
                    && activeWindowId == event.windowId
                    && TARGET_GAME_PACKAGE.equals(event.packageName);
            return directPackageWindow || directEventForActiveWindow;
        }
        // Android and BlackBox can contain the same package simultaneously. A desktop
        // copy must not satisfy a run explicitly configured for the sandbox copy.
        if (TARGET_GAME_PACKAGE.equals(activeWindowPackage)) return false;
        return ScriptForegroundTarget.isTargetForeground(
                currentWindow, activeWindowPackage, activeWindowTitle, targetGameLabel,
                event.packageName, event.className, hostTopActivityClass,
                virtualTopPackage, TARGET_GAME_PACKAGE);
    }

    private String loadTargetGameLabel() {
        if (ScriptToolsActivity.TARGET_MODE_DESKTOP.equals(targetMode)) {
            try {
                ApplicationInfo info = getPackageManager().getApplicationInfo(TARGET_GAME_PACKAGE, 0);
                CharSequence label = getPackageManager().getApplicationLabel(info);
                if (label != null) return label.toString();
            } catch (Exception ignored) {
                // Package visibility or a not-installed desktop client leaves only package matching.
            }
        }
        try {
            for (ApplicationInfo info : top.niunaijun.blackbox.BlackBoxCore.get()
                    .getInstalledApplications(0, 0)) {
                if (info == null || !TARGET_GAME_PACKAGE.equals(info.packageName)) continue;
                CharSequence label = info.loadLabel(top.niunaijun.blackbox.BlackBoxCore.getPackageManager());
                if (label != null) return label.toString();
            }
        } catch (RuntimeException ignored) {
            // The proxy event/package checks remain available if the sandbox is not ready.
        }
        return "";
    }

    private void updateCrashDialog(AccessibilityEvent event) {
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        String cls = event.getClassName() == null ? "" : event.getClassName().toString();
        if (!ScriptCrashDialogDetector.isPotentialSystemDialog(pkg, cls)) return;
        StringBuilder text = new StringBuilder();
        if (event.getText() != null) {
            for (CharSequence item : event.getText()) {
                if (item != null) text.append(item).append(' ');
            }
        }
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            appendNodeText(root, text, 0, new int[]{0});
        } catch (RuntimeException ignored) {
            // Event text may still be enough for a system error dialog.
        } finally {
            if (root != null) root.recycle();
        }
        if (ScriptCrashDialogDetector.isTargetCrashDialog(pkg, cls, text.toString(),
                TARGET_GAME_PACKAGE, targetGameLabel)) {
            crashDialogPackage = pkg;
            crashDialogClass = cls;
            crashDialogText = text.toString();
            crashDialogObservedAt = SystemClock.elapsedRealtime();
            Log.w(TAG, "target game crash dialog detected; " + cls);
        }
    }

    private static void appendNodeText(AccessibilityNodeInfo node, StringBuilder out,
                                       int depth, int[] visited) {
        if (node == null || depth > 12 || visited[0] >= 100) return;
        visited[0]++;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        if (text != null) out.append(text).append(' ');
        if (description != null) out.append(description).append(' ');
        for (int i = 0; i < node.getChildCount() && visited[0] < 100; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { appendNodeText(child, out, depth + 1, visited); }
            finally { child.recycle(); }
        }
    }

    private boolean hasRecentTargetCrashDialog() {
        long age = SystemClock.elapsedRealtime() - crashDialogObservedAt;
        return !crashDialogText.isEmpty() && age >= 0 && age <= CRASH_DIALOG_TTL_MS;
    }

    private boolean dismissCrashDialog() {
        if (!hasRecentTargetCrashDialog()) return false;
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            boolean clicked = clickCloseButton(root, 0, new int[]{0});
            if (clicked) {
                crashDialogText = "";
                crashDialogObservedAt = 0L;
            }
            return clicked;
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            if (root != null) root.recycle();
        }
    }

    private static boolean clickCloseButton(AccessibilityNodeInfo node, int depth, int[] visited) {
        if (node == null || depth > 12 || visited[0] >= 100) return false;
        visited[0]++;
        CharSequence text = node.getText();
        CharSequence description = node.getContentDescription();
        boolean close = ScriptCrashDialogDetector.isCloseAppButton(text == null ? "" : text.toString())
                || ScriptCrashDialogDetector.isCloseAppButton(
                        description == null ? "" : description.toString());
        if (close && node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        for (int i = 0; i < node.getChildCount() && visited[0] < 100; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { if (clickCloseButton(child, depth + 1, visited)) return true; }
            finally { child.recycle(); }
        }
        return false;
    }

    /** Return 1 for the verified login button, 0 when absent, -1 when its click fails. */
    public int accountLoginButton(boolean click) {
        requireTargetGameForeground();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return 0;
        java.util.List<AccessibilityNodeInfo> matches = null;
        try {
            matches = root.findAccessibilityNodeInfosByText("Đăng nhập");
            if (matches == null) return 0;
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            for (AccessibilityNodeInfo node : matches) {
                if (node == null || node.getText() == null || !"Đăng nhập".equalsIgnoreCase(node.getText().toString().trim())
                        || !"android.widget.Button".contentEquals(node.getClassName() == null ? "" : node.getClassName())
                        || !node.isVisibleToUser() || !node.isEnabled() || !node.isClickable()) continue;
                CharSequence pkg = node.getPackageName();
                if (pkg == null || (!TARGET_GAME_PACKAGE.contentEquals(pkg) && !getPackageName().contentEquals(pkg))) continue;
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                int x = bounds.centerX(), y = bounds.centerY();
                if (bounds.isEmpty() || x < dm.widthPixels * 0.10 || x > dm.widthPixels * 0.60
                        || y < dm.heightPixels * 0.65 || y > dm.heightPixels * 0.95) continue;
                return !click || node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ? 1 : -1;
            }
            return 0;
        } finally {
            if (matches != null) for (AccessibilityNodeInfo node : matches) if (node != null) node.recycle();
            root.recycle();
        }
    }

    private String describeForegroundState() {
        ForegroundEvent event = foregroundEvent;
        String activePackage = "";
        String activeTitle = "";
        String windowError = "";
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) {
                try {
                    for (AccessibilityWindowInfo window : windows) {
                        if (window == null || !window.isActive()
                                || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                        CharSequence title = window.getTitle();
                        activeTitle = title == null ? "" : title.toString();
                        AccessibilityNodeInfo root = null;
                        try {
                            root = window.getRoot();
                            CharSequence name = root == null ? null : root.getPackageName();
                            activePackage = name == null ? "" : name.toString();
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
        } catch (RuntimeException failure) {
            windowError = failure.getClass().getSimpleName();
        }
        String virtualTop = "";
        try {
            ComponentName top = BActivityManager.get().getTopActivity(0);
            if (top != null) virtualTop = top.flattenToShortString();
        } catch (RuntimeException failure) {
            virtualTop = "读取失败:" + failure.getClass().getSimpleName();
        }
        String hostTopActivity = "";
        try {
            ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> tasks = activityManager == null
                    ? null : activityManager.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty() && tasks.get(0).topActivity != null) {
                hostTopActivity = tasks.get(0).topActivity.flattenToShortString();
            }
        } catch (RuntimeException failure) {
            hostTopActivity = "读取失败:" + failure.getClass().getSimpleName();
        }
        return "targetMode=" + (ScriptToolsActivity.TARGET_MODE_DESKTOP.equals(targetMode) ? "desktop" : "sandbox")
                + ", activeWindow=" + activePackage + "/" + activeTitle
                + ", event=" + event.packageName + "/" + event.className + "#" + event.windowId
                + ", hostTop=" + hostTopActivity + ", virtualTop=" + virtualTop
                + ", gameLabel=" + targetGameLabel
                + (windowError.isEmpty() ? "" : ", windowsError=" + windowError);
    }

    private void requireTargetGameForeground() {
        if (!hasTargetGameForeground()) {
            throw new TargetGameNotForegroundException("所选的封神榜客户端不在前台，已阻止截图、输入和触控（"
                    + describeForegroundState() + "）");
        }
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

    /** Sends a small set of safe key actions through the focused game editor. */
    public boolean inputKey(String requestedKey) {
        requireTargetGameForeground();
        String key = requestedKey == null ? "" : requestedKey.trim();
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        if ("escape".equals(normalized) || "esc".equals(normalized) || "back".equals(normalized)) {
            return performGlobalAction(GLOBAL_ACTION_BACK);
        }
        String shellCommand = null;
        if ("enter".equals(normalized) || "return".equals(normalized)) {
            shellCommand = "input keyevent 66";
        } else if ("backspace".equals(normalized) || "delete".equals(normalized)) {
            shellCommand = "input keyevent 67";
        } else if (key.matches("[A-Za-z0-9._-]{1,8}")) {
            // Cocos2d-x draws its coordinate fields on the GL surface. The
            // focused field often has no focused AccessibilityNodeInfo, so
            // ACTION_SET_TEXT cannot reach it. On rooted emulators/devices,
            // use Android's input dispatcher just like the ADB workflow does.
            shellCommand = "input text " + key;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return runShellInputFallback(shellCommand, key);
        AccessibilityNodeInfo focused = null;
        try {
            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused == null) focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (focused != null && focused.isEditable()) {
                CharSequence nodePackage = focused.getPackageName();
                ForegroundEvent event = foregroundEvent;
                boolean sameWindow = nodePackage != null && focused.getWindowId() == event.windowId;
                boolean directGameNode = sameWindow && TARGET_GAME_PACKAGE.contentEquals(nodePackage);
                boolean sandboxProxyNode = sameWindow && getPackageName().contentEquals(event.packageName)
                        && ScriptForegroundTarget.isProxyActivity(event.className)
                        && getPackageName().contentEquals(nodePackage);
                if (directGameNode || sandboxProxyNode) {
                    if ("enter".equals(normalized) || "return".equals(normalized)) {
                        if (Build.VERSION.SDK_INT >= 30 && focused.performAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId())) {
                            return true;
                        }
                    } else {
                        CharSequence current = focused.getText();
                        String value = current == null ? "" : current.toString();
                        if ("backspace".equals(normalized) || "delete".equals(normalized)) {
                            if (value.isEmpty()) return true;
                            int end = value.offsetByCodePoints(value.length(), -1);
                            value = value.substring(0, end);
                        } else if ("space".equals(normalized)) {
                            value += " ";
                        } else if (key.matches("[A-Za-z0-9._-]{1,8}")) {
                            value += key;
                        } else {
                            return false;
                        }
                        Bundle arguments = new Bundle();
                        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                        if (focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return true;
                    }
                }
            }
            return runShellInputFallback(shellCommand, key);
        } finally {
            if (focused != null) focused.recycle();
            root.recycle();
        }
    }

    private boolean runShellInputFallback(String command, String key) {
        if (command == null) return false;
        RootShell.Result result = RootShell.run(command);
        if (result.ok()) {
            Log.i(TAG, "Accessibility 无法输入“" + key + "”，已通过 Android 输入分发器补发");
            return true;
        }
        Log.w(TAG, "Accessibility 与 Android 输入分发器均无法输入“" + key + "”：" + result);
        return false;
    }

    public boolean clearFocusedText() { return setFocusedText(""); }

    /**
     * Read a state node published by the guest game's accessibility bridge.
     * The game-side provider must expose exactly one visible node whose content
     * description is rylux.game.state.{login,role,connecting,world}. No pixels,
     * OCR, or generic host controls are used to infer the state.
     */
    public String semanticGameState() {
        requireTargetGameForeground();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "unknown";
        int windowId = root.getWindowId();
        if (!isCurrentTargetWindow(windowId)) {
            root.recycle();
            return "unknown";
        }
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        String state = null;
        int visited = 0;
        try {
            while (!pending.isEmpty() && visited++ < 512) {
                AccessibilityNodeInfo node = pending.removeFirst();
                try {
                    CharSequence description = node.getContentDescription();
                    if (node.isVisibleToUser() && description != null) {
                        String value = description.toString();
                        if (value.startsWith(SEMANTIC_NODE_PREFIX + "state.")) {
                            String candidate = value.substring((SEMANTIC_NODE_PREFIX + "state.").length());
                            if (!isSemanticState(candidate) || state != null) return "unknown";
                            state = candidate;
                        }
                    }
                    for (int i = 0; i < node.getChildCount() && pending.size() < 512; i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) pending.addLast(child);
                    }
                } finally {
                    node.recycle();
                }
            }
            return state == null ? "unknown" : state;
        } finally {
            while (!pending.isEmpty()) pending.removeFirst().recycle();
        }
    }

    /** Activate only an explicitly published guest-game semantic action node. */
    public boolean clickSemanticGameAction(String action) {
        if (!("login".equals(action) || "confirm".equals(action) || "back".equals(action))) {
            throw new IllegalArgumentException("不支持的游戏语义动作");
        }
        requireTargetGameForeground();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        int windowId = root.getWindowId();
        if (!isCurrentTargetWindow(windowId)) {
            root.recycle();
            return false;
        }
        String expected = SEMANTIC_NODE_PREFIX + "action." + action;
        ArrayDeque<AccessibilityNodeInfo> pending = new ArrayDeque<>();
        pending.add(root);
        AccessibilityNodeInfo match = null;
        int visited = 0;
        try {
            while (!pending.isEmpty() && visited++ < 512) {
                AccessibilityNodeInfo node = pending.removeFirst();
                CharSequence description = node.getContentDescription();
                if (node.isVisibleToUser() && node.isEnabled() && description != null
                        && expected.contentEquals(description)) {
                    if (match != null) {
                        node.recycle();
                        match.recycle();
                        match = null;
                        return false;
                    }
                    match = node;
                    continue;
                }
                try {
                    for (int i = 0; i < node.getChildCount() && pending.size() < 512; i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) pending.addLast(child);
                    }
                } finally {
                    if (node != match) node.recycle();
                }
            }
            return match != null && match.isClickable() && isCurrentTargetWindow(windowId)
                    && match.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } finally {
            if (match != null) match.recycle();
            while (!pending.isEmpty()) pending.removeFirst().recycle();
        }
    }

    private static boolean isSemanticState(String state) {
        return "login".equals(state) || "role".equals(state)
                || "connecting".equals(state) || "world".equals(state);
    }

    private boolean isCurrentTargetWindow(int windowId) {
        ForegroundEvent event = foregroundEvent;
        return windowId == event.windowId && hasTargetGameForeground();
    }

    @Override public void onDestroy() {
        if (instance == this) instance = null;
        Log.w(TAG, "accessibility service destroyed; instance=" + System.identityHashCode(this));
        super.onDestroy();
    }

    public synchronized Bitmap captureDisplay(long timeoutMs) throws Exception {
        if (Build.VERSION.SDK_INT < 30) {
            throw new UnsupportedOperationException("系统版本过低：截图需要 Android 11 或更新版本");
        }
        if (Build.VERSION.SDK_INT >= 34) {
            return captureTargetWindow(timeoutMs);
        }
        // Android 11–13 do not have the per-window screenshot API. Retain the
        // old unobstructed-display fallback there; Android 14+ never toggles
        // overlay visibility, so screenshots do not make the panel blink.
        if (!ScriptControlOverlayService.setScreenshotCaptureSuppressed(true, 1_000L)) {
            throw new IllegalStateException("无法暂时从游戏截图中隐藏 RYLUX 悬浮窗");
        }
        try {
            // Wait for WindowManager to commit the hidden overlay before the
            // display compositor produces a screenshot.
            SystemClock.sleep(90L);
            return captureDisplayInternal(timeoutMs, false);
        } finally {
            if (!ScriptControlOverlayService.setScreenshotCaptureSuppressed(false, 1_000L)) {
                Log.w(TAG, "could not restore RYLUX overlay after screenshot capture");
            }
        }
    }

    private Bitmap captureTargetWindow(long timeoutMs) throws Exception {
        return captureDisplayInternal(timeoutMs, true);
    }

    private Bitmap captureDisplayInternal(long timeoutMs, boolean targetWindowOnly) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + Math.max(1L, timeoutMs);
        int lastErrorCode = 0;
        int internalErrorRetries = 0;
        do {
            requireTargetGameForeground();
            ScreenshotWindow targetWindow = targetWindowOnly ? resolveActiveApplicationWindow() : null;
            CountDownLatch done = new CountDownLatch(1);
            Bitmap[] result = new Bitmap[1];
            Throwable[] failure = new Throwable[1];
            int[] errorCode = new int[1];
            TakeScreenshotCallback callback = new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult screenshot) {
                    HardwareBuffer buffer = screenshot.getHardwareBuffer();
                    try {
                        Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer,
                                ColorSpace.get(ColorSpace.Named.SRGB));
                        if (hardware == null) {
                            failure[0] = new IllegalStateException("截图缓冲区不可用");
                        } else {
                            Bitmap captured = hardware.copy(Bitmap.Config.ARGB_8888, false);
                            hardware.recycle();
                            if (captured == null) {
                                failure[0] = new IllegalStateException("无法读取截图像素");
                            } else if (targetWindowOnly) {
                                try {
                                    result[0] = expandWindowFrameToDisplay(captured, targetWindow.bounds);
                                } catch (Throwable normalizeFailure) {
                                    if (!captured.isRecycled()) captured.recycle();
                                    failure[0] = normalizeFailure;
                                }
                            } else {
                                result[0] = captured;
                            }
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
            };
            if (targetWindowOnly) {
                takeScreenshotOfWindow(targetWindow.id, getMainExecutor(), callback);
            } else {
                takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), callback);
            }
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0 || !done.await(remaining, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("等待游戏截图超时");
            }
            if (result[0] != null) return result[0];
            if (failure[0] != null) throw new IllegalStateException("无法获取游戏画面", failure[0]);
            lastErrorCode = errorCode[0];
            long retryDelay = 250L;
            if (lastErrorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                internalErrorRetries = 0;
            } else if (Build.VERSION.SDK_INT >= 34
                    && lastErrorCode == ERROR_TAKE_SCREENSHOT_INVALID_WINDOW) {
                // A game activity can replace its window during scene changes.
                // Re-resolve the active application window on the next attempt.
                SystemClock.sleep(Math.min(100L,
                        Math.max(0L, deadline - SystemClock.elapsedRealtime())));
                continue;
            } else if (lastErrorCode == ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR) {
                // BlackBox/emulator transitions can briefly make Android's screenshot backend
                // return INTERNAL_ERROR even though the guest window is still foreground.
                // Retry a few times with bounded backoff, then surface the original failure.
                retryDelay = ScriptScreenshotRetry.internalErrorDelayMs(++internalErrorRetries);
                if (retryDelay < 0) {
                    throw new IllegalStateException("系统截图失败：" + screenshotErrorDescription(lastErrorCode)
                            + "（错误码 " + lastErrorCode + "，重试 3 次仍失败）");
                }
            } else {
                throw new IllegalStateException("系统截图失败：" + screenshotErrorDescription(lastErrorCode)
                        + "（错误码 " + lastErrorCode + "）");
            }
            long delay = Math.min(retryDelay, deadline - SystemClock.elapsedRealtime());
            if (delay > 0) SystemClock.sleep(delay);
        } while (SystemClock.elapsedRealtime() < deadline);
        throw new IllegalStateException("系统截图请求过于频繁，等待重试超时（错误码 " + lastErrorCode + "）");
    }

    private ScreenshotWindow resolveActiveApplicationWindow() {
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (RuntimeException unavailable) {
            windows = null;
        }
        if (windows != null) {
            try {
                ScreenshotWindow eventMatch = null;
                for (AccessibilityWindowInfo window : windows) {
                    if (window == null || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    Rect bounds = new Rect();
                    window.getBoundsInScreen(bounds);
                    ScreenshotWindow candidate = new ScreenshotWindow(window.getId(), bounds);
                    if (window.isActive()) return candidate;
                    if (candidate.id == foregroundEvent.windowId) eventMatch = candidate;
                }
                if (eventMatch != null) return eventMatch;
            } finally {
                for (AccessibilityWindowInfo window : windows) {
                    if (window != null) window.recycle();
                }
            }
        }
        throw new TargetGameNotForegroundException("无法定位当前游戏窗口；未截取整屏或悬浮窗（"
                + describeForegroundState() + "）");
    }

    private Bitmap expandWindowFrameToDisplay(Bitmap windowFrame, Rect windowBounds) {
        if (windowFrame == null || windowFrame.isRecycled()) {
            throw new IllegalArgumentException("游戏窗口截图不可用");
        }
        Display display = getSystemService(android.view.WindowManager.class).getDefaultDisplay();
        Point displaySize = new Point();
        display.getRealSize(displaySize);
        if (windowFrame.getWidth() == displaySize.x && windowFrame.getHeight() == displaySize.y) {
            return windowFrame;
        }
        if (windowBounds == null || windowBounds.isEmpty()
                || displaySize.x <= 0 || displaySize.y <= 0) {
            throw new IllegalStateException("无法把游戏窗口截图映射回设备坐标");
        }
        Bitmap displayFrame = Bitmap.createBitmap(displaySize.x, displaySize.y, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(displayFrame);
        canvas.drawColor(android.graphics.Color.BLACK);
        canvas.drawBitmap(windowFrame, null, windowBounds, null);
        windowFrame.recycle();
        return displayFrame;
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

    public boolean doubleTap(float x, float y, long timeoutMs) throws Exception {
        requireTargetGameForeground();
        if (Build.VERSION.SDK_INT < 24) throw new UnsupportedOperationException("触控需要 Android 7 或更新版本");
        if (!ScriptControlOverlayService.setGameInputPassthrough(true, 1_000L)) {
            throw new IllegalStateException("无法暂时让 RYLUX 悬浮窗为游戏触控让路");
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);
        try {
            Path firstTap = new Path();
            firstTap.moveTo(x, y);
            Path secondTap = new Path();
            secondTap.moveTo(x, y);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(firstTap, 0, 55))
                    .addStroke(new GestureDescription.StrokeDescription(secondTap, 155, 55))
                    .build();
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
        } finally {
            if (!ScriptControlOverlayService.setGameInputPassthrough(false, 1_000L)) {
                Log.e(TAG, "could not restore RYLUX overlay touch controls after a game double-tap");
            }
        }
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
        long deadline = SystemClock.elapsedRealtime() + Math.max(1L, timeoutMs);
        for (int attempt = 1; attempt <= 3; attempt++) {
            requireTargetGameForeground();
            if (!ScriptControlOverlayService.setGameInputPassthrough(true, 1_000L)) {
                throw new IllegalStateException("无法暂时让 RYLUX 悬浮窗为游戏触控让路");
            }
            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean completed = new AtomicBoolean(false);
            AtomicBoolean cancelled = new AtomicBoolean(false);
            try {
                // WindowManager updates the overlay flags asynchronously on some emulator builds.
                // Give the non-touchable state a short moment to reach the input dispatcher.
                SystemClock.sleep(60L);
                GestureDescription gesture = new GestureDescription.Builder()
                        .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs)).build();
                boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription description) {
                        completed.set(true);
                        done.countDown();
                    }

                    @Override public void onCancelled(GestureDescription description) {
                        cancelled.set(true);
                        done.countDown();
                    }
                }, null);
                if (accepted) {
                    long remaining = deadline - SystemClock.elapsedRealtime();
                    if (remaining > 0 && done.await(remaining, TimeUnit.MILLISECONDS) && completed.get()) {
                        return true;
                    }
                    Log.w(TAG, "game gesture did not complete; attempt=" + attempt
                            + ", cancelled=" + cancelled.get());
                } else {
                    Log.w(TAG, "Android rejected game gesture; attempt=" + attempt);
                }
            } finally {
                if (!ScriptControlOverlayService.setGameInputPassthrough(false, 1_000L)) {
                    Log.e(TAG, "could not restore RYLUX overlay touch controls after a game gesture");
                }
            }
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (attempt == 3 || remaining <= 0) break;
            SystemClock.sleep(Math.min(100L, remaining));
        }
        return false;
    }
}
