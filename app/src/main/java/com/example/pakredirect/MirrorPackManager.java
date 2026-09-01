package com.example.pakredirect;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Verifies and mounts a user-selected .ryluxmirror file through Android's
 * Storage Access Framework. The large PAK payload stays where the user selected
 * it; RYLUX stores only the persisted URI + metadata and serves byte ranges
 * directly from the package over localhost. This avoids an extra multi-GB copy.
 */
public final class MirrorPackManager {
    public static final String MODULE_CODE = "sg_localization";

    private static final byte[] MAGIC = "RYLUXM01".getBytes(StandardCharsets.US_ASCII);
    private static final int PREFIX_BYTES = 12;
    private static final int MAX_HEADER_BYTES = 512 * 1024;
    private static final long MAX_TOTAL_BYTES = 8L * 1024L * 1024L * 1024L;
    private static final String PREFS = "rylux_mirror_pack";
    private static final String KEY_URI = "uri";
    private static final String KEY_META = "meta";

    private static final Set<String> ALLOWED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "resource.pak",
            "spr.pak",
            "script.pak",
            "fs2008.pak",
            "maps.pak",
            "update.pak",
            "blaze.pak"
    )));

    private MirrorPackManager() {}

    public interface ProgressListener {
        void onProgress(String message, int percent);
    }

    public static ImportResult selectPack(Context context, Uri uri, ProgressListener listener) throws Exception {
        if (uri == null) throw new IllegalArgumentException("未选择镜像包");

        ParsedPack pack = parsePack(context, uri);
        verifyPack(context, pack, listener);

        try {
            context.getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException e) {
            throw new IllegalStateException("无法长期访问该文件，请将镜像包放到本机下载目录后重新选择", e);
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String oldUri = prefs.getString(KEY_URI, "");
        prefs.edit()
                .putString(KEY_URI, uri.toString())
                .putString(KEY_META, pack.metaText)
                .apply();

        if (oldUri != null && !oldUri.isEmpty() && !oldUri.equals(uri.toString())) {
            try {
                context.getContentResolver().releasePersistableUriPermission(
                        Uri.parse(oldUri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Throwable ignored) {
            }
        }

        emit(listener, "镜像包已就绪", 100);
        return new ImportResult(true, pack.name, pack.entries.size(), pack.totalBytes);
    }

    public static MirrorStatus status(Context context) {
        try {
            InstalledPack pack = installed(context);
            if (pack == null || pack.entries.isEmpty()) return MirrorStatus.none();
            try (ParcelFileDescriptor ignored = context.getContentResolver().openFileDescriptor(pack.uri, "r")) {
                if (ignored == null) return MirrorStatus.none();
            }
            return new MirrorStatus(true, pack.name, pack.entries.size(), pack.totalBytes);
        } catch (Throwable ignored) {
            return MirrorStatus.none();
        }
    }

    public static Map<String, MirrorEntry> entries(Context context) {
        try {
            InstalledPack pack = installed(context);
            if (pack == null) return Collections.emptyMap();
            return new HashMap<>(pack.entries);
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
    }

    public static InputStream openEntry(Context context, MirrorEntry entry, long relativeStart) throws Exception {
        if (entry == null || entry.uri == null) throw new IllegalArgumentException("镜像条目无效");
        if (relativeStart < 0 || relativeStart >= entry.size) throw new IllegalArgumentException("镜像读取范围无效");

        ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(entry.uri, "r");
        if (pfd == null) throw new IllegalStateException("镜像包已不可访问");
        FileInputStream input = new FileInputStream(pfd.getFileDescriptor());
        try {
            input.getChannel().position(entry.absoluteOffset + relativeStart);
        } catch (Throwable t) {
            try { input.close(); } catch (Throwable ignored) {}
            try { pfd.close(); } catch (Throwable ignored) {}
            throw new IllegalStateException("镜像包不支持随机读取，请将文件保存到本机下载目录", t);
        }
        return new PfdInputStream(input, pfd);
    }

    public static void clear(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String uriText = prefs.getString(KEY_URI, "");
        prefs.edit().clear().apply();
        if (uriText != null && !uriText.isEmpty()) {
            try {
                context.getContentResolver().releasePersistableUriPermission(
                        Uri.parse(uriText),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            } catch (Throwable ignored) {
            }
        }
    }

    private static ParsedPack parsePack(Context context, Uri uri) throws Exception {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) throw new IllegalStateException("无法读取镜像包");
            try (FileInputStream input = new FileInputStream(pfd.getFileDescriptor())) {
                FileChannel channel = input.getChannel();
                long fileSize = channel.size();
                if (fileSize < PREFIX_BYTES) throw new IllegalStateException("镜像包文件过小");

                ByteBuffer prefix = ByteBuffer.allocate(PREFIX_BYTES).order(ByteOrder.BIG_ENDIAN);
                readFully(channel, prefix);
                prefix.flip();
                byte[] magic = new byte[MAGIC.length];
                prefix.get(magic);
                if (!Arrays.equals(MAGIC, magic)) throw new IllegalStateException("不是有效的 RYLUX 镜像包");
                int headerLength = prefix.getInt();
                if (headerLength <= 0 || headerLength > MAX_HEADER_BYTES) {
                    throw new IllegalStateException("镜像包头部长度无效");
                }
                if ((long) PREFIX_BYTES + headerLength >= fileSize) {
                    throw new IllegalStateException("镜像包头部损坏");
                }

                ByteBuffer header = ByteBuffer.allocate(headerLength);
                readFully(channel, header);
                header.flip();
                byte[] bytes = new byte[headerLength];
                header.get(bytes);
                String metaText = new String(bytes, StandardCharsets.UTF_8);
                JSONObject root = new JSONObject(metaText);
                validateRoot(root);

                JSONArray files = root.optJSONArray("files");
                if (files == null || files.length() == 0) throw new IllegalStateException("镜像包没有 PAK 文件");
                long payloadStart = PREFIX_BYTES + (long) headerLength;
                List<MirrorEntry> entries = new ArrayList<>();
                Set<String> names = new HashSet<>();
                long total = 0L;

                for (int i = 0; i < files.length(); i++) {
                    JSONObject item = files.optJSONObject(i);
                    if (item == null) throw new IllegalStateException("镜像包清单项目异常");
                    String name = item.optString("name", "").trim().toLowerCase(Locale.US);
                    long size = item.optLong("size", -1L);
                    long revision = item.optLong("revision", 0L);
                    long offset = item.optLong("offset", -1L);
                    String sha = item.optString("sha256", "").trim().toLowerCase(Locale.US);
                    validateItem(name, size, revision, offset, sha);
                    if (!names.add(name)) throw new IllegalStateException("镜像包重复文件：" + name);
                    long absolute = payloadStart + offset;
                    if (absolute < payloadStart || absolute + size < absolute || absolute + size > fileSize) {
                        throw new IllegalStateException("镜像包文件范围无效：" + name);
                    }
                    entries.add(new MirrorEntry(name, uri, absolute, size, revision, sha));
                    total += size;
                    if (total > MAX_TOTAL_BYTES) throw new IllegalStateException("镜像包体积超过安全上限");
                }

                List<MirrorEntry> ordered = new ArrayList<>(entries);
                ordered.sort(Comparator.comparingLong(a -> a.absoluteOffset));
                long end = payloadStart;
                for (MirrorEntry entry : ordered) {
                    if (entry.absoluteOffset < end) throw new IllegalStateException("镜像包文件区间重叠");
                    end = entry.absoluteOffset + entry.size;
                }

                // Test that the provider actually exposes a seekable descriptor.
                channel.position(payloadStart);
                return new ParsedPack(packName(root), metaText, entries, total);
            }
        }
    }

    private static void verifyPack(Context context, ParsedPack pack, ProgressListener listener) throws Exception {
        long verified = 0L;
        ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(pack.entries.get(0).uri, "r")) {
            if (pfd == null) throw new IllegalStateException("无法校验镜像包");
            try (FileInputStream input = new FileInputStream(pfd.getFileDescriptor())) {
                FileChannel channel = input.getChannel();
                for (MirrorEntry entry : pack.entries) {
                    emit(nullSafe(listener), "正在校验 " + entry.name + "…", percent(verified, pack.totalBytes));
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    channel.position(entry.absoluteOffset);
                    long remaining = entry.size;
                    while (remaining > 0) {
                        buffer.clear();
                        buffer.limit((int) Math.min(buffer.capacity(), remaining));
                        int n = channel.read(buffer);
                        if (n < 0) throw new IllegalStateException("镜像包读取长度不足：" + entry.name);
                        if (n == 0) continue;
                        digest.update(buffer.array(), 0, n);
                        remaining -= n;
                        verified += n;
                        emit(listener, "正在校验 " + entry.name + " · " + humanBytes(entry.size - remaining), percent(verified, pack.totalBytes));
                    }
                    String actual = hex(digest.digest());
                    if (!entry.sha256.equals(actual)) throw new IllegalStateException("镜像包校验失败：" + entry.name);
                }
            }
        }
    }

    private static InstalledPack installed(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String uriText = prefs.getString(KEY_URI, "");
        String metaText = prefs.getString(KEY_META, "");
        if (uriText == null || uriText.isEmpty() || metaText == null || metaText.isEmpty()) return null;

        Uri uri = Uri.parse(uriText);
        JSONObject root = new JSONObject(metaText);
        validateRoot(root);
        JSONArray files = root.optJSONArray("files");
        if (files == null || files.length() == 0) return null;

        // Re-open just the fixed header to recover payloadStart. The stored JSON
        // contains relative offsets, so moving the file does not change metadata.
        int headerLength;
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (pfd == null) return null;
            try (FileInputStream input = new FileInputStream(pfd.getFileDescriptor())) {
                FileChannel channel = input.getChannel();
                ByteBuffer prefix = ByteBuffer.allocate(PREFIX_BYTES).order(ByteOrder.BIG_ENDIAN);
                readFully(channel, prefix);
                prefix.flip();
                byte[] magic = new byte[MAGIC.length];
                prefix.get(magic);
                if (!Arrays.equals(MAGIC, magic)) return null;
                headerLength = prefix.getInt();
                if (headerLength <= 0 || headerLength > MAX_HEADER_BYTES) return null;
            }
        }

        long payloadStart = PREFIX_BYTES + (long) headerLength;
        Map<String, MirrorEntry> result = new HashMap<>();
        long total = 0L;
        for (int i = 0; i < files.length(); i++) {
            JSONObject item = files.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name", "").trim().toLowerCase(Locale.US);
            long size = item.optLong("size", -1L);
            long revision = item.optLong("revision", 0L);
            long offset = item.optLong("offset", -1L);
            String sha = item.optString("sha256", "").trim().toLowerCase(Locale.US);
            if (!ALLOWED.contains(name) || size <= 0 || revision <= 0 || offset < 0 || sha.length() != 64) continue;
            result.put(name, new MirrorEntry(name, uri, payloadStart + offset, size, revision, sha));
            total += size;
        }
        if (result.isEmpty()) return null;
        return new InstalledPack(uri, packName(root), result, total);
    }

    private static void validateRoot(JSONObject root) {
        if (root.optInt("schema", 0) != 1) throw new IllegalStateException("镜像包格式版本不支持");
        if (!MODULE_CODE.equals(root.optString("module", ""))) throw new IllegalStateException("镜像包模块不匹配");
    }

    private static void validateItem(String name, long size, long revision, long offset, String sha) {
        if (!ALLOWED.contains(name)) throw new IllegalStateException("镜像包包含不允许的 PAK：" + name);
        if (size <= 0 || revision <= 0 || offset < 0 || sha.length() != 64) {
            throw new IllegalStateException("镜像包清单无效：" + name);
        }
    }

    private static String packName(JSONObject root) {
        String value = root.optString("name", "RYLUX 官方资源镜像").trim();
        return value.isEmpty() ? "RYLUX 官方资源镜像" : value;
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer) throws Exception {
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer);
            if (n < 0) throw new IllegalStateException("镜像包读取失败");
            if (n == 0) Thread.yield();
        }
    }

    private static int percent(long done, long total) {
        if (total <= 0) return -1;
        return Math.max(0, Math.min(99, (int) ((done * 100L) / total)));
    }

    private static ProgressListener nullSafe(ProgressListener listener) {
        return listener;
    }

    private static void emit(ProgressListener listener, String message, int percent) {
        if (listener != null) listener.onProgress(message, percent);
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.2f GB", mb / 1024.0);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static final class PfdInputStream extends FilterInputStream {
        private final ParcelFileDescriptor pfd;
        PfdInputStream(FileInputStream input, ParcelFileDescriptor pfd) {
            super(input);
            this.pfd = pfd;
        }
        @Override public void close() throws java.io.IOException {
            java.io.IOException first = null;
            try { super.close(); } catch (java.io.IOException e) { first = e; }
            try { pfd.close(); } catch (java.io.IOException e) { if (first == null) first = e; }
            if (first != null) throw first;
        }
    }

    private static final class ParsedPack {
        final String name;
        final String metaText;
        final List<MirrorEntry> entries;
        final long totalBytes;
        ParsedPack(String name, String metaText, List<MirrorEntry> entries, long totalBytes) {
            this.name = name;
            this.metaText = metaText;
            this.entries = entries;
            this.totalBytes = totalBytes;
        }
    }

    private static final class InstalledPack {
        final Uri uri;
        final String name;
        final Map<String, MirrorEntry> entries;
        final long totalBytes;
        InstalledPack(Uri uri, String name, Map<String, MirrorEntry> entries, long totalBytes) {
            this.uri = uri;
            this.name = name;
            this.entries = entries;
            this.totalBytes = totalBytes;
        }
    }

    public static final class MirrorEntry {
        public final String name;
        public final Uri uri;
        public final long absoluteOffset;
        public final long size;
        public final long revision;
        public final String sha256;
        MirrorEntry(String name, Uri uri, long absoluteOffset, long size, long revision, String sha256) {
            this.name = name;
            this.uri = uri;
            this.absoluteOffset = absoluteOffset;
            this.size = size;
            this.revision = revision;
            this.sha256 = sha256;
        }
    }

    public static final class MirrorStatus {
        public final boolean ready;
        public final String name;
        public final int fileCount;
        public final long totalBytes;
        MirrorStatus(boolean ready, String name, int fileCount, long totalBytes) {
            this.ready = ready;
            this.name = name;
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
        }
        static MirrorStatus none() { return new MirrorStatus(false, "", 0, 0L); }
    }

    public static final class ImportResult {
        public final boolean success;
        public final String name;
        public final int fileCount;
        public final long totalBytes;
        ImportResult(boolean success, String name, int fileCount, long totalBytes) {
            this.success = success;
            this.name = name;
            this.fileCount = fileCount;
            this.totalBytes = totalBytes;
        }
    }
}
