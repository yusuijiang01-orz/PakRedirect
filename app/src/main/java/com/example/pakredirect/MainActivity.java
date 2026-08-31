package com.example.pakredirect;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class MainActivity extends Activity {
    private static final String TARGET_PACKAGE = "com.tepaylink.tamgioiphantranhmobile";
    private static final String MODULE_CODE = "sg_localization";

    private static final int BG = Color.rgb(17, 19, 24);
    private static final int CARD = Color.rgb(28, 32, 40);
    private static final int CARD_SOFT = Color.rgb(34, 39, 48);
    private static final int TEXT = Color.rgb(245, 247, 250);
    private static final int MUTED = Color.rgb(154, 163, 176);
    private static final int PRIMARY = Color.rgb(72, 113, 255);
    private static final int GREEN = Color.rgb(52, 199, 89);
    private static final int ORANGE = Color.rgb(255, 159, 10);
    private static final int RED = Color.rgb(255, 69, 58);

    private AuthStorage authStorage;
    private String currentToken;
    private String currentUsername;
    private boolean currentMembershipActive;
    private boolean registerMode;

    private EditText usernameEdit;
    private EditText passwordEdit;
    private Button authButton;
    private Button switchModeButton;
    private ProgressBar authProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        authStorage = new AuthStorage(this);
        currentToken = authStorage.loadToken();
        currentUsername = authStorage.loadUsername();

        if (currentToken == null || currentToken.trim().isEmpty()) {
            showAuthUi(false);
        } else {
            showSessionLoading();
            refreshProfile(true);
        }
    }

    private void showSessionLoading() {
        LinearLayout root = baseContent();
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView brand = title("RYLUX");
        brand.setGravity(Gravity.CENTER);
        root.addView(brand);
        root.addView(text("正在恢复登录状态…", 14, MUTED, false));
        ProgressBar p = new ProgressBar(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.topMargin = dp(22);
        root.addView(p, lp);
        setScrollableContent(root);
    }

    private void showAuthUi(boolean asRegister) {
        registerMode = asRegister;
        currentMembershipActive = false;

        LinearLayout root = baseContent();
        root.setPadding(dp(22), dp(42), dp(22), dp(30));

        TextView brand = title("RYLUX");
        brand.setTextSize(34);
        root.addView(brand);
        TextView subtitle = text("游戏工具与模块启动平台", 14, MUTED, false);
        subtitle.setPadding(0, 0, 0, dp(24));
        root.addView(subtitle);

        LinearLayout card = card();
        card.addView(text(asRegister ? "创建 RYLUX 账号" : "登录 RYLUX", 20, TEXT, true));
        TextView tip = text(
                asRegister ? "新用户注册后自动获得 24 小时体验时间。" : "使用账号登录后进入游戏与模块中心。",
                13,
                MUTED,
                false
        );
        tip.setPadding(0, dp(6), 0, dp(14));
        card.addView(tip);

        usernameEdit = input("用户名", false);
        usernameEdit.setText(currentUsername == null ? "" : currentUsername);
        card.addView(usernameEdit, inputParams());

        passwordEdit = input("密码", true);
        card.addView(passwordEdit, inputParams());

        authButton = button(asRegister ? "注册并开始 24 小时体验" : "登录", PRIMARY, Color.WHITE);
        authButton.setOnClickListener(v -> submitAuth());
        LinearLayout.LayoutParams authLp = new LinearLayout.LayoutParams(-1, dp(52));
        authLp.topMargin = dp(14);
        card.addView(authButton, authLp);

        switchModeButton = button(
                asRegister ? "已有账号？返回登录" : "没有账号？免费注册",
                CARD_SOFT,
                TEXT
        );
        switchModeButton.setOnClickListener(v -> showAuthUi(!registerMode));
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(-1, dp(46));
        switchLp.topMargin = dp(9);
        card.addView(switchModeButton, switchLp);

        authProgress = new ProgressBar(this);
        authProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        pLp.gravity = Gravity.CENTER_HORIZONTAL;
        pLp.topMargin = dp(12);
        card.addView(authProgress, pLp);

        root.addView(card, blockParams());
        root.addView(text("V1 · 账号 + VIP 时长 · 兑换码充值 · 本地游戏模块", 12, MUTED, false));
        setScrollableContent(root);
    }

    private void submitAuth() {
        String username = usernameEdit == null ? "" : usernameEdit.getText().toString().trim();
        String password = passwordEdit == null ? "" : passwordEdit.getText().toString();
        if (username.length() < 3) {
            toast("用户名至少 3 个字符");
            return;
        }
        if (password.length() < 6) {
            toast("密码至少 6 个字符");
            return;
        }

        setAuthBusy(true);
        final boolean registering = registerMode;
        new Thread(() -> {
            AuthClient.AuthResult result = registering
                    ? AuthClient.register(username, password, deviceId())
                    : AuthClient.login(username, password, deviceId());
            runOnUiThread(() -> {
                setAuthBusy(false);
                if (!result.requestOk || !result.success) {
                    toast(result.message);
                    return;
                }
                currentToken = result.token;
                currentUsername = result.username;
                if (!authStorage.saveSession(currentToken, currentUsername)) {
                    toast("登录成功，但本机登录状态保存失败");
                }
                toast(result.message);
                showHome(new AuthClient.ProfileResult(
                        true,
                        true,
                        result.username,
                        result.membershipActive,
                        result.membershipKind,
                        result.expiresAt,
                        ""
                ));
            });
        }, registering ? "RYLUX-Register" : "RYLUX-Login").start();
    }

    private void refreshProfile(boolean fromStartup) {
        final String token = currentToken;
        if (token == null || token.trim().isEmpty()) {
            showAuthUi(false);
            return;
        }
        new Thread(() -> {
            AuthClient.ProfileResult profile = AuthClient.me(token);
            runOnUiThread(() -> {
                if (!profile.requestOk) {
                    if (fromStartup) {
                        showAuthUi(false);
                        toast("无法连接服务器，请检查网络后重新登录");
                    } else {
                        toast(profile.message);
                    }
                    return;
                }
                if (!profile.success) {
                    authStorage.clear();
                    currentToken = null;
                    currentUsername = null;
                    showAuthUi(false);
                    toast(profile.message);
                    return;
                }
                currentUsername = profile.username;
                showHome(profile);
            });
        }, "RYLUX-Profile").start();
    }

    private void showHome(AuthClient.ProfileResult profile) {
        currentMembershipActive = profile.membershipActive;
        currentUsername = profile.username;

        LinearLayout root = baseContent();
        root.setPadding(dp(18), dp(18), dp(18), dp(30));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = title("RYLUX");
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(0, -2, 1f);
        header.addView(brand, brandLp);
        Button logout = button("退出", CARD_SOFT, TEXT);
        logout.setOnClickListener(v -> performLogout());
        header.addView(logout, new LinearLayout.LayoutParams(dp(72), dp(40)));
        root.addView(header);

        LinearLayout memberCard = card();
        TextView account = text(profile.username, 18, TEXT, true);
        memberCard.addView(account);
        String kind = "trial".equals(profile.membershipKind)
                ? "24 小时体验"
                : (profile.membershipActive ? "VIP 会员" : "使用时间已到期");
        TextView status = text(kind, 14, profile.membershipActive ? GREEN : RED, true);
        status.setPadding(0, dp(6), 0, dp(4));
        memberCard.addView(status);
        memberCard.addView(text("到期时间：" + formatExpiry(profile.expiresAt), 13, MUTED, false));

        Button refresh = button("刷新会员状态", CARD_SOFT, TEXT);
        refresh.setOnClickListener(v -> refreshProfile(false));
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(-1, dp(42));
        refreshLp.topMargin = dp(12);
        memberCard.addView(refresh, refreshLp);
        root.addView(memberCard, blockParams());

        LinearLayout planCard = card();
        planCard.addView(text("会员套餐", 17, TEXT, true));
        TextView plans = text("7 天   ·   30 天   ·   90 天   ·   180 天   ·   365 天", 14, TEXT, true);
        plans.setPadding(0, dp(10), 0, dp(6));
        planCard.addView(plans);
        planCard.addView(text("V1 暂未开放在线支付，可通过兑换码给账号增加使用时间。", 12, MUTED, false));
        root.addView(planCard, blockParams());

        LinearLayout redeemCard = card();
        redeemCard.addView(text("兑换码充值", 17, TEXT, true));
        EditText code = input("输入兑换码", false);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        redeemCard.addView(code, inputParams());
        Button redeem = button("兑换到当前账号", PRIMARY, Color.WHITE);
        redeem.setOnClickListener(v -> redeemCode(code, redeem));
        LinearLayout.LayoutParams redeemLp = new LinearLayout.LayoutParams(-1, dp(48));
        redeemLp.topMargin = dp(10);
        redeemCard.addView(redeem, redeemLp);
        root.addView(redeemCard, blockParams());

        root.addView(sectionTitle("游戏与模块"));
        root.addView(gameModuleCard(profile), blockParams());

        setScrollableContent(root);
    }

    private LinearLayout gameModuleCard(AuthClient.ProfileResult profile) {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Drawable gameIcon = loadTargetIcon();
        if (gameIcon != null) icon.setImageDrawable(gameIcon);
        else icon.setImageDrawable(android.graphics.drawable.ColorDrawable.createFromStream(null, ""));
        GradientDrawable iconBg = round(Color.rgb(44, 50, 61), 18);
        icon.setBackground(iconBg);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(84), dp(84));
        iconLp.rightMargin = dp(14);
        row.addView(icon, iconLp);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.addView(text("三国汉化", 18, TEXT, true));
        TextView desc = text("本地汉化模块 · 127.0.0.1 PAK 推送", 12, MUTED, false);
        desc.setPadding(0, dp(5), 0, dp(5));
        info.addView(desc);
        info.addView(text(
                profile.membershipActive ? "当前账号可启动" : "体验 / VIP 已到期",
                12,
                profile.membershipActive ? GREEN : ORANGE,
                true
        ));
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(row);

        Button start = button("启动游戏", PRIMARY, Color.WHITE);
        start.setOnClickListener(v -> activateModule(start));
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(-1, dp(52));
        startLp.topMargin = dp(14);
        card.addView(start, startLp);
        return card;
    }

    private void redeemCode(EditText codeEdit, Button button) {
        String code = codeEdit.getText().toString().trim();
        if (code.isEmpty()) {
            toast("请输入兑换码");
            return;
        }
        button.setEnabled(false);
        button.setText("兑换中…");
        final String token = currentToken;
        new Thread(() -> {
            AuthClient.ActionResult result = AuthClient.redeem(token, code);
            runOnUiThread(() -> {
                button.setEnabled(true);
                button.setText("兑换到当前账号");
                toast(result.message);
                if (result.requestOk && result.success) {
                    codeEdit.setText("");
                    refreshProfile(false);
                }
            });
        }, "RYLUX-Redeem").start();
    }

    private void activateModule(Button button) {
        if (currentToken == null || currentToken.trim().isEmpty()) {
            showAuthUi(false);
            return;
        }
        button.setEnabled(false);
        button.setText("正在验证账号…");
        final String token = currentToken;

        new Thread(() -> {
            AuthClient.ActionResult result = AuthClient.authorize(token, MODULE_CODE);
            if (!result.requestOk || !result.success) {
                runOnUiThread(() -> {
                    button.setEnabled(true);
                    button.setText("启动游戏");
                    toast(result.message);
                    if (result.requestOk) refreshProfile(false);
                });
                return;
            }

            if (getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE) == null) {
                runOnUiThread(() -> {
                    button.setEnabled(true);
                    button.setText("启动游戏");
                    toast("未检测到目标游戏");
                });
                return;
            }

            runOnUiThread(() -> button.setText("正在启动本地模块…"));
            try {
                Intent service = new Intent(this, InterceptService.class)
                        .setAction(InterceptService.ACTION_START);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                else startService(service);
            } catch (Throwable t) {
                resetStartButton(button, "本地模块启动失败：" + safeMessage(t));
                return;
            }

            if (!waitForLocalServer()) {
                resetStartButton(button, "本地模块服务启动失败");
                return;
            }

            runOnUiThread(() -> {
                button.setEnabled(true);
                button.setText("启动游戏");
                if (!launchGame()) toast("本地模块已启动，但未找到游戏启动入口");
            });
        }, "RYLUX-Module-Authorize").start();
    }

    private void performLogout() {
        final String token = currentToken;
        currentToken = null;
        currentUsername = null;
        currentMembershipActive = false;
        authStorage.clear();
        showAuthUi(false);
        if (token != null && !token.trim().isEmpty()) {
            new Thread(() -> AuthClient.logout(token), "RYLUX-Logout").start();
        }
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

    private Drawable loadTargetIcon() {
        try {
            return getPackageManager().getApplicationIcon(TARGET_PACKAGE);
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    private String deviceId() {
        try {
            String value = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void resetStartButton(Button button, String message) {
        runOnUiThread(() -> {
            button.setEnabled(true);
            button.setText("启动游戏");
            toast(message);
        });
    }

    private void setAuthBusy(boolean busy) {
        if (authButton != null) {
            authButton.setEnabled(!busy);
            authButton.setText(busy ? (registerMode ? "注册中…" : "登录中…")
                    : (registerMode ? "注册并开始 24 小时体验" : "登录"));
        }
        if (switchModeButton != null) switchModeButton.setEnabled(!busy);
        if (usernameEdit != null) usernameEdit.setEnabled(!busy);
        if (passwordEdit != null) passwordEdit.setEnabled(!busy);
        if (authProgress != null) authProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private LinearLayout baseContent() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 23) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(30));
        root.setBackgroundColor(BG);
        return root;
    }

    private void setScrollableContent(LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = round(CARD, 16);
        bg.setStroke(dp(1), Color.rgb(47, 53, 64));
        v.setBackground(bg);
        return v;
    }

    private TextView title(String value) {
        TextView v = text(value, 28, TEXT, true);
        v.setPadding(0, 0, 0, dp(12));
        return v;
    }

    private TextView sectionTitle(String value) {
        TextView v = text(value, 16, TEXT, true);
        v.setPadding(dp(2), dp(4), 0, dp(10));
        return v;
    }

    private EditText input(String hint, boolean password) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(15);
        edit.setTextColor(TEXT);
        edit.setHintTextColor(MUTED);
        edit.setHint(hint);
        edit.setPadding(dp(13), 0, dp(13), 0);
        edit.setBackground(round(CARD_SOFT, 10));
        if (password) {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        return edit;
    }

    private LinearLayout.LayoutParams inputParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(50));
        lp.topMargin = dp(9);
        return lp;
    }

    private LinearLayout.LayoutParams blockParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(14);
        return lp;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
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
