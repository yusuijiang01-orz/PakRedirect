package com.example.pakredirect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** RYLUX-styled floating controls for the explicitly started Lua runtime. */
public final class ScriptControlOverlayService extends Service {
    public static final String ACTION_PREVIEW = "com.example.pakredirect.script.PREVIEW_OVERLAY";
    public static final String ACTION_RUNTIME = "com.example.pakredirect.script.RUNTIME_OVERLAY";
    private static final String CHANNEL_ID = "script_control_overlay";
    private static final int NOTIFICATION_ID = 48;
    private static final int BG = Color.rgb(17, 19, 24);
    private static final int CARD = Color.rgb(28, 32, 40);
    private static final int SOFT = Color.rgb(34, 39, 48);
    private static final int BLUE = Color.rgb(72, 113, 255);
    private static final int BORDER = Color.rgb(47, 53, 64);
    private static final int TEXT = Color.rgb(245, 247, 250);
    private static final int MUTED = Color.rgb(154, 163, 176);

    private WindowManager windowManager;
    private View overlay;
    private WindowManager.LayoutParams params;
    private TextView statusLabel;
    private Button pauseAction;
    private String runtimeState = ScriptExecutionControl.State.IDLE.name();
    private final BroadcastReceiver runtimeStatus = new BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) {
            if (statusLabel == null) return;
            String state = intent.getStringExtra(ScriptRuntimeService.EXTRA_STATE);
            String message = intent.getStringExtra(ScriptRuntimeService.EXTRA_MESSAGE);
            runtimeState = state == null ? ScriptExecutionControl.State.IDLE.name() : state;
            statusLabel.setText((state == null ? "运行" : state) + " · " + (message == null ? "" : message));
            if (pauseAction != null) pauseAction.setText(ScriptExecutionControl.State.PAUSED.name().equals(runtimeState) ? "继续" : "暂停");
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
                dp(252), WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(18);
        params.y = dp(180);
        try {
            windowManager.addView(overlay, params);
        } catch (RuntimeException error) {
            Toast.makeText(this, "悬浮窗显示失败，请检查系统悬浮窗权限", Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    private View buildOverlay() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13), dp(12), dp(13), dp(13));
        card.setBackground(round(BG, 18, BORDER));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView mark = text("RYLUX", 15, TEXT, true);
        header.addView(mark, new LinearLayout.LayoutParams(0, dp(30), 1));
        statusLabel = text("● 已就绪 · 点击启动后运行", 10, MUTED, false);
        header.addView(statusLabel);
        header.setOnTouchListener(new DragListener());
        card.addView(header);

        LinearLayout first = row();
        first.addView(action("启动", BLUE, v -> startRuntime()), cellParams(true));
        pauseAction = action("暂停", SOFT, v -> sendRuntimeAction(
                ScriptExecutionControl.State.PAUSED.name().equals(runtimeState)
                        ? ScriptRuntimeService.ACTION_RESUME : ScriptRuntimeService.ACTION_PAUSE));
        first.addView(pauseAction, cellParams(false));
        card.addView(first);

        LinearLayout second = row();
        second.addView(action("设置", SOFT, v -> {
            Intent open = new Intent(this, ScriptToolsActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(open);
        }), cellParams(true));
        second.addView(action("退出", Color.rgb(74, 41, 48), v -> {
            sendRuntimeAction(ScriptRuntimeService.ACTION_STOP);
            Toast.makeText(this, "已请求停止脚本并关闭悬浮窗", Toast.LENGTH_SHORT).show();
            stopSelf();
        }), cellParams(false));
        card.addView(second);
        return card;
    }

    private void startRuntime() {
        SharedPreferences preferences = getSharedPreferences("script_workbench_profiles_v1", MODE_PRIVATE);
        String selected = preferences.getString("selected", "默认方案");
        String config = preferences.getString("profile:" + selected, "{}");
        sendRuntimeAction(ScriptRuntimeService.ACTION_START, config);
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

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);
        return row;
    }

    private LinearLayout.LayoutParams cellParams(boolean left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(43), 1);
        if (!left) lp.leftMargin = dp(8);
        return lp;
    }

    private Button action(String title, int color, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(title);
        button.setTextColor(TEXT);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(round(color, 12, color == BLUE ? BLUE : BORDER));
        button.setOnClickListener(listener);
        return button;
    }

    private final class DragListener implements View.OnTouchListener {
        private float downX;
        private float downY;
        private int startX;
        private int startY;

        @Override public boolean onTouch(View view, MotionEvent event) {
            if (params == null || windowManager == null) return false;
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                downX = event.getRawX();
                downY = event.getRawY();
                startX = params.x;
                startY = params.y;
                return true;
            }
            if (event.getAction() == MotionEvent.ACTION_MOVE) {
                params.x = startX + Math.round(event.getRawX() - downX);
                params.y = startY + Math.round(event.getRawY() - downY);
                try { windowManager.updateViewLayout(overlay, params); }
                catch (RuntimeException ignored) { }
                return true;
            }
            return event.getAction() == MotionEvent.ACTION_UP;
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
