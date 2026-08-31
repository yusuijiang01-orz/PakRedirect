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
import java.util.ArrayList;
import java.util.List;
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

    /**
     * Checks GitHub for the current content manifest and applies changed files as
     * one verified transaction. Failure to reach the manifest is a soft failure
     * so an already verified local copy can still be used. Once a newer manifest
     * has been received, any download, checksum or write failure is thrown so the
     * launcher does not silently start the game with a partial/stale PAK set.
     */
    public static UpdateResult checkAndApply(Context context) throws Exception {
        String jsonText;
        HttpURLConnection connection = null;
        try {
            connection = open(cacheBust(MANIFEST_URL, String.valueOf(System.currentTimeMillis())));
            int code = connection.getResponseCode();
            if (code != 200) {
                return UpdateResult.softFailure("资源更新检查失败 HTTP " + code);
            }
            jsonText = readUtf8(connection.getInputStream(), 512 * 1024);
        } catch (Throwable t) {
            return UpdateResult.softFailure("资源更新检查暂不可用");
        } finally {
            if (connection != null) connection.disconnect();
        }

        final JSONObject manifest;
        try {
            manifest = new JSONObject(jsonText);
        } catch (Throwable t) {
            throw new IllegalStateException("资源清单解析失败", t);
        }
        if (manifest.optInt("schema", 0) != 1) {
            throw new IllegalStateException("资源清单版本不支持");
        }
        if (!MODULE_CODE.equals(manifest.optString("module", ""))) {
            throw new IllegalStateException("资源清单模块不匹配");
        }

        JSONArray files = manifest.optJSONArray("files");
        if (files == null || files.length() == 0) {
            throw new IllegalStateException("资源清单为空");
        }

        String version = manifest.optString("version", "").trim();
        if (version.isEmpty()) throw new IllegalStateException("资源清单缺少版本号");

        File dir = moduleDir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建资源目录");
        }

        List<ContentItem> all = new ArrayList<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i);
            if (item == null) throw new IllegalStateException("资源清单项目异常");

            String name = safeName(item.optString("name", ""));
            String url = item.optString("url", "").trim();
            String expectedSha = item.optString("sha256", "").trim().toLowerCase(Locale.US);
            long expectedSize = item.optLong("size", -1L);
            long revision = item.optLong("revision", 0L);
            if (name == null || url.isEmpty() || expectedSha.length() != 64 || expectedSize < 0) {
                throw new IllegalStateException("资源清单项目无效");
            }
            if (name.toLowerCase(Locale.US).endsWith(".pak") && revision <= 0) {
                throw new IllegalStateException("PAK 缺少内容版本号：" + name);
            }
            all.add(new ContentItem(dir, name, url, expectedSha, expectedSize, revision));
        }

        for (ContentItem item : all) recoverStaleFiles(item);

        List<ContentItem> changed = new ArrayList<>();
        try {
            // Stage and verify every changed resource before touching a live file.
            for (ContentItem item : all) {
                if (matches(item.target, item.expectedSize, item.expectedSha)) continue;
                deleteIfExists(item.stage);
                String token = version + "-" + item.revision;
                download(cacheBust(item.url, token), item.stage, item.expectedSize);
                if (!matches(item.stage, item.expectedSize, item.expectedSha)) {
                    deleteIfExists(item.stage);
                    throw new IllegalStateException("资源校验失败：" + item.name);
                }
                changed.add(item);
            }

            commitAtomically(changed);

            // Final verification is against the manifest after all renames.
            for (ContentItem item : all) {
                if (!matches(item.target, item.expectedSize, item.expectedSha)) {
                    throw new IllegalStateException("资源写入后校验失败：" + item.name);
                }
            }

            for (ContentItem item : changed) deleteIfExists(item.backup);
        } catch (Throwable t) {
            rollback(changed);
            for (ContentItem item : changed) deleteIfExists(item.stage);
            if (t instanceof Exception) throw (Exception) t;
            throw new IllegalStateException("资源更新失败", t);
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString("version", version).apply();
        return changed.isEmpty()
                ? UpdateResult.noUpdate(version)
                : UpdateResult.updated(changed.size(), version);
    }

    public static String installedVersion(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getString("version", "内置版本");
    }

    private static void recoverStaleFiles(ContentItem item) throws Exception {
        deleteIfExists(item.stage);
        if (!item.backup.exists()) return;
        if (!item.target.exists()) {
            if (!item.backup.renameTo(item.target)) {
                throw new IllegalStateException("无法恢复资源备份：" + item.name);
            }
        } else {
            deleteIfExists(item.backup);
        }
    }

    private static void commitAtomically(List<ContentItem> changed) throws Exception {
        List<ContentItem> committed = new ArrayList<>();
        try {
            for (ContentItem item : changed) {
                deleteIfExists(item.backup);
                item.hadTarget = item.target.exists();
                if (item.hadTarget && !item.target.renameTo(item.backup)) {
                    throw new IllegalStateException("无法备份旧资源：" + item.name);
                }
                if (!item.stage.renameTo(item.target)) {
                    if (item.hadTarget && item.backup.exists()) item.backup.renameTo(item.target);
                    throw new IllegalStateException("无法替换资源：" + item.name);
                }
                item.committed = true;
                committed.add(item);
            }
        } catch (Throwable t) {
            rollback(committed);
            if (t instanceof Exception) throw (Exception) t;
            throw new IllegalStateException("资源提交失败", t);
        }
    }

    private static void rollback(List<ContentItem> items) {
        for (int i = items.size() - 1; i >= 0; i--) {
            ContentItem item = items.get(i);
            if (!item.committed) continue;
            try {
                if (item.target.exists()) item.target.delete();
                if (item.hadTarget && item.backup.exists()) item.backup.renameTo(item.target);
                if (!item.hadTarget && item.backup.exists()) item.backup.delete();
                item.committed = false;
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean matches(File file, long expectedSize, String expectedSha) throws Exception {
        return file.isFile()
                && file.length() == expectedSize
                && expectedSha.equals(sha256(file));
    }

    private static void deleteIfExists(File file) throws Exception {
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("无法清理临时资源：" + file.getName());
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(30000);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "application/json,application/octet-stream,*/*");
        c.setRequestProperty("Cache-Control", "no-cache, no-store");
        c.setRequestProperty("Pragma", "no-cache");
        c.setRequestProperty("User-Agent", "RYLUX/2.1.2");
        return c;
    }

    private static String cacheBust(String url, String token) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "rylux_rev=" + token.replaceAll("[^A-Za-z0-9._-]", "");
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

    private static final class ContentItem {
        final String name;
        final String url;
        final String expectedSha;
        final long expectedSize;
        final long revision;
        final File target;
        final File stage;
        final File backup;
        boolean hadTarget;
        boolean committed;

        ContentItem(File dir, String name, String url, String expectedSha, long expectedSize, long revision) {
            this.name = name;
            this.url = url;
            this.expectedSha = expectedSha;
            this.expectedSize = expectedSize;
            this.revision = revision;
            this.target = new File(dir, name);
            this.stage = new File(dir, name + ".stage");
            this.backup = new File(dir, name + ".rollback");
        }
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
