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
    private static final String RELEASES_API =
            "https://gitee.com/api/v5/repos/perish-with-honor/rlyux/releases?page=1&per_page=100";
    private static final String GITEE_RELEASE_DOWNLOAD =
            "https://gitee.com/perish-with-honor/rlyux/releases/download/";
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
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(RELEASES_API).openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(8000);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "RYLUX/Gitee-updater");
            if (connection.getResponseCode() != 200) return null;
            JSONArray releases = new JSONArray(readAll(connection.getInputStream()));
            ReleaseInfo newest = null;
            for (int releaseIndex = 0; releaseIndex < releases.length(); releaseIndex++) {
                JSONObject json = releases.optJSONObject(releaseIndex);
                if (json == null || json.optBoolean("prerelease", false)) continue;
                String tag = json.optString("tag_name", "").trim();
                String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
                if (version.isEmpty()) continue;
                JSONArray assets = json.optJSONArray("assets");
                if (assets == null) continue;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    String name = asset.optString("name", "");
                    if (!name.startsWith("RYLUX-") || !name.toLowerCase().endsWith(".apk")) continue;
                    String direct = asset.optString("browser_download_url", "").trim();
                    if (direct.isEmpty()) {
                        direct = GITEE_RELEASE_DOWNLOAD + Uri.encode(tag) + "/" + Uri.encode(name);
                    }
                    ReleaseInfo candidate = new ReleaseInfo(version, direct);
                    if (newest == null || compareVersions(candidate.versionName, newest.versionName) > 0) {
                        newest = candidate;
                    }
                    break;
                }
            }
            return newest;
        } catch (Throwable ignored) {
        } finally {
            if (connection != null) connection.disconnect();
        }
        return null;
    }

    private static void showDialog(Activity activity, ReleaseInfo info) {
        if (activity.isFinishing()) return;
        new AlertDialog.Builder(activity)
                .setTitle("发现新版本 " + info.versionName)
                .setMessage("检测到新版 RYLUX，将从 Gitee 下载。正式版本使用固定签名，可直接覆盖更新。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("Gitee下载",
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

    private static int compareVersions(String left, String right) {
        int[] a = parse(left);
        int[] b = parse(right);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
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

        ReleaseInfo(String versionName, String primaryUrl) {
            this.versionName = versionName;
            this.primaryUrl = primaryUrl;
        }
    }
}
