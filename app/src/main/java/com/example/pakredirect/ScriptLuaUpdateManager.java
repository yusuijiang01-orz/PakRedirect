package com.example.pakredirect;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fetches versioned Lua overrides; packaged assets remain the offline fallback. */
public final class ScriptLuaUpdateManager {
    private static final String PREFS = "rylux_script_lua_updates";
    private static final String ACTIVE_VERSION = "active_version";
    private static final String PREVIOUS_VERSION = "previous_version";
    private static final String REPOSITORY_PATH = "script-updates";
    private static final String GITHUB_BASE =
            "https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/"
                    + "codex-sign-610-lua-runtime/" + REPOSITORY_PATH;
    private static final String GITEE_BASE =
            "https://gitee.com/perish-with-honor/rlyux/raw/"
                    + "codex-sign-610-lua-runtime/" + REPOSITORY_PATH;
    private static final int MAX_MANIFEST_BYTES = 64 * 1024;
    private static final int MAX_LUA_BYTES = 512 * 1024;
    private static final int MAX_TOTAL_BYTES = 2 * 1024 * 1024;
    private static final Set<String> ALLOWED_FILES = new HashSet<>(Arrays.asList(
            "封神榜.lua", "挂机模式.lua", "通用数据.lua", "网页路线.lua", "中控.lua",
            "登录无障碍.lua", "tomocr.lua", "TURING_VIP.lua", "w.lua"));

    private ScriptLuaUpdateManager() {}

    public static final class UpdateResult {
        public final String version;
        public final boolean changed;
        public final String message;

        private UpdateResult(String version, boolean changed, String message) {
            this.version = version;
            this.changed = changed;
            this.message = message;
        }
    }

    /**
     * Check and stage an immutable release. Any network/manifest failure is soft:
     * the last verified remote release or APK assets remain usable.
     */
    public static UpdateResult checkAndApply(Context context) {
        Context app = context.getApplicationContext();
        File root = new File(app.getFilesDir(), "rylux-script-lua");
        File releases = new File(root, "releases");
        String oldActive = activeVersion(app);
        try {
            if (!releases.isDirectory() && !releases.mkdirs()) {
                throw new IllegalStateException("无法创建 Lua 版本目录");
            }
            JSONObject manifest = fetchManifest();
            String version = validateManifest(manifest);
            File releaseDir = new File(releases, version);
            String manifestText = manifest.toString();
            if (releaseDir.isDirectory()) {
                File savedManifest = new File(releaseDir, "manifest.json");
                if (!savedManifest.isFile()
                        || !manifestText.equals(readUtf8(savedManifest, MAX_MANIFEST_BYTES))) {
                    throw new IllegalStateException("Lua 版本号已存在但内容不同，请递增版本号");
                }
                if (!verifyRelease(releaseDir, manifest)) {
                    throw new IllegalStateException("已缓存的 Lua 版本校验失败：" + version);
                }
                activate(app, version, oldActive);
                releaseDir.setLastModified(System.currentTimeMillis());
                pruneOldReleases(releases, version, oldActive);
                return new UpdateResult(version, !version.equals(oldActive), "Lua 版本 " + version + " 已就绪");
            }

            File stage = new File(releases, ".stage-" + version);
            deleteTree(stage);
            if (!stage.mkdirs()) throw new IllegalStateException("无法创建 Lua 暂存目录");
            try {
                JSONArray files = manifest.getJSONArray("files");
                for (int i = 0; i < files.length(); i++) {
                    JSONObject item = files.getJSONObject(i);
                    String name = item.getString("name");
                    long size = item.getLong("size");
                    String sha256 = item.getString("sha256").toLowerCase(Locale.US);
                    File target = new File(stage, name);
                    downloadFile(version, name, target, size, sha256);
                }
                writeSynced(new File(stage, "manifest.json"), manifestText.getBytes(StandardCharsets.UTF_8));
                if (!verifyRelease(stage, manifest)) throw new IllegalStateException("暂存 Lua 版本最终校验失败");
                if (!stage.renameTo(releaseDir)) throw new IllegalStateException("无法原子启用 Lua 版本目录");
                releaseDir.setLastModified(System.currentTimeMillis());
                activate(app, version, oldActive);
                pruneOldReleases(releases, version, oldActive);
                return new UpdateResult(version, !version.equals(oldActive), "Lua 已更新到版本 " + version);
            } catch (Exception failure) {
                deleteTree(stage);
                throw failure;
            } catch (Error failure) {
                deleteTree(stage);
                throw failure;
            }
        } catch (Throwable unavailable) {
            String usable = verifiedActiveVersion(app);
            if (usable != null) {
                return new UpdateResult(usable, false, "远程 Lua 更新暂不可用，继续使用已校验版本 " + usable);
            }
            return new UpdateResult("内置版本", false, "远程 Lua 更新暂不可用，继续使用 APK 内置脚本");
        }
    }

    /** Return a verified override for a top-level script module, otherwise null. */
    public static File verifiedOverride(Context context, String assetPath) {
        if (assetPath == null || !assetPath.startsWith("脚本/")) return null;
        String name = assetPath.substring("脚本/".length());
        if (!ALLOWED_FILES.contains(name) || name.contains("/") || name.contains("\\")) return null;
        String version = verifiedActiveVersion(context.getApplicationContext());
        if (version == null) return null;
        File releaseDir = new File(new File(context.getFilesDir(), "rylux-script-lua/releases"), version);
        try {
            JSONObject manifest = new JSONObject(readUtf8(new File(releaseDir, "manifest.json"), MAX_MANIFEST_BYTES));
            JSONArray files = manifest.getJSONArray("files");
            for (int i = 0; i < files.length(); i++) {
                JSONObject item = files.getJSONObject(i);
                if (!name.equals(item.optString("name"))) continue;
                File source = new File(releaseDir, name);
                if (source.isFile() && source.length() == item.optLong("size", -1)
                        && item.optString("sha256").equalsIgnoreCase(sha256(source))) return source;
                return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    public static String activeVersion(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(ACTIVE_VERSION, "");
    }

    private static String verifiedActiveVersion(Context context) {
        String version = activeVersion(context);
        if (!validVersion(version)) return null;
        File dir = new File(new File(context.getFilesDir(), "rylux-script-lua/releases"), version);
        try {
            JSONObject manifest = new JSONObject(readUtf8(new File(dir, "manifest.json"), MAX_MANIFEST_BYTES));
            return version.equals(validateManifest(manifest)) && verifyRelease(dir, manifest) ? version : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static JSONObject fetchManifest() throws Exception {
        Throwable last = null;
        for (String base : new String[]{GITEE_BASE, GITHUB_BASE}) {
            HttpURLConnection connection = null;
            try {
                connection = open(base + "/manifest.json?rylux_rev=" + System.currentTimeMillis());
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("HTTP " + connection.getResponseCode());
                }
                assertTrustedFinalUrl(connection.getURL());
                return new JSONObject(readUtf8(connection.getInputStream(), MAX_MANIFEST_BYTES));
            } catch (Throwable error) {
                last = error;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw new IllegalStateException("Lua 版本清单无法读取", last);
    }

    private static String validateManifest(JSONObject manifest) throws Exception {
        if (manifest.optInt("schema", 0) != 1 || !"rylux-lua".equals(manifest.optString("module"))) {
            throw new IllegalStateException("Lua 更新清单类型或版本不匹配");
        }
        String version = manifest.optString("version", "").trim();
        if (!validVersion(version)) throw new IllegalStateException("Lua 版本号格式无效");
        JSONArray files = manifest.optJSONArray("files");
        if (files == null || files.length() > ALLOWED_FILES.size()) throw new IllegalStateException("Lua 文件列表无效");
        Set<String> seen = new HashSet<>();
        long total = 0L;
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i);
            if (item == null) throw new IllegalStateException("Lua 清单条目异常");
            String name = item.optString("name", "");
            String hash = item.optString("sha256", "").toLowerCase(Locale.US);
            long size = item.optLong("size", -1L);
            if (!ALLOWED_FILES.contains(name) || !seen.add(name) || size <= 0 || size > MAX_LUA_BYTES
                    || !hash.matches("[0-9a-f]{64}")) {
                throw new IllegalStateException("Lua 清单条目无效：" + name);
            }
            total += size;
            if (total > MAX_TOTAL_BYTES) throw new IllegalStateException("Lua 更新总大小超限");
        }
        return version;
    }

    private static boolean validVersion(String version) {
        return version != null && version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    }

    private static void downloadFile(String version, String name, File target,
                                     long expectedSize, String expectedHash) throws Exception {
        String encodedName = Uri.encode(name, "-_.~");
        String relative = "/releases/" + version + "/" + encodedName;
        Throwable last = null;
        for (String base : new String[]{GITEE_BASE, GITHUB_BASE}) {
            HttpURLConnection connection = null;
            File temp = new File(target.getParentFile(), target.getName() + ".part");
            try {
                connection = open(base + relative);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    throw new IllegalStateException("HTTP " + connection.getResponseCode());
                }
                assertTrustedFinalUrl(connection.getURL());
                long total = 0;
                try (InputStream in = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[16 * 1024];
                    int count;
                    while ((count = in.read(buffer)) >= 0) {
                        if (count == 0) continue;
                        total += count;
                        if (total > expectedSize || total > MAX_LUA_BYTES) {
                            throw new IllegalStateException("Lua 文件长度超过清单");
                        }
                        out.write(buffer, 0, count);
                    }
                    out.getFD().sync();
                }
                if (total != expectedSize || !expectedHash.equals(sha256(temp))) {
                    throw new IllegalStateException("Lua 文件长度或 SHA-256 不匹配：" + name);
                }
                validateUtf8(temp);
                if (!temp.renameTo(target)) throw new IllegalStateException("无法保存 Lua 文件：" + name);
                return;
            } catch (Throwable error) {
                last = error;
                if (temp.exists()) temp.delete();
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw new IllegalStateException("Lua 下载失败：" + name, last);
    }

    private static HttpURLConnection open(String address) throws Exception {
        URL url = new URL(address);
        if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IllegalArgumentException("Lua 更新只允许 HTTPS");
        assertTrustedFinalUrl(url);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(3_500);
        connection.setReadTimeout(5_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache, no-store");
        connection.setRequestProperty("Pragma", "no-cache");
        connection.setRequestProperty("User-Agent", "RYLUX-LuaUpdater/1");
        return connection;
    }

    private static void assertTrustedFinalUrl(URL url) {
        String host = url.getHost() == null ? "" : url.getHost().toLowerCase(Locale.US);
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !(host.equals("gitee.com") || host.equals("raw.githubusercontent.com"))) {
            throw new IllegalArgumentException("Lua 更新地址不在信任域名内");
        }
    }

    private static boolean verifyRelease(File dir, JSONObject manifest) throws Exception {
        JSONArray files = manifest.getJSONArray("files");
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.getJSONObject(i);
            File file = new File(dir, item.getString("name"));
            if (!file.isFile() || file.length() != item.getLong("size")
                    || !item.getString("sha256").equalsIgnoreCase(sha256(file))) return false;
            validateUtf8(file);
        }
        return true;
    }

    private static void activate(Context context, String version, String oldActive) {
        SharedPreferences.Editor editor = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (!version.equals(oldActive) && validVersion(oldActive)) editor.putString(PREVIOUS_VERSION, oldActive);
        editor.putString(ACTIVE_VERSION, version).apply();
    }

    private static void pruneOldReleases(File releases, String active, String previous) {
        File[] dirs = releases.listFiles(File::isDirectory);
        if (dirs == null) return;
        List<File> versions = new ArrayList<>();
        for (File dir : dirs) if (validVersion(dir.getName())) versions.add(dir);
        versions.sort(Comparator.comparingLong(File::lastModified).reversed());
        int kept = 0;
        for (File dir : versions) {
            if (dir.getName().equals(active) || dir.getName().equals(previous) || kept < 3) {
                kept++;
                continue;
            }
            deleteTree(dir);
        }
    }

    private static void validateUtf8(File file) throws Exception {
        byte[] bytes;
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
            bytes = out.toByteArray();
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
        } catch (CharacterCodingException badEncoding) {
            throw new IllegalStateException("Lua 文件不是有效 UTF-8：" + file.getName(), badEncoding);
        }
    }

    private static String readUtf8(InputStream in, int maxBytes) throws Exception {
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = source.read(buffer)) >= 0) {
                if (n == 0) continue;
                if (out.size() + n > maxBytes) throw new IllegalStateException("Lua 清单过大");
                out.write(buffer, 0, n);
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(out.toByteArray())).toString();
        }
    }

    private static String readUtf8(File file, int maxBytes) throws Exception {
        if (!file.isFile() || file.length() > maxBytes) throw new IllegalStateException("Lua 清单文件异常");
        try (InputStream in = new FileInputStream(file)) { return readUtf8(in, maxBytes); }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) if (n > 0) digest.update(buffer, 0, n);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest.digest()) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static void writeSynced(File file, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
            out.getFD().sync();
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
