package com.example.pakredirect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.pm.InstallResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A minimal, opt-in test surface for importing and launching apps in BlackBox. */
public final class BlackBoxSandboxActivity extends Activity {
    private static final int PICK_APK = 6017;
    private static final String LOCALIZED_GAME_PACKAGE = "com.tepaylink.tamgioiphantranhmobile";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout appList;
    private TextView status;
    private ProgressBar progress;
    private Switch localizationToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        RyluxApplication application = (RyluxApplication) getApplication();
        if (application.isBlackBoxReady()) {
            try {
                BlackBoxCore.get().onBeforeMainActivityOnCreate(this);
            } catch (Throwable ignored) {
                // Keep the diagnostic screen available if an optional hook fails.
            }
        }
        super.onCreate(savedInstanceState);
        if (application.isBlackBoxReady()) {
            try {
                BlackBoxCore.get().onAfterMainActivityOnCreate(this);
            } catch (Throwable ignored) {
                // The sandbox screen can still report core startup failures.
            }
        }
        buildUi();
        refreshApps();
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean cached = MembershipAccess.hasCachedAccess(this);
        if (!cached && LocalizationSettings.isEnabled(this)) {
            LocalizationSettings.setEnabled(this, false);
            stopInterceptModule();
        }
        if (localizationToggle != null) {
            localizationToggle.setOnCheckedChangeListener(null);
            localizationToggle.setChecked(cached && LocalizationSettings.isEnabled(this));
            localizationToggle.setEnabled(true);
            bindLocalizationToggle();
        }
        MembershipAccess.refresh(this, result -> runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) applyMembershipCheck(result);
        }));
    }

    private void buildUi() {
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(20));
        page.addView(root, new ScrollView.LayoutParams(-1, -2));

        Button back = button("‹  返回 RYLUX", false);
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(44)));

        TextView title = label("BlackBox 游戏沙箱", 23, RyluxDesignSystem.TEXT, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(18);
        root.addView(title, titleLp);

        TextView note = label(
                "可克隆桌面已安装的封神榜手游，也可手动导入单个 APK。沙箱数据独立；暂不支持分卷 APK（XAPK/APKS）。",
                13,
                RyluxDesignSystem.MUTED,
                false
        );
        note.setLineSpacing(dp(3), 1.08f);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.topMargin = dp(8);
        noteLp.bottomMargin = dp(16);
        root.addView(note, noteLp);

        Button cloneButton = button("克隆桌面封神榜手游到沙箱", true);
        cloneButton.setOnClickListener(v -> prepareDesktopClone());
        root.addView(cloneButton, new LinearLayout.LayoutParams(-1, dp(50)));

        Button importButton = button("选择 APK 手动导入", false);
        importButton.setOnClickListener(v -> pickApk());
        LinearLayout.LayoutParams importLp = new LinearLayout.LayoutParams(-1, dp(44));
        importLp.topMargin = dp(8);
        root.addView(importButton, importLp);

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusCard.setBackground(RyluxDesignSystem.card(this, 14));
        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        progressLp.rightMargin = dp(10);
        statusCard.addView(progress, progressLp);
        status = label("正在检查虚拟环境…", 13, RyluxDesignSystem.MUTED, false);
        statusCard.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = dp(14);
        root.addView(statusCard, statusLp);

        TextView section = label("沙箱中的应用", 16, RyluxDesignSystem.TEXT, true);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.topMargin = dp(20);
        sectionLp.bottomMargin = dp(8);
        root.addView(section, sectionLp);

        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        root.addView(appList, new LinearLayout.LayoutParams(-1, -2));

        Button refresh = button("刷新列表", false);
        refresh.setOnClickListener(v -> refreshApps());
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(-1, dp(44));
        refreshLp.topMargin = dp(10);
        root.addView(refresh, refreshLp);

        View divider = new View(this);
        divider.setBackgroundColor(RyluxDesignSystem.BORDER);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(-1, dp(1));
        dividerLp.topMargin = dp(24);
        dividerLp.bottomMargin = dp(20);
        root.addView(divider, dividerLp);

        LinearLayout scriptsCard = new LinearLayout(this);
        scriptsCard.setOrientation(LinearLayout.VERTICAL);
        scriptsCard.setPadding(dp(14), dp(14), dp(14), dp(14));
        scriptsCard.setBackground(RyluxDesignSystem.card(this, 14));
        scriptsCard.addView(label("脚本工具", 16, RyluxDesignSystem.TEXT, true));
        TextView scriptNote = label("Lua 执行宿主已接入 · 配置方案并确认后运行", 13, RyluxDesignSystem.MUTED, false);
        scriptNote.setPadding(0, dp(7), 0, dp(12));
        scriptsCard.addView(scriptNote);
        Button openScripts = button("打开脚本工作台", false);
        openScripts.setOnClickListener(v -> openScriptWorkbench());
        scriptsCard.addView(openScripts, new LinearLayout.LayoutParams(-1, dp(46)));
        root.addView(scriptsCard, new LinearLayout.LayoutParams(-1, -2));
        FrameLayout host = new FrameLayout(this);
        RyluxDesignSystem.installHomeBackdrop(this, host);
        host.addView(page, new FrameLayout.LayoutParams(-1, -1));
        setContentView(host);
    }

    private void pickApk() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/vnd.android.package-archive");
        startActivityForResult(intent, PICK_APK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_APK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri apkUri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    apkUri,
                    data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            );
        } catch (SecurityException ignored) {
            // The picker may provide a temporary read grant instead of a persistable one.
        }
        setBusy(true, "正在将 APK 安装到虚拟环境…");
        worker.execute(() -> {
            File stagedApk = null;
            try {
                stagedApk = stageApkForImport(apkUri);
                InstallResult result = BlackBoxCore.get().installPackageAsUser(stagedApk, 0);
                runOnUiThread(() -> {
                    setBusy(false, result.success
                            ? "导入成功：" + safe(result.packageName)
                            : "导入失败：" + safe(result.msg));
                    Toast.makeText(this, result.success ? "已加入沙箱" : "导入失败", Toast.LENGTH_LONG).show();
                    refreshApps();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    setBusy(false, "导入失败：" + safe(error.getMessage()));
                    Toast.makeText(this, "导入失败，请查看状态说明", Toast.LENGTH_LONG).show();
                });
            } finally {
                if (stagedApk != null) stagedApk.delete();
            }
        });
    }

    private File stageApkForImport(Uri apkUri) throws IOException {
        File importDir = new File(getCacheDir(), "blackbox-import");
        if (!importDir.isDirectory() && !importDir.mkdirs()) {
            throw new IOException("无法创建 APK 暂存目录");
        }
        File stagedApk = File.createTempFile("selected-", ".apk", importDir);
        try (InputStream input = getContentResolver().openInputStream(apkUri)) {
            if (input == null) throw new IOException("无法读取所选 APK");
            try (FileOutputStream output = new FileOutputStream(stagedApk)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            return stagedApk;
        } catch (IOException | RuntimeException error) {
            stagedApk.delete();
            throw error;
        }
    }

    private void prepareDesktopClone() {
        setBusy(true, "正在检查桌面封神榜手游…");
        worker.execute(() -> {
            try {
                RyluxApplication application = (RyluxApplication) getApplication();
                if (!application.isBlackBoxReady()) {
                    String error = application.getBlackBoxError();
                    throw new IOException(error == null || error.trim().isEmpty()
                            ? "BlackBox 虚拟环境尚未就绪" : error);
                }

                ApplicationInfo desktopApplication = getPackageManager()
                        .getApplicationInfo(LOCALIZED_GAME_PACKAGE, 0);
                PackageInfo desktopPackage = getPackageManager()
                        .getPackageInfo(LOCALIZED_GAME_PACKAGE, 0);
                if (desktopApplication.sourceDir == null
                        || desktopApplication.sourceDir.trim().isEmpty()) {
                    throw new IOException("桌面游戏没有可读取的 APK 路径");
                }
                String[] splits = desktopApplication.splitSourceDirs;
                if (splits != null && splits.length > 0) {
                    throw new IOException("桌面游戏由多个分卷 APK 组成，当前沙箱克隆只支持单 APK；请使用整包 APK 手动导入");
                }

                boolean installedInSandbox = BlackBoxCore.get()
                        .isInstalled(LOCALIZED_GAME_PACKAGE, 0);
                PackageInfo sandboxPackage = installedInSandbox
                        ? BlackBoxCore.getBPackageManager().getPackageInfo(
                                LOCALIZED_GAME_PACKAGE, 0, 0)
                        : null;
                DesktopClonePlan plan = new DesktopClonePlan(
                        desktopPackage.versionName,
                        getVersionCode(desktopPackage),
                        sandboxPackage == null ? null : sandboxPackage.versionName,
                        sandboxPackage == null ? null : getVersionCode(sandboxPackage));
                runOnUiThread(() -> showDesktopCloneConfirmation(plan));
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    setBusy(false, "无法克隆桌面客户端：" + safe(error.getMessage()));
                    Toast.makeText(this, "克隆失败：" + safe(error.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showDesktopCloneConfirmation(DesktopClonePlan plan) {
        if (isFinishing() || isDestroyed()) return;
        StringBuilder details = new StringBuilder()
                .append("桌面客户端：").append(safe(plan.desktopVersionName))
                .append("（版本码 ").append(plan.desktopVersionCode).append("）");
        if (plan.sandboxVersionCode != null) {
            details.append("\n沙箱当前版本：").append(safe(plan.sandboxVersionName))
                    .append("（版本码 ").append(plan.sandboxVersionCode).append("）")
                    .append("\n继续后会用桌面 APK 替换沙箱内同包游戏。");
        } else {
            details.append("\n沙箱中尚未安装该游戏，将把桌面 APK 安装到沙箱。");
        }
        details.append("\n沙箱原有数据由 BlackBox 保留；桌面端的登录状态和游戏数据不会复制过去。继续后可能需要在沙箱重新登录。");

        new AlertDialog.Builder(this)
                .setTitle("克隆桌面客户端")
                .setMessage(details.toString())
                .setNegativeButton("取消", (dialog, which) -> setBusy(false, "已取消克隆"))
                .setPositiveButton("继续克隆", (dialog, which) -> cloneDesktopPackage(plan))
                .setOnCancelListener(dialog -> setBusy(false, "已取消克隆"))
                .show();
    }

    private void cloneDesktopPackage(DesktopClonePlan plan) {
        setBusy(true, "正在把桌面客户端安装到沙箱…");
        worker.execute(() -> {
            try {
                InstallResult result = BlackBoxCore.get()
                        .installPackageAsUser(LOCALIZED_GAME_PACKAGE, 0);
                if (result == null || !result.success) {
                    String reason = result == null ? "BlackBox 没有返回安装结果" : safe(result.msg);
                    throw new IOException(reason);
                }
                PackageInfo installed = BlackBoxCore.getBPackageManager()
                        .getPackageInfo(LOCALIZED_GAME_PACKAGE, 0, 0);
                if (installed == null || getVersionCode(installed) != plan.desktopVersionCode) {
                    String found = installed == null ? "未找到" : String.valueOf(getVersionCode(installed));
                    throw new IOException("安装结果版本校验失败，期望 "
                            + plan.desktopVersionCode + "，沙箱读取到 " + found);
                }
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setBusy(false, "克隆完成：封神榜手游 " + safe(plan.desktopVersionName)
                            + "（" + plan.desktopVersionCode + "）");
                    Toast.makeText(this, "桌面客户端已克隆到沙箱", Toast.LENGTH_LONG).show();
                    refreshApps();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    setBusy(false, "克隆失败：" + safe(error.getMessage()));
                    Toast.makeText(this, "克隆失败：" + safe(error.getMessage()), Toast.LENGTH_LONG).show();
                    refreshApps();
                });
            }
        });
    }

    private static long getVersionCode(PackageInfo packageInfo) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? packageInfo.getLongVersionCode() : packageInfo.versionCode;
    }

    private static final class DesktopClonePlan {
        final String desktopVersionName;
        final long desktopVersionCode;
        final String sandboxVersionName;
        final Long sandboxVersionCode;

        DesktopClonePlan(String desktopVersionName, long desktopVersionCode,
                         String sandboxVersionName, Long sandboxVersionCode) {
            this.desktopVersionName = desktopVersionName;
            this.desktopVersionCode = desktopVersionCode;
            this.sandboxVersionName = sandboxVersionName;
            this.sandboxVersionCode = sandboxVersionCode;
        }
    }

    private void refreshApps() {
        RyluxApplication application = (RyluxApplication) getApplication();
        if (!application.isBlackBoxReady()) {
            String detail = application.getBlackBoxError();
            setBusy(false, detail.isEmpty() ? "虚拟环境尚未就绪" : "虚拟环境初始化失败：" + detail);
            return;
        }
        setBusy(true, "正在读取沙箱应用…");
        worker.execute(() -> {
            try {
                List<ApplicationInfo> apps = BlackBoxCore.get().getInstalledApplications(0, 0);
                runOnUiThread(() -> showApps(apps));
            } catch (Throwable error) {
                runOnUiThread(() -> setBusy(false, "读取失败：" + safe(error.getMessage())));
            }
        });
    }

    private void showApps(List<ApplicationInfo> apps) {
        localizationToggle = null;
        appList.removeAllViews();
        int count = 0;
        for (ApplicationInfo app : apps) {
            if (app == null || app.packageName == null || getPackageName().equals(app.packageName)) continue;
            count++;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setBackground(RyluxDesignSystem.card(this, 14));

            CharSequence appName;
            try {
                appName = app.loadLabel(BlackBoxCore.getPackageManager());
            } catch (Throwable ignored) {
                appName = app.packageName;
            }
            card.addView(label(String.valueOf(appName), 15, RyluxDesignSystem.TEXT, true));
            TextView packageName = label(app.packageName, 11, RyluxDesignSystem.MUTED, false);
            packageName.setPadding(0, dp(3), 0, dp(8));
            card.addView(packageName);

            String packageToLaunch = app.packageName;
            if (LOCALIZED_GAME_PACKAGE.equals(packageToLaunch)) {
                LinearLayout toggleRow = new LinearLayout(this);
                toggleRow.setGravity(Gravity.CENTER_VERTICAL);
                toggleRow.addView(label("RYLUX 汉化", 13, RyluxDesignSystem.TEXT, true),
                        new LinearLayout.LayoutParams(0, -2, 1));
                localizationToggle = new Switch(this);
                localizationToggle.setContentDescription("RYLUX 汉化");
                localizationToggle.setChecked(LocalizationSettings.isEnabled(this));
                bindLocalizationToggle();
                toggleRow.addView(localizationToggle);
                LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(-1, dp(44));
                toggleLp.bottomMargin = dp(6);
                card.addView(toggleRow, toggleLp);

                Button launch = button("启动游戏", true);
                launch.setOnClickListener(v -> launch(packageToLaunch,
                        LocalizationSettings.isEnabled(this)));
                card.addView(launch, new LinearLayout.LayoutParams(-1, dp(40)));
            } else {
                Button launch = button("启动", true);
                launch.setOnClickListener(v -> launch(packageToLaunch, false));
                card.addView(launch, new LinearLayout.LayoutParams(-1, dp(40)));
            }

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
            cardLp.bottomMargin = dp(8);
            appList.addView(card, cardLp);
        }
        if (count == 0) appList.addView(label("还没有导入应用。选择一个 APK 开始测试。", 13, RyluxDesignSystem.MUTED, false));
        setBusy(false, "虚拟环境就绪 · " + count + " 个应用");
    }

    private void launch(String packageName, boolean withLocalization) {
        if (withLocalization && LOCALIZED_GAME_PACKAGE.equals(packageName)) {
            setBusy(true, "正在验证会员状态…");
            MembershipAccess.refresh(this, result -> runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                applyMembershipCheck(result);
                if (result != null && result.allowed) {
                    launchAuthorized(packageName, true);
                } else {
                    LocalizationSettings.setEnabled(this, false);
                    if (localizationToggle != null) {
                        localizationToggle.setOnCheckedChangeListener(null);
                        localizationToggle.setChecked(false);
                        localizationToggle.setEnabled(true);
                        bindLocalizationToggle();
                    }
                    stopInterceptModule();
                    setBusy(false, result == null ? "会员状态验证失败，无法使用汉化" : result.message);
                    Toast.makeText(this, result == null ? "会员状态验证失败" : result.message,
                            Toast.LENGTH_LONG).show();
                }
            }));
            return;
        }
        launchAuthorized(packageName, false);
    }

    private void launchAuthorized(String packageName, boolean withLocalization) {
        setBusy(true, withLocalization ? "正在准备 RYLUX 汉化…" : "正在启动 " + packageName + "…");
        worker.execute(() -> {
            try {
                if (withLocalization && LOCALIZED_GAME_PACKAGE.equals(packageName)) {
                    LocalizationLaunchPreparation.prepare(this,
                            message -> runOnUiThread(() -> setBusy(true, message)));
                }
                boolean launched = BlackBoxCore.get().launchApk(packageName, 0);
                String result = launched
                        ? (withLocalization ? "RYLUX 汉化已就绪，已请求在沙箱启动游戏" : "已请求启动：" + packageName)
                        : "启动失败：" + packageName;
                runOnUiThread(() -> setBusy(false, result));
            } catch (Throwable error) {
                String prefix = withLocalization ? "汉化启动失败：" : "启动失败：";
                runOnUiThread(() -> setBusy(false, prefix + safe(error.getMessage())));
            }
        });
    }

    private void bindLocalizationToggle() {
        if (localizationToggle == null) return;
        localizationToggle.setOnCheckedChangeListener((view, enabled) -> {
            if (!enabled) {
                LocalizationSettings.setEnabled(this, false);
                return;
            }
            localizationToggle.setOnCheckedChangeListener(null);
            localizationToggle.setChecked(false);
            localizationToggle.setEnabled(false);
            MembershipAccess.refresh(this, result -> runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                applyMembershipCheck(result);
                boolean allowed = result != null && result.allowed;
                LocalizationSettings.setEnabled(this, allowed);
                localizationToggle.setChecked(allowed);
                localizationToggle.setEnabled(true);
                bindLocalizationToggle();
                if (!allowed) {
                    stopInterceptModule();
                    Toast.makeText(this, result == null ? "无法验证会员状态，请检查网络后重试"
                            : result.message, Toast.LENGTH_LONG).show();
                }
            }));
        });
    }

    private void applyMembershipCheck(MembershipAccess.CheckResult result) {
        if (result == null || !result.success || result.profile == null) return;
        if (!MembershipAccess.isAllowed(result.profile)) {
            LocalizationSettings.setEnabled(this, false);
            stopInterceptModule();
            stopService(new Intent(this, ScriptRuntimeService.class));
            stopService(new Intent(this, ScriptControlOverlayService.class));
            if (localizationToggle != null) {
                localizationToggle.setOnCheckedChangeListener(null);
                localizationToggle.setChecked(false);
                localizationToggle.setEnabled(true);
                bindLocalizationToggle();
            }
        }
    }

    private void openScriptWorkbench() {
        MembershipAccess.refresh(this, result -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            applyMembershipCheck(result);
            if (result != null && result.allowed) {
                startActivity(new Intent(this, ScriptToolsActivity.class)
                        .putExtra(ScriptToolsActivity.EXTRA_TARGET_MODE,
                                ScriptToolsActivity.TARGET_MODE_SANDBOX));
            } else {
                Toast.makeText(this, result == null ? "无法验证会员状态，请检查网络后重试"
                        : result.message, Toast.LENGTH_LONG).show();
            }
        }));
    }

    private void stopInterceptModule() {
        try { stopService(new Intent(this, InterceptService.class)); }
        catch (Throwable ignored) { }
    }

    private void setBusy(boolean busy, String message) {
        if (isFinishing() || isDestroyed() || status == null) return;
        status.setText(message);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private Button button(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(RyluxDesignSystem.TEXT);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(primary
                ? RyluxDesignSystem.primaryButton(this, 13)
                : RyluxDesignSystem.secondaryButton(this, 13));
        return button;
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String safe(String value) {
        return value == null || value.trim().isEmpty() ? "未知错误" : value;
    }
}
