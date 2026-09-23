package com.example.pakredirect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Cloud-aware installer. The current Git LFS SHA-256/size is authoritative,
 * while public GitHub metadata and LFS bytes can use an accelerated route on
 * networks where GitHub is slow. Every accepted APK is still verified locally.
 */
public final class GameApkCloudInstaller {
    private static final String TARGET_PACKAGE = "com.tepaylink.tamgioiphantranhmobile";
    private static final String GAME_APK_NAME = "TamGioiPhanTranhMobile-587.apk";
    private static final String META_URL =
            "https://api.github.com/repos/yusuijiang01-orz/PakRedirect/contents/apk/"
                    + GAME_APK_NAME + "?ref=main";
    // 游戏 APK 本体迁移到 GitHub Release（tag=game-apk）分发，避开 Git LFS 每月 1GB 带宽配额；
    // 校验元数据（sha256/size）仍来自仓库内 LFS 指针，上传到 Release 的文件必须与指针一致。
    private static final String MEDIA_URL =
            "https://github.com/yusuijiang01-orz/PakRedirect/releases/download/game-apk/"
                    + GAME_APK_NAME;

    private static final String PREFS = "rylux_game_installer_cloud_v2";
    private static final String KEY_PENDING_UNKNOWN = "pending_unknown_sources";
    private static final String KEY_PENDING_UNINSTALL = "pending_after_uninstall";
    private static final String INSTALL_BUTTON_TAG = "rylux_install_game_button";

    private static final Object INSTALL_LOCK = new Object();
    private static boolean installBusy;

    private static final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> LISTENERS =
            new WeakHashMap<>();
    private static final Set<View> HOOKED =
            Collections.newSetFromMap(new WeakHashMap<>());

    private GameApkCloudInstaller() {}

    public static void onActivityResumed(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        attach(activity);
        resumePending(activity);
    }

    public static void attach(Activity activity) {
        if (!(activity instanceof MainActivity)) return;
        synchronized (LISTENERS) {
            if (LISTENERS.containsKey(activity)) {
                activity.getWindow().getDecorView().post(() -> hook(activity));
                return;
            }
            View decor = activity.getWindow().getDecorView();
            ViewTreeObserver.OnGlobalLayoutListener listener =
                    () -> decor.post(() -> hook(activity));
            LISTENERS.put(activity, listener);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(listener);
            decor.post(() -> hook(activity));
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

    private static void hook(Activity activity) {
        View found = findTagged(activity.getWindow().getDecorView(), INSTALL_BUTTON_TAG);
        if (!(found instanceof Button)) return;
        Button button = (Button) found;
        if (!HOOKED.add(button)) return;
        button.setOnClickListener(v -> startInstall(activity, button));
    }

    private static View findTagged(View view, String tag) {
        Object value = view.getTag();
        if (tag.equals(value)) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findTagged(group.getChildAt(i), tag);
            if (found != null) return found;
        }
        return null;
    }

    static void startInstall(Activity activity, Button button) {
        synchronized (INSTALL_LOCK) {
            if (installBusy) {
                toast(activity, "游戏安装任务正在进行中");
                return;
            }
            installBusy = true;
        }

        button.setEnabled(false);
        button.setText("正在读取云端 APK 信息…");

        new Thread(() -> {
            try {
                RemoteApkInfo remote = fetchRemoteApkInfo();
                File apk = gameApkFile(activity);
                boolean hadLocal = apk.isFile();
                Verification local = verify(apk, remote);

                if (!local.valid) {
                    if (hadLocal) {
                        updateButton(activity, button, "检测到云端 APK 已更新…");
                    } else {
                        updateButton(activity, button, "本地无 APK，准备下载…");
                    }
                    if (apk.exists() && !apk.delete()) {
                        throw new IllegalStateException("无法删除旧的本地 APK");
                    }
                    downloadApk(activity, button, apk, remote);
                    local = verify(apk, remote);
                    if (!local.valid) {
                        throw new IllegalStateException("下载完成，但 APK 与云端校验信息不一致");
                    }
                } else {
                    updateButton(activity, button, "本地 APK 与云端一致");
                }

                rememberVerification(activity, remote, local);
                InstallDecision decision = inspectInstallDecision(activity, apk);
                activity.runOnUiThread(() -> {
                    finishBusyButton(button);
                    handleInstallDecision(activity, apk, decision);
                });
            } catch (Throwable t) {
                activity.runOnUiThread(() -> {
                    finishBusyButton(button);
                    toast(activity, "安装游戏失败：" + safeMessage(t));
                });
            }
        }, "RYLUX-Cloud-APK").start();
    }

    private static RemoteApkInfo fetchRemoteApkInfo() throws Exception {
        Throwable last = null;
        String[] urls = CnDownloadRouter.githubApiUrls(META_URL);
        for (int i = 0; i < urls.length; i++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(urls[i]).openConnection();
                connection.setConnectTimeout(4500);
                connection.setReadTimeout(8000);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "RYLUX/2.3 cloud-integrity");
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
                connection.setRequestProperty("Cache-Control", "no-cache");
                connection.setRequestProperty("Pragma", "no-cache");

                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("HTTP " + code);
                }
                return parseRemoteApkInfo(readUtf8(connection));
            } catch (Throwable t) {
                last = t;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw new IllegalStateException(
                "读取云端 APK 元数据失败，请检查网络后重试"
                        + (last == null ? "" : "：" + safeMessage(last))
        );
    }

    private static RemoteApkInfo parseRemoteApkInfo(String body) throws Exception {
        JSONObject json = new JSONObject(body);
        String encoded = json.optString("content", "");
        if (encoded == null || encoded.trim().isEmpty()) {
            throw new IllegalStateException("云端 APK 元数据缺少 LFS 指针内容");
        }

        byte[] decoded = Base64.decode(encoded.replace("\n", ""), Base64.DEFAULT);
        String pointer = new String(decoded, StandardCharsets.UTF_8);
        String sha256 = null;
        long size = -1L;
        for (String line : pointer.split("\\r?\\n")) {
            String value = line == null ? "" : line.trim();
            if (value.startsWith("oid sha256:")) {
                sha256 = value.substring("oid sha256:".length()).trim().toLowerCase(Locale.US);
            } else if (value.startsWith("size ")) {
                try {
                    size = Long.parseLong(value.substring("size ".length()).trim());
                } catch (NumberFormatException ignored) {
                    size = -1L;
                }
            }
        }

        if (sha256 == null || !sha256.matches("[0-9a-f]{64}") || size <= 0L) {
            throw new IllegalStateException("无法解析云端 Git LFS 校验信息");
        }
        return new RemoteApkInfo(sha256, size);
    }

    private static String readUtf8(HttpURLConnection connection) throws Exception {
        try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > 1024 * 1024) throw new IllegalStateException("云端元数据响应过大");
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        }
    }

    private static File gameApkFile(Context context) {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = new File(context.getFilesDir(), "downloads");
        if (!base.exists() && !base.mkdirs() && !base.exists()) {
            throw new IllegalStateException("无法创建 APK 下载目录");
        }
        return new File(base, GAME_APK_NAME);
    }

    private static Verification verify(File apk, RemoteApkInfo remote) throws Exception {
        if (apk == null || remote == null || !apk.isFile()) return Verification.invalid();
        if (apk.length() != remote.size) return Verification.invalid();

        String sha256 = digest(apk, "SHA-256");
        if (!remote.sha256.equalsIgnoreCase(sha256)) return Verification.invalid();

        String md5 = digest(apk, "MD5");
        return new Verification(true, md5, sha256);
    }

    private static void downloadApk(
            Activity activity,
            Button button,
            File target,
            RemoteApkInfo remote
    ) throws Exception {
        File part = new File(target.getParentFile(), target.getName() + ".part");
        String direct = MEDIA_URL + "?sha256=" + remote.sha256;
        String[] sources = CnDownloadRouter.largeGithubFileUrls(direct);
        Throwable last = null;

        for (int sourceIndex = 0; sourceIndex < sources.length; sourceIndex++) {
            if (part.exists() && !part.delete()) {
                throw new IllegalStateException("无法清理旧下载缓存");
            }
            HttpURLConnection connection = null;
            try {
                final int line = sourceIndex + 1;
                updateButton(activity, button, "正在下载游戏（线路 " + line + "/" + sources.length + "）… 0%");
                connection = (HttpURLConnection) new URL(sources[sourceIndex]).openConnection();
                connection.setConnectTimeout(sourceIndex == 0 ? 6000 : 10000);
                connection.setReadTimeout(45_000);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "RYLUX/2.3 cloud-integrity");
                connection.setRequestProperty("Accept", "application/octet-stream,*/*");
                connection.setRequestProperty("Cache-Control", "no-cache");
                connection.setRequestProperty("Pragma", "no-cache");

                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("下载服务器返回 HTTP " + code);
                }

                long read = 0L;
                int lastPercent = -1;
                try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream(), 128 * 1024);
                     FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buffer = new byte[128 * 1024];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                        read += n;
                        if (read > remote.size) throw new IllegalStateException("下载内容长度超过云端元数据");
                        int percent = remote.size > 0
                                ? (int) Math.min(99L, (read * 100L) / remote.size)
                                : 0;
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            updateButton(activity, button,
                                    "正在下载游戏（线路 " + line + "/" + sources.length + "）… " + percent + "%");
                        }
                    }
                    out.getFD().sync();
                }

                if (read != remote.size) {
                    throw new IllegalStateException("下载文件大小不正确：" + read + " 字节");
                }
                if (!remote.sha256.equalsIgnoreCase(digest(part, "SHA-256"))) {
                    throw new IllegalStateException("下载线路返回的 APK SHA-256 不匹配");
                }

                if (target.exists() && !target.delete()) {
                    throw new IllegalStateException("无法替换旧 APK");
                }
                if (!part.renameTo(target)) {
                    copyFile(part, target);
                    if (!part.delete()) part.deleteOnExit();
                }
                updateButton(activity, button, "正在校验 APK 内容…");
                return;
            } catch (Throwable t) {
                last = t;
                if (part.exists()) part.delete();
                if (sourceIndex + 1 < sources.length) {
                    updateButton(activity, button, "当前线路不可用，正在切换备用线路…");
                }
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

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
        for (byte b : md.digest()) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static void rememberVerification(Context context, RemoteApkInfo remote, Verification local) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString("remote_sha256", remote.sha256)
                .putLong("remote_size", remote.size)
                .putString("local_sha256", local.sha256)
                .putString("local_md5", local.md5)
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
            showUninstallRequiredDialog(activity);
            return;
        }

        if (Build.VERSION.SDK_INT >= 26
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_PENDING_UNKNOWN, true).apply();
            try {
                Intent settings = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())
                );
                activity.startActivity(settings);
                toast(activity, "请允许 RYLUX 安装未知应用，返回后会继续安装");
            } catch (Throwable t) {
                prefs.edit().putBoolean(KEY_PENDING_UNKNOWN, false).apply();
                toast(activity, "无法打开“安装未知应用”设置");
            }
            return;
        }

        launchPackageInstaller(activity, apk);
    }

    private static void showUninstallRequiredDialog(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle("需要先卸载旧版本")
                .setMessage("检测到设备已安装相同包名的游戏，但签名与当前云端 APK 不同。"
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

    private static void resumePending(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean pendingUnknown = prefs.getBoolean(KEY_PENDING_UNKNOWN, false);
        boolean pendingUninstall = prefs.getBoolean(KEY_PENDING_UNINSTALL, false);
        if (!pendingUnknown && !pendingUninstall) return;

        if (pendingUnknown) {
            if (Build.VERSION.SDK_INT < 26
                    || activity.getPackageManager().canRequestPackageInstalls()) {
                prefs.edit().putBoolean(KEY_PENDING_UNKNOWN, false).apply();
                continueVerifiedInstall(activity);
            }
            return;
        }

        if (pendingUninstall && !isTargetInstalled(activity)) {
            prefs.edit().putBoolean(KEY_PENDING_UNINSTALL, false).apply();
            continueVerifiedInstall(activity);
        }
    }

    private static void continueVerifiedInstall(Activity activity) {
        new Thread(() -> {
            try {
                RemoteApkInfo remote = fetchRemoteApkInfo();
                File apk = gameApkFile(activity);
                Verification verification = verify(apk, remote);
                if (!verification.valid) {
                    activity.runOnUiThread(() ->
                            toast(activity, "云端 APK 已变化，请重新点击“安装游戏”下载最新版本"));
                    return;
                }
                InstallDecision decision = inspectInstallDecision(activity, apk);
                activity.runOnUiThread(() -> handleInstallDecision(activity, apk, decision));
            } catch (Throwable t) {
                activity.runOnUiThread(() ->
                        toast(activity, "继续安装失败：" + safeMessage(t)));
            }
        }, "RYLUX-Resume-APK").start();
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
            if (!activity.isFinishing()) button.setText(text);
        });
    }

    private static void finishBusyButton(Button button) {
        synchronized (INSTALL_LOCK) {
            installBusy = false;
        }
        button.setEnabled(true);
        button.setText("安装游戏");
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

    private static final class RemoteApkInfo {
        final String sha256;
        final long size;

        RemoteApkInfo(String sha256, long size) {
            this.sha256 = sha256;
            this.size = size;
        }
    }

    private static final class Verification {
        final boolean valid;
        final String md5;
        final String sha256;

        Verification(boolean valid, String md5, String sha256) {
            this.valid = valid;
            this.md5 = md5 == null ? "" : md5;
            this.sha256 = sha256 == null ? "" : sha256;
        }

        static Verification invalid() {
            return new Verification(false, "", "");
        }
    }

    private static final class InstallDecision {
        static final int NOT_INSTALLED = 1;
        static final int SAME_SIGNATURE = 2;
        static final int DIFFERENT_SIGNATURE = 3;
        static final int UNKNOWN = 4;

        final boolean valid;
        final int kind;
        final String message;

        InstallDecision(boolean valid, int kind, String message) {
            this.valid = valid;
            this.kind = kind;
            this.message = message == null ? "" : message;
        }

        static InstallDecision invalid(String message) {
            return new InstallDecision(false, UNKNOWN, message);
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
            return new InstallDecision(true, UNKNOWN, "");
        }
    }
}
