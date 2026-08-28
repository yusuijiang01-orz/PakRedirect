package com.example.pakredirect;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URI;

public class MainActivity extends Activity {
    private static final String PREF_PAK_DIRECTORY_URI = "pak_directory_uri";
    private static final String DEFAULT_URL = "https://cdn-tgpt.tepaylink.vn/production/ui.pak?ver=2";
    private static final String CA_HASH = "204d3e6e";

    private static final int BG = Color.rgb(246, 247, 249);
    private static final int CARD = Color.WHITE;
    private static final int TEXT = Color.rgb(30, 35, 43);
    private static final int MUTED = Color.rgb(105, 113, 126);
    private static final int PRIMARY = Color.rgb(47, 111, 237);
    private static final int SUCCESS = Color.rgb(30, 155, 95);
    private static final int DANGER = Color.rgb(214, 67, 67);

    private EditText urlEdit;
    private TextView fileText, indexText, statusText, hitsText, stateText;
    private Button startButton, legacyButton, stopButton;
    private Uri selectedDirectoryUri;
    private SharedPreferences prefs;
    private int hits;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String msg = intent.getStringExtra(InterceptService.EXTRA_MESSAGE);
            int h = intent.getIntExtra(InterceptService.EXTRA_HITS, -1);
            boolean running = intent.getBooleanExtra(InterceptService.EXTRA_RUNNING, false);
            if (h >= 0) hits = h;
            if (msg != null) appendStatus((running ? "● " : "○ ") + msg);
            hitsText.setText(String.valueOf(hits));
            setRunningUi(running);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        buildUi();
        requestNotificationPermission();
        restore();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(InterceptService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, f, RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusReceiver, f);
        hits = InterceptService.hitCount();
        hitsText.setText(String.valueOf(hits));
        setRunningUi(InterceptService.isRunning());
    }

    @Override protected void onStop() {
        try { unregisterReceiver(statusReceiver); } catch (Throwable ignored) {}
        super.onStop();
    }

    private void buildUi() {
        getWindow().setStatusBarColor(BG);
        if (Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        int pad = dp(18);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(18), pad, dp(30));
        root.setBackgroundColor(BG);

        TextView title = text("PakRedirect", 28, TEXT, true);
        root.addView(title);
        TextView subtitle = text("本地 HTTP PAK 直供工具", 14, MUTED, false);
        subtitle.setPadding(0, dp(2), 0, dp(18));
        root.addView(subtitle);

        LinearLayout overview = card();
        TextView stateLabel = text("拦截状态", 12, MUTED, false);
        overview.addView(stateLabel);
        stateText = text("未启动", 20, TEXT, true);
        stateText.setPadding(0, dp(4), 0, dp(14));
        overview.addView(stateText);
        TextView hitLabel = text("本地 PAK 命中次数", 12, MUTED, false);
        overview.addView(hitLabel);
        hitsText = text("0", 30, PRIMARY, true);
        overview.addView(hitsText);
        root.addView(overview, blockParams());

        LinearLayout config = card();
        config.addView(sectionTitle("拦截配置"));
        TextView ulabel = text("远端 PAK URL（用于识别清单项目）", 13, MUTED, false);
        ulabel.setPadding(0, dp(12), 0, dp(6));
        config.addView(ulabel);

        urlEdit = new EditText(this);
        urlEdit.setSingleLine(true);
        urlEdit.setTextSize(15);
        urlEdit.setTextColor(TEXT);
        urlEdit.setHintTextColor(MUTED);
        urlEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlEdit.setPadding(dp(12), 0, dp(12), 0);
        urlEdit.setBackground(round(Color.rgb(242,244,247), 10));
        urlEdit.setText(DEFAULT_URL);
        config.addView(urlEdit, new LinearLayout.LayoutParams(-1, dp(48)));

        Button pick = button("选择 PAK 目录", Color.rgb(235,240,249), TEXT);
        pick.setOnClickListener(v -> PakDirectoryPicker.open(this));
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(-1, dp(48));
        pickLp.topMargin = dp(12);
        config.addView(pick, pickLp);

        fileText = text("当前目录：\n尚未选择目录", 12, MUTED, false);
        fileText.setTextIsSelectable(true);
        fileText.setPadding(dp(2), dp(8), dp(2), 0);
        config.addView(fileText);

        indexText = text("扫描结果：\n尚未扫描", 12, MUTED, false);
        indexText.setTextIsSelectable(true);
        indexText.setPadding(dp(2), dp(10), dp(2), 0);
        config.addView(indexText);
        root.addView(config, blockParams());

        LinearLayout actions = card();
        actions.addView(sectionTitle("控制"));

        Button ca = button("兼容模式：安装系统 CA（Root）", Color.rgb(235,240,249), TEXT);
        ca.setOnClickListener(v -> installCa());
        LinearLayout.LayoutParams caLp = new LinearLayout.LayoutParams(-1, dp(48));
        caLp.topMargin = dp(12);
        actions.addView(ca, caLp);

        startButton = button("启动本地直供（无需 Root / CA）", PRIMARY, Color.WHITE);
        startButton.setOnClickListener(v -> startIntercept(InterceptService.MODE_LOCAL_HTTP));
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(-1, dp(50));
        startLp.topMargin = dp(10);
        actions.addView(startButton, startLp);

        legacyButton = button("启动旧版 HTTPS 拦截", Color.rgb(235,240,249), TEXT);
        legacyButton.setOnClickListener(v -> startIntercept(InterceptService.MODE_LEGACY_TLS));
        LinearLayout.LayoutParams legacyLp = new LinearLayout.LayoutParams(-1, dp(48));
        legacyLp.topMargin = dp(10);
        actions.addView(legacyButton, legacyLp);

        stopButton = button("停止并清理", Color.rgb(253,237,237), DANGER);
        stopButton.setOnClickListener(v -> stopIntercept());
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(-1, dp(48));
        stopLp.topMargin = dp(10);
        actions.addView(stopButton, stopLp);
        root.addView(actions, blockParams());

        LinearLayout logCard = card();
        LinearLayout logHeader = new LinearLayout(this);
        logHeader.setOrientation(LinearLayout.HORIZONTAL);
        logHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView logTitle = sectionTitle("运行日志");
        logHeader.addView(logTitle, new LinearLayout.LayoutParams(0, -2, 1));
        Button clear = button("清空", Color.TRANSPARENT, PRIMARY);
        clear.setMinWidth(0); clear.setMinimumWidth(0);
        clear.setPadding(dp(10), 0, dp(10), 0);
        clear.setOnClickListener(v -> statusText.setText(""));
        logHeader.addView(clear, new LinearLayout.LayoutParams(-2, dp(36)));
        logCard.addView(logHeader);

        statusText = text("等待配置…\n", 12, Color.rgb(74,82,94), false);
        statusText.setTextIsSelectable(true);
        statusText.setTypeface(Typeface.MONOSPACE);
        statusText.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusText.setBackground(round(Color.rgb(247,248,250), 8));
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, dp(190));
        logLp.topMargin = dp(10);
        logCard.addView(statusText, logLp);
        root.addView(logCard, blockParams());

        TextView note = text("推荐使用本地直供：无需 Root、CA、hosts 或 iptables。修改版游戏的清单地址应指向 http://127.0.0.1:18480/linkspak.txt。", 12, MUTED, false);
        note.setPadding(dp(4), dp(4), dp(4), 0);
        root.addView(note);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(root);
        setContentView(sv);
        setRunningUi(false);
    }

    private void restore() {
        urlEdit.setText(prefs.getString("url", DEFAULT_URL));
        String dir = prefs.getString(PREF_PAK_DIRECTORY_URI, null);
        if (dir != null) {
            selectedDirectoryUri = Uri.parse(dir);
            scanPakDirectory(selectedDirectoryUri, false);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PakDirectoryPicker.REQUEST_CODE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedDirectoryUri = data.getData();
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            try { getContentResolver().takePersistableUriPermission(selectedDirectoryUri, flags); } catch (Throwable ignored) {}
            prefs.edit().putString(PREF_PAK_DIRECTORY_URI, selectedDirectoryUri.toString()).apply();
            scanPakDirectory(selectedDirectoryUri, true);
        }
    }

    private void scanPakDirectory(Uri uri, boolean selectedNow) {
        fileText.setText("当前目录：\n" + displayDirectory(uri));
        fileText.setTextColor(TEXT);
        try {
            PakIndex index = PakDirectoryManager.scan(this, uri);
            indexText.setText("扫描结果：\n\n" + PakIndexFormatter.format(index));
            indexText.setTextColor(TEXT);
            appendStatus(selectedNow ? "已选择 PAK 目录并完成扫描" : "已恢复 PAK 目录并完成扫描");
        } catch (Throwable t) {
            indexText.setText("扫描结果：\n扫描失败：" + t.getMessage());
            indexText.setTextColor(DANGER);
            appendStatus("PAK 目录扫描失败：" + t.getMessage());
        }
    }

    private void installCa() {
        appendStatus("正在安装系统 CA…");
        Toast.makeText(this, "正在安装 CA，结果将显示在运行日志", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File f = new File(getFilesDir(), CA_HASH + ".0");
                try (InputStream in = getAssets().open("pakredirect_ca.pem"); FileOutputStream out = new FileOutputStream(f)) {
                    byte[] b = new byte[8192]; int n; while ((n = in.read(b)) >= 0) out.write(b,0,n);
                }
                String dest = "/system/etc/security/cacerts/" + CA_HASH + ".0";
                String q = "'" + f.getAbsolutePath().replace("'", "'\\''") + "'";
                RootShell.Result r = RootShell.run(
                        "set -e; " +
                        "for m in / /system /system_root; do mount -o rw,remount $m >/dev/null 2>&1 || true; done; " +
                        "mkdir -p /system/etc/security/cacerts; " +
                        "cp " + q + " " + dest + "; " +
                        "chown root:root " + dest + "; chmod 644 " + dest + "; " +
                        "restorecon " + dest + " >/dev/null 2>&1 || true; " +
                        "ls -lZ " + dest);
                runOnUiThread(() -> appendStatus(r.ok() ? "CA 已写入系统目录，首次安装后请重启 MuMu。" : "CA 安装失败：" + r));
            } catch (Throwable t) {
                runOnUiThread(() -> appendStatus("CA 安装异常：" + t.getMessage()));
            }
        }).start();
    }

    private void startIntercept(String mode) {
        String url = urlEdit.getText().toString().trim();
        if (selectedDirectoryUri == null) { toast("请先选择 PAK 目录"); return; }
        try {
            URI u = URI.create(url);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) throw new Exception();
        } catch (Throwable t) { toast("请输入完整的 https:// URL"); return; }

        prefs.edit().putString("url", url).putString(PREF_PAK_DIRECTORY_URI, selectedDirectoryUri.toString()).apply();
        hits = 0;
        hitsText.setText("0");
        stateText.setText("正在启动…");
        stateText.setTextColor(PRIMARY);
        startButton.setEnabled(false);

        Intent i = new Intent(this, InterceptService.class).setAction(InterceptService.ACTION_START)
                .putExtra(InterceptService.EXTRA_URL, url)
                .putExtra(InterceptService.EXTRA_DIRECTORY_URI, selectedDirectoryUri.toString())
                .putExtra(InterceptService.EXTRA_MODE, mode);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        appendStatus(InterceptService.MODE_LOCAL_HTTP.equals(mode)
                ? "正在启动本地 HTTP 直供…"
                : "正在启动旧版 HTTPS 拦截…");
        Toast.makeText(this, "正在启动，请查看运行日志", Toast.LENGTH_SHORT).show();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 3001);
        }
    }

    private void stopIntercept() {
        Intent i = new Intent(this, InterceptService.class).setAction(InterceptService.ACTION_STOP);
        startService(i);
        appendStatus("正在停止并清理规则…");
    }

    private void setRunningUi(boolean running) {
        if (stateText == null) return;
        stateText.setText(running ? "运行中" : "未启动");
        stateText.setTextColor(running ? SUCCESS : TEXT);
        startButton.setEnabled(!running);
        legacyButton.setEnabled(!running);
        stopButton.setEnabled(running);
    }

    private void appendStatus(String s) {
        statusText.append(s + "\n");
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(16), dp(15), dp(16), dp(15));
        v.setBackground(round(CARD, 14));
        v.setElevation(dp(1));
        return v;
    }

    private LinearLayout.LayoutParams blockParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        return lp;
    }

    private TextView sectionTitle(String s) { return text(s, 16, TEXT, true); }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private Button button(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label); b.setTextSize(14); b.setTextColor(fg);
        b.setAllCaps(false); b.setGravity(Gravity.CENTER);
        b.setBackground(round(bg, 10));
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private String displayDirectory(Uri uri) {
        String p = uri.getLastPathSegment();
        if (p == null) return uri.toString();
        if (p.startsWith("primary:")) {
            String rest = p.substring("primary:".length());
            return rest.length() == 0 ? "/storage/emulated/0" : "/storage/emulated/0/" + rest;
        }
        int colon = p.indexOf(':');
        if (colon > 0) {
            String volume = p.substring(0, colon);
            String rest = p.substring(colon + 1);
            return rest.length() == 0 ? "/storage/" + volume : "/storage/" + volume + "/" + rest;
        }
        return p.replace("MuMuShared:", "MuMu共享 / ");
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
