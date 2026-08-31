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
            connection = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(7000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "RYLUX/2.1");
            if (connection.getResponseCode() != 200) return null;
            String text = readAll(connection.getInputStream());
            JSONObject json = new JSONObject(text);
            String tag = json.optString("tag_name", "").trim();
            String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
            if (version.isEmpty()) return null;

            String apkUrl = null;
            JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset == null) continue;
                    String name = asset.optString("name", "");
                    if (name.startsWith("RYLUX-") && name.toLowerCase().endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url", null);
                        break;
                    }
                }
            }
            if (apkUrl == null || apkUrl.trim().isEmpty()) apkUrl = json.optString("html_url", "");
            if (apkUrl == null || apkUrl.trim().isEmpty()) return null;
            return new ReleaseInfo(version, apkUrl);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void showDialog(Activity activity, ReleaseInfo info) {
        if (activity.isFinishing()) return;
        new AlertDialog.Builder(activity)
                .setTitle("发现新版本 " + info.versionName)
                .setMessage("可直接下载新版 RYLUX 覆盖更新。正式版本使用固定签名后，无需卸载旧版。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("立即更新", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(info.url));
                        activity.startActivity(intent);
                    } catch (Throwable ignored) {
                    }
                })
                .show();
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
        final String url;
        ReleaseInfo(String versionName, String url) {
            this.versionName = versionName;
            this.url = url;
        }
    }
}
