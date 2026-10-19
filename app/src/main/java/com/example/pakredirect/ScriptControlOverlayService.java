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
    private static final long IDLE_COLLAPSE_MS = 5_000L;
    private static final float COLLAPSED_ALPHA = 0.5f;
    private static final int PANEL_WIDTH_DP = 320;
    private static final int HANDLE_WIDTH_DP = 64;
    private static final int HANDLE_HEIGHT_DP = 72;

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
    private final Runnable collapseWhenIdle = this::collapseToNearestEdge;
    private String runtimeState = ScriptExecutionControl.State.IDLE.name();
    private final BroadcastReceiver runtimeStatus = new BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            if (statusLabel == null) return;
            String state = intent.getStringExtra(ScriptRuntimeService.EXTRA_STATE);
            String message = intent.getStringExtra(ScriptRuntimeService.EXTRA_MESSAGE);
            runtimeState = state == null ? ScriptExecutionControl.State.IDLE.name() : state;
            statusLabel.setText((state == null ? "运行" : state) + " · " + (message == null ? "" : message));
            boolean active = isRuntimeActive();
            if (runActionLabel != null) runActionLabel.setText(active ? "停止" : "启动");
            if (pauseActionLabel != null) pauseActionLabel.setText(
                    ScriptExecutionControl.State.PAUSED.name().equals(runtimeState) ? "继续" : "暂停");
        }
    };

    @Override public void onCreate() {
        super.onCreate();
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
        params.x = dp(18);
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
        panel.setPadding(dp(10), dp(8), dp(10), dp(8));
        panel.setBackground(round(CARD, 18, BORDER));
        panel.setContentDescription("RYLUX 脚本控制面板");
        overlayRoot.addView(panel, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.START));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text("RYLUX", 12, TEXT, true);
        header.addView(mark, new LinearLayout.LayoutParams(dp(50), dp(20)));
        mark.setContentDescription("拖动 RYLUX 悬浮控制面板");
        mark.setOnTouchListener(new OverlayDragTouchListener());
        statusLabel = text("● 已就绪 · 点击启动后运行", 10, MUTED, false);
        statusLabel.setMaxLines(1);
        statusLabel.setEllipsize(TextUtils.TruncateAt.END);
        statusLabel.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        statusLabel.setContentDescription("运行状态；点击查看完整诊断");
        statusLabel.setOnClickListener(v -> {
            noteInteraction();
            Intent open = new Intent(this, ScriptToolsActivity.class);
            open.putExtra(ScriptToolsActivity.EXTRA_SHOW_RUNTIME_DIAGNOSTIC, true);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(open);
        });
        header.addView(statusLabel, new LinearLayout.LayoutParams(0, dp(20), 1));
        panel.addView(header);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(3), 0, 0);
        actions.addView(actionCell("▶", "启动", BLUE, v -> {
            noteInteraction();
            if (isRuntimeActive()) sendRuntimeAction(ScriptRuntimeService.ACTION_STOP);
            else openStartConfirmation();
        }), actionCellParams(true));
        actions.addView(actionCell("Ⅱ", "暂停", SOFT, v -> {
            noteInteraction();
            sendRuntimeAction(ScriptExecutionControl.State.PAUSED.name().equals(runtimeState)
                    ? ScriptRuntimeService.ACTION_RESUME : ScriptRuntimeService.ACTION_PAUSE);
        }), actionCellParams(false));
        actions.addView(actionCell("⚙", "设置", SOFT, v -> {
            noteInteraction();
            Intent open = new Intent(this, ScriptToolsActivity.class);
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
        if (!collapsed && overlay != null) mainHandler.postDelayed(collapseWhenIdle, IDLE_COLLAPSE_MS);
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

    private void updateOverlayLayout() {
        try { windowManager.updateViewLayout(overlay, params); }
        catch (RuntimeException ignored) { }
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

    private void sendRuntimeAction(String action) { sendRuntimeAction(action, null); }

    private void sendRuntimeAction(String action, String config) {
        Intent command = new Intent(this, ScriptRuntimeService.class).setAction(action);
        if (config != null) command.putExtra(ScriptRuntimeService.EXTRA_CONFIG, config);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(command);
            else startService(command);
        } catch (RuntimeException error) {
            Toast.makeText(this, "无法控制脚本服务：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private LinearLayout.LayoutParams actionCellParams(boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(54), 1);
        if (!first) lp.leftMargin = dp(3);
        return lp;
    }

    private LinearLayout actionCell(String glyph, String title, int color, View.OnClickListener listener) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER);
        cell.setBackground(round(color, 11, color == BLUE ? BLUE : BORDER));
        cell.setContentDescription(title);
        TextView icon = text(glyph, 19, TEXT, true);
        icon.setGravity(Gravity.CENTER);
        cell.addView(icon, new LinearLayout.LayoutParams(-1, dp(27)));
        TextView label = text(title, 10, TEXT, true);
        label.setGravity(Gravity.CENTER);
        cell.addView(label, new LinearLayout.LayoutParams(-1, dp(18)));
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
