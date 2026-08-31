package com.example.pakredirect;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

public final class ContentUpdateManager {
    public static final String MODULE_CODE = "sg_localization";
    private static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/main/pak/manifest.json";
    private static final String PREFS = "rylux_content_update";

    private ContentUpdateManager() {}

    public static File moduleDir(Context context) {
        return new File(context.getFilesDir(), "rylux-content/" + MODULE_CODE);
    }

    public static UpdateResult checkAndApply(Context context) {
        HttpURLConnection connection = null;
        try {
            connection = open(MANIFEST_URL);
            int code = connection.getResponseCode();
            if (code != 200) return UpdateResult.softFailure("资源更新检查失败 HTTP " + code);
            String jsonText = readUtf8(connection.getInputStream(), 512 * 1024);
            JSONObject manifest = new JSONObject(jsonText);
            if (manifest.optInt("schema", 0) != 1) return UpdateResult.softFailure("资源清单版本不支持");
            if (!MODULE_CODE.equals(manifest.optString("module", ""))) {
                return UpdateResult.softFailure("资源清单模块不匹配");
            }

            JSONArray files = manifest.optJSONArray("files");
            if (files == null || files.length() == 0) return UpdateResult.noUpdate();
            File dir = moduleDir(context);
            if (!dir.exists() && !dir.mkdirs()) return UpdateResult.softFailure("无法创建资源目录");

            int changed = 0;
            for (int i = 0; i < files.length(); i++) {
                JSONObject item = files.optJSONObject(i);
                if (item == null) continue;
                String name = safeName(item.optString("name", ""));
                String url = item.optString("url", "").trim();
                String expectedSha = item.optString("sha256", "").trim().toLowerCase(Locale.US);
                long expectedSize = item.optLong("size", -1L);
                if (name == null || url.isEmpty() || expectedSha.length() != 64 || expectedSize < 0) continue;

                File target = new File(dir, name);
                if (target.isFile() && target.length() == expectedSize && expectedSha.equals(sha256(target))) {
                    continue;
                }

                File temp = new File(dir, name + ".download");
                if (temp.exists()) temp.delete();
                download(url, temp, expectedSize);
                String actualSha = sha256(temp);
                if (temp.length() != expectedSize || !expectedSha.equals(actualSha)) {
                    temp.delete();
                    return UpdateResult.softFailure("资源校验失败：" + name);
                }

                File old = new File(dir, name + ".old");
                if (old.exists()) old.delete();
                if (target.exists() && !target.renameTo(old)) {
                    temp.delete();
                    return UpdateResult.softFailure("无法备份旧资源：" + name);
                }
                if (!temp.renameTo(target)) {
                    if (old.exists()) old.renameTo(target);
                    temp.delete();
                    return UpdateResult.softFailure("无法替换资源：" + name);
                }
                if (old.exists()) old.delete();
                changed++;
            }

            String version = manifest.optString("version", "").trim();
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString("version", version).apply();
            return changed > 0 ? UpdateResult.updated(changed, version) : UpdateResult.noUpdate(version);
        } catch (Throwable t) {
            return UpdateResult.softFailure("资源更新检查暂不可用");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public static String installedVersion(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString("version", "内置版本");
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "application/json,application/octet-stream,*/*");
        c.setRequestProperty("Cache-Control", "no-cache");
        c.setRequestProperty("User-Agent", "RYLUX/2.1");
        return c;
    }

    private static void download(String url, File target, long expectedSize) throws Exception {
        HttpURLConnection c = null;
        try {
            c = open(url);
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    if (n == 0) continue;
                    out.write(buffer, 0, n);
                    total += n;
                    if (expectedSize >= 0 && total > expectedSize) {
                        throw new IllegalStateException("文件长度超过清单");
                    }
                }
                out.getFD().sync();
            }
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (n > 0) digest.update(buffer, 0, n);
            }
        }
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest.digest()) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static String readUtf8(InputStream in, int maxBytes) throws Exception {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = in.read(buffer)) >= 0) {
            if (n == 0) continue;
            if (out.size() + n > maxBytes) throw new IllegalStateException("响应过大");
            out.write(buffer, 0, n);
        }
        return out.toString("UTF-8");
    }

    private static String safeName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) return null;
        String lower = name.toLowerCase(Locale.US);
        if (!(lower.endsWith(".pak") || "linkspak.txt".equals(lower))) return null;
        return name;
    }

    public static final class UpdateResult {
        public final boolean updated;
        public final boolean hardFailure;
        public final int changedFiles;
        public final String version;
        public final String message;

        private UpdateResult(boolean updated, boolean hardFailure, int changedFiles, String version, String message) {
            this.updated = updated;
            this.hardFailure = hardFailure;
            this.changedFiles = changedFiles;
            this.version = version;
            this.message = message;
        }

        static UpdateResult updated(int count, String version) {
            return new UpdateResult(true, false, count, version, "资源已更新");
        }
        static UpdateResult noUpdate() { return noUpdate(""); }
        static UpdateResult noUpdate(String version) {
            return new UpdateResult(false, false, 0, version, "资源已是最新");
        }
        static UpdateResult softFailure(String message) {
            return new UpdateResult(false, false, 0, "", message);
        }
    }
}
