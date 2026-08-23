package com.example.pakredirect;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
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
    private EditText urlEdit;
    private TextView fileText, statusText, hitsText;
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
            hitsText.setText("命中次数：" + hits);
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
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("PakRedirect");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Root 模拟器专用：把 ui.pak HTTPS 请求映射到本地 PAK，并自动拦截 linkspak.txt，把 ui.pak 文件大小改为本地文件实际大小。支持 GET/HEAD 与单段 Range(206)。");
        desc.setTextSize(14);
        desc.setPadding(0, dp(4), 0, dp(14));
        root.addView(desc);

        TextView ulabel = new TextView(this); ulabel.setText("目标 HTTPS URL"); root.addView(ulabel);
        urlEdit = new EditText(this);
        urlEdit.setSingleLine(true);
        urlEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlEdit.setText(DEFAULT_URL);
        root.addView(urlEdit, new LinearLayout.LayoutParams(-1, -2));

        Button pick = new Button(this); pick.setText("选择本地 PAK 文件"); pick.setOnClickListener(v -> pickPak()); root.addView(pick);
        fileText = new TextView(this); fileText.setText("未选择文件"); fileText.setTextIsSelectable(true); fileText.setPadding(0,0,0,dp(8)); root.addView(fileText);

        Button ca = new Button(this); ca.setText("安装 PakRedirect 系统 CA（Root）"); ca.setOnClickListener(v -> installCa()); root.addView(ca);

        Button start = new Button(this); start.setText("启动拦截"); start.setOnClickListener(v -> startIntercept()); root.addView(start);
        Button stop = new Button(this); stop.setText("停止并清理规则"); stop.setOnClickListener(v -> stopIntercept()); root.addView(stop);

        hitsText = new TextView(this); hitsText.setText("命中次数：0"); hitsText.setTextSize(16); hitsText.setPadding(0,dp(12),0,dp(6)); root.addView(hitsText);
        statusText = new TextView(this); statusText.setText("状态：等待配置\n"); statusText.setTextIsSelectable(true); statusText.setTypeface(Typeface.MONOSPACE); root.addView(statusText);

        TextView note = new TextView(this);
        note.setText("固定校验地址：https://cdn.tamgioipt.vn/linkspak.txt。启动时会先读取远端内容，仅把 ui.pak 的大小字段替换为所选本地文件实际大小；读取失败时使用内置模板。PAK 匹配忽略 ?ver= 等查询参数。需要 Root 与 /system 可写。若目标应用使用 Pinning、硬编码 IP 或仅 QUIC，仍可能无法命中。停止按钮会清理本应用规则。");
        note.setTextSize(12); note.setPadding(0,dp(14),0,dp(8)); root.addView(note);

        ScrollView sv = new ScrollView(this); sv.addView(root); setContentView(sv);
    }

    private void restore() {
        String u = prefs.getString("url", DEFAULT_URL);
        urlEdit.setText(u);
        String s = prefs.getString("uri", null);
        if (s != null) { selectedUri = Uri.parse(s); fileText.setText(selectedUri.toString()); }
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
            fileText.setText(selectedUri.toString());
            appendStatus("已选择 PAK");
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
                runOnUiThread(() -> appendStatus(r.ok() ? "CA 已写入系统目录。首次安装后请重启 MuMu。\n" + r.output : "CA 安装失败：\n" + r));
            } catch (Throwable t) { runOnUiThread(() -> appendStatus("CA 安装异常：" + t.getMessage())); }
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
        hits = 0; hitsText.setText("命中次数：0");
        Intent i = new Intent(this, InterceptService.class).setAction(InterceptService.ACTION_START)
                .putExtra(InterceptService.EXTRA_URL, url)
                .putExtra(InterceptService.EXTRA_URI, selectedUri.toString());
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        appendStatus("正在启动…");
    }

    private void stopIntercept() {
        Intent i = new Intent(this, InterceptService.class).setAction(InterceptService.ACTION_STOP);
        startService(i);
        appendStatus("请求停止并清理规则…");
    }

    private void appendStatus(String s) {
        statusText.append(s + "\n");
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
}
