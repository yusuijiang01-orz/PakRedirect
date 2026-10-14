package com.example.pakredirect;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
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
    private static final int BG = Color.rgb(17, 19, 24);
    private static final int CARD = Color.rgb(28, 32, 40);
    private static final int SOFT = Color.rgb(34, 39, 48);
    private static final int TEXT = Color.rgb(245, 247, 250);
    private static final int MUTED = Color.rgb(154, 163, 176);
    private static final int BLUE = Color.rgb(72, 113, 255);
    private static final int BORDER = Color.rgb(47, 53, 64);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout appList;
    private TextView status;
    private ProgressBar progress;

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

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(20));
        root.setBackgroundColor(BG);

        Button back = button("‹  返回 RYLUX", SOFT);
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(44)));

        TextView title = label("BlackBox 游戏沙箱", 23, TEXT, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(18);
        root.addView(title, titleLp);

        TextView note = label(
                "测试版：导入单个 APK 并尝试在虚拟环境运行。无需 Root；暂不支持 XAPK/APKS，游戏与模拟器兼容性需逐个验证。",
                13,
                MUTED,
                false
        );
        note.setLineSpacing(dp(3), 1.08f);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.topMargin = dp(8);
        noteLp.bottomMargin = dp(16);
        root.addView(note, noteLp);

        Button importButton = button("选择游戏 APK 导入沙箱", BLUE);
        importButton.setOnClickListener(v -> pickApk());
        root.addView(importButton, new LinearLayout.LayoutParams(-1, dp(50)));

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setGravity(Gravity.CENTER_VERTICAL);
        statusCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        statusCard.setBackground(round(CARD, 14, BORDER));
        progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        progressLp.rightMargin = dp(10);
        statusCard.addView(progress, progressLp);
        status = label("正在检查虚拟环境…", 13, MUTED, false);
        statusCard.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = dp(14);
        root.addView(statusCard, statusLp);

        TextView section = label("沙箱中的应用", 16, TEXT, true);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(-1, -2);
        sectionLp.topMargin = dp(20);
        sectionLp.bottomMargin = dp(8);
        root.addView(section, sectionLp);

        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(appList, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        Button refresh = button("刷新列表", SOFT);
        refresh.setOnClickListener(v -> refreshApps());
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(-1, dp(44));
        refreshLp.topMargin = dp(10);
        root.addView(refresh, refreshLp);
        setContentView(root);
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
        appList.removeAllViews();
        int count = 0;
        for (ApplicationInfo app : apps) {
            if (app == null || app.packageName == null || getPackageName().equals(app.packageName)) continue;
            count++;
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setBackground(round(CARD, 14, BORDER));

            CharSequence appName;
            try {
                appName = app.loadLabel(BlackBoxCore.getPackageManager());
            } catch (Throwable ignored) {
                appName = app.packageName;
            }
            card.addView(label(String.valueOf(appName), 15, TEXT, true));
            TextView packageName = label(app.packageName, 11, MUTED, false);
            packageName.setPadding(0, dp(3), 0, dp(8));
            card.addView(packageName);

            String packageToLaunch = app.packageName;
            if (LOCALIZED_GAME_PACKAGE.equals(packageToLaunch)) {
                TextView localization = label(
                        "RYLUX 汉化：" + (LocalizationSettings.isEnabled(this) ? "已开启" : "已关闭"),
                        11,
                        MUTED,
                        false
                );
                localization.setPadding(0, 0, 0, dp(8));
                card.addView(localization);

                Button localizedLaunch = button("汉化后启动", BLUE);
                localizedLaunch.setOnClickListener(v -> launch(packageToLaunch, true));
                card.addView(localizedLaunch, new LinearLayout.LayoutParams(-1, dp(40)));

                Button directLaunch = button("直接启动（不使用汉化）", SOFT);
                directLaunch.setOnClickListener(v -> launch(packageToLaunch, false));
                LinearLayout.LayoutParams directLp = new LinearLayout.LayoutParams(-1, dp(40));
                directLp.topMargin = dp(6);
                card.addView(directLaunch, directLp);
            } else {
                Button launch = button("启动", BLUE);
                launch.setOnClickListener(v -> launch(packageToLaunch, false));
                card.addView(launch, new LinearLayout.LayoutParams(-1, dp(40)));
            }

            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
            cardLp.bottomMargin = dp(8);
            appList.addView(card, cardLp);
        }
        if (count == 0) appList.addView(label("还没有导入应用。选择一个 APK 开始测试。", 13, MUTED, false));
        setBusy(false, "虚拟环境就绪 · " + count + " 个应用");
    }

    private void launch(String packageName, boolean withLocalization) {
        setBusy(true, withLocalization ? "正在准备 RYLUX 汉化…" : "正在启动 " + packageName + "…");
        worker.execute(() -> {
            try {
                if (withLocalization && LOCALIZED_GAME_PACKAGE.equals(packageName)) {
                    if (!LocalizationSettings.isEnabled(this)) {
                        throw new IllegalStateException("RYLUX 汉化开关已关闭，请在首页开启后重试");
                    }
                    if (!InterceptService.isRunning()) {
                        LaunchProgress.begin("正在准备 RYLUX 汉化资源…");
                        Intent service = new Intent(this, InterceptService.class)
                                .setAction(InterceptService.ACTION_START);
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                        else startService(service);
                        if (!waitForLocalizationReady()) {
                            String error = LaunchProgress.error();
                            if (error == null || error.trim().isEmpty()) {
                                error = LaunchProgress.message();
                            }
                            throw new IllegalStateException(error == null || error.trim().isEmpty()
                                    ? "RYLUX 汉化服务未能就绪" : error);
                        }
                    }
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

    private boolean waitForLocalizationReady() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5L * 60L * 1000L;
        String lastMessage = null;
        while (System.currentTimeMillis() < deadline) {
            if (InterceptService.isRunning()) return true;
            String error = LaunchProgress.error();
            if (!LaunchProgress.isStarting() && error != null && !error.trim().isEmpty()) return false;
            String message = LaunchProgress.message();
            if (message != null && !message.trim().isEmpty() && !message.equals(lastMessage)) {
                lastMessage = message;
                String progressMessage = message;
                runOnUiThread(() -> setBusy(true, progressMessage));
            }
            Thread.sleep(200L);
        }
        LaunchProgress.fail("等待 RYLUX 汉化服务超时");
        return false;
    }

    private void setBusy(boolean busy, String message) {
        if (isFinishing() || isDestroyed() || status == null) return;
        status.setText(message);
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private Button button(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(TEXT);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(round(color, 13, color == BLUE ? BLUE : BORDER));
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

    private GradientDrawable round(int color, int radiusDp, int strokeColor) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        shape.setStroke(dp(1), strokeColor);
        return shape;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String safe(String value) {
        return value == null || value.trim().isEmpty() ? "未知错误" : value;
    }
}
