package com.example.pakredirect;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/** Fetches an immutable, versioned Lua bundle and keeps the last known-good copy. */
final class RemoteLuaScriptStore {
    private static final String TAG = "RYLUX-RemoteLua";
    private static final Object REFRESH_LOCK = new Object();
    private static boolean refreshInProgress;
    private static long lastRefreshAttemptAt;
    private static CountDownLatch refreshFinished = new CountDownLatch(0);
    private static final String RAW_ROOT =
            "https://raw.githubusercontent.com/yusuijiang01-orz/PakRedirect/rylux-lua-live/lua-live/";
    private static final String MANIFEST_URL = RAW_ROOT + "manifest.json";
    private static final String GH_PROXY = "https://gh-proxy.com/";
    private static final String ASSET_ROOT = "script_modules/";
    private static final String VERSION_PATTERN = "[A-Za-z0-9._-]{1,64}";
    private static final int MAX_MANIFEST_BYTES = 128 * 1024;
    private static final int MAX_FILE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 16 * 1024 * 1024;
    private static final int MAX_FILES = 64;
    private static final int CONNECT_TIMEOUT_MS = 1800;
    private static final int READ_TIMEOUT_MS = 5000;

    private RemoteLuaScriptStore() {}

    static CountDownLatch refreshInBackground(Context sourceContext) {
        Context context = sourceContext.getApplicationContext();
        synchronized (REFRESH_LOCK) {
            long now = System.currentTimeMillis();
            if (refreshInProgress || now - lastRefreshAttemptAt < 30_000L) {
                return refreshFinished;
            }
        }
        if (!hasUsableNetwork(context)) return new CountDownLatch(0);

        final CountDownLatch finished;
        synchronized (REFRESH_LOCK) {
            long now = System.currentTimeMillis();
            if (refreshInProgress || now - lastRefreshAttemptAt < 30_000L) {
                return refreshFinished;
            }
            refreshInProgress = true;
            lastRefreshAttemptAt = now;
            finished = new CountDownLatch(1);
            refreshFinished = finished;
        }
        try {
            Thread worker = new Thread(() -> {
                try {
                    refresh(context, message -> Log.i(TAG, message));
                } finally {
                    finishBackgroundRefresh(finished);
                }
            }, "RYLUX-RemoteLua");
            worker.setDaemon(true);
            worker.start();
        } catch (RuntimeException error) {
            finishBackgroundRefresh(finished);
            Log.w(TAG, "Unable to start background Lua update", error);
        }
        return finished;
    }

    private static boolean hasUsableNetwork(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(
                    Context.CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            if (Build.VERSION.SDK_INT >= 23) {
                Network network = manager.getActiveNetwork();
                NetworkCapabilities capabilities = network == null
                        ? null : manager.getNetworkCapabilities(network);
                return capabilities != null
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }
            NetworkInfo networkInfo = manager.getActiveNetworkInfo();
            return networkInfo != null && networkInfo.isConnected();
        } catch (SecurityException error) {
            Log.w(TAG, "Unable to inspect network state", error);
            return false;
        }
    }

    private static void finishBackgroundRefresh(CountDownLatch finished) {
        finished.countDown();
        synchronized (REFRESH_LOCK) { refreshInProgress = false; }
    }

    static String refresh(Context sourceContext, Consumer<String> status) {
        Context context = sourceContext.getApplicationContext();
        File root = new File(context.getFilesDir(), "remote_lua");
        File versions = new File(root, "versions");
        String activeVersion = readActiveVersion(root);
        File stage = null;
        try {
            report(status, "检查 Lua");
            if (!versions.isDirectory() && !versions.mkdirs()) {
                throw new IOException("无法创建 Lua 缓存目录");
            }

            ManifestResponse response = fetchManifest();
            JSONObject manifest = new JSONObject(new String(response.bytes, StandardCharsets.UTF_8));
            if (manifest.optInt("schema", 0) != 1) throw new IOException("Lua 清单版本不支持");
            String version = manifest.optString("version", "").trim();
            if (!version.matches(VERSION_PATTERN)) throw new IOException("Lua 版本号无效");

            List<Entry> entries = parseEntries(manifest.optJSONArray("files"));
            File versionDir = new File(versions, version);
            if (version.equals(activeVersion) && isComplete(versionDir, version, entries)) {
                report(status, "Lua v" + version);
                return version;
            }
            if (isComplete(versionDir, version, entries)) {
                activate(root, version);
                report(status, "Lua v" + version);
                cleanupVersions(versions, version);
                return version;
            }

            stage = new File(versions, ".stage-" + UUID.randomUUID());
            if (!stage.mkdir()) throw new IOException("无法创建 Lua 暂存目录");
            int total = 0;
            for (Entry entry : entries) {
                total += entry.size;
                if (total > MAX_TOTAL_BYTES) throw new IOException("Lua 更新包过大");
                String rawUrl = RAW_ROOT + "versions/" + Uri.encode(version, "-_.~") + "/"
                        + Uri.encode(entry.path, "/");
                byte[] bytes;
                try {
                    bytes = download(response.routePrefix + rawUrl, entry.size);
                } catch (IOException proxyFailure) {
                    if (response.routePrefix.isEmpty()) throw proxyFailure;
                    bytes = download(rawUrl, entry.size);
                }
                if (bytes.length != entry.size || !gitBlobSha1(bytes).equals(entry.gitBlobSha1)) {
                    throw new IOException("Lua 文件校验失败：" + entry.path);
                }
                File target = safeFile(stage, entry.path);
                File parent = target.getParentFile();
                if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
                    throw new IOException("无法创建 Lua 文件目录");
                }
                try (FileOutputStream output = new FileOutputStream(target)) {
                    output.write(bytes);
                    output.getFD().sync();
                }
            }
            writeFile(new File(stage, ".complete"), version.getBytes(StandardCharsets.UTF_8));
            if (versionDir.exists()) deleteTree(versionDir);
            if (!stage.renameTo(versionDir)) throw new IOException("无法启用 Lua 更新包");
            stage = null;
            activate(root, version);
            cleanupVersions(versions, version);
            report(status, "Lua 更新为 v" + version);
            return version;
        } catch (Exception error) {
            Log.w(TAG, "Remote Lua bundle not activated", error);
            report(status, activeVersion.isEmpty()
                    ? "Lua 暂不可用，使用内置版"
                    : "Lua 更新失败，继续 v" + activeVersion);
            return activeVersion;
        } finally {
            if (stage != null && stage.exists()) deleteTree(stage);
        }
    }

    static String currentVersion(Context sourceContext) {
        Context context = sourceContext.getApplicationContext();
        File root = new File(context.getFilesDir(), "remote_lua");
        String version = readActiveVersion(root);
        if (version.isEmpty()) return "";
        File marker = new File(new File(new File(root, "versions"), version), ".complete");
        return marker.isFile() ? version : "";
    }

    static InputStream open(Context sourceContext, String modulePath) throws IOException {
        return open(sourceContext, modulePath, currentVersion(sourceContext));
    }

    static InputStream open(Context sourceContext, String modulePath, String pinnedVersion)
            throws IOException {
        Context context = sourceContext.getApplicationContext();
        if (isSafeModulePath(modulePath) && pinnedVersion != null
                && pinnedVersion.matches(VERSION_PATTERN)) {
            File file = safeFile(new File(new File(context.getFilesDir(), "remote_lua"),
                    "versions/" + pinnedVersion), modulePath);
            if (file.isFile()) return new FileInputStream(file);
        }
        return context.getAssets().open(ASSET_ROOT + modulePath);
    }

    private static ManifestResponse fetchManifest() throws IOException {
        IOException last = null;
        String[] routes = {GH_PROXY, ""};
        for (String route : routes) {
            try {
                return new ManifestResponse(download(route + MANIFEST_URL, MAX_MANIFEST_BYTES), route);
            } catch (IOException error) {
                last = error;
            }
        }
        throw last == null ? new IOException("无法获取 Lua 版本清单") : last;
    }

    private static List<Entry> parseEntries(JSONArray files) throws IOException {
        if (files == null || files.length() == 0 || files.length() > MAX_FILES) {
            throw new IOException("Lua 清单文件数量无效");
        }
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int total = 0;
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i);
            if (item == null) throw new IOException("Lua 清单条目无效");
            String path = item.optString("path", "");
            String sha1 = item.optString("git_blob_sha1", "").toLowerCase(Locale.ROOT);
            int size = item.optInt("size", -1);
            if (!isSafeModulePath(path) || !seen.add(path)
                    || !sha1.matches("[0-9a-f]{40}") || size <= 0 || size > MAX_FILE_BYTES) {
                throw new IOException("Lua 清单条目校验失败");
            }
            total += size;
            if (total > MAX_TOTAL_BYTES) throw new IOException("Lua 更新包过大");
            entries.add(new Entry(path, sha1, size));
        }
        return entries;
    }

    private static boolean isSafeModulePath(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0
                || path.contains("..") || path.contains(":") || !path.endsWith(".lua")) return false;
        return path.startsWith("脚本/") || path.startsWith("插件/");
    }

    private static File safeFile(File base, String relativePath) throws IOException {
        if (!isSafeModulePath(relativePath)) throw new IOException("Lua 文件路径无效");
        File canonicalBase = base.getCanonicalFile();
        File target = new File(canonicalBase, relativePath.replace('/', File.separatorChar)).getCanonicalFile();
        String prefix = canonicalBase.getPath() + File.separator;
        if (!target.getPath().startsWith(prefix)) throw new IOException("Lua 文件路径越界");
        return target;
    }

    private static byte[] download(String address, int maxBytes) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "RYLUX/RemoteLua");
            connection.setRequestProperty("Accept", "application/json, application/octet-stream");
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("Lua 服务器 HTTP " + code);
            int declaredLength = connection.getContentLength();
            if (declaredLength > maxBytes) throw new IOException("Lua 响应超出大小限制");
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 8192))) {
                byte[] buffer = new byte[8192];
                int count;
                int total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > maxBytes) throw new IOException("Lua 响应超出大小限制");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String gitBlobSha1(byte[] content) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
            byte[] hash = digest.digest(content);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (Exception error) {
            throw new IOException("无法校验 Lua 文件", error);
        }
    }

    private static boolean isComplete(File directory, String version, List<Entry> entries) {
        File marker = new File(directory, ".complete");
        if (!marker.isFile()) return false;
        try (FileInputStream input = new FileInputStream(marker)) {
            byte[] bytes = new byte[128];
            int count = input.read(bytes);
            if (count <= 0 || !version.equals(new String(bytes, 0, count, StandardCharsets.UTF_8))) return false;
            for (Entry entry : entries) {
                File file = safeFile(directory, entry.path);
                if (!file.isFile() || file.length() != entry.size) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String readActiveVersion(File root) {
        File marker = new File(root, "active-version");
        try (FileInputStream input = new FileInputStream(marker)) {
            byte[] bytes = new byte[128];
            int count = input.read(bytes);
            if (count <= 0) return "";
            String version = new String(bytes, 0, count, StandardCharsets.UTF_8).trim();
            return version.matches(VERSION_PATTERN) ? version : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void activate(File root, String version) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("无法创建 Lua 缓存目录");
        AtomicFile atomic = new AtomicFile(new File(root, "active-version"));
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            output.write(version.getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(output);
        } catch (IOException error) {
            if (output != null) atomic.failWrite(output);
            throw error;
        }
    }

    private static void writeFile(File target, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(bytes);
            output.getFD().sync();
        }
    }

    private static void cleanupVersions(File versions, String activeVersion) {
        File[] directories = versions.listFiles(File::isDirectory);
        if (directories == null || directories.length <= 3) return;
        Arrays.sort(directories, (left, right) -> Long.compare(right.lastModified(), left.lastModified()));
        int kept = 0;
        for (File directory : directories) {
            String name = directory.getName();
            if (name.startsWith(".stage-")) {
                deleteTree(directory);
                continue;
            }
            if (name.equals(activeVersion) || kept < 3) {
                kept++;
            } else {
                deleteTree(directory);
            }
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        if (!file.delete()) Log.w(TAG, "Unable to remove stale Lua cache entry");
    }

    private static void report(Consumer<String> status, String message) {
        if (status == null) return;
        try { status.accept(message); }
        catch (RuntimeException ignored) { }
    }

    private static final class ManifestResponse {
        final byte[] bytes;
        final String routePrefix;
        ManifestResponse(byte[] bytes, String routePrefix) {
            this.bytes = bytes;
            this.routePrefix = routePrefix;
        }
    }

    private static final class Entry {
        final String path;
        final String gitBlobSha1;
        final int size;
        Entry(String path, String gitBlobSha1, int size) {
            this.path = path;
            this.gitBlobSha1 = gitBlobSha1;
            this.size = size;
        }
    }
}
