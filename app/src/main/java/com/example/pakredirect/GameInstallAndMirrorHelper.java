package com.example.pakredirect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Adds the game APK install entry to the existing game-detail panel and makes
 * the mirror-pack chooser prefer QQ's receive directory without changing
 * MainActivity's authentication, mirror import, or game-launch flow.
 */
public final class GameInstallAndMirrorHelper {
    private static final String TARGET_PACKAGE = "com.tepaylink.tamgioiphantranhmobile";
    private static final int REQUEST_MIRROR_PACK = 4107;

    private static final String GAME_APK_NAME = "TamGioiPhanTranhMobile-587.apk";
    // 游戏 APK 本体迁移到 GitHub Release（tag=game-apk）分发，避开 Git LFS 每月 1GB 带宽配额。
    private static final String GAME_APK_URL =
            "https://github.com/yusuijiang01-orz/PakRedirect/releases/download/game-apk/"
                    + GAME_APK_NAME;
    private static final long GAME_APK_SIZE = 101_938_646L;
    // Git LFS object id from apk/TamGioiPhanTranhMobile-587.apk（上传到 Release 的 APK 必须与此一致）。
    private static final String GAME_APK_SHA256 =
            "48b5037202bedfcdbe3bf37d5447af6f71a8b5fddc6e79cc57efb6c304fdec6c";

    private static final String QQ_RELATIVE_DIR =
            "Android/data/com.tencent.mobileqq/Tencent/QQfile_recv";
    // 国内用户镜像包下载地址（迅雷云盘分享，免登录可见，无强制扫码付费）。
    private static final String MIRROR_DOWNLOAD_URL =
            "https://pan.xunlei.com/s/VP26gzcX-oxUz_z11q56k2AAA1?pwd=c2cx";
    private static final String XUNLEI_PACKAGE = "com.xunlei.downloadprovider";

    private static final String PREFS = "rylux_game_installer";
    private static final String KEY_MD5 = "game_apk_md5";
    private static final String KEY_PENDING_UNKNOWN_SOURCES = "pending_unknown_sources";
    private static final String KEY_PENDING_UNINSTALL = "pending_after_uninstall";

    private static final Object INSTALL_LOCK = new Object();
    private static boolean installBusy;

    private static final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> LISTENERS =
            new WeakHashMap<>();
    private static final Set<View> MIRROR_HOOKED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private GameInstallAndMirrorHelper() {}

    public static void onActivityResumed(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        attach(activity);
        resumePendingInstall(activity);
    }

    public static void attach(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        synchronized (LISTENERS) {
            if (LISTENERS.containsKey(activity)) {
                activity.getWindow().getDecorView().post(() -> decorate(activity));
                return;
            }
            View decor = activity.getWindow().getDecorView();
            ViewTreeObserver.OnGlobalLayoutListener listener =
                    () -> decor.post(() -> decorate(activity));
            LISTENERS.put(activity, listener);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            decor.post(() -> decorate(activity));
        }
    }

    public static void detach(Activity activity) {
        synchronized (LISTENERS) {
            ViewTreeObserver.OnGlobalLayoutListener listener = LISTENERS.remove(activity);
            if (listener == null) return;
            View decor = activity.getWindow().getDecorView();
            ViewTreeObserver observer = decor.getViewTreeObserver();
            if (observer.isAlive()) observer.removeOnGlobalLayoutListener(listener);
        }
    }

    private static void decorate(Activity activity) {
        Button mirrorButton = findMirrorButton(activity.getWindow().getDecorView());
        if (mirrorButton == null) return;
        if (!(mirrorButton.getParent() instanceof LinearLayout)) return;

        // Wait until RyluxUiPolish has rebuilt the panel. Before that pass the
        // original mirror button is only 128dp wide; injecting earlier would
        // disturb the presentation layer's fixed child ordering.
        ViewGroup.LayoutParams raw = mirrorButton.getLayoutParams();
        if (raw == null || raw.width != ViewGroup.LayoutParams.MATCH_PARENT) return;

        if (MIRROR_HOOKED.add(mirrorButton)) {
            mirrorButton.setOnClickListener(v -> showMirrorChoiceDialog(activity));
        }

        LinearLayout parent = (LinearLayout) mirrorButton.getParent();
        if (hasInstallButton(parent)) return;

        Button installButton = new Button(activity);
        installButton.setTag("rylux_install_game_button");
        styleInstallButton(activity, installButton);
        installButton.setOnClickListener(v -> startGameInstall(activity, installButton));

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 46));
        lp.topMargin = dp(activity, 12);
        int mirrorIndex = parent.indexOfChild(mirrorButton);
        parent.addView(installButton, Math.max(0, mirrorIndex), lp);
    }

    private static boolean hasInstallButton(LinearLayout parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            Object tag = parent.getChildAt(i).getTag();
            if ("rylux_install_game_button".equals(tag)) return true;
        }
        return false;
    }

    private static Button findButton(View root, String exactText) {
        if (root instanceof Button) {
            Button button = (Button) root;
            if (exactText.contentEquals(button.getText())) return button;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                Button found = findButton(group.getChildAt(i), exactText);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Button findMirrorButton(View root) {
        Button button = findButton(root, "选择镜像包（可选）");
        return button != null ? button : findButton(root, "选择镜像包");
    }

    private static void styleInstallButton(Activity activity, Button button) {
        button.setText("安装游戏");
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setAllCaps(false);
        button.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(37, 103, 226));
        bg.setCornerRadius(dp(activity, 12));
        bg.setStroke(dp(activity, 1), Color.rgb(82, 151, 255));
        button.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 21) button.setElevation(dp(activity, 2));
    }

    public static void showMirrorChoiceDialog(Activity activity) {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity, 20), dp(activity, 18), dp(activity, 20), dp(activity, 16));
        panel.setBackground(dialogSurface(activity));

        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = dialogText(activity, "镜像包说明", 20, Color.rgb(245, 247, 250), true);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView badge = dialogText(activity, "可选资源", 11, Color.rgb(218, 232, 255), true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(activity, 9), 0, dp(activity, 9), 0);
        badge.setBackground(dialogRound(activity, Color.rgb(24, 47, 87), 7,
                Color.rgb(64, 123, 219), 1));
        titleRow.addView(badge, new LinearLayout.LayoutParams(-2, dp(activity, 28)));
        panel.addView(titleRow);

        TextView intro = dialogText(activity,
                "镜像包方便国内用户快速更新，也可以在游戏内直接更新游戏补丁。",
                14, Color.rgb(190, 202, 218), false);
        intro.setLineSpacing(0f, 1.2f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(-1, -2);
        introLp.topMargin = dp(activity, 14);
        panel.addView(intro, introLp);

        TextView version = dialogText(activity, "当前版本：v2.4.0", 13,
                Color.rgb(190, 202, 218), false);
        LinearLayout.LayoutParams versionLp = new LinearLayout.LayoutParams(-1, -2);
        versionLp.topMargin = dp(activity, 8);
        panel.addView(version, versionLp);

        LinearLayout sourceCard = new LinearLayout(activity);
        sourceCard.setOrientation(LinearLayout.VERTICAL);
        sourceCard.setPadding(dp(activity, 13), dp(activity, 10), dp(activity, 13), dp(activity, 10));
        sourceCard.setBackground(dialogRound(activity, Color.rgb(16, 24, 36), 10,
                Color.rgb(55, 83, 121), 1));
        TextView sourceLabel = dialogText(activity, "下载来源", 12,
                Color.rgb(154, 163, 176), false);
        sourceCard.addView(sourceLabel);
        TextView sourceLink = dialogText(activity, "迅雷网盘镜像包  ·  点击查看下载页面", 14,
                Color.rgb(118, 169, 255), true);
        sourceLink.setPadding(0, dp(activity, 5), 0, 0);
        sourceLink.setOnClickListener(v -> openMirrorDownloadLink(activity));
        sourceCard.addView(sourceLink);
        LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(-1, -2);
        sourceLp.topMargin = dp(activity, 14);
        panel.addView(sourceCard, sourceLp);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(panel)
                .create();

        Button download = dialogButton(activity, "前往下载", true);
        download.setOnClickListener(v -> {
            dialog.dismiss();
            openMirrorDownloadLink(activity);
        });
        LinearLayout.LayoutParams downloadLp = new LinearLayout.LayoutParams(-1, dp(activity, 46));
        downloadLp.topMargin = dp(activity, 16);
        panel.addView(download, downloadLp);

        Button local = dialogButton(activity, "选择本地镜像包", false);
        local.setOnClickListener(v -> {
            dialog.dismiss();
            openMirrorPicker(activity, QQ_RELATIVE_DIR);
        });
        LinearLayout.LayoutParams localLp = new LinearLayout.LayoutParams(-1, dp(activity, 44));
        localLp.topMargin = dp(activity, 10);
        panel.addView(local, localLp);

        TextView cancel = dialogText(activity, "取消", 14, Color.rgb(154, 163, 176), false);
        cancel.setGravity(Gravity.CENTER);
        cancel.setClickable(true);
        cancel.setPadding(0, dp(activity, 8), 0, 0);
        cancel.setOnClickListener(v -> dialog.dismiss());
        panel.addView(cancel, new LinearLayout.LayoutParams(-1, dp(activity, 34)));

        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                dialog.getWindow().setLayout(-1, -2);
            }
        });
        dialog.show();
    }

    private static TextView dialogText(Activity activity, String value, float size, int color, boolean bold) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }

    private static Button dialogButton(Activity activity, String label, boolean primary) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : Color.rgb(221, 229, 240));
        button.setPadding(dp(activity, 12), 0, dp(activity, 12), 0);
        if (primary) {
            button.setBackground(dialogRound(activity, Color.rgb(37, 103, 226), 10,
                    Color.rgb(104, 173, 255), 1));
        } else {
            button.setBackground(dialogRound(activity, Color.rgb(17, 26, 39), 10,
                    Color.rgb(55, 83, 121), 1));
        }
        if (Build.VERSION.SDK_INT >= 21) {
            button.setStateListAnimator(null);
            button.setElevation(primary ? dp(activity, 2) : 0);
        }
        return button;
    }

    private static GradientDrawable dialogSurface(Activity activity) {
        return dialogRound(activity, Color.rgb(20, 29, 43), 18,
                Color.rgb(55, 83, 121), 1);
    }

    private static GradientDrawable dialogRound(Activity activity, int color, int radius,
                                                int strokeColor, int strokeWidth) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(activity, radius));
        if (strokeWidth > 0) background.setStroke(dp(activity, strokeWidth), strokeColor);
        return background;
    }

    private static void openMirrorDownloadLink(Activity activity) {
        try {
            Uri url = Uri.parse(MIRROR_DOWNLOAD_URL);
            Intent xunlei = new Intent(Intent.ACTION_VIEW, url);
            xunlei.setPackage(XUNLEI_PACKAGE);
            if (xunlei.resolveActivity(activity.getPackageManager()) != null) {
                activity.startActivity(xunlei);
                return;
            }

            Intent browser = new Intent(Intent.ACTION_VIEW, url);
            activity.startActivity(Intent.createChooser(browser, "打开迅雷网盘"));
        } catch (Throwable t) {
            toast(activity, "无法打开浏览器，请手动访问下载链接");
        }
    }

    private static void openMirrorPicker(Activity activity, String relativeInitialDir) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/zip",
                "application/octet-stream",
                "application/x-zip-compressed"
        });
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        if (relativeInitialDir != null && !relativeInitialDir.trim().isEmpty()) {
            try {
                String documentId = "primary:" + relativeInitialDir.replace('\\', '/');
                Uri initialUri = DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        documentId
                );
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
            } catch (Throwable ignored) {
            }
        }

        try {
            activity.startActivityForResult(intent, REQUEST_MIRROR_PACK);
        } catch (Throwable first) {
            try {
                intent.removeExtra(DocumentsContract.EXTRA_INITIAL_URI);
                activity.startActivityForResult(intent, REQUEST_MIRROR_PACK);
            } catch (Throwable second) {
                toast(activity, "无法打开文件选择器");
            }
        }
    }

    private static void startGameInstall(Activity activity, Button installButton) {
        synchronized (INSTALL_LOCK) {
            if (installBusy) {
                toast(activity, "游戏安装任务正在进行中");
                return;
            }
            installBusy = true;
        }
        installButton.setEnabled(false);
        installButton.setText("正在检查本地 APK…");

        new Thread(() -> {
            try {
                File apk = gameApkFile(activity);
                Verification verification = verifyApk(activity, apk, true);
                if (!verification.valid) {
                    if (apk.exists() && !apk.delete()) {
                        throw new IllegalStateException("无法删除损坏的本地 APK");
                    }
                    downloadApk(activity, installButton, apk);
                    verification = verifyApk(activity, apk, false);
                    if (!verification.valid) {
                        throw new IllegalStateException("APK 下载完成，但完整性校验失败");
                    }
                } else {
                    updateButton(activity, installButton, "本地 APK MD5 校验通过");
                }

                rememberMd5(activity, verification.md5);
                InstallDecision decision = inspectInstallDecision(activity, apk);
                activity.runOnUiThread(() -> {
                    finishBusyButton(installButton);
                    handleInstallDecision(activity, apk, decision);
                });
            } catch (Throwable t) {
                activity.runOnUiThread(() -> {
                    finishBusyButton(installButton);
                    toast(activity, "安装游戏失败：" + safeMessage(t));
                });
            }
        }, "RYLUX-Game-APK").start();
    }

    private static File gameApkFile(Context context) {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = new File(context.getFilesDir(), "downloads");
        if (!base.exists() && !base.mkdirs() && !base.exists()) {
            throw new IllegalStateException("无法创建 APK 下载目录");
        }
        return new File(base, GAME_APK_NAME);
    }

    private static Verification verifyApk(Context context, File apk, boolean requireStoredMd5WhenPresent)
            throws Exception {
        if (apk == null || !apk.isFile() || apk.length() != GAME_APK_SIZE) {
            return Verification.invalid();
        }
        String md5 = digest(apk, "MD5");
        String sha256 = digest(apk, "SHA-256");
        if (!GAME_APK_SHA256.equalsIgnoreCase(sha256)) return Verification.invalid();

        String storedMd5 = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MD5, "");
        if (requireStoredMd5WhenPresent
                && storedMd5 != null
                && !storedMd5.trim().isEmpty()
                && !storedMd5.equalsIgnoreCase(md5)) {
            return Verification.invalid();
        }
        return new Verification(true, md5, sha256);
    }

    private static void downloadApk(Activity activity, Button button, File target) throws Exception {
        File part = new File(target.getParentFile(), target.getName() + ".part");
        if (part.exists() && !part.delete()) throw new IllegalStateException("无法清理旧下载缓存");

        String[] sources = CnDownloadRouter.largeGithubFileUrls(GAME_APK_URL);
        Throwable last = null;
        HttpURLConnection connection = null;
        for (int sourceIndex = 0; sourceIndex < sources.length; sourceIndex++) {
            if (part.exists() && !part.delete()) {
                throw new IllegalStateException("无法清理旧下载缓存");
            }
            try {
                updateButton(activity, button, "正在下载游戏（线路 " + (sourceIndex + 1)
                        + "/" + sources.length + "）… 0%");
                connection = (HttpURLConnection) new URL(sources[sourceIndex]).openConnection();
                connection.setConnectTimeout(sourceIndex == 0 ? 15_000 : 20_000);
                connection.setReadTimeout(30_000);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "RYLUX/2.3");
                connection.setRequestProperty("Accept", "application/octet-stream,*/*");
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("下载服务器返回 HTTP " + code);
                }

                long total = connection.getContentLengthLong();
                long read = 0L;
                int lastPercent = -1;
                try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream(), 64 * 1024);
                     FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                        read += n;
                        long denominator = total > 0 ? total : GAME_APK_SIZE;
                        int percent = denominator > 0
                                ? (int) Math.min(99L, (read * 100L) / denominator)
                                : 0;
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            updateButton(activity, button, "正在下载游戏… " + percent + "%");
                        }
                    }
                    out.getFD().sync();
                }

                if (read != GAME_APK_SIZE) {
                    throw new IllegalStateException("下载文件大小不正确：" + read + " 字节");
                }
                if (target.exists() && !target.delete()) {
                    throw new IllegalStateException("无法替换旧 APK");
                }
                if (!part.renameTo(target)) {
                    copyFile(part, target);
                    if (!part.delete()) part.deleteOnExit();
                }
                updateButton(activity, button, "正在校验 APK（MD5）…");
                return;
            } catch (Throwable t) {
                last = t;
                if (part.exists()) part.delete();
                if (sourceIndex + 1 < sources.length) {
                    updateButton(activity, button, "当前线路不可用，正在切换备用线路…");
                }
            } finally {
                if (connection != null) connection.disconnect();
                connection = null;
            }
        }
        if (part.exists() && !target.exists()) part.delete();
        throw new IllegalStateException("所有 APK 下载线路均不可用"
                + (last == null ? "" : "：" + safeMessage(last)));
    }

    private static void copyFile(File source, File target) throws Exception {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            out.getFD().sync();
        }
    }

    private static String digest(File file, String algorithm) throws Exception {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) md.update(buffer, 0, n);
        }
        StringBuilder out = new StringBuilder();
        for (byte value : md.digest()) out.append(String.format(Locale.US, "%02x", value & 0xff));
        return out.toString();
    }

    private static void rememberMd5(Context context, String md5) {
        if (md5 == null || md5.trim().isEmpty()) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MD5, md5)
                .apply();
    }

    private static InstallDecision inspectInstallDecision(Context context, File apk) {
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo archive;
        try {
            archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        } catch (Throwable t) {
            return InstallDecision.invalid("无法读取下载 APK 的包信息");
        }
        if (archive == null) return InstallDecision.invalid("无法解析下载 APK");
        if (!TARGET_PACKAGE.equals(archive.packageName)) {
            return InstallDecision.invalid("下载 APK 包名不正确：" + archive.packageName);
        }

        PackageInfo installed;
        try {
            installed = pm.getPackageInfo(TARGET_PACKAGE, flags);
        } catch (PackageManager.NameNotFoundException e) {
            return InstallDecision.notInstalled();
        } catch (Throwable t) {
            return InstallDecision.unknown();
        }

        Set<String> archiveSigners = signerDigests(archive);
        Set<String> installedSigners = signerDigests(installed);
        if (!archiveSigners.isEmpty() && !installedSigners.isEmpty()) {
            Set<String> common = new HashSet<>(archiveSigners);
            common.retainAll(installedSigners);
            if (!common.isEmpty()) return InstallDecision.sameSignature();
            return InstallDecision.differentSignature();
        }
        return InstallDecision.unknown();
    }

    private static Set<String> signerDigests(PackageInfo info) {
        Set<String> out = new HashSet<>();
        if (info == null) return out;
        try {
            if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
                addSignatures(out, info.signingInfo.getApkContentsSigners());
                Signature[] history = info.signingInfo.getSigningCertificateHistory();
                if (history != null) addSignatures(out, history);
            } else {
                addSignatures(out, info.signatures);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void addSignatures(Set<String> out, Signature[] signatures) throws Exception {
        if (signatures == null) return;
        for (Signature signature : signatures) {
            if (signature == null) continue;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(signature.toByteArray());
            StringBuilder value = new StringBuilder();
            for (byte b : digest) value.append(String.format(Locale.US, "%02x", b & 0xff));
            out.add(value.toString());
        }
    }

    private static void handleInstallDecision(Activity activity, File apk, InstallDecision decision) {
        if (activity.isFinishing()) return;
        if (!decision.valid) {
            toast(activity, "无法安装：" + decision.message);
            return;
        }
        if (decision.kind == InstallDecision.DIFFERENT_SIGNATURE) {
            showUninstallRequiredDialog(activity, apk);
            return;
        }

        if (Build.VERSION.SDK_INT >= 26
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_PENDING_UNKNOWN_SOURCES, true).apply();
            try {
                Intent settings = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())
                );
                activity.startActivity(settings);
                toast(activity, "请允许 RYLUX 安装未知应用，返回后会继续安装");
            } catch (Throwable t) {
                prefs.edit().putBoolean(KEY_PENDING_UNKNOWN_SOURCES, false).apply();
                toast(activity, "无法打开“安装未知应用”设置");
            }
            return;
        }

        launchPackageInstaller(activity, apk);
    }

    private static void showUninstallRequiredDialog(Activity activity, File apk) {
        new AlertDialog.Builder(activity)
                .setTitle("需要先卸载旧版本")
                .setMessage("检测到设备已安装相同包名的游戏，但签名与当前 APK 不同。"
                        + "Android 无法直接覆盖安装。\n\n"
                        + "卸载旧版会删除该游戏的本地应用数据，请确认后继续。")
                .setNegativeButton("取消", null)
                .setPositiveButton("卸载旧版本", (dialog, which) -> {
                    SharedPreferences prefs =
                            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    prefs.edit().putBoolean(KEY_PENDING_UNINSTALL, true).apply();
                    try {
                        Intent uninstall = new Intent(
                                Intent.ACTION_DELETE,
                                Uri.parse("package:" + TARGET_PACKAGE)
                        );
                        activity.startActivity(uninstall);
                    } catch (Throwable t) {
                        prefs.edit().putBoolean(KEY_PENDING_UNINSTALL, false).apply();
                        toast(activity, "无法打开卸载界面");
                    }
                })
                .show();
    }

    private static void launchPackageInstaller(Activity activity, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    activity,
                    activity.getPackageName() + ".fileprovider",
                    apk
            );
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivity(install);
        } catch (Throwable t) {
            toast(activity, "无法打开 APK 安装器：" + safeMessage(t));
        }
    }

    private static void resumePendingInstall(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean pendingUnknown = prefs.getBoolean(KEY_PENDING_UNKNOWN_SOURCES, false);
        boolean pendingUninstall = prefs.getBoolean(KEY_PENDING_UNINSTALL, false);
        if (!pendingUnknown && !pendingUninstall) return;

        if (pendingUnknown) {
            if (Build.VERSION.SDK_INT >= 26
                    && !activity.getPackageManager().canRequestPackageInstalls()) {
                return;
            }
            prefs.edit().putBoolean(KEY_PENDING_UNKNOWN_SOURCES, false).apply();
        }

        if (pendingUninstall) {
            if (isTargetInstalled(activity)) {
                prefs.edit().putBoolean(KEY_PENDING_UNINSTALL, false).apply();
                toast(activity, "旧版本仍在，请卸载后重新点击“安装游戏”");
                return;
            }
            prefs.edit().putBoolean(KEY_PENDING_UNINSTALL, false).apply();
        }

        new Thread(() -> {
            try {
                File apk = gameApkFile(activity);
                Verification verification = verifyApk(activity, apk, true);
                if (!verification.valid) {
                    activity.runOnUiThread(() -> toast(activity, "本地 APK 校验失败，请重新点击“安装游戏”"));
                    return;
                }
                InstallDecision decision = inspectInstallDecision(activity, apk);
                activity.runOnUiThread(() -> handleInstallDecision(activity, apk, decision));
            } catch (Throwable t) {
                activity.runOnUiThread(() ->
                        toast(activity, "继续安装失败：" + safeMessage(t)));
            }
        }, "RYLUX-Resume-APK-Install").start();
    }

    private static boolean isTargetInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    private static void updateButton(Activity activity, Button button, String text) {
        activity.runOnUiThread(() -> {
            if (button != null) button.setText(text);
        });
    }

    private static void finishBusyButton(Button button) {
        synchronized (INSTALL_LOCK) {
            installBusy = false;
        }
        if (button != null) {
            button.setEnabled(true);
            button.setText("安装游戏");
        }
    }

    private static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "未知错误";
        String message = t.getMessage();
        if (message == null || message.trim().isEmpty()) return t.getClass().getSimpleName();
        return message.trim();
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class Verification {
        final boolean valid;
        final String md5;
        final String sha256;

        Verification(boolean valid, String md5, String sha256) {
            this.valid = valid;
            this.md5 = md5;
            this.sha256 = sha256;
        }

        static Verification invalid() {
            return new Verification(false, "", "");
        }
    }

    private static final class InstallDecision {
        static final int NOT_INSTALLED = 0;
        static final int SAME_SIGNATURE = 1;
        static final int DIFFERENT_SIGNATURE = 2;
        static final int UNKNOWN_SIGNATURE = 3;

        final boolean valid;
        final int kind;
        final String message;

        private InstallDecision(boolean valid, int kind, String message) {
            this.valid = valid;
            this.kind = kind;
            this.message = message == null ? "" : message;
        }

        static InstallDecision invalid(String message) {
            return new InstallDecision(false, UNKNOWN_SIGNATURE, message);
        }

        static InstallDecision notInstalled() {
            return new InstallDecision(true, NOT_INSTALLED, "");
        }

        static InstallDecision sameSignature() {
            return new InstallDecision(true, SAME_SIGNATURE, "");
        }

        static InstallDecision differentSignature() {
            return new InstallDecision(true, DIFFERENT_SIGNATURE, "");
        }

        static InstallDecision unknown() {
            return new InstallDecision(true, UNKNOWN_SIGNATURE, "");
        }
    }
}
