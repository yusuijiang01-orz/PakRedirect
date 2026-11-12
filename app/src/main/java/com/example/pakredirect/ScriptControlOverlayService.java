package com.example.pakredirect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import top.niunaijun.blackbox.BlackBoxCore;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** RYLUX-styled floating controls for the explicitly started Lua runtime. */
public final class ScriptControlOverlayService extends Service {
    public static final String ACTION_PREVIEW = "com.example.pakredirect.script.PREVIEW_OVERLAY";
    public static final String ACTION_RUNTIME = "com.example.pakredirect.script.RUNTIME_OVERLAY";
    private static final String CHANNEL_ID = "script_control_overlay";
    private static final int NOTIFICATION_ID = 48;
    private static final int CARD = Color.rgb(28, 32, 40);
    private static final int SOFT = Color.rgb(34, 39, 48);
    private static final int BLUE = Color.rgb(72, 113, 255);
    private static final int BORDER = Color.rgb(47, 53, 64);
    private static final int TEXT = Color.rgb(245, 247, 250);
    private static final int MUTED = Color.rgb(154, 163, 176);
    private static final long IDLE_COLLAPSE_MS = 15_000L;
    private static final float COLLAPSED_ALPHA = 0.78f;
    private static final int PANEL_WIDTH_DP = 240;
    private static final int HANDLE_WIDTH_DP = 48;
    private static final int HANDLE_HEIGHT_DP = 52;
    private static volatile ScriptControlOverlayService activeInstance;

    private WindowManager windowManager;
    private View overlay;
    private WindowManager.LayoutParams params;
    private FrameLayout overlayRoot;
    private LinearLayout panel;
    private View compactHandle;
    private TextView statusLabel;
    private TextView runActionLabel;
    private TextView pauseActionLabel;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean collapsed;
    private boolean snappedRight;
    private boolean restartRequested;
    private boolean memoryProbeActive;
    private int screenshotSuppressionDepth;
    private int flagsBeforeScreenshotSuppression;
    private boolean visibleBeforeScreenshotSuppression;
    private final Runnable collapseWhenIdle = this::collapseToNearestEdge;
    private String runtimeState = ScriptExecutionControl.State.IDLE.name();
    private final BroadcastReceiver runtimeStatus = new BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            if (statusLabel == null) return;
            String state = intent.getStringExtra(ScriptRuntimeService.EXTRA_STATE);
            String message = intent.getStringExtra(ScriptRuntimeService.EXTRA_MESSAGE);
            runtimeState = state == null ? ScriptExecutionControl.State.IDLE.name() : state;
            memoryProbeActive = "MEMORY_PROBE".equals(runtimeState);
            boolean runtimeRunning = ScriptExecutionControl.State.RUNNING.name().equals(runtimeState);
            boolean runtimeFinished = ScriptExecutionControl.State.STOPPED.name().equals(runtimeState)
                    || ScriptExecutionControl.State.COMPLETED.name().equals(runtimeState)
                    || ScriptExecutionControl.State.FAILED.name().equals(runtimeState);
            if (runtimeRunning
                    || (message != null && (message.startsWith("启动失败")
                    || message.startsWith("启动被兼容检查拦截")))) {
                restartRequested = false;
            }
            statusLabel.setText((state == null ? "运行" : state) + " · " + (message == null ? "" : message));
            boolean active = isRuntimeActive();
            if (runActionLabel != null) runActionLabel.setText(active ? "停止" : "启动");
            if (pauseActionLabel != null) pauseActionLabel.setText(
                    ScriptExecutionControl.State.PAUSED.name().equals(runtimeState) ? "继续" : "暂停");
            if (runtimeRunning) {
                // Keep the expanded controller steady during a run. The game
                // adapter hides it only for each screenshot, so it cannot
                // contaminate image matching.
                mainHandler.removeCallbacks(collapseWhenIdle);
            } else if (runtimeFinished && collapsed) {
                // Bring completion and failure details back into view after
                // the controller was tucked away for unobstructed execution.
                mainHandler.removeCallbacks(collapseWhenIdle);
                expandFromEdge();
            } else if (memoryProbeActive) mainHandler.removeCallbacks(collapseWhenIdle);
            else scheduleIdleCollapse();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        activeInstance = this;
        IntentFilter filter = new IntentFilter(ScriptRuntimeService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(runtimeStatus, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(runtimeStatus, filter);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = buildOverlay();
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
                dp(PANEL_WIDTH_DP), WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(12);
        params.y = dp(180);
        try {
            windowManager.addView(overlay, params);
            scheduleIdleCollapse();
        } catch (RuntimeException error) {
            Toast.makeText(this, "悬浮窗显示失败，请检查系统悬浮窗权限", Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    private View buildOverlay() {
        overlayRoot = new FrameLayout(this);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(6), dp(8), dp(6));
        panel.setBackground(round(CARD, 18, BORDER));
        panel.setContentDescription("RYLUX 脚本控制面板");
        overlayRoot.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.START));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text("RYLUX", 12, TEXT, true);
        header.addView(mark, new LinearLayout.LayoutParams(dp(44), dp(18)));
        mark.setContentDescription("拖动 RYLUX 悬浮控制面板");
        mark.setOnTouchListener(new OverlayDragTouchListener());
        statusLabel = text("● 已就绪 · 点击启动", 9, MUTED, false);
        statusLabel.setMaxLines(1);
        statusLabel.setEllipsize(TextUtils.TruncateAt.END);
        statusLabel.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        statusLabel.setContentDescription("运行状态；点击复制完整诊断并留在游戏");
        statusLabel.setOnClickListener(v -> {
            noteInteraction();
            android.content.SharedPreferences diagnostics = getSharedPreferences(
                    "script_runtime_diagnostics", MODE_PRIVATE);
            String memoryProbe = diagnostics.getString("last_memory_probe", "");
            String lastError = diagnostics.getString("last_error", "");
            boolean memoryProbeIsNewer = diagnostics.getLong("last_memory_probe_time", 0L)
                    > diagnostics.getLong("last_error_time", 0L);
            String detail = memoryProbeIsNewer && !memoryProbe.isEmpty()
                    ? memoryProbe : lastError;
            if (detail.isEmpty()) detail = memoryProbe.isEmpty() ? "尚无诊断记录。" : memoryProbe;
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                        "RYLUX script diagnostic", detail));
                Toast.makeText(this, "完整脚本诊断已复制；游戏仍保持在前台", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, detail, Toast.LENGTH_LONG).show();
            }
        });
        header.addView(statusLabel, new LinearLayout.LayoutParams(0, dp(18), 1));
        TextView collapseLabel = text("−", 16, MUTED, true);
        collapseLabel.setGravity(Gravity.CENTER);
        collapseLabel.setContentDescription("手动收起悬浮窗");
        collapseLabel.setOnClickListener(v -> {
            mainHandler.removeCallbacks(collapseWhenIdle);
            collapseToNearestEdge();
        });
        header.addView(collapseLabel, new LinearLayout.LayoutParams(dp(22), dp(22)));
        panel.addView(header);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(2), 0, 0);
        actions.addView(actionCell("▶", "启动", BLUE, v -> {
            noteInteraction();
            if (memoryProbeActive) Toast.makeText(this, "只读内存探测正在运行", Toast.LENGTH_SHORT).show();
            else if (isRuntimeActive()) sendRuntimeAction(ScriptRuntimeService.ACTION_STOP);
            else restartLastRun();
        }), actionCellParams(true));
        actions.addView(actionCell("Ⅱ", "暂停", SOFT, v -> {
            noteInteraction();
            if (memoryProbeActive) Toast.makeText(this, "只读探测不需要暂停", Toast.LENGTH_SHORT).show();
            else sendRuntimeAction(ScriptExecutionControl.State.PAUSED.name().equals(runtimeState)
                    ? ScriptRuntimeService.ACTION_RESUME : ScriptRuntimeService.ACTION_PAUSE);
        }), actionCellParams(false));
        actions.addView(actionCell("⚙", "设置", SOFT, v -> {
            noteInteraction();
            Intent open = new Intent(this, ScriptToolsActivity.class);
            String config = ScriptLastRunConfig.load(this);
            if (config != null) {
                open.putExtra(ScriptToolsActivity.EXTRA_TARGET_MODE,
                        ScriptToolsActivity.targetModeFromConfig(config));
            }
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(open);
        }), actionCellParams(false));
        actions.addView(actionCell("⏻", "退出", Color.rgb(74, 41, 48), v -> {
            mainHandler.removeCallbacks(collapseWhenIdle);
            sendRuntimeAction(ScriptRuntimeService.ACTION_STOP);
            Toast.makeText(this, "已请求停止脚本并关闭悬浮窗", Toast.LENGTH_SHORT).show();
            stopSelf();
        }), actionCellParams(false));
        panel.addView(actions);

        LinearLayout probeAction = new LinearLayout(this);
        probeAction.setOrientation(LinearLayout.HORIZONTAL);
        probeAction.setGravity(Gravity.CENTER_VERTICAL);
        probeAction.setPadding(dp(9), dp(4), dp(9), dp(4));
        probeAction.setBackground(round(SOFT, 11, BORDER));
        probeAction.setContentDescription("只读探测沙箱背包空格和负重；不会点击或操作游戏");
        TextView probeIcon = text("▣", 17, BLUE, true);
        probeIcon.setGravity(Gravity.CENTER);
        probeAction.addView(probeIcon, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout probeCopy = new LinearLayout(this);
        probeCopy.setOrientation(LinearLayout.VERTICAL);
        probeCopy.setGravity(Gravity.CENTER_VERTICAL);
        TextView probeTitle = text("只读探测背包内存", 10, TEXT, true);
        TextView probeSubtitle = text("读取空格和负重，不执行游戏操作", 8, MUTED, false);
        probeCopy.addView(probeTitle, new LinearLayout.LayoutParams(-1, dp(16)));
        probeCopy.addView(probeSubtitle, new LinearLayout.LayoutParams(-1, dp(14)));
        LinearLayout.LayoutParams probeCopyParams = new LinearLayout.LayoutParams(0, -2, 1);
        probeCopyParams.leftMargin = dp(5);
        probeAction.addView(probeCopy, probeCopyParams);
        TextView probeButtonLabel = text("读取", 11, TEXT, true);
        probeButtonLabel.setGravity(Gravity.CENTER);
        probeButtonLabel.setBackground(round(BLUE, 8, BLUE));
        probeAction.addView(probeButtonLabel, new LinearLayout.LayoutParams(dp(42), dp(28)));
        probeAction.setOnClickListener(v -> startBagMemoryProbe());
        LinearLayout.LayoutParams probeActionParams = new LinearLayout.LayoutParams(-1, dp(36));
        probeActionParams.topMargin = dp(3);
        panel.addView(probeAction, probeActionParams);

        LinearLayout handle = new LinearLayout(this);
        handle.setOrientation(LinearLayout.VERTICAL);
        handle.setGravity(Gravity.CENTER);
        handle.setPadding(dp(4), dp(6), dp(4), dp(6));
        handle.setBackground(round(BLUE, 14, BORDER));
        handle.setContentDescription("展开 RYLUX 脚本控制面板");
        TextView handleMark = text("R", 18, TEXT, true);
        handleMark.setGravity(Gravity.CENTER);
        handle.addView(handleMark, new LinearLayout.LayoutParams(-1, dp(28)));
        TextView handleDots = text("···", 14, TEXT, true);
        handleDots.setGravity(Gravity.CENTER);
        handle.addView(handleDots, new LinearLayout.LayoutParams(-1, dp(20)));
        handle.setOnClickListener(v -> expandFromEdge());
        compactHandle = handle;
        overlayRoot.addView(compactHandle,
                new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        compactHandle.setVisibility(View.GONE);
        return overlayRoot;
    }

    private void scheduleIdleCollapse() {
        mainHandler.removeCallbacks(collapseWhenIdle);
        if (!collapsed && overlay != null && !isRuntimeActive() && !memoryProbeActive) {
            mainHandler.postDelayed(collapseWhenIdle, IDLE_COLLAPSE_MS);
        }
    }

    private void noteInteraction() { scheduleIdleCollapse(); }

    private void collapseToNearestEdge() {
        if (collapsed || params == null || windowManager == null || overlay == null) return;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int currentRight = params.x + dp(PANEL_WIDTH_DP);
        snappedRight = Math.abs(screenWidth - currentRight) < Math.abs(params.x);
        collapsed = true;
        panel.setVisibility(View.GONE);
        compactHandle.setVisibility(View.VISIBLE);
        params.width = dp(HANDLE_WIDTH_DP);
        params.height = dp(HANDLE_HEIGHT_DP);
        params.x = snappedRight
                ? screenWidth - dp(HANDLE_WIDTH_DP / 2f)
                : -dp(HANDLE_WIDTH_DP / 2f);
        params.alpha = COLLAPSED_ALPHA;
        updateOverlayLayout();
    }

    private void expandFromEdge() {
        if (!collapsed || params == null || windowManager == null || overlay == null) return;
        collapsed = false;
        compactHandle.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
        params.width = dp(PANEL_WIDTH_DP);
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.x = snappedRight
                ? Math.max(0, getResources().getDisplayMetrics().widthPixels - params.width)
                : 0;
        params.alpha = 1f;
        updateOverlayLayout();
        scheduleIdleCollapse();
    }

    /** Hide the RYLUX window briefly so display screenshots contain only the game. */
    static boolean setScreenshotCaptureSuppressed(boolean suppressed, long timeoutMs) {
        ScriptControlOverlayService service = activeInstance;
        if (service == null || service.overlay == null || service.params == null) return true;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return service.applyScreenshotCaptureSuppression(suppressed);
        }
        CountDownLatch updated = new CountDownLatch(1);
        boolean[] success = new boolean[1];
        service.mainHandler.post(() -> {
            try { success[0] = service.applyScreenshotCaptureSuppression(suppressed); }
            finally { updated.countDown(); }
        });
        try {
            return updated.await(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS) && success[0];
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean applyScreenshotCaptureSuppression(boolean suppressed) {
        if (overlay == null || params == null || windowManager == null) return true;
        if (suppressed) {
            if (screenshotSuppressionDepth++ > 0) return true;
            visibleBeforeScreenshotSuppression = overlay.getVisibility() == View.VISIBLE;
            flagsBeforeScreenshotSuppression = params.flags;
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            try {
                windowManager.updateViewLayout(overlay, params);
                overlay.setVisibility(View.INVISIBLE);
                return true;
            } catch (RuntimeException failure) {
                screenshotSuppressionDepth = 0;
                params.flags = flagsBeforeScreenshotSuppression;
                return false;
            }
        }
        if (screenshotSuppressionDepth == 0) return true;
        if (--screenshotSuppressionDepth > 0) return true;
        overlay.setVisibility(visibleBeforeScreenshotSuppression ? View.VISIBLE : View.INVISIBLE);
        params.flags = flagsBeforeScreenshotSuppression;
        try {
            windowManager.updateViewLayout(overlay, params);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private void updateOverlayLayout() {
        try { windowManager.updateViewLayout(overlay, params); }
        catch (RuntimeException ignored) { }
    }

    /** Temporarily let accessibility gestures pass through the visible controller to the game. */
    static boolean setGameInputPassthrough(boolean enabled, long timeoutMs) {
        ScriptControlOverlayService service = activeInstance;
        if (service == null || service.overlay == null || service.params == null) return true;
        if (Looper.myLooper() == Looper.getMainLooper()) return service.applyGameInputPassthrough(enabled);
        CountDownLatch updated = new CountDownLatch(1);
        boolean[] success = new boolean[1];
        service.mainHandler.post(() -> {
            try { success[0] = service.applyGameInputPassthrough(enabled); }
            finally { updated.countDown(); }
        });
        try {
            return updated.await(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS) && success[0];
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean applyGameInputPassthrough(boolean enabled) {
        if (overlay == null || params == null || windowManager == null) return true;
        int flags = enabled
                ? params.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                : params.flags & ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (flags == params.flags) return true;
        int previousFlags = params.flags;
        params.flags = flags;
        try {
            windowManager.updateViewLayout(overlay, params);
            return true;
        } catch (RuntimeException failure) {
            params.flags = previousFlags;
            return false;
        }
    }

    private boolean isRuntimeActive() {
        return ScriptExecutionControl.State.RUNNING.name().equals(runtimeState)
                || ScriptExecutionControl.State.PAUSED.name().equals(runtimeState)
                || ScriptExecutionControl.State.STOP_REQUESTED.name().equals(runtimeState);
    }

    private void openStartConfirmation() {
        Intent open = new Intent(this, ScriptToolsActivity.class);
        open.putExtra(ScriptToolsActivity.EXTRA_REQUEST_START, true);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(open);
    }

    private void restartLastRun() {
        if (restartRequested) return;
        String config = ScriptLastRunConfig.load(this);
        if (config == null) {
            // A preview or a first use has no confirmed scheme to run.
            openStartConfirmation();
            return;
        }
        boolean desktop = ScriptToolsActivity.TARGET_MODE_DESKTOP.equals(
                ScriptToolsActivity.targetModeFromConfig(config));
        String target = desktop ? "桌面客户端" : "沙箱游戏";
        restartRequested = true;
        if (statusLabel != null) statusLabel.setText("正在切回" + target + "并启动上次方案…");
        mainHandler.postDelayed(() -> restartRequested = false, 10_000L);
        // Restore the same game source as the confirmed configuration. The
        // desktop and BlackBox copies have the same game package name, so
        // always launching the proxy here would conflict with a desktop run.
        Thread launch = new Thread(() -> {
            try {
                if (desktop) {
                    Intent game = getPackageManager().getLaunchIntentForPackage(
                            ScriptGameAdapter.TARGET_GAME_PACKAGE);
                    if (game == null) throw new IllegalStateException("未找到桌面版封神榜");
                    game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(game);
                } else if (!BlackBoxCore.get().launchApk(ScriptGameAdapter.TARGET_GAME_PACKAGE, 0)) {
                    throw new IllegalStateException("无法前置沙箱中的封神榜");
                }
                mainHandler.post(() -> {
                    if (restartRequested) {
                        sendRuntimeAction(ScriptRuntimeService.ACTION_START, config);
                    }
                });
            } catch (RuntimeException error) {
                mainHandler.post(() -> {
                    restartRequested = false;
                    String message = error.getMessage() == null
                            ? "无法前置" + target : error.getMessage();
                    if (statusLabel != null) statusLabel.setText("重启失败 · " + message);
                    Toast.makeText(this, "无法启动" + target + "：" + message, Toast.LENGTH_LONG).show();
                });
            }
        }, "RYLUX-Overlay-Restart");
        launch.setDaemon(true);
        launch.start();
    }

    private void sendRuntimeAction(String action) { sendRuntimeAction(action, null); }

    private void startBagMemoryProbe() {
        noteInteraction();
        if (memoryProbeActive) {
            Toast.makeText(this, "只读内存探测正在运行", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isRuntimeActive()) {
            Toast.makeText(this, "请先停止当前工作流，再运行只读探测", Toast.LENGTH_LONG).show();
            return;
        }
        memoryProbeActive = true;
        mainHandler.removeCallbacks(collapseWhenIdle);
        if (statusLabel != null) statusLabel.setText("内存探测中 · 只读，不操作游戏");
        Intent probe = new Intent(this, BagMemoryProbeService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(probe);
            else startService(probe);
        } catch (RuntimeException error) {
            memoryProbeActive = false;
            scheduleIdleCollapse();
            Toast.makeText(this, "无法启动只读探测：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void sendRuntimeAction(String action, String config) {
        Intent command = new Intent(this, ScriptRuntimeService.class).setAction(action);
        if (config != null) command.putExtra(ScriptRuntimeService.EXTRA_CONFIG, config);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(command);
            else startService(command);
        } catch (RuntimeException error) {
            restartRequested = false;
            Toast.makeText(this, "无法控制脚本服务：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout.LayoutParams actionCellParams(boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
        if (!first) lp.leftMargin = dp(3);
        return lp;
    }

    private LinearLayout actionCell(String glyph, String title, int color, View.OnClickListener listener) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setBackground(round(color, 11, color == BLUE ? BLUE : BORDER));
        cell.setContentDescription(title);
        TextView icon = text(glyph, 16, TEXT, true);
        icon.setGravity(Gravity.CENTER);
        cell.addView(icon, new LinearLayout.LayoutParams(-1, dp(24)));
        TextView label = text(title, 9, TEXT, true);
        label.setGravity(Gravity.CENTER);
        cell.addView(label, new LinearLayout.LayoutParams(-1, dp(16)));
        if ("启动".equals(title)) runActionLabel = label;
        if ("暂停".equals(title)) pauseActionLabel = label;
        cell.setOnClickListener(listener);
        return cell;
    }

    /** Drag the whole integrated panel from the RYLUX wordmark. */
    private final class OverlayDragTouchListener implements View.OnTouchListener {
        private float downX;
        private float downY;
        private int startX;
        private int startY;
        private boolean dragged;

        @Override public boolean onTouch(View view, MotionEvent event) {
            if (params == null || windowManager == null) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                noteInteraction();
                downX = event.getRawX();
                downY = event.getRawY();
                startX = params.x;
                startY = params.y;
                dragged = false;
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                noteInteraction();
                float deltaX = event.getRawX() - downX;
                float deltaY = event.getRawY() - downY;
                if (Math.abs(deltaX) + Math.abs(deltaY) > dp(8)) dragged = true;
                if (!dragged) return true;
                int screenWidth = getResources().getDisplayMetrics().widthPixels;
                params.x = Math.max(0, Math.min(screenWidth - dp(PANEL_WIDTH_DP),
                        startX + Math.round(deltaX)));
                params.y = startY + Math.round(deltaY);
                updateOverlayLayout();
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                return true;
            }
            return event.getAction() == MotionEvent.ACTION_CANCEL;
        }
    }

    private Notification buildNotification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("RYLUX 脚本控制")
                .setContentText("脚本控制就绪；只有点击启动才运行")
                .setOngoing(true).build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "脚本控制悬浮窗", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("用户主动开启时，保持脚本控制悬浮窗服务运行");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable round(int color, int radiusDp, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override public void onDestroy() {
        if (activeInstance == this) activeInstance = null;
        mainHandler.removeCallbacks(collapseWhenIdle);
        try { unregisterReceiver(runtimeStatus); } catch (RuntimeException ignored) { }
        if (overlay != null && windowManager != null) {
            try { windowManager.removeView(overlay); }
            catch (RuntimeException ignored) { }
            overlay = null;
        }
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
