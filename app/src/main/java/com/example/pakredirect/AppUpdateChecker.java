package com.example.pakredirect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class AppUpdateChecker {
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/yusuijiang01-orz/PakRedirect/releases/latest";
    private static final String GITEE_RELEASE_DOWNLOAD =
            "https://gitee.com/perish-with-honor/rlyux/releases/download/";
    private static final String GITHUB_RELEASES =
            "https://github.com/yusuijiang01-orz/PakRedirect/releases";

    private AppUpdateChecker() {}

    public static void check(Activity activity) {
        final String installed = installedVersion(activity);
        new Thread(() -> {
            ReleaseInfo info = fetchLatest();
            if (info == null || !isNewer(info.versionName, installed)) return;
            activity.runOnUiThread(() -> showDialog(activity, info));
        }, "RYLUX-App-Update").start();
    }

    private static String installedVersion(Activity activity) {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return info.versionName == null ? "0" : info.versionName;
        } catch (Throwable ignored) {
            return "0";
        }
    }

    private static ReleaseInfo fetchLatest() {
        return fetchGithubLatest();
    }

    private static ReleaseInfo fetchGithubLatest() {
        for (String apiUrl : CnDownloadRouter.githubApiUrls(LATEST_RELEASE_API)) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(apiUrl).openConnection();
                connection.setConnectTimeout(4500);
                connection.setReadTimeout(7000);
                connection.setUseCaches(false);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "RYLUX/2.3");
                if (connection.getResponseCode() != 200) continue;
                String text = readAll(connection.getInputStream());
                JSONObject json = new JSONObject(text);
                String tag = json.optString("tag_name", "").trim();
                String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
                if (version.isEmpty()) continue;

                String apkUrl = null;
                String apkName = null;
                JSONArray assets = json.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.optJSONObject(i);
                        if (asset == null) continue;
                        String name = asset.optString("name", "");
                        if (name.startsWith("RYLUX-") && name.toLowerCase().endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", null);
                            apkName = name;
                            break;
                        }
                    }
                }
                if (apkUrl == null || apkUrl.trim().isEmpty()) apkUrl = json.optString("html_url", "");
                if (apkUrl == null || apkUrl.trim().isEmpty()) continue;
                if (apkName != null && !tag.isEmpty()) {
                    String giteeUrl = GITEE_RELEASE_DOWNLOAD + Uri.encode(tag) + "/" + Uri.encode(apkName);
                    return new ReleaseInfo(version, giteeUrl, GITHUB_RELEASES, true);
                }
                return new ReleaseInfo(version, CnDownloadRouter.accelerated(apkUrl), apkUrl, false);
            } catch (Throwable ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return null;
    }

    private static void showDialog(Activity activity, ReleaseInfo info) {
        if (activity.isFinishing()) return;
        String message = info.fromGitee
                ? "检测到新版 RYLUX。优先从 Gitee 下载；GitHub 发布页可作为备用。正式版本使用固定签名，可直接覆盖更新。"
                : "检测到新版 RYLUX。国内网络建议优先使用加速下载；若加速节点异常，可改用 GitHub 直连。正式版本使用固定签名，可直接覆盖更新。";
        new AlertDialog.Builder(activity)
                .setTitle("发现新版本 " + info.versionName)
                .setMessage(message)
                .setNegativeButton("稍后", null)
                .setNeutralButton(info.fromGitee ? "GitHub发布页" : "GitHub直连",
                        (dialog, which) -> open(activity, info.alternateUrl))
                .setPositiveButton(info.fromGitee ? "Gitee下载" : "加速下载",
                        (dialog, which) -> open(activity, info.primaryUrl))
                .show();
    }

    private static void open(Activity activity, String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            activity.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    static boolean isNewer(String remote, String local) {
        int[] a = parse(remote);
        int[] b = parse(local);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) return av > bv;
        }
        return false;
    }

    private static int[] parse(String value) {
        String core = value == null ? "" : value.trim().split("-", 2)[0];
        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", "")); }
            catch (Throwable ignored) { out[i] = 0; }
        }
        return out;
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private static final class ReleaseInfo {
        final String versionName;
        final String primaryUrl;
        final String alternateUrl;
        final boolean fromGitee;

        ReleaseInfo(String versionName, String primaryUrl, String alternateUrl, boolean fromGitee) {
            this.versionName = versionName;
            this.primaryUrl = primaryUrl;
            this.alternateUrl = alternateUrl;
            this.fromGitee = fromGitee;
        }
    }
}
