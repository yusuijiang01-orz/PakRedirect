package com.example.pakredirect;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads one versioned Lua bundle and switches it atomically after SHA-256 verification. */
public final class LuaScriptUpdateManager {
    private static final String PREFS = "rylux_lua_update";
    private static final String PREF_VERSION = "active_version";
    private static final String MANIFEST = "manifest.json";
    private static final String BUNDLE = "bundle.zip";
    private static final String ENCRYPTED_BUNDLE = "bundle.rpe";
    private static final String PROTECTED_MODULE = ProtectedContentManager.MODULE_CODE;
    private static final long MAX_MANIFEST_BYTES = 256 * 1024L;
    private static final long MAX_BUNDLE_BYTES = 48 * 1024 * 1024L;
    private static final long MAX_UNPACKED_BYTES = 96 * 1024 * 1024L;
    private static final int MAX_ENTRIES = 256;

    private LuaScriptUpdateManager() {}

    public static String currentVersion(Context context) {
        return readVersion(new File(context.getFilesDir(), "rylux-lua/current"));
    }

    public static String previousVersion(Context context) {
        return readVersion(new File(context.getFilesDir(), "rylux-lua/previous"));
    }

    public static boolean canRollback(Context context) {
        File root = new File(context.getFilesDir(), "rylux-lua");
        return hasMainScript(new File(root, "current"));
    }

    /** Switches to the last verified bundle and pins the rejected remote version until it changes. */
    public static boolean rollback(Context context) {
        Context app = context.getApplicationContext();
        File root = new File(app.getFilesDir(), "rylux-lua");
        File current = new File(root, "current");
        File previous = new File(root, "previous");
        if (!hasMainScript(current)) return false;
        String rejected = readVersion(current);
        if (!hasMainScript(previous)) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("skip_version", rejected).putString(PREF_VERSION, "内置脚本").apply();
            deleteTree(current);
            return true;
        }
        String restored = readVersion(previous);
        File rejectedDir = new File(root, "rejected-tmp");
        deleteTree(rejectedDir);
        if (!current.renameTo(rejectedDir)) return false;
        if (!previous.renameTo(current)) {
            rejectedDir.renameTo(current);
            return false;
        }
        if (!rejectedDir.renameTo(previous)) deleteTree(rejectedDir);
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_VERSION, restored)
                .putString("skip_version", rejected)
                .apply();
        return true;
    }

    /** Remote failures never prevent starting the last verified bundle or APK assets. */
    public static String prepare(Context context, Consumer<String> status) {
        Context app = context.getApplicationContext();
        File root = new File(app.getFilesDir(), "rylux-lua");
        File current = new File(root, "current");
        File previous = new File(root, "previous");
        ensureFallback(root, current, previous);
        String cachedVersion = readVersion(current);

        try {
            notify(status, "正在检查 Lua 脚本更新");
            String remoteManifestText = readRemoteText(MANIFEST, MAX_MANIFEST_BYTES);
            JSONObject outerManifest = new JSONObject(remoteManifestText);
            boolean authenticated = PROTECTED_MODULE.equals(outerManifest.optString("module", ""));
            JSONObject manifest = authenticated
                    ? ProtectedContentManager.verifyLuaManifest(app, new AuthStorage(app).loadToken(), remoteManifestText)
                    : outerManifest;
            boolean encrypted = authenticated && manifest.optInt("schema", 0) == 2;
            if ((!encrypted && (authenticated || manifest.optInt("schema", 0) != 1))) {
                throw new IllegalStateException("Lua 清单格式不支持");
            }
            String version = manifest.optString("version", "").trim();
            String expectedSha = manifest.optString("sha256", "").trim().toLowerCase(Locale.US);
            long expectedSize = manifest.optLong("size", -1L);
            String bundleName = manifest.optString("bundle", encrypted ? ENCRYPTED_BUNDLE : BUNDLE).trim();
            if (!version.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalStateException("Lua 版本号无效");
            if (!(encrypted ? ENCRYPTED_BUNDLE : BUNDLE).equals(bundleName)) throw new IllegalStateException("Lua 包文件名无效");
            if (!expectedSha.matches("[0-9a-f]{64}") || expectedSize <= 0 || expectedSize > MAX_BUNDLE_BYTES) {
                throw new IllegalStateException("Lua 包校验信息无效");
            }
            SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (version.equals(prefs.getString("skip_version", ""))) {
                notify(status, "Lua v" + version + " 已回退，暂不重复安装");
                return hasMainScript(current) ? "v" + cachedVersion : "内置脚本";
            }
            if (version.equals(cachedVersion) && hasMainScript(current)) {
                notify(status, "Lua 脚本已是最新 v" + version);
                return "v" + version;
            }

            if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("无法创建 Lua 缓存目录");
            File stage = new File(root, "staging-" + version);
            deleteTree(stage);
            if (!stage.mkdirs()) throw new IllegalStateException("无法创建 Lua 暂存目录");
            File archive = new File(root, "bundle-" + version + (encrypted ? ".rpe.part" : ".zip.part"));
            File decryptedArchive = new File(root, "bundle-" + version + ".decrypted.zip.part");
            try {
                notify(status, "正在下载 Lua v" + version);
                downloadBundle(bundleName, archive, expectedSize, expectedSha);
                File unpackArchive = archive;
                if (encrypted) {
                    notify(status, "正在验证授权并解密 Lua v" + version);
                    ProtectedContentManager.decryptLuaBundle(
                            app,
                            new AuthStorage(app).loadToken(),
                            archive,
                            decryptedArchive,
                            manifest
                    );
                    unpackArchive = decryptedArchive;
                }
                notify(status, "正在安装 Lua v" + version);
                unpack(unpackArchive, stage);
                if (!hasMainScript(stage)) throw new IllegalStateException("Lua 包缺少封神榜主脚本");
                writeUtf8(new File(stage, "VERSION"), version);
                activate(stage, current, previous);
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .putString(PREF_VERSION, version).remove("skip_version").apply();
                cachedVersion = version;
                notify(status, "Lua 脚本已更新 v" + version);
                return "v" + version;
            } finally {
                deleteQuietly(archive);
                deleteQuietly(decryptedArchive);
                deleteTree(stage);
            }
        } catch (Exception failure) {
            File usable = hasMainScript(current) ? current : previous;
            String usableVersion = readVersion(usable);
            if (!hasMainScript(usable)) {
                notify(status, "远端 Lua 暂不可用，使用 APK 内置脚本");
                return "内置脚本";
            }
            notify(status, "Lua 更新未完成，继续使用 v" + (usableVersion.isEmpty() ? "缓存" : usableVersion));
            return usableVersion.isEmpty() ? "缓存脚本" : "v" + usableVersion;
        }
    }

    /** Opens a verified remote file when present, otherwise the original APK asset. */
    public static InputStream open(Context context, String assetPath) throws Exception {
        return open(context, assetPath, currentVersion(context));
    }

    public static InputStream open(Context context, String assetPath, String pinnedVersion) throws Exception {
        String relative = assetPath == null ? "" : assetPath.replace('\\', '/');
        String prefix = "script_modules/";
        if (relative.startsWith(prefix)) relative = relative.substring(prefix.length());
        if (pinnedVersion != null && !pinnedVersion.isEmpty() && isSafeBundlePath(relative)) {
            File root = new File(context.getFilesDir(), "rylux-lua");
            File current = new File(root, "current");
            File previous = new File(root, "previous");
            File versionDir = readVersion(current).equals(pinnedVersion) ? current
                    : readVersion(previous).equals(pinnedVersion) ? previous : null;
            if (versionDir != null) {
                File module = new File(versionDir, relative);
                if (module.getCanonicalPath().startsWith(versionDir.getCanonicalPath() + File.separator)
                        && module.isFile()) return new FileInputStream(module);
            }
        }
        return context.getAssets().open(assetPath);
    }

    private static String readRemoteText(String name, long maximum) throws Exception {
        byte[] bytes = readRemoteBytes(name, maximum);
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] readRemoteBytes(String name, long maximum) throws Exception {
        Exception last = null;
        for (String candidate : CnDownloadRouter.luaRepoFileUrls(name)) {
            HttpURLConnection connection = null;
            try {
                connection = open(candidate);
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) throw new IllegalStateException("Lua 服务器返回 HTTP " + code);
                long declared = connection.getContentLengthLong();
                if (declared > maximum) throw new IllegalStateException("Lua 下载超过大小限制");
                try (InputStream input = new BufferedInputStream(connection.getInputStream());
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[16 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if ((long) output.size() + count > maximum) throw new IllegalStateException("Lua 下载超过大小限制");
                        output.write(buffer, 0, count);
                    }
                    return output.toByteArray();
                }
            } catch (Exception failure) {
                last = failure;
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw last == null ? new IllegalStateException("Lua 文件下载失败") : last;
    }

    private static HttpURLConnection open(String source) throws Exception {
        String separator = source.contains("?") ? "&" : "?";
        HttpURLConnection connection = (HttpURLConnection) new URL(source + separator + "rylux=" + System.currentTimeMillis()).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(12000);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "RYLUX-LuaUpdater/1");
        connection.setRequestProperty("Accept", "application/octet-stream, application/json");
        return connection;
    }

    private static void downloadBundle(String bundleName, File destination, long expectedSize, String expectedSha) throws Exception {
        Exception last = null;
        for (String candidate : CnDownloadRouter.luaRepoFileUrls(bundleName)) {
            HttpURLConnection connection = null;
            try {
                connection = open(candidate);
                int code = connection.getResponseCode();
                if (code != HttpURLConnection.HTTP_OK) throw new IllegalStateException("Lua 包服务器返回 HTTP " + code);
                long declared = connection.getContentLengthLong();
                if (declared >= 0 && declared != expectedSize) throw new IllegalStateException("Lua 包大小与清单不一致");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long written = 0;
                try (InputStream input = new BufferedInputStream(connection.getInputStream());
                     FileOutputStream file = new FileOutputStream(destination);
                     BufferedOutputStream output = new BufferedOutputStream(file)) {
                    byte[] buffer = new byte[32 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        written += count;
                        if (written > expectedSize || written > MAX_BUNDLE_BYTES) throw new IllegalStateException("Lua 包超过清单大小");
                        digest.update(buffer, 0, count);
                        output.write(buffer, 0, count);
                    }
                }
                if (written != expectedSize) throw new IllegalStateException("Lua 包下载不完整");
                if (!expectedSha.equals(hex(digest.digest()))) throw new IllegalStateException("Lua 包 SHA-256 校验失败");
                return;
            } catch (Exception failure) {
                last = failure;
                deleteQuietly(destination);
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw last == null ? new IllegalStateException("Lua 包下载失败") : last;
    }

    private static void unpack(File archive, File destination) throws Exception {
        int entries = 0;
        long expanded = 0;
        try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[16 * 1024];
            while ((entry = input.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IllegalStateException("Lua 包文件数量超过限制");
                String name = entry.getName().replace('\\', '/');
                if (entry.isDirectory()) continue;
                if (!isSafeBundlePath(name)) throw new IllegalStateException("Lua 包含有非法路径");
                File target = new File(destination, name);
                if (!target.getCanonicalPath().startsWith(destination.getCanonicalPath() + File.separator)) {
                    throw new IllegalStateException("Lua 包路径越界");
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IllegalStateException("无法创建 Lua 文件目录");
                try (FileOutputStream file = new FileOutputStream(target); BufferedOutputStream output = new BufferedOutputStream(file)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        expanded += count;
                        if (expanded > MAX_UNPACKED_BYTES) throw new IllegalStateException("Lua 包解压后超过大小限制");
                        output.write(buffer, 0, count);
                    }
                }
                input.closeEntry();
            }
        }
    }

    private static boolean isSafeBundlePath(String value) {
        if (value == null || value.isEmpty() || value.startsWith("/") || value.contains("..") || value.contains(":")) return false;
        String lower = value.toLowerCase(Locale.US);
        boolean supported = lower.endsWith(".lua") || lower.endsWith(".png") || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".bmp");
        return supported && (value.startsWith("脚本/") || value.startsWith("插件/"));
    }

    private static void activate(File stage, File current, File previous) throws Exception {
        deleteTree(previous);
        boolean hadCurrent = current.exists();
        if (hadCurrent && !current.renameTo(previous)) throw new IllegalStateException("无法保留 Lua 上一版本");
        if (!stage.renameTo(current)) {
            if (hadCurrent) previous.renameTo(current);
            throw new IllegalStateException("无法切换 Lua 版本");
        }
        // Only keep the immediately previous verified directory for rollback.
        if (!hasMainScript(current)) {
            deleteTree(current);
            if (hadCurrent) previous.renameTo(current);
            throw new IllegalStateException("切换后的 Lua 主脚本无效");
        }
    }

    private static void ensureFallback(File root, File current, File previous) {
        if (hasMainScript(current) || !hasMainScript(previous)) return;
        if (!root.exists()) root.mkdirs();
        deleteTree(current);
        previous.renameTo(current);
    }

    private static boolean hasMainScript(File directory) {
        return directory != null && new File(directory, "脚本/封神榜.lua").isFile();
    }

    private static String readVersion(File directory) {
        if (!hasMainScript(directory)) return "";
        try (InputStream input = new FileInputStream(new File(directory, "VERSION"));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8").trim();
        } catch (Exception ignored) { return ""; }
    }

    private static void writeUtf8(File file, String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.US, "%02x", item & 0xff));
        return value.toString();
    }

    private static void notify(Consumer<String> listener, String message) {
        if (listener == null) return;
        try { listener.accept(message); }
        catch (RuntimeException ignored) { }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static void deleteQuietly(File file) { if (file != null) file.delete(); }
}
