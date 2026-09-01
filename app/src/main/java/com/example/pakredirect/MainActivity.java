package com.example.pakredirect;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TARGET_PACKAGE = "com.tepaylink.tamgioiphantranhmobile";
    private static final String MODULE_CODE = "sg_localization";
    private static final int REQUEST_MIRROR_PACK = 4107;

    private static final String GAME_NAME = "封神榜(越南版)";
    private static final String GAME_DESCRIPTION = "越南版封神榜，RYLUX 提供本地汉化、资源校验与本地 PAK 接管。";
    private static final String GAME_LAST_UPDATED = "2026-09-01";
    private static final String LOCALIZATION_PROGRESS = "持续更新中";

    private static final int BG = Color.rgb(17, 19, 24);
    private static final int CARD = Color.rgb(28, 32, 40);
    private static final int CARD_SOFT = Color.rgb(34, 39, 48);
    private static final int TEXT = Color.rgb(245, 247, 250);
    private static final int MUTED = Color.rgb(154, 163, 176);
    private static final int PRIMARY = Color.rgb(72, 113, 255);
    private static final int GREEN = Color.rgb(52, 199, 89);
    private static final int ORANGE = Color.rgb(255, 159, 10);
    private static final int RED = Color.rgb(255, 69, 58);
    private static final int YELLOW = Color.rgb(245, 183, 0);
    private static final int BORDER = Color.rgb(47, 53, 64);
    private static final int DISABLED = Color.rgb(70, 75, 84);
    private static final int BADGE_GRAY = Color.rgb(104, 111, 124);

    private AuthStorage authStorage;
    private String currentToken;
    private String currentUsername;
    private boolean currentMembershipActive;
    private String currentMembershipKind = "expired";
    private String currentExpiresAt;
    private AuthClient.ProfileResult currentProfile;
    private boolean registerMode;

    private EditText usernameEdit;
    private EditText passwordEdit;
    private EditText confirmPasswordEdit;
    private CheckBox rememberCheck;
    private Button authButton;
    private TextView switchModeLink;
    private ProgressBar authProgress;

    private FrameLayout overlayHost;
    private FrameLayout activeOverlay;
    private View activePanel;
    private boolean activePanelFromBottom;

    private ProgressBar moduleProgress;
    private TextView moduleProgressText;
    private TextView mirrorStatusView;
    private Button mirrorSelectButton;
    private volatile String launchWaitError = "";

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

    @Override
    public void onBackPressed() {
        if (activeOverlay != null) {
            closeActiveOverlay();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_MIRROR_PACK || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        importMirrorPack(uri);
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
        currentMembershipKind = "expired";
        currentExpiresAt = null;
        currentProfile = null;
        confirmPasswordEdit = null;
        rememberCheck = null;
        clearTransientViews();

        LinearLayout root = baseContent();
        root.setPadding(dp(22), dp(52), dp(22), dp(30));

        TextView brand = title("RYLUX");
        brand.setTextSize(34);
        brand.setPadding(0, 0, 0, dp(26));
        root.addView(brand);

        LinearLayout card = card();
        card.addView(text(asRegister ? "创建 RYLUX 账号" : "登录 RYLUX", 20, TEXT, true));

        usernameEdit = input("用户名", false);
        if (asRegister) {
            usernameEdit.setText("");
        } else {
            String rememberedUser = authStorage.loadRememberedUsername();
            String preferredUser = rememberedUser == null || rememberedUser.trim().isEmpty()
                    ? currentUsername
                    : rememberedUser;
            usernameEdit.setText(preferredUser == null ? "" : preferredUser);
        }
        card.addView(usernameEdit, inputParams());

        passwordEdit = input("密码", true);
        if (!asRegister && authStorage.isRememberPasswordEnabled()) {
            passwordEdit.setText(authStorage.loadRememberedPassword());
            passwordEdit.setSelection(passwordEdit.length());
        }
        card.addView(passwordEdit, inputParams());

        if (asRegister) {
            confirmPasswordEdit = input("确认密码", true);
            card.addView(confirmPasswordEdit, inputParams());
        } else {
            rememberCheck = new CheckBox(this);
            rememberCheck.setText("记住密码");
            rememberCheck.setTextColor(TEXT);
            rememberCheck.setTextSize(14);
            rememberCheck.setButtonTintList(ColorStateList.valueOf(PRIMARY));
            rememberCheck.setChecked(authStorage.isRememberPasswordEnabled());
            LinearLayout.LayoutParams rememberLp = new LinearLayout.LayoutParams(-1, -2);
            rememberLp.topMargin = dp(9);
            card.addView(rememberCheck, rememberLp);
        }

        authButton = button(asRegister ? "免费注册" : "登录", PRIMARY, Color.WHITE);
        authButton.setOnClickListener(v -> submitAuth());
        LinearLayout.LayoutParams authLp = new LinearLayout.LayoutParams(-1, dp(52));
        authLp.topMargin = dp(14);
        card.addView(authButton, authLp);

        switchModeLink = text(
                asRegister ? "已有账号？返回登录" : "没有账号？免费注册",
                14,
                TEXT,
                false
        );
        switchModeLink.setGravity(Gravity.CENTER);
        switchModeLink.setPaintFlags(switchModeLink.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        switchModeLink.setClickable(true);
        switchModeLink.setFocusable(true);
        switchModeLink.setOnClickListener(v -> showAuthUi(!registerMode));
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(-1, dp(46));
        switchLp.topMargin = dp(6);
        card.addView(switchModeLink, switchLp);

        authProgress = new ProgressBar(this);
        authProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        pLp.gravity = Gravity.CENTER_HORIZONTAL;
        pLp.topMargin = dp(8);
        card.addView(authProgress, pLp);

        root.addView(card, blockParams());
        setScrollableContent(root);
    }

    private void submitAuth() {
        String username = usernameEdit == null ? "" : usernameEdit.getText().toString().trim();
        String password = passwordEdit == null ? "" : passwordEdit.getText().toString();
        if (username.length() < 3) {
            focusWithMessage(usernameEdit, "用户名至少 3 个字符");
            return;
        }
        if (password.length() < 6) {
            focusWithMessage(passwordEdit, "密码至少 6 个字符");
            return;
        }
        if (registerMode) {
            String confirm = confirmPasswordEdit == null ? "" : confirmPasswordEdit.getText().toString();
            if (!password.equals(confirm)) {
                focusWithMessage(confirmPasswordEdit, "两次输入的密码不一致");
                return;
            }
        }

        setAuthBusy(true);
        final boolean registering = registerMode;
        final boolean rememberPassword = !registering && rememberCheck != null && rememberCheck.isChecked();
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
                if (!registering) {
                    if (rememberPassword) {
                        if (!authStorage.saveRememberedCredentials(username, password)) {
                            toast("登录成功，但记住密码失败");
                        }
                    } else {
                        authStorage.clearRememberedCredentials();
                    }
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
                    authStorage.clearSession();
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
        currentProfile = profile;
        currentMembershipActive = profile.membershipActive;
        currentMembershipKind = profile.membershipKind == null ? "expired" : profile.membershipKind;
        currentExpiresAt = profile.expiresAt;
        currentUsername = profile.username;
        clearTransientViews();

        LinearLayout root = baseContent();
        root.setPadding(dp(16), dp(12), dp(16), dp(30));

        FrameLayout avatar = buildAvatar(profile);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        root.addView(avatar, avatarLp);

        Space gap = new Space(this);
        root.addView(gap, new LinearLayout.LayoutParams(1, dp(18)));

        GlowFrameLayout canvas = buildGameCanvas(profile);
        LinearLayout.LayoutParams canvasLp = new LinearLayout.LayoutParams(-1, dp(272));
        canvasLp.leftMargin = dp(2);
        canvasLp.rightMargin = dp(2);
        canvasLp.bottomMargin = dp(16);
        root.addView(canvas, canvasLp);

        setScrollableContent(root);
    }

    private FrameLayout buildAvatar(AuthClient.ProfileResult profile) {
        FrameLayout shell = new FrameLayout(this);
        shell.setClickable(true);
        shell.setFocusable(true);
        shell.setOnClickListener(v -> showUserPanel(currentProfile));

        TextView avatar = text(avatarLetter(profile.username), 22, Color.WHITE, true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(round(Color.rgb(55, 67, 92), 28));
        FrameLayout.LayoutParams avatarLp = new FrameLayout.LayoutParams(dp(56), dp(56));
        avatarLp.gravity = Gravity.BOTTOM | Gravity.START;
        shell.addView(avatar, avatarLp);

        TextView badge = membershipBadge(profile);
        FrameLayout.LayoutParams badgeLp = new FrameLayout.LayoutParams(-2, dp(22));
        badgeLp.gravity = Gravity.TOP | Gravity.END;
        badgeLp.topMargin = dp(2);
        shell.addView(badge, badgeLp);
        return shell;
    }

    private TextView membershipBadge(AuthClient.ProfileResult profile) {
        boolean trial = profile.membershipActive && "trial".equals(profile.membershipKind);
        boolean vip = profile.membershipActive && !trial;
        String label = trial ? "体验" : "VIP";
        int color = trial ? YELLOW : (vip ? RED : BADGE_GRAY);
        TextView badge = text(label, 10, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(7), 0, dp(7), 0);
        badge.setBackground(round(color, 7));
        badge.setElevation(dp(6));
        return badge;
    }

    private GlowFrameLayout buildGameCanvas(AuthClient.ProfileResult profile) {
        GlowFrameLayout canvas = new GlowFrameLayout(this);
        canvas.setPadding(dp(28), dp(26), dp(28), dp(26));
        canvas.setClickable(true);
        canvas.setFocusable(true);
        canvas.setOnClickListener(v -> showGamePanel(currentProfile));

        TextView supported = pill("已支持汉化", GREEN, Color.WHITE);
        FrameLayout.LayoutParams supportedLp = new FrameLayout.LayoutParams(-2, dp(28));
        supportedLp.gravity = Gravity.TOP | Gravity.START;
        supportedLp.leftMargin = dp(22);
        supportedLp.topMargin = dp(22);
        canvas.addView(supported, supportedLp);

        MirrorPackManager.MirrorStatus mirror = MirrorPackManager.status(this);
        if (mirror.ready) {
            TextView mirrorBadge = pill("镜像已就绪", PRIMARY, Color.WHITE);
            FrameLayout.LayoutParams mirrorLp = new FrameLayout.LayoutParams(-2, dp(28));
            mirrorLp.gravity = Gravity.TOP | Gravity.END;
            mirrorLp.rightMargin = dp(22);
            mirrorLp.topMargin = dp(22);
            canvas.addView(mirrorBadge, mirrorLp);
        }

        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Drawable gameIcon = loadTargetIcon();
        if (gameIcon != null) icon.setImageDrawable(gameIcon);
        icon.setBackground(round(Color.rgb(44, 50, 61), 24));
        icon.setElevation(dp(8));
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(112), dp(112));
        iconLp.gravity = Gravity.CENTER;
        canvas.addView(icon, iconLp);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(text(GAME_NAME, 20, TEXT, true));
        TextView hint = text("点击查看游戏详情", 12, MUTED, false);
        hint.setPadding(0, dp(4), 0, 0);
        titleBox.addView(hint);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(-2, -2);
        titleLp.gravity = Gravity.BOTTOM | Gravity.START;
        titleLp.leftMargin = dp(24);
        titleLp.bottomMargin = dp(24);
        canvas.addView(titleBox, titleLp);

        return canvas;
    }

    private void showUserPanel(AuthClient.ProfileResult profile) {
        if (profile == null || overlayHost == null) return;
        closeActiveOverlayImmediate();

        FrameLayout overlay = overlay();
        LinearLayout panel = panel();
        panel.setPadding(dp(20), dp(18), dp(20), dp(20));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView close = closeCircle();
        close.setOnClickListener(v -> closeActiveOverlay());
        head.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView heading = text("账号中心", 20, TEXT, true);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(0, -2, 1f);
        headingLp.leftMargin = dp(12);
        head.addView(heading, headingLp);
        panel.addView(head);

        TextView user = text(profile.username, 22, TEXT, true);
        user.setPadding(0, dp(20), 0, dp(8));
        panel.addView(user);

        TextView state = membershipStateText(profile);
        panel.addView(state);
        panel.addView(infoRow("VIP 到期时间", formatExpiry(profile.expiresAt)));

        Button refresh = button("刷新会员状态", CARD_SOFT, TEXT);
        refresh.setOnClickListener(v -> refreshMembershipFromPanel(refresh));
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(-1, dp(46));
        refreshLp.topMargin = dp(16);
        panel.addView(refresh, refreshLp);

        TextView redeemTitle = text("兑换码充值", 16, TEXT, true);
        redeemTitle.setPadding(0, dp(22), 0, 0);
        panel.addView(redeemTitle);
        EditText code = input("输入兑换码", false);
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        panel.addView(code, inputParams());
        Button redeem = button("兑换到当前账号", PRIMARY, Color.WHITE);
        redeem.setOnClickListener(v -> redeemFromPanel(code, redeem));
        LinearLayout.LayoutParams redeemLp = new LinearLayout.LayoutParams(-1, dp(48));
        redeemLp.topMargin = dp(10);
        panel.addView(redeem, redeemLp);

        Button logout = button("退出登录", Color.rgb(68, 35, 38), Color.rgb(255, 185, 185));
        logout.setOnClickListener(v -> {
            closeActiveOverlayImmediate();
            performLogout();
        });
        LinearLayout.LayoutParams logoutLp = new LinearLayout.LayoutParams(-1, dp(46));
        logoutLp.topMargin = dp(18);
        panel.addView(logout, logoutLp);

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(-1, -2);
        panelLp.gravity = Gravity.TOP;
        panelLp.leftMargin = dp(16);
        panelLp.rightMargin = dp(16);
        panelLp.topMargin = dp(72);
        overlay.addView(panel, panelLp);
        attachOverlay(overlay, panel, false);
    }

    private void showGamePanel(AuthClient.ProfileResult profile) {
        if (profile == null || overlayHost == null) return;
        closeActiveOverlayImmediate();

        FrameLayout overlay = overlay();
        LinearLayout panel = panel();
        panel.setPadding(dp(20), dp(18), dp(20), dp(20));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView close = closeCircle();
        close.setOnClickListener(v -> closeActiveOverlay());
        head.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView heading = text("游戏详情", 20, TEXT, true);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(0, -2, 1f);
        headingLp.leftMargin = dp(12);
        head.addView(heading, headingLp);
        panel.addView(head);

        LinearLayout gameHead = new LinearLayout(this);
        gameHead.setOrientation(LinearLayout.HORIZONTAL);
        gameHead.setGravity(Gravity.CENTER_VERTICAL);
        gameHead.setPadding(0, dp(18), 0, dp(12));
        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Drawable drawable = loadTargetIcon();
        if (drawable != null) icon.setImageDrawable(drawable);
        icon.setBackground(round(Color.rgb(44, 50, 61), 18));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(76), dp(76));
        iconLp.rightMargin = dp(14);
        gameHead.addView(icon, iconLp);

        LinearLayout gameText = new LinearLayout(this);
        gameText.setOrientation(LinearLayout.VERTICAL);
        gameText.addView(text(GAME_NAME, 19, TEXT, true));
        TextView supported = pill("已支持汉化", GREEN, Color.WHITE);
        LinearLayout.LayoutParams supportedLp = new LinearLayout.LayoutParams(-2, dp(27));
        supportedLp.topMargin = dp(8);
        gameText.addView(supported, supportedLp);
        gameHead.addView(gameText, new LinearLayout.LayoutParams(0, -2, 1f));
        panel.addView(gameHead);

        TextView description = text(GAME_DESCRIPTION, 13, MUTED, false);
        description.setLineSpacing(0f, 1.25f);
        description.setPadding(0, 0, 0, dp(8));
        panel.addView(description);

        panel.addView(infoRow("最近更新时间", GAME_LAST_UPDATED));
        panel.addView(infoRow("汉化完成度", LOCALIZATION_PROGRESS));

        MirrorPackManager.MirrorStatus mirror = MirrorPackManager.status(this);
        mirrorStatusView = text(mirrorStatusText(mirror), 13, mirror.ready ? GREEN : MUTED, false);
        mirrorStatusView.setPadding(0, dp(14), 0, dp(8));
        panel.addView(mirrorStatusView);

        mirrorSelectButton = button("选择镜像包", CARD_SOFT, TEXT);
        mirrorSelectButton.setOnClickListener(v -> selectMirrorPack());
        LinearLayout.LayoutParams mirrorButtonLp = new LinearLayout.LayoutParams(dp(128), dp(40));
        panel.addView(mirrorSelectButton, mirrorButtonLp);

        boolean canLaunch = profile.membershipActive;
        Button start = button(
                canLaunch ? "启动游戏" : "暂时无法使用",
                canLaunch ? PRIMARY : DISABLED,
                canLaunch ? Color.WHITE : MUTED
        );
        start.setEnabled(canLaunch);
        start.setAlpha(canLaunch ? 1f : 0.72f);
        if (canLaunch) start.setOnClickListener(v -> activateModule(start));
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(-1, dp(52));
        startLp.topMargin = dp(18);
        panel.addView(start, startLp);

        moduleProgressText = text("", 12, MUTED, false);
        moduleProgressText.setVisibility(View.GONE);
        moduleProgressText.setGravity(Gravity.CENTER_HORIZONTAL);
        moduleProgressText.setPadding(0, dp(10), 0, dp(5));
        panel.addView(moduleProgressText, new LinearLayout.LayoutParams(-1, -2));

        moduleProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        moduleProgress.setMax(100);
        moduleProgress.setProgress(0);
        moduleProgress.setProgressTintList(ColorStateList.valueOf(PRIMARY));
        moduleProgress.setIndeterminateTintList(ColorStateList.valueOf(PRIMARY));
        moduleProgress.setProgressBackgroundTintList(ColorStateList.valueOf(CARD_SOFT));
        moduleProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(-1, dp(6));
        progressLp.topMargin = dp(2);
        panel.addView(moduleProgress, progressLp);

        FrameLayout.LayoutParams panelLp = new FrameLayout.LayoutParams(-1, -2);
        panelLp.gravity = Gravity.BOTTOM;
        panelLp.leftMargin = dp(14);
        panelLp.rightMargin = dp(14);
        panelLp.bottomMargin = dp(14);
        overlay.addView(panel, panelLp);
        attachOverlay(overlay, panel, true);
    }

    private void refreshMembershipFromPanel(Button button) {
        final String token = currentToken;
        if (token == null || token.trim().isEmpty()) return;
        button.setEnabled(false);
        button.setText("刷新中…");
        new Thread(() -> {
            AuthClient.ProfileResult profile = AuthClient.me(token);
            runOnUiThread(() -> {
                button.setEnabled(true);
                button.setText("刷新会员状态");
                if (!profile.requestOk || !profile.success) {
                    toast(profile.message == null || profile.message.isEmpty() ? "刷新失败" : profile.message);
                    return;
                }
                closeActiveOverlayImmediate();
                showHome(profile);
                toast("会员状态已刷新");
            });
        }, "RYLUX-Panel-Refresh").start();
    }

    private void redeemFromPanel(EditText codeEdit, Button button) {
        String code = codeEdit.getText().toString().trim();
        if (code.isEmpty()) {
            focusWithMessage(codeEdit, "请输入兑换码");
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
                    closeActiveOverlayImmediate();
                    refreshProfile(false);
                }
            });
        }, "RYLUX-Redeem").start();
    }

    private void selectMirrorPack() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/octet-stream",
                "application/x-zip-compressed"
        });
        try {
            startActivityForResult(intent, REQUEST_MIRROR_PACK);
        } catch (Throwable t) {
            toast("无法打开文件选择器");
        }
    }

    private void importMirrorPack(Uri uri) {
        if (mirrorSelectButton != null) {
            mirrorSelectButton.setEnabled(false);
            mirrorSelectButton.setText("导入中…");
        }
        if (mirrorStatusView != null) {
            mirrorStatusView.setText("正在导入镜像包，请保持 RYLUX 在前台…");
            mirrorStatusView.setTextColor(MUTED);
        }
        showLaunchProgress("正在读取镜像包…", 0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        new Thread(() -> {
            try {
                MirrorPackManager.ImportResult result = MirrorPackManager.importPack(this, uri, (message, percent) ->
                        runOnUiThread(() -> showLaunchProgress(message, percent))
                );
                runOnUiThread(() -> {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    hideLaunchProgress();
                    if (mirrorSelectButton != null) {
                        mirrorSelectButton.setEnabled(true);
                        mirrorSelectButton.setText("选择镜像包");
                    }
                    MirrorPackManager.MirrorStatus status = MirrorPackManager.status(this);
                    if (mirrorStatusView != null) {
                        mirrorStatusView.setText(mirrorStatusText(status));
                        mirrorStatusView.setTextColor(status.ready ? GREEN : MUTED);
                    }
                    toast(result.name + " 已导入，共 " + result.fileCount + " 个 PAK");
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    hideLaunchProgress();
                    if (mirrorSelectButton != null) {
                        mirrorSelectButton.setEnabled(true);
                        mirrorSelectButton.setText("选择镜像包");
                    }
                    if (mirrorStatusView != null) {
                        mirrorStatusView.setText("镜像包导入失败");
                        mirrorStatusView.setTextColor(RED);
                    }
                    toast("镜像包导入失败：" + safeMessage(t));
                });
            }
        }, "RYLUX-Mirror-Import").start();
    }

    private void activateModule(Button button) {
        if (!currentMembershipActive) return;
        if (currentToken == null || currentToken.trim().isEmpty()) {
            showAuthUi(false);
            return;
        }

        button.setEnabled(false);
        button.setText("正在验证账号…");
        showLaunchProgress("正在验证账号…", -1);
        final String token = currentToken;

        new Thread(() -> {
            AuthClient.ActionResult result = AuthClient.authorize(token, MODULE_CODE);
            if (!result.requestOk || !result.success) {
                runOnUiThread(() -> {
                    hideLaunchProgress();
                    button.setEnabled(true);
                    button.setText("启动游戏");
                    toast(result.message);
                    if (result.requestOk) refreshProfile(false);
                });
                return;
            }

            if (getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE) == null) {
                resetStartButton(button, "未检测到封神榜游戏");
                return;
            }

            LaunchProgress.begin("正在检查封神榜资源更新…");
            runOnUiThread(() -> {
                button.setText("正在准备资源…");
                showLaunchProgress("正在检查封神榜资源更新…", -1);
            });

            try {
                Intent service = new Intent(this, InterceptService.class)
                        .setAction(InterceptService.ACTION_START);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                else startService(service);
            } catch (Throwable t) {
                LaunchProgress.fail("启动失败：" + safeMessage(t));
                resetStartButton(button, LaunchProgress.error());
                return;
            }

            if (!waitForModuleReady()) {
                String message = launchWaitError;
                if (message == null || message.trim().isEmpty()) message = "资源准备失败，请重试";
                resetStartButton(button, message);
                return;
            }

            runOnUiThread(() -> {
                hideLaunchProgress();
                button.setEnabled(true);
                button.setText("启动游戏");
                if (!launchGame()) toast("服务已启动，但未找到封神榜游戏启动入口");
            });
        }, "RYLUX-Module-Authorize").start();
    }

    private boolean waitForModuleReady() {
        launchWaitError = "";
        long deadline = System.currentTimeMillis() + 5L * 60L * 1000L;
        String lastMessage = null;
        int lastProgress = Integer.MIN_VALUE;

        while (System.currentTimeMillis() < deadline) {
            if (LaunchProgress.isRunning()) return true;

            String message = LaunchProgress.message();
            int progress = LaunchProgress.progress();
            if ((lastMessage == null && message != null)
                    || (lastMessage != null && !lastMessage.equals(message))
                    || progress != lastProgress) {
                final String uiMessage = message == null || message.trim().isEmpty()
                        ? "正在准备资源…"
                        : message;
                final int uiProgress = progress;
                runOnUiThread(() -> showLaunchProgress(uiMessage, uiProgress));
                lastMessage = message;
                lastProgress = progress;
            }

            String error = LaunchProgress.error();
            if (!LaunchProgress.isStarting()
                    && !LaunchProgress.isRunning()
                    && error != null
                    && !error.trim().isEmpty()) {
                launchWaitError = error;
                return false;
            }

            try {
                Thread.sleep(150L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                launchWaitError = "资源准备已中断";
                return false;
            }
        }

        launchWaitError = "资源准备超时，请检查网络后重试";
        return false;
    }

    private void showLaunchProgress(String message, int progress) {
        if (moduleProgressText != null) {
            moduleProgressText.setText(message == null ? "正在准备资源…" : message);
            moduleProgressText.setVisibility(View.VISIBLE);
        }
        if (moduleProgress != null) {
            moduleProgress.setVisibility(View.VISIBLE);
            if (progress < 0) {
                moduleProgress.setIndeterminate(true);
            } else {
                moduleProgress.setIndeterminate(false);
                moduleProgress.setProgress(Math.max(0, Math.min(100, progress)));
            }
        }
    }

    private void hideLaunchProgress() {
        if (moduleProgressText != null) moduleProgressText.setVisibility(View.GONE);
        if (moduleProgress != null) {
            moduleProgress.setIndeterminate(false);
            moduleProgress.setProgress(0);
            moduleProgress.setVisibility(View.GONE);
        }
    }

    private void performLogout() {
        final String token = currentToken;
        currentToken = null;
        currentUsername = null;
        currentMembershipActive = false;
        currentMembershipKind = "expired";
        currentExpiresAt = null;
        currentProfile = null;
        authStorage.clearSession();
        showAuthUi(false);
        if (token != null && !token.trim().isEmpty()) {
            new Thread(() -> AuthClient.logout(token), "RYLUX-Logout").start();
        }
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
            hideLaunchProgress();
            button.setEnabled(currentMembershipActive);
            button.setText(currentMembershipActive ? "启动游戏" : "暂时无法使用");
            toast(message);
        });
    }

    private void setAuthBusy(boolean busy) {
        if (authButton != null) {
            authButton.setEnabled(!busy);
            authButton.setText(busy ? (registerMode ? "注册中…" : "登录中…")
                    : (registerMode ? "免费注册" : "登录"));
        }
        if (switchModeLink != null) switchModeLink.setEnabled(!busy);
        if (usernameEdit != null) usernameEdit.setEnabled(!busy);
        if (passwordEdit != null) passwordEdit.setEnabled(!busy);
        if (confirmPasswordEdit != null) confirmPasswordEdit.setEnabled(!busy);
        if (rememberCheck != null) rememberCheck.setEnabled(!busy);
        if (authProgress != null) authProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private FrameLayout overlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.argb(178, 0, 0, 0));
        overlay.setClickable(true);
        overlay.setFocusable(true);
        overlay.setOnClickListener(v -> closeActiveOverlay());
        return overlay;
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClickable(true);
        panel.setFocusable(true);
        panel.setOnClickListener(v -> {});
        GradientDrawable bg = round(CARD, 24);
        bg.setStroke(dp(1), BORDER);
        panel.setBackground(bg);
        panel.setElevation(dp(20));
        return panel;
    }

    private void attachOverlay(FrameLayout overlay, View panel, boolean fromBottom) {
        if (overlayHost == null) return;
        activeOverlay = overlay;
        activePanel = panel;
        activePanelFromBottom = fromBottom;
        overlay.setAlpha(0f);
        panel.setAlpha(0f);
        panel.setScaleX(0.985f);
        panel.setScaleY(0.985f);
        panel.setTranslationY(dp(fromBottom ? 28 : -18));
        overlayHost.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        overlay.animate().alpha(1f).setDuration(190L).start();
        panel.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setDuration(260L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void closeActiveOverlay() {
        final FrameLayout overlay = activeOverlay;
        final View panel = activePanel;
        if (overlay == null || overlayHost == null) return;
        activeOverlay = null;
        activePanel = null;
        float exitY = dp(activePanelFromBottom ? 20 : -14);
        overlay.animate().alpha(0f).setDuration(160L).start();
        if (panel != null) {
            panel.animate()
                    .alpha(0f)
                    .scaleX(0.99f)
                    .scaleY(0.99f)
                    .translationY(exitY)
                    .setDuration(180L)
                    .setInterpolator(new AccelerateDecelerateInterpolator())
                    .withEndAction(() -> {
                        if (overlay.getParent() instanceof ViewGroup) {
                            ((ViewGroup) overlay.getParent()).removeView(overlay);
                        }
                        clearTransientViews();
                    })
                    .start();
        } else if (overlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) overlay.getParent()).removeView(overlay);
        }
    }

    private void closeActiveOverlayImmediate() {
        if (activeOverlay != null && activeOverlay.getParent() instanceof ViewGroup) {
            ((ViewGroup) activeOverlay.getParent()).removeView(activeOverlay);
        }
        activeOverlay = null;
        activePanel = null;
        clearTransientViews();
    }

    private void clearTransientViews() {
        moduleProgress = null;
        moduleProgressText = null;
        mirrorStatusView = null;
        mirrorSelectButton = null;
    }

    private LinearLayout baseContent() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        if (Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(0);
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

        FrameLayout host = new FrameLayout(this);
        host.setBackgroundColor(BG);
        host.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        overlayHost = host;
        activeOverlay = null;
        activePanel = null;
        setContentView(host);
    }

    private LinearLayout card() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = round(CARD, 16);
        bg.setStroke(dp(1), BORDER);
        v.setBackground(bg);
        return v;
    }

    private TextView title(String value) {
        TextView v = text(value, 28, TEXT, true);
        v.setPadding(0, 0, 0, dp(12));
        return v;
    }

    private EditText input(String hint, boolean password) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(15);
        edit.setTextColor(TEXT);
        edit.setHintTextColor(MUTED);
        edit.setHint(hint);
        edit.setPadding(dp(13), 0, password ? dp(46) : dp(13), 0);
        edit.setBackground(inputBackground(false));
        edit.setFocusable(true);
        edit.setFocusableInTouchMode(true);
        edit.setCursorVisible(true);
        edit.setSelectAllOnFocus(false);
        edit.setOnFocusChangeListener((v, hasFocus) -> edit.setBackground(inputBackground(hasFocus)));
        if (Build.VERSION.SDK_INT >= 29) {
            GradientDrawable cursor = new GradientDrawable();
            cursor.setColor(PRIMARY);
            cursor.setSize(dp(2), dp(24));
            edit.setTextCursorDrawable(cursor);
        }
        if (password) {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            edit.setTransformationMethod(PasswordTransformationMethod.getInstance());
            attachPasswordToggle(edit);
        }
        return edit;
    }

    private void attachPasswordToggle(EditText edit) {
        edit.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.ic_menu_view, 0);
        edit.setCompoundDrawablePadding(dp(10));
        edit.setCompoundDrawableTintList(ColorStateList.valueOf(MUTED));
        edit.setTag(Boolean.FALSE);
        edit.setOnTouchListener((v, event) -> {
            if (event.getAction() != MotionEvent.ACTION_UP) return false;
            Drawable end = edit.getCompoundDrawables()[2];
            if (end == null) return false;
            float trigger = edit.getWidth() - edit.getPaddingRight() - end.getBounds().width() - dp(12);
            if (event.getX() < trigger) return false;

            boolean visible = Boolean.TRUE.equals(edit.getTag());
            visible = !visible;
            edit.setTag(visible);
            edit.setTransformationMethod(visible ? null : PasswordTransformationMethod.getInstance());
            edit.setSelection(edit.length());
            edit.requestFocus();
            return true;
        });
    }

    private GradientDrawable inputBackground(boolean focused) {
        GradientDrawable bg = round(CARD_SOFT, 10);
        bg.setStroke(dp(focused ? 2 : 1), focused ? PRIMARY : BORDER);
        return bg;
    }

    private LinearLayout.LayoutParams inputParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(52));
        lp.topMargin = dp(10);
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

    private TextView pill(String label, int bg, int fg) {
        TextView v = text(label, 11, fg, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(9), 0, dp(9), 0);
        v.setBackground(round(bg, 8));
        return v;
    }

    private TextView closeCircle() {
        TextView close = text("×", 22, TEXT, false);
        close.setGravity(Gravity.CENTER);
        close.setBackground(round(CARD_SOFT, 20));
        close.setClickable(true);
        close.setFocusable(true);
        return close;
    }

    private LinearLayout infoRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(11), 0, dp(11));
        TextView l = text(label, 13, MUTED, false);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView r = text(value == null || value.trim().isEmpty() ? "-" : value, 13, TEXT, true);
        r.setGravity(Gravity.END);
        row.addView(r, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private TextView membershipStateText(AuthClient.ProfileResult profile) {
        boolean trial = profile.membershipActive && "trial".equals(profile.membershipKind);
        String label = trial ? "24 小时体验" : (profile.membershipActive ? "VIP 会员" : "使用时间已到期");
        int color = trial ? YELLOW : (profile.membershipActive ? GREEN : RED);
        TextView v = text(label, 14, color, true);
        v.setPadding(0, 0, 0, dp(6));
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

    private void focusWithMessage(EditText edit, String message) {
        if (edit != null) {
            edit.requestFocus();
            edit.setSelection(edit.length());
        }
        toast(message);
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

    private String avatarLetter(String username) {
        if (username == null || username.trim().isEmpty()) return "U";
        String value = username.trim();
        return value.substring(0, 1).toUpperCase(Locale.getDefault());
    }

    private String mirrorStatusText(MirrorPackManager.MirrorStatus status) {
        if (status == null || !status.ready) return "镜像包：未选择";
        return "镜像包：" + status.name + " · " + status.fileCount + " 个 PAK · " + humanBytes(status.totalBytes);
    }

    private String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
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
