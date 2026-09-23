package com.example.pakredirect;

import android.content.Context;
import android.os.StatFs;

import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** On-demand cache for official counterparts of localized PAKs. */
public final class OfficialPakCacheManager {
    private static final String CACHE_DIR = "rylux-official-cache";
    private static final String OFFICIAL_MANIFEST_URL = "https://cdn.tamgioipt.vn/linkspak.txt";
    private static final String OFFICIAL_MANIFEST_FILE = "official-linkspak.txt";
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final long RESERVED_BYTES = 128L * 1024L * 1024L;
    private static final String[] LOCALIZED_NAMES = {"settings.pak", "ui.pak", "updatefs.pak"};

    public interface ProgressListener { void onProgress(String message); }

    private OfficialPakCacheManager() {}

    public static List<Entry> localizedEntries(String manifest) {
        List<Entry> result = new ArrayList<>();
        if (manifest == null) return result;
        for (String line : manifest.split("\\r?\\n")) {
            String[] fields = line.split(",", -1);
            if (fields.length < 6) continue;
            String name = fields[2].trim().toLowerCase(Locale.US);
            if (!isLocalizedName(name)) continue;
            try {
                long size = Long.parseLong(fields[3].trim());
                long revision = Long.parseLong(fields[5].trim());
                String url = fields[0].trim();
                validateUrl(url);
                if (size <= 0 || revision <= 0) continue;
                result.add(new Entry(name, url, size, revision));
            } catch (Throwable ignored) {
            }
        }
        return result;
    }

    /** Gets the current official index, keeping the last successful copy for offline startup. */
    public static String loadOfficialManifest(Context context) {
        File dir = new File(context.getFilesDir(), CACHE_DIR);
        File cached = new File(dir, OFFICIAL_MANIFEST_FILE);
        HttpURLConnection connection = null;
        try {
            URL url = new URL(OFFICIAL_MANIFEST_URL);
            if (!"https".equalsIgnoreCase(url.getProtocol())
                    || !"cdn.tamgioipt.vn".equalsIgnoreCase(url.getHost())) return readCachedManifest(cached);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(15000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "RYLUX/2.4");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return readCachedManifest(cached);
            long length = connection.getContentLengthLong();
            if (length > MAX_MANIFEST_BYTES) return readCachedManifest(cached);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (bytes.size() + count > MAX_MANIFEST_BYTES) return readCachedManifest(cached);
                    bytes.write(buffer, 0, count);
                }
            }
            String text = bytes.toString("UTF-8");
            if (localizedEntries(text).isEmpty()) return readCachedManifest(cached);
            if (!dir.isDirectory() && !dir.mkdirs()) return text;
            File stage = new File(dir, OFFICIAL_MANIFEST_FILE + ".part");
            try (FileOutputStream output = new FileOutputStream(stage)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            if (cached.exists() && !cached.delete()) {
                stage.delete();
                return text;
            }
            if (!stage.renameTo(cached)) stage.delete();
            return text;
        } catch (Throwable ignored) {
            return readCachedManifest(cached);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readCachedManifest(File file) {
        if (!file.isFile() || file.length() <= 0 || file.length() > MAX_MANIFEST_BYTES) return null;
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            String text = output.toString("UTF-8");
            return localizedEntries(text).isEmpty() ? null : text;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static InputStream open(
            Context context,
            Entry entry,
            ProgressListener listener
    ) throws Exception {
        File target = cacheFile(context, entry);
        synchronized (lockFor(target)) {
            if (!isValid(target, entry.size)) {
                download(context, entry, target, listener);
            }
        }
        return new BufferedInputStream(new FileInputStream(target), 128 * 1024);
    }

    public static boolean hasCache(Context context, Entry entry) {
        return isValid(cacheFile(context, entry), entry.size);
    }

    /** Streams the first full request to the game while atomically caching that same transfer. */
    public static void streamAndCache(
            Context context,
            Entry entry,
            OutputStream client,
            ProgressListener listener
    ) throws Exception {
        File target = cacheFile(context, entry);
        synchronized (lockFor(target)) {
            if (isValid(target, entry.size)) {
                try (InputStream cached = new BufferedInputStream(new FileInputStream(target), 128 * 1024)) {
                    writePakResponse(client, entry.size);
                    copy(cached, client, entry.size, entry.name);
                }
                return;
            }

            File dir = target.getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("无法创建官方资源缓存目录");
            StatFs stat = new StatFs(context.getFilesDir().getAbsolutePath());
            if (stat.getAvailableBytes() < entry.size + RESERVED_BYTES) {
                throw new IllegalStateException("设备可用空间不足，官方 " + entry.name
                        + " 需要约 " + formatBytes(entry.size) + "，并保留 128 MB 空间");
            }
            File part = new File(dir, target.getName() + ".part");
            if (part.exists() && !part.delete()) throw new IllegalStateException("无法清理未完成的官方缓存");
            HttpURLConnection connection = openOfficial(entry);
            long received = 0L;
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = new BufferedInputStream(connection.getInputStream(), 256 * 1024);
                     BufferedOutputStream disk = new BufferedOutputStream(new FileOutputStream(part), 256 * 1024)) {
                    writePakResponse(client, entry.size);
                    byte[] buffer = new byte[256 * 1024];
                    int count;
                    long lastNotice = 0L;
                    while ((count = input.read(buffer)) != -1) {
                        received += count;
                        if (received > entry.size) throw new IllegalStateException("官方资源超过清单大小：" + entry.name);
                        disk.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        client.write(buffer, 0, count);
                        if (received - lastNotice >= 32L * 1024L * 1024L) {
                            client.flush();
                            if (listener != null) listener.onProgress("正在缓存官方 " + entry.name + " · "
                                    + formatBytes(received) + "/" + formatBytes(entry.size));
                            lastNotice = received;
                        }
                    }
                    client.flush();
                }
                if (received != entry.size || part.length() != entry.size) {
                    throw new IllegalStateException("官方资源下载不完整：" + entry.name + "（"
                            + formatBytes(received) + "/" + formatBytes(entry.size) + "）");
                }
                writeMarker(new File(dir, target.getName() + ".sha256"), toHex(digest.digest()));
                if (target.exists() && !target.delete()) throw new IllegalStateException("无法替换旧官方缓存");
                if (!part.renameTo(target)) throw new IllegalStateException("无法提交官方资源缓存");
                if (listener != null) listener.onProgress("官方 " + entry.name + " 已缓存（" + formatBytes(entry.size) + "）");
            } catch (Throwable error) {
                if (part.exists()) part.delete();
                if (error instanceof Exception) throw (Exception) error;
                throw new IllegalStateException("官方资源缓存失败：" + entry.name, error);
            } finally {
                connection.disconnect();
            }
        }
    }

    private static void download(Context context, Entry entry, File target, ProgressListener listener) throws Exception {
        File dir = target.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建官方资源缓存目录");
        }
        StatFs stat = new StatFs(context.getFilesDir().getAbsolutePath());
        long available = stat.getAvailableBytes();
        if (available < entry.size + RESERVED_BYTES) {
            throw new IllegalStateException("设备可用空间不足，官方 " + entry.name
                    + " 需要约 " + formatBytes(entry.size) + "，并保留 128 MB 空间");
        }

        File part = new File(dir, target.getName() + ".part");
        if (part.exists() && !part.delete()) throw new IllegalStateException("无法清理未完成的官方缓存");
        HttpURLConnection connection = null;
        long received = 0L;
        long lastNotice = 0L;
        try {
            connection = openOfficial(entry);
            long contentLength = connection.getContentLengthLong();
            if (contentLength >= 0 && contentLength != entry.size) {
                throw new IllegalStateException("官方资源大小与清单不符：" + entry.name);
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new BufferedInputStream(connection.getInputStream(), 256 * 1024);
                 BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(part), 256 * 1024)) {
                byte[] buffer = new byte[256 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    received += count;
                    if (received > entry.size) throw new IllegalStateException("官方资源超过清单大小：" + entry.name);
                    output.write(buffer, 0, count);
                    digest.update(buffer, 0, count);
                    if (listener != null && (received - lastNotice >= 32L * 1024L * 1024L || received == entry.size)) {
                        listener.onProgress("正在缓存官方 " + entry.name + " · "
                                + formatBytes(received) + "/" + formatBytes(entry.size));
                        lastNotice = received;
                    }
                }
            }
            if (received != entry.size || !part.isFile() || part.length() != entry.size) {
                throw new IllegalStateException("官方资源下载不完整：" + entry.name + "（"
                        + formatBytes(received) + "/" + formatBytes(entry.size) + "）");
            }
            // The official manifest provides size and revision, but no cryptographic
            // digest. Record a local digest so later reads can cheaply detect tampering.
            writeMarker(new File(dir, target.getName() + ".sha256"), toHex(digest.digest()));
            if (target.exists() && !target.delete()) throw new IllegalStateException("无法替换旧官方缓存");
            if (!part.renameTo(target)) throw new IllegalStateException("无法提交官方资源缓存");
            if (listener != null) listener.onProgress("官方 " + entry.name + " 已缓存（" + formatBytes(entry.size) + "）");
        } catch (Throwable error) {
            if (part.exists()) part.delete();
            File marker = new File(dir, target.getName() + ".sha256");
            if (!target.exists() && marker.exists()) marker.delete();
            if (error instanceof Exception) throw (Exception) error;
            throw new IllegalStateException("官方资源缓存失败：" + entry.name, error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isValid(File file, long expectedSize) {
        if (!file.isFile() || file.length() != expectedSize) return false;
        File marker = new File(file.getParentFile(), file.getName() + ".sha256");
        if (!marker.isFile()) return false;
        try {
            String expected = new String(readSmall(marker), "UTF-8").trim();
            return expected.matches("(?i)[0-9a-f]{64}");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static File cacheFile(Context context, Entry entry) {
        String identity = sha256((entry.url + "|" + entry.size + "|" + entry.revision).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new File(new File(context.getFilesDir(), CACHE_DIR), entry.name + "-" + identity.substring(0, 16) + ".pak");
    }

    private static Object lockFor(File file) { return file.getAbsolutePath().intern(); }

    private static boolean isLocalizedName(String name) {
        for (String localized : LOCALIZED_NAMES) if (localized.equals(name)) return true;
        return false;
    }

    private static void validateUrl(String value) throws Exception {
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !"cdn-tgpt.tepaylink.vn".equalsIgnoreCase(url.getHost())
                || url.getUserInfo() != null) {
            throw new IllegalStateException("拒绝非官方 HTTPS PAK 地址");
        }
    }

    private static String sha256(byte[] value) {
        try { return toHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.toString();
    }

    private static byte[] readSmall(File file) throws Exception {
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[128];
            int count = input.read(bytes);
            if (count < 0) return new byte[0];
            byte[] result = new byte[count];
            System.arraycopy(bytes, 0, result, 0, count);
            return result;
        }
    }

    private static void writeMarker(File file, String digest) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(digest.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            output.getFD().sync();
        }
    }

    private static HttpURLConnection openOfficial(Entry entry) throws Exception {
        URL current = new URL(entry.url);
        for (int redirects = 0; redirects <= 5; redirects++) {
            validateUrl(current.toString());
            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "RYLUX/2.4");
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || redirects == 5) throw new IllegalStateException("官方资源地址重定向异常");
                current = new URL(current, location);
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                throw new IllegalStateException("官方资源下载失败 HTTP " + code + "：" + entry.name);
            }
            long length = connection.getContentLengthLong();
            if (length >= 0 && length != entry.size) {
                connection.disconnect();
                throw new IllegalStateException("官方资源大小与清单不符：" + entry.name);
            }
            return connection;
        }
        throw new IllegalStateException("官方资源地址重定向次数过多");
    }

    private static void writePakResponse(OutputStream client, long size) throws Exception {
        String response = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n"
                + "Accept-Ranges: bytes\r\nContent-Length: " + size
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        client.write(response.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        client.flush();
    }

    private static void copy(InputStream input, OutputStream output, long expected, String name) throws Exception {
        byte[] buffer = new byte[128 * 1024];
        long copied = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            copied += count;
            output.write(buffer, 0, count);
        }
        if (copied != expected) throw new IllegalStateException("官方缓存读取长度异常：" + name);
        output.flush();
    }

    private static String formatBytes(long bytes) {
        return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    public static final class Entry {
        public final String name;
        public final String url;
        public final long size;
        public final long revision;
        Entry(String name, String url, long size, long revision) {
            this.name = name;
            this.url = url;
            this.size = size;
            this.revision = revision;
        }
    }
}
