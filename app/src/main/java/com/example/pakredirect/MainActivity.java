package com.example.pakredirect;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
    private static final int PICK_PAK = 1001;
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
    private TextView fileText, statusText, hitsText, stateText;
    private Button startButton, stopButton;
    private Uri selectedUri;
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
        restore();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter(InterceptService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, f, RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusReceiver, f);
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
        TextView subtitle = text("轻量 HTTPS PAK 映射工具", 14, MUTED, false);
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
        TextView ulabel = text("目标 HTTPS URL", 13, MUTED, false);
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

        Button pick = button("选择本地 PAK", Color.rgb(235,240,249), TEXT);
        pick.setOnClickListener(v -> pickPak());
        LinearLayout.LayoutParams pickLp = new LinearLayout.LayoutParams(-1, dp(48));
        pickLp.topMargin = dp(12);
        config.addView(pick, pickLp);

        fileText = text("尚未选择文件", 12, MUTED, false);
        fileText.setTextIsSelectable(true);
        fileText.setPadding(dp(2), dp(8), dp(2), 0);
        config.addView(fileText);
        root.addView(config, blockParams());

        LinearLayout actions = card();
        actions.addView(sectionTitle("控制"));

        Button ca = button("安装系统 CA（Root）", Color.rgb(235,240,249), TEXT);
        ca.setOnClickListener(v -> installCa());
        LinearLayout.LayoutParams caLp = new LinearLayout.LayoutParams(-1, dp(48));
        caLp.topMargin = dp(12);
        actions.addView(ca, caLp);

        startButton = button("启动拦截", PRIMARY, Color.WHITE);
        startButton.setOnClickListener(v -> startIntercept());
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(-1, dp(50));
        startLp.topMargin = dp(10);
        actions.addView(startButton, startLp);

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

        TextView note = text("linkspak.txt 会自动把 ui.pak 大小改为所选文件的实际大小；目标 PAK 匹配忽略 ?ver= 参数。需要 Root、可写 /system。", 12, MUTED, false);
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
        String s = prefs.getString("uri", null);
        if (s != null) {
            selectedUri = Uri.parse(s);
            fileText.setText(shortUri(selectedUri));
            fileText.setTextColor(TEXT);
        }
    }

    private void pickPak() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, PICK_PAK);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_PAK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedUri = data.getData();
            try { getContentResolver().takePersistableUriPermission(selectedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Throwable ignored) {}
            prefs.edit().putString("uri", selectedUri.toString()).apply();
            fileText.setText(shortUri(selectedUri));
            fileText.setTextColor(TEXT);
            appendStatus("已选择 PAK 文件");
        }
    }

    private void installCa() {
        appendStatus("正在安装系统 CA…");
        new Thread(() -> {
            try {
                File f = new File(getFilesDir(), CA_HASH + ".0");
                try (InputStream in = getAssets().open("pakredirect_ca.pem"); FileOutputStream out = new FileOutputStream(f)) {
                    byte[] b = new byte[8192]; int n; while ((n = in.read(b)) >= 0) out.write(b,0,n);
                }
                String dest = "/system/etc/security/cacerts/" + CA_HASH + ".0";
                String q = "'" + f.getAbsolutePath().replace("'", "'\\''") + "'";
                RootShell.Result r = RootShell.run(
                        "mount -o rw,remount /system >/dev/null 2>&1 || true; " +
                        "cp " + q + " " + dest + " && chown root:root " + dest + " && chmod 644 " + dest + " && restorecon " + dest + "; ls -lZ " + dest);
                runOnUiThread(() -> appendStatus(r.ok() ? "CA 已写入系统目录，首次安装后请重启 MuMu。" : "CA 安装失败：" + r));
            } catch (Throwable t) {
                runOnUiThread(() -> appendStatus("CA 安装异常：" + t.getMessage()));
            }
        }).start();
    }

    private void startIntercept() {
        String url = urlEdit.getText().toString().trim();
        if (selectedUri == null) { toast("请先选择 PAK 文件"); return; }
        try {
            URI u = URI.create(url);
            if (!"https".equalsIgnoreCase(u.getScheme()) || u.getHost() == null) throw new Exception();
        } catch (Throwable t) { toast("请输入完整的 https:// URL"); return; }

        prefs.edit().putString("url", url).putString("uri", selectedUri.toString()).apply();
        hits = 0;
        hitsText.setText("0");
        stateText.setText("正在启动…");
        stateText.setTextColor(PRIMARY);
        startButton.setEnabled(false);

        Intent i = new Intent(this, InterceptService.class).setAction(InterceptService.ACTION_START)
                .putExtra(InterceptService.EXTRA_URL, url)
                .putExtra(InterceptService.EXTRA_URI, selectedUri.toString());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        appendStatus("正在启动拦截…");
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

    private String shortUri(Uri uri) {
        String p = uri.getLastPathSegment();
        return p == null ? uri.toString() : p.replace("MuMuShared:", "MuMu共享 / ");
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
