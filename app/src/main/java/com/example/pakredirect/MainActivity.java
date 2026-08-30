package com.example.pakredirect;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class MainActivity extends Activity {
    private static final String TARGET_PACKAGE = "com.tepaylink.tamgioiphantranhmobile";

    private static final int BG = Color.rgb(246, 247, 249);
    private static final int CARD = Color.WHITE;
    private static final int TEXT = Color.rgb(30, 35, 43);
    private static final int MUTED = Color.rgb(105, 113, 126);
    private static final int PRIMARY = Color.rgb(47, 111, 237);

    private LicenseStorage licenseStorage;
    private String currentLicenseKey;

    private EditText keyEdit;
    private CheckBox rememberCheck;
    private Button loginButton;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        licenseStorage = new LicenseStorage(this);
        showLoginUi();

        String remembered = licenseStorage.loadKey();
        if (remembered != null && !remembered.trim().isEmpty()) {
            keyEdit.setText(remembered);
            rememberCheck.setChecked(true);
            verifyLicense(remembered, true);
        }
    }

    private void showLoginUi() {
        currentLicenseKey = null;

        LinearLayout root = baseRoot();
        root.addView(title("PakRedirect"));
        TextView subtitle = text("卡密登录", 14, MUTED, false);
        subtitle.setPadding(0, dp(2), 0, dp(18));
        root.addView(subtitle);

        LinearLayout card = card();
        card.addView(text("请输入卡密", 14, TEXT, true));

        keyEdit = new EditText(this);
        keyEdit.setSingleLine(true);
        keyEdit.setTextSize(15);
        keyEdit.setTextColor(TEXT);
        keyEdit.setHintTextColor(MUTED);
        keyEdit.setHint("PR-XXXX-XXXX-XXXX-XXXX");
        keyEdit.setPadding(dp(12), 0, dp(12), 0);
        keyEdit.setBackground(round(Color.rgb(242, 244, 247), 10));
        LinearLayout.LayoutParams keyLp = new LinearLayout.LayoutParams(-1, dp(50));
        keyLp.topMargin = dp(12);
        card.addView(keyEdit, keyLp);

        rememberCheck = new CheckBox(this);
        rememberCheck.setText("记住卡密");
        rememberCheck.setTextSize(14);
        rememberCheck.setTextColor(TEXT);
        LinearLayout.LayoutParams rememberLp = new LinearLayout.LayoutParams(-1, -2);
        rememberLp.topMargin = dp(8);
        card.addView(rememberCheck, rememberLp);

        loginButton = button("登录", PRIMARY, Color.WHITE);
        loginButton.setOnClickListener(v -> {
            String key = keyEdit.getText().toString().trim();
            if (key.isEmpty()) {
                toast("请输入卡密");
                return;
            }
            verifyLicense(key, false);
        });
        LinearLayout.LayoutParams loginLp = new LinearLayout.LayoutParams(-1, dp(50));
        loginLp.topMargin = dp(12);
        card.addView(loginButton, loginLp);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        progressLp.gravity = Gravity.CENTER_HORIZONTAL;
        progressLp.topMargin = dp(12);
        card.addView(progress, progressLp);

        root.addView(card, blockParams());
        setContentView(root);
    }

    private void verifyLicense(String key, boolean autoLogin) {
        setLoginBusy(true);
        new Thread(() -> {
            LicenseClient.VerifyResult result = LicenseClient.verify(key);
            runOnUiThread(() -> {
                setLoginBusy(false);

                if (!result.requestOk) {
                    toast(autoLogin ? "网络验证失败，请检查网络后重试" : result.message);
                    return;
                }

                if (!result.valid) {
                    if (autoLogin) {
                        licenseStorage.clear();
                        rememberCheck.setChecked(false);
                    }
                    toast(result.message);
                    return;
                }

                if (!autoLogin) {
                    if (rememberCheck.isChecked()) {
                        if (!licenseStorage.saveKey(key)) {
                            toast("卡密验证成功，但记住卡密失败");
                        }
                    } else {
                        licenseStorage.clear();
                    }
                }

                currentLicenseKey = key;
                showAuthorizedUi(result.expiresAt);
            });
        }, "PakRedirect-License-Login").start();
    }

    private void showAuthorizedUi(String expiresAt) {
        LinearLayout root = baseRoot();
        root.addView(title("PakRedirect"));

        LinearLayout card = card();
        card.addView(text("卡密到期时间", 13, MUTED, false));

        TextView expiry = text(formatExpiry(expiresAt), 22, TEXT, true);
        expiry.setPadding(0, dp(8), 0, dp(4));
        card.addView(expiry);
        root.addView(card, blockParams());

        Button activate = button("开启汉化", PRIMARY, Color.WHITE);
        activate.setOnClickListener(v -> activateLocalization(activate));
        root.addView(activate, new LinearLayout.LayoutParams(-1, dp(54)));

        setContentView(root);
    }

    private void activateLocalization(Button activateButton) {
        if (currentLicenseKey == null || currentLicenseKey.trim().isEmpty()) {
            showLoginUi();
            return;
        }

        activateButton.setEnabled(false);
        activateButton.setText("正在验证…");

        new Thread(() -> {
            LicenseClient.VerifyResult result = LicenseClient.verify(currentLicenseKey);

            if (!result.requestOk) {
                resetActivateButton(activateButton, "网络验证失败，未开启汉化");
                return;
            }

            if (!result.valid) {
                licenseStorage.clear();
                runOnUiThread(() -> {
                    toast(result.message);
                    showLoginUi();
                });
                return;
            }

            if (getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE) == null) {
                resetActivateButton(activateButton, "未检测到目标游戏");
                return;
            }

            runOnUiThread(() -> activateButton.setText("正在启动本地汉化…"));

            try {
                Intent service = new Intent(this, InterceptService.class)
                        .setAction(InterceptService.ACTION_START);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                else startService(service);
            } catch (Throwable t) {
                resetActivateButton(activateButton, "本地汉化服务启动失败：" + safeMessage(t));
                return;
            }

            if (!waitForLocalServer()) {
                resetActivateButton(activateButton, "本地汉化服务启动失败");
                return;
            }

            runOnUiThread(() -> {
                activateButton.setEnabled(true);
                activateButton.setText("开启汉化");
                if (!launchGame()) toast("本地汉化已启动，但未找到游戏启动入口");
            });
        }, "PakRedirect-Activate").start();
    }

    private boolean waitForLocalServer() {
        for (int i = 0; i < 30; i++) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL("http://127.0.0.1:" + InterceptService.LOCAL_HTTP_PORT + "/health");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(500);
                connection.setReadTimeout(500);
                connection.setUseCaches(false);
                if (connection.getResponseCode() == 200) return true;
            } catch (Throwable ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void resetActivateButton(Button button, String message) {
        runOnUiThread(() -> {
            button.setEnabled(true);
            button.setText("开启汉化");
            toast(message);
        });
    }

    private boolean launchGame() {
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
            if (launchIntent == null) return false;
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(launchIntent);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void setLoginBusy(boolean busy) {
        if (loginButton != null) {
            loginButton.setEnabled(!busy);
            loginButton.setText(busy ? "验证中…" : "登录");
        }
        if (keyEdit != null) keyEdit.setEnabled(!busy);
        if (rememberCheck != null) rememberCheck.setEnabled(!busy);
        if (progress != null) progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private LinearLayout baseRoot() {
        getWindow().setStatusBarColor(BG);
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(30));
        root.setBackgroundColor(BG);
        return root;
    }

    private TextView title(String value) {
        TextView title = text(value, 28, TEXT, true);
        title.setPadding(0, 0, 0, dp(16));
        return title;
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

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button button(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setBackground(round(bg, 10));
        b.setPadding(dp(12), 0, dp(12), 0);
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private String formatExpiry(String iso) {
        if (iso == null || iso.trim().isEmpty()) return "未知";
        try {
            OffsetDateTime dt = OffsetDateTime.parse(iso);
            return dt.atZoneSameInstant(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Throwable ignored) {
            return iso;
        }
    }

    private String safeMessage(Throwable t) {
        String msg = t.getMessage();
        return msg == null || msg.trim().isEmpty() ? t.getClass().getSimpleName() : msg;
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
